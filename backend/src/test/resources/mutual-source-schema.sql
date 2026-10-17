CREATE TABLE external_systems(id BIGINT PRIMARY KEY,campus_id BIGINT,type VARCHAR(32),provider VARCHAR(32),status VARCHAR(32));
CREATE TABLE external_references(id BIGINT PRIMARY KEY,external_system_id BIGINT,local_type VARCHAR(32),local_id BIGINT,external_id VARCHAR(128));
