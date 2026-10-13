-- =============================================================
-- 材智通 · V5 消息、治理与集成（跨阶段）
-- 覆盖：站内消息、订阅授权、发送记录、
--       审核任务、信用事件、审计、安全调查、
--       外部系统、同步游标、原始摘要与同步错误
-- 安全说明：admin_audit_logs 追加写，普通业务账号无 UPDATE/DELETE 权限
-- =============================================================

SET NAMES utf8mb4;

-- 会话（站内消息，可单聊/群聊/组队）
CREATE TABLE conversations (
  id         BIGINT UNSIGNED NOT NULL,
  campus_id  BIGINT UNSIGNED NOT NULL,
  type       VARCHAR(32) NOT NULL DEFAULT 'direct' COMMENT 'direct/group/team',
  title      VARCHAR(128) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_conversations_campus (campus_id),
  CONSTRAINT fk_conversations_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '会话';

-- 会话成员
CREATE TABLE conversation_members (
  id              BIGINT UNSIGNED NOT NULL,
  conversation_id BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  unread_count    INT UNSIGNED NOT NULL DEFAULT 0,
  created_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_conversation_members (conversation_id, user_id),
  KEY idx_conversation_members_user (user_id),
  CONSTRAINT fk_conversation_members_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
  CONSTRAINT fk_conversation_members_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '会话成员';

-- 消息
CREATE TABLE messages (
  id              BIGINT UNSIGNED NOT NULL,
  conversation_id BIGINT UNSIGNED NOT NULL,
  sender_id       BIGINT UNSIGNED NOT NULL,
  content         TEXT NOT NULL,
  status          VARCHAR(32) NOT NULL DEFAULT 'sent' COMMENT 'sent/reported/blocked',
  created_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_messages_conversation (conversation_id, created_at),
  CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
  CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '消息';

-- 站内通知
CREATE TABLE notifications (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  type        VARCHAR(32) NOT NULL COMMENT 'deadline/workorder/reservation/borrow/system',
  title       VARCHAR(128) NOT NULL,
  content     VARCHAR(512) NOT NULL,
  target_type VARCHAR(32) NULL,
  target_id   BIGINT UNSIGNED NULL,
  read_at     DATETIME(3) NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_notifications_user_read (user_id, read_at),
  KEY idx_notifications_campus (campus_id),
  CONSTRAINT fk_notifications_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_notifications_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '站内通知';

-- 订阅授权（微信订阅消息模板）
CREATE TABLE subscription_authorizations (
  id          BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  scene       VARCHAR(64)  NOT NULL COMMENT '授权场景',
  status      VARCHAR(32)  NOT NULL DEFAULT 'granted' COMMENT 'granted/revoked',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  granted_at  DATETIME(3)  NOT NULL,
  revoked_at  DATETIME(3)  NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_subscription_user (user_id),
  CONSTRAINT fk_subscription_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '订阅授权';

-- 消息发送记录（可靠任务：去重键唯一 + 重试时间）
CREATE TABLE message_send_records (
  id            BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  channel       VARCHAR(32) NOT NULL COMMENT 'in_app/wechat_subscribe',
  template_id   VARCHAR(128) NULL,
  content_json  JSON        NULL,
  dedup_key     CHAR(64)    NULL COMMENT '业务去重键',
  result        VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/success/failed',
  error         VARCHAR(512) NULL,
  retry_count   INT UNSIGNED NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL COMMENT '下次重试时间',
  created_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_send_records_dedup (dedup_key),
  KEY idx_send_records_user (user_id),
  KEY idx_send_records_result (result, next_retry_at),
  CONSTRAINT fk_send_records_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '消息发送记录';

-- 审核任务（机器/人工队列）
CREATE TABLE moderation_tasks (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id   BIGINT UNSIGNED NOT NULL,
  status      VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/assigned/resolved',
  assigned_to BIGINT UNSIGNED NULL,
  result      VARCHAR(32) NULL COMMENT 'approve/reject/restrict',
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_moderation_tasks_status (campus_id, status),
  KEY idx_moderation_tasks_target (target_type, target_id),
  CONSTRAINT fk_moderation_tasks_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '审核任务';

-- 审核规则
CREATE TABLE moderation_rules (
  id         BIGINT UNSIGNED NOT NULL,
  rule_type  VARCHAR(32) NOT NULL COMMENT 'keyword/regex/model',
  pattern    VARCHAR(1024) NOT NULL,
  action     VARCHAR(32) NOT NULL COMMENT 'block/review/restrict',
  enabled    TINYINT(1)  NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '审核规则';

-- 信用事件（可解释的业务事件）
CREATE TABLE credit_events (
  id           BIGINT UNSIGNED NOT NULL,
  campus_id    BIGINT UNSIGNED NOT NULL,
  user_id      BIGINT UNSIGNED NOT NULL,
  event_type   VARCHAR(32) NOT NULL COMMENT 'no_show/late_cancel/damage/...',
  reason       VARCHAR(512) NOT NULL,
  points       INT NOT NULL DEFAULT 0,
  related_type VARCHAR(32) NULL,
  related_id   BIGINT UNSIGNED NULL,
  created_at   DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_credit_events_user (user_id),
  KEY idx_credit_events_campus (campus_id),
  CONSTRAINT fk_credit_events_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_credit_events_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '信用事件';

-- 管理员操作审计（追加写，不可删除）
CREATE TABLE admin_audit_logs (
  id          BIGINT UNSIGNED NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  action      VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NULL,
  target_id   BIGINT UNSIGNED NULL,
  reason      VARCHAR(512) NULL,
  detail_json JSON        NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_operator (operator_id),
  KEY idx_audit_created (created_at),
  CONSTRAINT fk_audit_operator FOREIGN KEY (operator_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '管理员操作审计';

-- 安全调查记录（授权查看匿名身份映射）
CREATE TABLE security_investigations (
  id              BIGINT UNSIGNED NOT NULL,
  investigator_id BIGINT UNSIGNED NOT NULL,
  reason          VARCHAR(512) NOT NULL,
  target_user_id  BIGINT UNSIGNED NULL,
  target_type     VARCHAR(32) NULL,
  target_id       BIGINT UNSIGNED NULL,
  result_json     JSON        NULL,
  created_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_security_investigations_investigator (investigator_id),
  CONSTRAINT fk_security_investigations_investigator FOREIGN KEY (investigator_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '安全调查记录';

-- 外部系统
CREATE TABLE external_systems (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  type        VARCHAR(32) NOT NULL COMMENT 'notice/library/classroom/repair/crowd',
  provider    VARCHAR(32) NOT NULL DEFAULT 'demo' COMMENT 'demo/manual/official',
  config_json JSON        NULL COMMENT '接口配置（不含密钥明文）',
  status      VARCHAR(32) NOT NULL DEFAULT 'active',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_external_systems (campus_id, type),
  CONSTRAINT fk_external_systems_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '外部系统';

-- 外部编号映射（一个外部对象只映射一个本地记录）
CREATE TABLE external_references (
  id                 BIGINT UNSIGNED NOT NULL,
  external_system_id BIGINT UNSIGNED NOT NULL,
  local_type         VARCHAR(32) NOT NULL,
  local_id           BIGINT UNSIGNED NOT NULL,
  external_id        VARCHAR(128) NOT NULL,
  created_at         DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_external_refs_external (external_system_id, external_id),
  KEY idx_external_refs_local (external_system_id, local_type, local_id),
  CONSTRAINT fk_external_refs_system FOREIGN KEY (external_system_id) REFERENCES external_systems (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '外部编号映射';

-- 同步游标
CREATE TABLE sync_cursors (
  id                 BIGINT UNSIGNED NOT NULL,
  external_system_id BIGINT UNSIGNED NOT NULL,
  cursor_value       VARCHAR(256) NULL COMMENT '增量同步游标',
  last_synced_at     DATETIME(3) NULL,
  updated_at         DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_sync_cursors (external_system_id),
  CONSTRAINT fk_sync_cursors_system FOREIGN KEY (external_system_id) REFERENCES external_systems (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '同步游标';

-- 同步原始摘要
CREATE TABLE sync_raw_digests (
  id                 BIGINT UNSIGNED NOT NULL,
  external_system_id BIGINT UNSIGNED NOT NULL,
  external_id        VARCHAR(128) NOT NULL,
  digest             CHAR(64) NOT NULL,
  created_at         DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_sync_digests (external_system_id, external_id),
  CONSTRAINT fk_sync_digests_system FOREIGN KEY (external_system_id) REFERENCES external_systems (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '同步原始摘要';

-- 同步错误
CREATE TABLE sync_errors (
  id                 BIGINT UNSIGNED NOT NULL,
  external_system_id BIGINT UNSIGNED NOT NULL,
  external_id        VARCHAR(128) NULL,
  error              VARCHAR(1024) NOT NULL,
  created_at         DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_sync_errors_system (external_system_id),
  CONSTRAINT fk_sync_errors_system FOREIGN KEY (external_system_id) REFERENCES external_systems (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '同步错误';
