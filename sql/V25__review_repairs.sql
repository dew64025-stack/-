-- =============================================================
-- V25 审查修复
-- 1) opportunities 补齐服务代码读取的官方来源列
-- 2) 一对一会话按参与者对隔离，避免同校其他用户加入他人会话
-- 3) 回填审计日志校园范围，供按校园读取
-- 4) 修正注销过程的匿名内容定位：
--    旧版评论身份映射的 comment_id 为空、post_id 指向父帖，原 JOIN 会漏掉旧评论；
--    改为以 community_comments/community_posts 的 pseudo_identity_id 为主，
--    并兼容新版评论映射的 comment_id。
--    注意：DROP/CREATE 会同时删除该过程的 EXECUTE 授权，部署后必须按
--    DATABASE.md 重新授予业务账号和迁移账号 EXECUTE，否则注销会显式失败。
-- =============================================================

SET NAMES utf8mb4;

ALTER TABLE opportunities
  ADD COLUMN source_name        VARCHAR(128) NULL,
  ADD COLUMN external_id        VARCHAR(128) NULL,
  ADD COLUMN official_reference VARCHAR(512) NULL,
  ADD COLUMN is_stale           TINYINT(1)   NOT NULL DEFAULT 0,
  ADD KEY idx_opportunities_external (external_id);

-- 由通知生成的既有机会回填来源信息，避免升级后列表丢失官方来源。
UPDATE opportunities o
  JOIN notices n ON n.id = o.notice_id
  SET o.source_name        = COALESCE(o.source_name, n.source_name),
      o.external_id        = COALESCE(o.external_id, n.external_id),
      o.official_reference = COALESCE(o.official_reference, n.official_reference),
      o.is_stale           = n.is_stale
  WHERE o.notice_id IS NOT NULL;

-- 旧会话的 participant_key 为空；新建的一对一会话写入排序后的双方用户ID。
-- 团队会话使用空串，仍按 team_id 共享同一会话。
ALTER TABLE conversations
  ADD COLUMN participant_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
  DROP INDEX uq_conversations_context,
  ADD UNIQUE KEY uq_conversations_context (campus_id, context_type, context_id, participant_key);

-- 已有的一对一上下文会话按两名成员回填参与者键，保证升级后仍能复用，而不是新建重复会话。
-- 旧版同一上下文被多名访客共用、成员超过两人的会话保持空键，不再被新会话匹配。
UPDATE conversations c
JOIN (
  SELECT m.conversation_id,
         GROUP_CONCAT(m.user_id ORDER BY m.user_id SEPARATOR ':') AS pair_key
  FROM conversation_members m
  GROUP BY m.conversation_id
  HAVING COUNT(*) = 2
     AND LENGTH(GROUP_CONCAT(m.user_id ORDER BY m.user_id SEPARATOR ':')) <= 64
) pairs ON pairs.conversation_id = c.id
SET c.participant_key = pairs.pair_key
WHERE c.participant_key = '' AND c.context_type IS NOT NULL AND c.context_type <> 'team';

UPDATE admin_audit_logs a
  JOIN users u ON u.id = a.operator_id
  SET a.campus_id = u.campus_id
  WHERE a.campus_id IS NULL;

DROP PROCEDURE IF EXISTS community_privacy_erase;

DELIMITER $$
CREATE PROCEDURE community_privacy_erase(
    IN p_user_id BIGINT UNSIGNED, IN p_user_search CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    -- 以帖子/评论自身的 pseudo_identity_id 为准；兼容仅 comment_id 关联的新版评论映射。
    UPDATE community_comments c
    JOIN pseudo_identities i
      ON c.pseudo_identity_id = i.id OR i.comment_id = c.id
    SET c.content = '[已删除]', c.status = 'deleted'
    WHERE i.user_id = p_user_id OR i.user_id_search = p_user_search;

    -- 新旧帖子映射都通过 community_posts.pseudo_identity_id 关联。
    UPDATE community_posts p
    JOIN pseudo_identities i ON p.pseudo_identity_id = i.id
    SET p.title = '[已删除]', p.content = '[已删除]', p.status = 'deleted',
        p.deleted_at = NOW(3), p.updated_at = NOW(3), p.version = p.version + 1
    WHERE i.user_id = p_user_id OR i.user_id_search = p_user_search;

    DELETE i FROM pseudo_identities i
    WHERE i.user_id = p_user_id OR i.user_id_search = p_user_search;

    SELECT ROW_COUNT() AS erased;
END$$
DELIMITER ;
