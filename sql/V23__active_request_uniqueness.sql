-- One active data-rights request per user/type and one pending dispute per credit event.
-- Generated keys are NULL outside the active window, so closed history is unlimited while
-- concurrent double-submits collide on the unique index instead of both inserting.

ALTER TABLE data_requests
  ADD COLUMN active_key VARCHAR(128)
    GENERATED ALWAYS AS (CASE WHEN status IN ('pending','processing')
      THEN CONCAT(user_id, ':', type) ELSE NULL END) STORED
    COMMENT '待处理申请唯一键；终态时为空',
  ADD UNIQUE KEY uq_data_requests_active (active_key);

ALTER TABLE credit_disputes
  ADD COLUMN pending_event_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN status='pending' THEN credit_event_id ELSE NULL END) STORED
    COMMENT '待处理纠错的信用事件唯一键；非待处理时为空',
  ADD UNIQUE KEY uq_credit_disputes_pending_event (pending_event_id);
