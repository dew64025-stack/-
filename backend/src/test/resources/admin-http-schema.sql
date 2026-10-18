-- H2 additions for the platform/campus administration HTTP tests.
ALTER TABLE campuses ADD COLUMN IF NOT EXISTS version INT DEFAULT 0;
ALTER TABLE campuses ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
ALTER TABLE campuses ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE campus_configs ADD COLUMN IF NOT EXISTS version INT DEFAULT 0;
ALTER TABLE campus_configs ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
ALTER TABLE campus_configs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE admin_audit_logs ADD COLUMN IF NOT EXISTS campus_id BIGINT;
CREATE TABLE IF NOT EXISTS campus_issues(id BIGINT PRIMARY KEY,campus_id BIGINT,user_id BIGINT,title VARCHAR(256),category VARCHAR(32),status VARCHAR(32),visibility VARCHAR(16),created_at TIMESTAMP,updated_at TIMESTAMP,deleted_at TIMESTAMP);
