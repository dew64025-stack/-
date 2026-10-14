-- Incremental identity constraints. Unique-key creation fails on old conflicts; no data is deleted.
CREATE TEMPORARY TABLE identity_v8_conflict_guard (
  conflict_count BIGINT NOT NULL,
  CONSTRAINT chk_identity_v8_no_conflicts CHECK(conflict_count=0)
);
INSERT INTO identity_v8_conflict_guard
  SELECT COUNT(*) FROM (SELECT user_id FROM user_phones WHERE verified=1 GROUP BY user_id HAVING COUNT(*)>1) phone_conflicts;
INSERT INTO identity_v8_conflict_guard
  SELECT COUNT(*) FROM (SELECT user_id FROM student_verifications WHERE status IN ('pending','verified') GROUP BY user_id HAVING COUNT(*)>1) active_conflicts;
INSERT INTO identity_v8_conflict_guard
  SELECT COUNT(*) FROM (SELECT campus_id,student_id_search FROM student_verifications WHERE status='verified' GROUP BY campus_id,student_id_search HAVING COUNT(*)>1) student_conflicts;
DROP TEMPORARY TABLE identity_v8_conflict_guard;

ALTER TABLE user_wechat_identities
  MODIFY app_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  MODIFY openid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

ALTER TABLE users ADD COLUMN avatar_attachment_id BIGINT UNSIGNED NULL;

ALTER TABLE user_phones
  ADD COLUMN verified_user_id BIGINT UNSIGNED
    GENERATED ALWAYS AS (CASE WHEN verified=1 THEN user_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uq_phone_current_user (verified_user_id);

ALTER TABLE student_verifications
  ADD KEY idx_student_campus_search (campus_id,student_id_search),
  DROP INDEX uq_student_campus_search,
  ADD COLUMN verified_student_search CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CASE WHEN status='verified' THEN student_id_search ELSE NULL END) STORED,
  ADD COLUMN active_user_id BIGINT UNSIGNED
    GENERATED ALWAYS AS (CASE WHEN status IN ('pending','verified') THEN user_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uq_student_verified_owner (campus_id,verified_student_search),
  ADD UNIQUE KEY uq_student_active_user (active_user_id);

CREATE TABLE admin_credentials (
  user_id BIGINT UNSIGNED NOT NULL,
  username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mfa_seed_encrypted VARBINARY(255) NOT NULL,
  last_totp_step BIGINT NOT NULL DEFAULT -1,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY(user_id),
  UNIQUE KEY uq_admin_username(username),
  CONSTRAINT fk_admin_credentials_user FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE async_jobs
  ADD COLUMN campus_id BIGINT UNSIGNED NULL,
  ADD COLUMN owner_user_id BIGINT UNSIGNED NULL,
  ADD COLUMN access_domain VARCHAR(32) NULL,
  ADD COLUMN claim_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD KEY idx_async_owner_domain (campus_id,owner_user_id,access_domain),
  ADD CONSTRAINT fk_async_campus FOREIGN KEY(campus_id) REFERENCES campuses(id),
  ADD CONSTRAINT fk_async_owner FOREIGN KEY(owner_user_id) REFERENCES users(id);
-- Existing jobs remain unscoped. Their ownership must not be guessed from historical payloads.

CREATE TABLE student_roster (
  id BIGINT UNSIGNED NOT NULL,
  campus_id BIGINT UNSIGNED NOT NULL,
  student_id_search CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  student_id_encrypted VARBINARY(255) NOT NULL,
  college_encrypted VARBINARY(512) NOT NULL,
  grade_encrypted VARBINARY(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  import_job_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uq_roster_campus_student(campus_id,student_id_search),
  CONSTRAINT chk_roster_status CHECK(status IN ('active','inactive')),
  CONSTRAINT fk_roster_campus FOREIGN KEY(campus_id) REFERENCES campuses(id),
  CONSTRAINT fk_roster_import FOREIGN KEY(import_job_id) REFERENCES async_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
