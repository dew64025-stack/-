-- =============================================================
-- 材智通 · V15 社区治理运行时（阶段 11）
-- 覆盖：屏蔽关系、信用纠错申请、匿名身份加密映射
-- 安全说明：匿名映射新写入只存密文 + HMAC 检索摘要，旧明文由受控转换流程处理
-- =============================================================

SET NAMES utf8mb4;

CREATE TABLE blocks (
  id          BIGINT UNSIGNED NOT NULL,
  blocker_id  BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'user/community_post/mutual_aid_post',
  target_id   BIGINT UNSIGNED NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_blocks (blocker_id, target_type, target_id),
  KEY idx_blocks_target (target_type, target_id),
  CONSTRAINT fk_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '屏蔽关系';

CREATE TABLE credit_disputes (
  id              BIGINT UNSIGNED NOT NULL,
  campus_id       BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  credit_event_id BIGINT UNSIGNED NOT NULL,
  reason          VARCHAR(512) NOT NULL,
  status          VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
  reviewed_by     BIGINT UNSIGNED NULL,
  reviewed_at     DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_credit_disputes_campus_status (campus_id, status),
  KEY idx_credit_disputes_user (user_id),
  CONSTRAINT fk_credit_disputes_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_credit_disputes_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id),
  CONSTRAINT fk_credit_disputes_event FOREIGN KEY (credit_event_id) REFERENCES credit_events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '信用纠错申请';

-- 匿名身份映射：新写入只存密文 + HMAC 检索摘要；帖子映射用 post_id，评论映射用 comment_id
ALTER TABLE pseudo_identities
  MODIFY post_id BIGINT UNSIGNED NULL,
  MODIFY user_id BIGINT UNSIGNED NULL,
  ADD COLUMN comment_id BIGINT UNSIGNED NULL COMMENT '评论匿名身份关联',
  ADD COLUMN user_id_encrypted VARBINARY(2048) NULL COMMENT '加密用户ID',
  ADD COLUMN user_id_search CHAR(64) NULL COMMENT 'HMAC检索摘要',
  ADD KEY idx_pseudo_identities_user_search (user_id_search),
  ADD KEY idx_pseudo_identities_comment (comment_id),
  ADD CONSTRAINT fk_pseudo_identities_comment FOREIGN KEY (comment_id) REFERENCES community_comments (id);
