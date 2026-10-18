package com.caizhitong.privacy;

import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.SnowflakeIds;
import com.caizhitong.community.AnonymousErasure;
import com.caizhitong.community.CommunityService;
import com.caizhitong.integration.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Data-right export and account deletion worker. External object storage runs outside the
 * transaction; claim fencing uses the request version. Export bodies never enter the database.
 */
@Component("dataRightsWorker")
public class DataRightsWorker implements Runnable {
    private record Claim(long id, long user, long campus, String type, int version, LocalDateTime requestedAt) {}
    private record ExportResult(String key, String sha256) {}

    private static final String EXPORT_FAILED = "数据导出生成失败，可重新申请";
    private static final String DELETION_FAILED = "注销处理失败，请稍后重试或联系管理员";
    private static final String RETAINED = "账号已停用，资料与联系方式已清除；信用、处罚、举报、匿名安全审计记录依法保留。";

    private final JdbcTemplate jdbc;
    private final SnowflakeIds ids;
    private final Clock clock;
    private final SensitiveDataCrypto crypto;
    private final ObjectStorage storage;
    private final DataExportBuilder exporter;
    private final AnonymousErasure erasure;
    private final TransactionTemplate tx;

    public DataRightsWorker(JdbcTemplate jdbc, SnowflakeIds ids, Clock clock, SensitiveDataCrypto crypto,
                            ObjectStorage storage, DataExportBuilder exporter, AnonymousErasure erasure,
                            PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.crypto = crypto;
        this.storage = storage;
        this.exporter = exporter;
        this.erasure = erasure;
        this.tx = new TransactionTemplate(tm);
        this.tx.setTimeout(10);
    }

    @Override
    public void run() {
        Claim claim = tx.execute(s -> claim());
        if (claim == null) return;
        String orphanExport = null;
        try {
            if ("export".equals(claim.type())) {
                ExportResult result = export(claim);
                orphanExport = result.key();
                tx.executeWithoutResult(s -> completeExport(claim, result));
                orphanExport = null;
            } else if (obligations(claim) > 0) {
                tx.executeWithoutResult(s -> reject(claim, "存在未完成的借用、预约或工单，请处理后再申请注销"));
            } else {
                tx.executeWithoutResult(s -> completeDeletion(claim));
            }
        } catch (RuntimeException error) {
            // The object was written outside the transaction; remove it if the receipt failed.
            if (orphanExport != null) {
                try {
                    storage.delete(orphanExport);
                } catch (RuntimeException ignored) {
                    // A failed cleanup leaves an unreferenced object; the request still fails explicitly.
                }
            }
            tx.executeWithoutResult(s -> fail(claim));
        }
    }

    private Claim claim() {
        LocalDateTime now = now();
        // A crashed worker's lease is reclaimed; the version bump fences the old completion.
        jdbc.update("UPDATE data_requests SET status='pending',version=version+1,updated_at=?"
                        + " WHERE status='processing' AND type IN ('export','deletion') AND updated_at<?",
                now, now.minusMinutes(5));
        var rows = jdbc.query("SELECT id,user_id,campus_id,type,version,requested_at FROM data_requests"
                        + " WHERE status='pending' AND campus_id IS NOT NULL"
                        + " AND (type='export' OR (type='deletion' AND cooling_off_until IS NOT NULL AND cooling_off_until<=?))"
                        + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Claim(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("campus_id"),
                        rs.getString("type"), rs.getInt("version") + 1,
                        rs.getObject("requested_at", LocalDateTime.class)), now);
        if (rows.isEmpty()) return null;
        Claim claim = rows.getFirst();
        jdbc.update("UPDATE data_requests SET status='processing',version=?,updated_at=? WHERE id=? AND version=?",
                claim.version(), now, claim.id(), claim.version() - 1);
        return claim;
    }

    private ExportResult export(Claim claim) {
        byte[] body = exporter.build(claim.user(), claim.campus(), claim.requestedAt().toInstant(ZoneOffset.UTC).toString());
        String key = "data-exports/" + claim.campus() + "/" + claim.user() + "/" + claim.id() + ".json";
        storage.put(key, new ByteArrayInputStream(body), "application/json");
        return new ExportResult(key, sha256(body));
    }

    private void completeExport(Claim claim, ExportResult result) {
        LocalDateTime now = now();
        jdbc.update("UPDATE data_requests SET status='completed',processed_at=?,export_object_key_encrypted=?,"
                        + "export_sha256=?,failure_reason=NULL,result_json=NULL,updated_at=?"
                        + " WHERE id=? AND status='processing' AND version=?",
                now, crypto.encrypt("data-export-key", result.key()), result.sha256(), now, claim.id(), claim.version());
    }

    /** Unfinished obligations must be surfaced before destroying data; never delete silently. */
    private int obligations(Claim claim) {
        // A soft-deleted listing can still have an open borrow; the obligation survives hiding it.
        int borrows = jdbc.queryForObject("SELECT COUNT(*) FROM borrow_requests b JOIN mutual_aid_posts p ON p.id=b.post_id"
                        + " WHERE (b.borrower_id=? OR p.user_id=?) AND b.status IN ('pending','confirmed','handed_over')",
                Integer.class, claim.user(), claim.user());
        int reservations = jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE user_id=?"
                        + " AND campus_id=? AND status IN ('pending','confirmed','checked_in') AND deleted_at IS NULL",
                Integer.class, claim.user(), claim.campus());
        int orders = jdbc.queryForObject("SELECT COUNT(*) FROM work_orders WHERE user_id=? AND campus_id=?"
                        + " AND status IN ('pending','reviewing','accepted','processing') AND deleted_at IS NULL",
                Integer.class, claim.user(), claim.campus());
        return borrows + reservations + orders;
    }

    private void completeDeletion(Claim claim) {
        LocalDateTime now = now();
        long user = claim.user();
        jdbc.update("UPDATE users SET nickname=NULL,avatar_url=NULL,avatar_attachment_id=NULL,status='disabled',"
                        + "deleted_at=?,version=version+1,updated_at=? WHERE id=?", now, now, user);
        jdbc.update("DELETE FROM user_phones WHERE user_id=?", user);
        jdbc.update("DELETE FROM user_profiles WHERE user_id=?", user);
        jdbc.update("DELETE FROM student_verifications WHERE user_id=?", user);
        jdbc.update("DELETE FROM user_wechat_identities WHERE user_id=?", user);
        jdbc.update("UPDATE authorizations SET status='revoked',revoked_at=?,version=version+1"
                + " WHERE user_id=? AND status='granted'", now, user);
        jdbc.update("UPDATE subscription_authorizations SET status='revoked',revoked_at=?,version=version+1"
                + " WHERE user_id=? AND status='granted'", now, user);
        jdbc.update("DELETE FROM favorites WHERE user_id=?", user);
        jdbc.update("DELETE FROM reminders WHERE user_id=?", user);
        jdbc.update("DELETE FROM browse_history WHERE user_id=?", user);
        jdbc.update("DELETE FROM privacy_settings WHERE user_id=?", user);
        jdbc.update("DELETE FROM conversation_members WHERE user_id=?", user);
        jdbc.update("DELETE FROM notifications WHERE user_id=?", user);
        jdbc.update("DELETE FROM message_send_records WHERE user_id=?", user);
        jdbc.update("UPDATE messages SET content='[已删除]' WHERE sender_id=? AND status='sent'", user);
        jdbc.update("DELETE FROM blocks WHERE blocker_id=?", user);
        jdbc.update("UPDATE growth_records SET deleted_at=?,updated_at=?,version=version+1"
                + " WHERE user_id=? AND deleted_at IS NULL", now, now, user);
        jdbc.update("UPDATE mutual_aid_posts SET status='cancelled',deleted_at=?,updated_at=?,version=version+1"
                + " WHERE user_id=? AND deleted_at IS NULL", now, now, user);
        jdbc.update("DELETE FROM team_applications WHERE user_id=?", user);
        jdbc.update("DELETE FROM team_members WHERE user_id=?", user);
        jdbc.update("UPDATE teams SET status='closed',title='已注销用户的组队',goal=NULL,version=version+1,updated_at=?"
                + " WHERE leader_id=? AND deleted_at IS NULL", now, user);
        // Anonymous content is anonymised rather than physically deleted: comments by other users
        // may reference the posts. The definer routine destroys both legacy and HMAC-only identity
        // mappings; the business account never reads the mapping directly.
        String anonymousSearch = HexFormat.of().formatHex(
                crypto.searchHash(CommunityService.ANONYMOUS_IDENTITY_DOMAIN, Long.toString(user)));
        erasure.erase(user, anonymousSearch);
        jdbc.update("UPDATE material_files SET status='deleted',deleted_at=?,updated_at=?,version=version+1"
                + " WHERE user_id=? AND deleted_at IS NULL", now, now, user);
        enqueueFileCleanup(user, now);
        jdbc.update("UPDATE data_requests SET status='completed',processed_at=?,retention_basis=?,updated_at=?,"
                + "version=version+1 WHERE id=? AND status='processing' AND version=?",
                now, RETAINED, now, claim.id(), claim.version());
    }

    private void enqueueFileCleanup(long user, LocalDateTime now) {
        List<Object[]> files = jdbc.query("SELECT object_key_encrypted,upload_id,id FROM file_attachments"
                        + " WHERE owner_user_id=? AND state='active'",
                (rs, i) -> new Object[]{rs.getBytes("object_key_encrypted"), rs.getLong("upload_id"), rs.getLong("id")}, user);
        for (Object[] file : files) {
            jdbc.update("INSERT INTO file_cleanup_jobs(id,object_key_encrypted,upload_id,attachment_id,state,retry_count,"
                            + "next_retry_at,created_at,updated_at) VALUES(?,?,?,?,'pending',0,?,?,?)",
                    ids.nextId(), file[0], file[1], file[2], now, now, now);
        }
        jdbc.update("UPDATE file_attachments SET state='deleted',updated_at=? WHERE owner_user_id=? AND state='active'",
                now, user);
    }

    private void reject(Claim claim, String reason) {
        LocalDateTime now = now();
        jdbc.update("UPDATE data_requests SET status='rejected',failure_reason=?,processed_at=?,updated_at=?,"
                        + "version=version+1 WHERE id=? AND status='processing' AND version=?",
                reason, now, now, claim.id(), claim.version());
    }

    private void fail(Claim claim) {
        LocalDateTime now = now();
        String reason = "export".equals(claim.type()) ? EXPORT_FAILED : DELETION_FAILED;
        jdbc.update("UPDATE data_requests SET status='failed',failure_reason=?,processed_at=?,updated_at=?,"
                        + "version=version+1 WHERE id=? AND status='processing' AND version=?",
                reason, now, now, claim.id(), claim.version());
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
