-- H2 projections for Phase 7.6 integration administration (V5 + V21).
CREATE TABLE IF NOT EXISTS external_systems(id BIGINT PRIMARY KEY,campus_id BIGINT,type VARCHAR(32),provider VARCHAR(32),config_json VARCHAR(4000),status VARCHAR(32),version INT DEFAULT 0,created_at TIMESTAMP,updated_at TIMESTAMP);
ALTER TABLE external_systems ADD COLUMN IF NOT EXISTS provider VARCHAR(32);
ALTER TABLE external_systems ADD COLUMN IF NOT EXISTS config_json VARCHAR(4000);
ALTER TABLE external_systems ADD COLUMN IF NOT EXISTS status VARCHAR(32);
ALTER TABLE external_systems ADD COLUMN IF NOT EXISTS version INT DEFAULT 0;
ALTER TABLE external_systems ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;
ALTER TABLE external_systems ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
CREATE TABLE sync_cursors(id BIGINT PRIMARY KEY,external_system_id BIGINT,cursor_value VARCHAR(256),last_synced_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(external_system_id));
CREATE TABLE sync_raw_digests(id BIGINT PRIMARY KEY,external_system_id BIGINT,external_id VARCHAR(128),digest CHAR(64),created_at TIMESTAMP,UNIQUE(external_system_id,external_id));
CREATE TABLE sync_errors(id BIGINT PRIMARY KEY,external_system_id BIGINT,external_id VARCHAR(128),error VARCHAR(1024),created_at TIMESTAMP);
