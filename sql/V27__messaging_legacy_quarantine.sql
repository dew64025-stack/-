-- Preserve old mixed private-chat history, but revoke access for every former participant.
-- V25 isolated newly created participant pairs; it did not revoke access to the old shared row.
ALTER TABLE conversations
  ADD COLUMN access_revoked_at DATETIME(3) NULL COMMENT '旧版混合私聊隔离时间；保留原始成员与消息';

UPDATE conversations c
SET c.access_revoked_at=UTC_TIMESTAMP(3)
WHERE c.context_type IN ('mutual_aid_post','borrow_request')
  AND (c.participant_key='' OR
       (SELECT COUNT(*) FROM conversation_members m WHERE m.conversation_id=c.id)<>2);
