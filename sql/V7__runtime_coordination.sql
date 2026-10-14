-- Durable business idempotency. The reservation, action and encrypted DTO commit together.
CREATE TABLE api_idempotency_records (
  id                 BIGINT UNSIGNED NOT NULL,
  campus_id          BIGINT UNSIGNED NOT NULL,
  user_id            BIGINT UNSIGNED NOT NULL,
  scope_digest       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_digest         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_digest     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  response_encrypted MEDIUMBLOB NULL,
  response_type      VARCHAR(255) NULL,
  status             VARCHAR(16) NOT NULL,
  created_at         DATETIME(3) NOT NULL,
  expires_at         DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_idempotency_scope_key (scope_digest, key_digest),
  KEY idx_idempotency_campus_user_expiry (campus_id, user_id, expires_at),
  KEY idx_idempotency_expiry (expires_at),
  CONSTRAINT fk_idempotency_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id),
  CONSTRAINT ck_idempotency_status CHECK (status IN ('pending', 'complete'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT 'Encrypted durable business idempotency';
