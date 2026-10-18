-- =============================================================
-- V26 第三轮审查修复
-- 1) reports 保存处置理由与动作，返回给举报人
-- 2) appeals 保存申诉决定理由；仅对 pending 状态建立唯一键，
--    阻止同一处罚并发产生多条待处理申诉
-- 3) external_systems 记录连续瞬时失败与熔断截止时间
-- =============================================================

SET NAMES utf8mb4;

ALTER TABLE reports
  ADD COLUMN resolution        VARCHAR(512) NULL COMMENT '管理员处置理由',
  ADD COLUMN resolution_action VARCHAR(32)  NULL COMMENT '可选处置动作';

ALTER TABLE appeals
  ADD COLUMN decision_reason VARCHAR(512) NULL COMMENT '申诉决定理由',
  ADD COLUMN pending_penalty_user VARCHAR(64)
    GENERATED ALWAYS AS (
      CASE WHEN status = 'pending' AND penalty_id IS NOT NULL
        THEN CONCAT(penalty_id, ':', user_id) ELSE NULL END
    ) STORED COMMENT '同一处罚仅一个待处理申诉',
  ADD UNIQUE KEY uq_appeals_pending_penalty (pending_penalty_user);

ALTER TABLE external_systems
  ADD COLUMN failure_count       INT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN circuit_open_until  DATETIME(3)  NULL;
