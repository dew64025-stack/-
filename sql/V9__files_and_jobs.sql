-- New objects are private. Legacy plaintext is retained until the controlled converter verifies ciphertext.
CREATE TABLE file_uploads (
 id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 campus_id BIGINT UNSIGNED NOT NULL, owner_user_id BIGINT UNSIGNED NOT NULL,
 access_domain VARCHAR(32) NOT NULL, purpose VARCHAR(32) NOT NULL,
 file_name VARCHAR(255) NOT NULL, mime_type VARCHAR(128) NOT NULL, size_bytes BIGINT UNSIGNED NOT NULL,
 expected_sha256 CHAR(64) NULL, token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(3) NOT NULL, state VARCHAR(32) NOT NULL DEFAULT 'issued',
 object_key_encrypted VARBINARY(2048) NOT NULL, original_sha256 CHAR(64) NULL,
 attachment_id BIGINT UNSIGNED NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_upload_token(token_digest),
 KEY idx_upload_expiry(state,expires_at),
 CONSTRAINT fk_upload_campus FOREIGN KEY(campus_id) REFERENCES campuses(id),
 CONSTRAINT fk_upload_owner FOREIGN KEY(owner_user_id) REFERENCES users(id),
 CONSTRAINT ck_upload_domain CHECK(access_domain IN ('student','admin')),
 CONSTRAINT ck_upload_size CHECK(size_bytes>0 AND size_bytes<=20971520)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE file_attachments (
 id BIGINT UNSIGNED NOT NULL PRIMARY KEY, upload_id BIGINT UNSIGNED NOT NULL,
 campus_id BIGINT UNSIGNED NOT NULL, owner_user_id BIGINT UNSIGNED NOT NULL,
 access_domain VARCHAR(32) NOT NULL, purpose VARCHAR(32) NOT NULL,
 file_name VARCHAR(255) NOT NULL, mime_type VARCHAR(128) NOT NULL, size_bytes BIGINT UNSIGNED NOT NULL,
 original_sha256 CHAR(64) NOT NULL, content_sha256 CHAR(64) NULL,
 object_key_encrypted VARBINARY(2048) NOT NULL,
 scan_status VARCHAR(32) NOT NULL DEFAULT 'pending', state VARCHAR(32) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_attachment_upload(upload_id),
 UNIQUE KEY uq_attachment_scope(id,campus_id,owner_user_id),
 CONSTRAINT fk_attachment_upload FOREIGN KEY(upload_id) REFERENCES file_uploads(id),
 CONSTRAINT fk_attachment_owner FOREIGN KEY(owner_user_id) REFERENCES users(id),
 CONSTRAINT fk_attachment_campus FOREIGN KEY(campus_id) REFERENCES campuses(id),
 CONSTRAINT ck_attachment_scan CHECK(scan_status IN ('pending','clean','infected','failed')),
 CONSTRAINT ck_attachment_domain CHECK(access_domain IN ('student','admin'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE file_uploads ADD CONSTRAINT fk_upload_attachment FOREIGN KEY(attachment_id) REFERENCES file_attachments(id);
ALTER TABLE async_jobs ADD COLUMN file_attachment_id BIGINT UNSIGNED NULL,
 ADD KEY idx_file_jobs(job_type,status,next_retry_at),
 ADD CONSTRAINT fk_job_file FOREIGN KEY(file_attachment_id) REFERENCES file_attachments(id);
CREATE TABLE file_cleanup_jobs (
 id BIGINT UNSIGNED NOT NULL PRIMARY KEY, object_key_encrypted VARBINARY(2048) NOT NULL,
 upload_id BIGINT UNSIGNED NULL, attachment_id BIGINT UNSIGNED NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'pending', retry_count INT UNSIGNED NOT NULL DEFAULT 0,
 next_retry_at DATETIME(3) NOT NULL, picked_at DATETIME(3) NULL, claim_token VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 KEY idx_cleanup_due(state,next_retry_at),
 CONSTRAINT fk_cleanup_upload FOREIGN KEY(upload_id) REFERENCES file_uploads(id),
 CONSTRAINT fk_cleanup_attachment FOREIGN KEY(attachment_id) REFERENCES file_attachments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE material_files MODIFY COLUMN object_key VARCHAR(512) NULL,
 ADD COLUMN campus_id BIGINT UNSIGNED NULL, ADD COLUMN object_key_encrypted VARBINARY(2048) NULL,
 ADD COLUMN current_attachment_id BIGINT UNSIGNED NULL,
 ADD KEY idx_material_owner_campus(campus_id,user_id,status,id),
 ADD CONSTRAINT fk_material_campus FOREIGN KEY(campus_id) REFERENCES campuses(id),
 ADD CONSTRAINT fk_material_attachment_scope FOREIGN KEY(current_attachment_id,campus_id,user_id) REFERENCES file_attachments(id,campus_id,owner_user_id);
ALTER TABLE material_versions MODIFY COLUMN object_key VARCHAR(512) NULL,
 ADD COLUMN object_key_encrypted VARBINARY(2048) NULL, ADD COLUMN attachment_id BIGINT UNSIGNED NULL,
 ADD COLUMN file_hash CHAR(64) NULL,
 ADD CONSTRAINT fk_version_attachment FOREIGN KEY(attachment_id) REFERENCES file_attachments(id);
-- Existing versions remain immutable for the business account. Converter UPDATE is a separate operator grant.
CREATE TABLE file_key_migrations (
 table_name VARCHAR(32) NOT NULL, row_id BIGINT UNSIGNED NOT NULL,
 backup_reference VARCHAR(128) NOT NULL, original_key_encrypted VARBINARY(2048) NOT NULL,
 converted_at DATETIME(3) NOT NULL, restored_at DATETIME(3) NULL,
 PRIMARY KEY(table_name,row_id),
 CONSTRAINT ck_migration_table CHECK(table_name IN ('material_files','material_versions'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
