-- H2 projections for Phase 7.4/7.5 (V6 + V21). files-schema.sql already created a minimal growth_records.
CREATE TABLE IF NOT EXISTS growth_records(id BIGINT PRIMARY KEY,user_id BIGINT,type VARCHAR(32),title VARCHAR(256),description VARCHAR(1024),proof_file_id BIGINT,confirmed BOOLEAN DEFAULT FALSE,occurred_at TIMESTAMP,version INT DEFAULT 0,created_at TIMESTAMP,updated_at TIMESTAMP,deleted_at TIMESTAMP);
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS type VARCHAR(32);
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS title VARCHAR(256);
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS description VARCHAR(1024);
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS confirmed BOOLEAN DEFAULT FALSE;
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMP;
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS version INT DEFAULT 0;
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE growth_records ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
CREATE TABLE data_requests(id BIGINT PRIMARY KEY,user_id BIGINT,campus_id BIGINT,type VARCHAR(32),status VARCHAR(32) DEFAULT 'pending',requested_at TIMESTAMP,processed_at TIMESTAMP,retention_basis VARCHAR(255),result_json VARCHAR(4000),cancelled_at TIMESTAMP,cooling_off_until TIMESTAMP,export_object_key_encrypted VARBINARY(2048),export_sha256 CHAR(64),failure_reason VARCHAR(512),version INT DEFAULT 0,created_at TIMESTAMP,updated_at TIMESTAMP);
CREATE TABLE privacy_settings(user_id BIGINT PRIMARY KEY,personalization_enabled BOOLEAN DEFAULT FALSE,history_enabled BOOLEAN DEFAULT FALSE,nearby_enabled BOOLEAN DEFAULT FALSE,version INT DEFAULT 0,created_at TIMESTAMP,updated_at TIMESTAMP);
CREATE TABLE browse_history(id BIGINT PRIMARY KEY,campus_id BIGINT,user_id BIGINT,target_type VARCHAR(32),target_id BIGINT,title VARCHAR(256),summary VARCHAR(512),viewed_at TIMESTAMP,UNIQUE(user_id,target_type,target_id));
CREATE TABLE IF NOT EXISTS borrow_requests(id BIGINT PRIMARY KEY,post_id BIGINT,borrower_id BIGINT,start_time TIMESTAMP,end_time TIMESTAMP,status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP);
ALTER TABLE admin_audit_logs ADD COLUMN IF NOT EXISTS campus_id BIGINT;
ALTER TABLE authorizations ADD COLUMN IF NOT EXISTS document_version VARCHAR(64);
