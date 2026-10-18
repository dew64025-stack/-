-- Phase 7.4-7.6 personal data rights and admin operations.
-- Export object keys are encrypted at rest; the export body never enters the database.

ALTER TABLE growth_records
  ADD KEY idx_growth_user_id (user_id, id);

ALTER TABLE data_requests
  ADD COLUMN campus_id BIGINT UNSIGNED NULL,
  ADD COLUMN cancelled_at DATETIME(3) NULL,
  ADD COLUMN cooling_off_until DATETIME(3) NULL,
  ADD COLUMN export_object_key_encrypted VARBINARY(2048) NULL,
  ADD COLUMN export_sha256 CHAR(64) NULL,
  ADD COLUMN failure_reason VARCHAR(512) NULL,
  ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0,
  ADD KEY idx_data_requests_user_id (user_id, id),
  ADD KEY idx_data_requests_due (status, cooling_off_until),
  ADD CONSTRAINT fk_data_requests_campus FOREIGN KEY (campus_id) REFERENCES campuses (id);

UPDATE data_requests d JOIN users u ON u.id = d.user_id SET d.campus_id = u.campus_id;

CREATE TABLE privacy_settings (
  user_id BIGINT UNSIGNED NOT NULL,
  personalization_enabled TINYINT(1) NOT NULL DEFAULT 0,
  history_enabled TINYINT(1) NOT NULL DEFAULT 0,
  nearby_enabled TINYINT(1) NOT NULL DEFAULT 0,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_privacy_settings_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '隐私设置（默认关闭）';

CREATE TABLE browse_history (
  id BIGINT UNSIGNED NOT NULL,
  campus_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'opportunity/resource/issue/mutual_aid/team',
  target_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(256) NOT NULL,
  summary VARCHAR(512) NULL,
  viewed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_browse_history_target (user_id, target_type, target_id),
  KEY idx_browse_history_user_id (user_id, id),
  CONSTRAINT fk_browse_history_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_browse_history_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '浏览历史；匿名社区内容不写入';

ALTER TABLE admin_audit_logs
  ADD COLUMN campus_id BIGINT UNSIGNED NULL,
  ADD KEY idx_audit_campus_created (campus_id, created_at, id),
  ADD CONSTRAINT fk_audit_campus FOREIGN KEY (campus_id) REFERENCES campuses (id);

UPDATE admin_audit_logs a JOIN users u ON u.id = a.operator_id SET a.campus_id = u.campus_id;
