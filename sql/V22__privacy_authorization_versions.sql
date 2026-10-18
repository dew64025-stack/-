-- Data-rights grant records carry the accepted privacy-document version; this is not
-- the optimistic-locking version column.

ALTER TABLE authorizations
  ADD COLUMN document_version VARCHAR(64) NULL COMMENT '已同意的协议版本' AFTER scope;
