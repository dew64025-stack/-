-- Messaging runtime constraints for Phase 7.1/7.2.
-- Context columns stay NULL for historical conversations; no historical participant is inferred.

ALTER TABLE conversations
  ADD COLUMN context_type VARCHAR(32) NULL COMMENT 'mutual_aid_post/borrow_request/team',
  ADD COLUMN context_id BIGINT UNSIGNED NULL,
  ADD COLUMN created_by BIGINT UNSIGNED NULL,
  ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0,
  ADD UNIQUE KEY uq_conversations_context (campus_id, context_type, context_id),
  ADD CONSTRAINT fk_conversations_creator FOREIGN KEY (created_by) REFERENCES users (id);

ALTER TABLE conversation_members
  ADD COLUMN last_read_message_id BIGINT UNSIGNED NULL,
  ADD COLUMN last_read_at DATETIME(3) NULL;

ALTER TABLE messages
  ADD COLUMN client_message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD UNIQUE KEY uq_messages_sender_client (conversation_id, sender_id, client_message_id),
  ADD KEY idx_messages_conversation_id (conversation_id, id);

ALTER TABLE notifications
  ADD COLUMN dedup_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD UNIQUE KEY uq_notifications_dedup (dedup_key),
  ADD KEY idx_notifications_user_id (user_id, id);

-- Existing duplicate (user, template, scene) rows would make the re-consent upsert ambiguous.
-- Fail loudly instead of silently deleting one of them.
CREATE TEMPORARY TABLE subscription_v20_conflict_guard (
  conflict_count BIGINT NOT NULL,
  CONSTRAINT chk_subscription_v20_no_conflicts CHECK (conflict_count = 0)
);
INSERT INTO subscription_v20_conflict_guard
  SELECT COUNT(*) FROM (
    SELECT user_id, template_id, scene FROM subscription_authorizations
    GROUP BY user_id, template_id, scene HAVING COUNT(*) > 1
  ) conflicts;
DROP TEMPORARY TABLE subscription_v20_conflict_guard;

ALTER TABLE subscription_authorizations
  ADD UNIQUE KEY uq_subscription_user_template_scene (user_id, template_id, scene);

ALTER TABLE message_send_records
  ADD KEY idx_send_records_created (created_at);
