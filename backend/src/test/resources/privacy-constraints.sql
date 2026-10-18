-- MySQL foreign keys that the H2 community projection normally omits.
ALTER TABLE community_comments ADD CONSTRAINT fk_community_comments_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id);
ALTER TABLE pseudo_identities ADD CONSTRAINT fk_pseudo_identities_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id);
ALTER TABLE pseudo_identities ADD CONSTRAINT fk_pseudo_identities_comment
    FOREIGN KEY (comment_id) REFERENCES community_comments(id);
ALTER TABLE pseudo_identities ADD CONSTRAINT fk_pseudo_identities_user
    FOREIGN KEY (user_id) REFERENCES users(id);

-- Tables touched by the deletion path that the privacy slice does not otherwise load.
CREATE TABLE IF NOT EXISTS favorites(id BIGINT PRIMARY KEY,user_id BIGINT,target_type VARCHAR(32),
    target_id BIGINT,created_at TIMESTAMP,UNIQUE(user_id,target_type,target_id));
CREATE TABLE IF NOT EXISTS subscription_authorizations(id BIGINT PRIMARY KEY,user_id BIGINT,
    template_id VARCHAR(128),scene VARCHAR(64),status VARCHAR(32),version INT DEFAULT 0,
    granted_at TIMESTAMP,revoked_at TIMESTAMP,created_at TIMESTAMP);
CREATE TABLE IF NOT EXISTS messages(id BIGINT PRIMARY KEY,conversation_id BIGINT,sender_id BIGINT,
    content VARCHAR(4000),status VARCHAR(32) DEFAULT 'sent',client_message_id VARCHAR(64),created_at TIMESTAMP);
CREATE TABLE IF NOT EXISTS message_send_records(id BIGINT PRIMARY KEY,user_id BIGINT,created_at TIMESTAMP);
CREATE TABLE IF NOT EXISTS material_files(id BIGINT PRIMARY KEY,user_id BIGINT,object_key VARCHAR(512),
    status VARCHAR(32),version INT DEFAULT 0,created_at TIMESTAMP,updated_at TIMESTAMP,deleted_at TIMESTAMP);
CREATE TABLE IF NOT EXISTS file_attachments(id BIGINT PRIMARY KEY,upload_id BIGINT,campus_id BIGINT,
    owner_user_id BIGINT,object_key_encrypted VARBINARY(2048),state VARCHAR(32),created_at TIMESTAMP,
    updated_at TIMESTAMP);
CREATE TABLE IF NOT EXISTS file_cleanup_jobs(id BIGINT PRIMARY KEY,object_key_encrypted VARBINARY(2048),
    upload_id BIGINT,attachment_id BIGINT,state VARCHAR(32),retry_count INT DEFAULT 0,
    next_retry_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP);
