-- =============================================================
-- 材智通 · V11 材料清单与预检运行时（Task 6）
-- 只追加新结构，不改 V1~V10 已发布内容：
--   1) precheck_tasks 补充 worker 租约/重试/fencing 字段
--   2) precheck_tasks 记录 AI 处理授权（authorizations.id）
--   3) 清单项与预检常用查询索引
-- =============================================================

SET NAMES utf8mb4;

-- 1. 整清单预检任务：多实例领取安全 + 有限重试 + 202 收据关联
ALTER TABLE precheck_tasks
  ADD COLUMN async_job_id BIGINT UNSIGNED NULL,
  ADD COLUMN claim_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN picked_at DATETIME(3) NULL,
  ADD COLUMN next_retry_at DATETIME(3) NULL,
  ADD COLUMN retry_count INT UNSIGNED NOT NULL DEFAULT 0,
  ADD KEY idx_precheck_tasks_due (status, next_retry_at),
  ADD CONSTRAINT fk_precheck_async_job FOREIGN KEY (async_job_id) REFERENCES async_jobs (id);

-- 2. AI 处理材料必须先取得专门授权；任务保存授权依据（不可覆盖为后续授权）
ALTER TABLE precheck_tasks
  ADD COLUMN ai_consent_id BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_precheck_ai_consent FOREIGN KEY (ai_consent_id) REFERENCES authorizations (id);

-- 3. 清单项查询（本人清单、预检结果回填）与预检任务时间线
ALTER TABLE material_checklist_items
  ADD KEY idx_checklist_items_due (checklist_id, id);
ALTER TABLE precheck_tasks
  ADD KEY idx_precheck_tasks_checklist_time (checklist_id, created_at);
