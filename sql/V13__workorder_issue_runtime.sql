-- =============================================================
-- 材智通 · V13 工单与校园问题运行时（阶段 8）
-- 覆盖：校园问题公开授权、工单待受理审核依据
-- =============================================================

SET NAMES utf8mb4;

-- 校园问题独立的公开授权结果，公开接口不能仅凭 status 判断可见性
ALTER TABLE campus_issues
  ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'private' COMMENT 'public/private 公开授权',
  ADD KEY idx_issues_visibility (campus_id, visibility);

-- 工单待受理阶段持久化审核通过依据（由 work_order_events 记录流转与 comment）
-- work_order_events 已含 from_status/to_status/operator_id/comment，无需额外列
