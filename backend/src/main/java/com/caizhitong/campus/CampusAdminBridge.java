package com.caizhitong.campus;

import com.caizhitong.common.BusinessException;
import com.caizhitong.common.SnowflakeIds;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Campus-owned write boundary used only by the platform admin API.
 * Reads stay in CampusService; this bridge couples campus/config storage to its owner package.
 */
@Component
public class CampusAdminBridge {
    public record Created(String id, String name, String code) {}
    public record ConfigRow(String key, String value, int version, Instant updatedAt) {}

    private final JdbcTemplate jdbc;
    private final SnowflakeIds ids;

    public CampusAdminBridge(JdbcTemplate jdbc, SnowflakeIds ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    public Created create(String name, String code, LocalDateTime now) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM campuses WHERE code=?", Integer.class, code) > 0) {
            throw new BusinessException(409, "CAMPUS_CODE_CONFLICT", "校园编码已存在");
        }
        long id = ids.nextId();
        try {
            jdbc.update("INSERT INTO campuses(id,name,code,status,version,created_at,updated_at)"
                    + " VALUES(?,?,?,'active',0,?,?)", id, name, code, now, now);
        } catch (DuplicateKeyException error) {
            throw new BusinessException(409, "CAMPUS_CODE_CONFLICT", "校园编码已存在");
        }
        return new Created(Long.toString(id), name, code);
    }

    public List<ConfigRow> configs(long campus) {
        return jdbc.query("SELECT config_key,config_value,version,updated_at FROM campus_configs"
                        + " WHERE campus_id=? ORDER BY config_key",
                (rs, i) -> new ConfigRow(rs.getString("config_key"), rs.getString("config_value"),
                        rs.getInt("version"), instant(rs.getObject("updated_at", LocalDateTime.class))), campus);
    }

    public ConfigRow updateConfig(long campus, String key, String json, int version, LocalDateTime now) {
        var rows = jdbc.queryForList("SELECT version FROM campus_configs WHERE campus_id=? AND config_key=? FOR UPDATE",
                campus, key);
        if (rows.isEmpty()) {
            if (version != 0) throw new BusinessException(409, "VERSION_CONFLICT", "信息已更新，请刷新后重试");
            jdbc.update("INSERT INTO campus_configs(id,campus_id,config_key,config_value,version,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?)", ids.nextId(), campus, key, json, 1, now, now);
            return new ConfigRow(key, json, 1, instant(now));
        }
        int actual = ((Number) rows.getFirst().get("version")).intValue();
        if (actual != version) throw new BusinessException(409, "VERSION_CONFLICT", "信息已更新，请刷新后重试");
        jdbc.update("UPDATE campus_configs SET config_value=?,version=version+1,updated_at=? WHERE campus_id=? AND config_key=?",
                json, now, campus, key);
        return new ConfigRow(key, json, actual + 1, instant(now));
    }

    public boolean exists(long campus) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM campuses WHERE id=? AND status='active' AND deleted_at IS NULL",
                Integer.class, campus) > 0;
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
