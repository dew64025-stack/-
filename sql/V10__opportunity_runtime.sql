-- =============================================================
-- 材智通 · V10 机会运行时（Task 5：通知/机会/AI解析/资格）
-- 只追加新结构，不改 V1~V9 已发布内容：
--   1) notices 关联通知附件（file_attachments）
--   2) ai_parse_tasks 补充租约/重试/复核/耗时/提示模板版本字段
--   3) user_opportunity_submissions 用户+机会唯一约束（先做冲突守卫，不静默删历史）
--   4) opportunity_rule_snapshots 资格规则与材料要求不可变版本快照
--   5) opportunity_eligibility_checks 记录画像版本
-- =============================================================

SET NAMES utf8mb4;

-- 1. 通知附件：只允许本校当前管理员自己的 clean notice_document 附件
ALTER TABLE notices
  ADD COLUMN attachment_id BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_notices_attachment FOREIGN KEY (attachment_id) REFERENCES file_attachments (id);

-- 2. AI 解析任务：worker 租约/fencing、复核闭环、模型与提示模板版本、耗时、202 收据关联
ALTER TABLE ai_parse_tasks
  ADD COLUMN async_job_id BIGINT UNSIGNED NULL,
  ADD COLUMN claim_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN picked_at DATETIME(3) NULL,
  ADD COLUMN next_retry_at DATETIME(3) NULL,
  ADD COLUMN retry_count INT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN prompt_template_version VARCHAR(64) NULL,
  ADD COLUMN duration_ms INT UNSIGNED NULL,
  ADD COLUMN reviewed_by BIGINT UNSIGNED NULL,
  ADD COLUMN review_decision VARCHAR(16) NULL,
  ADD COLUMN review_reason VARCHAR(512) NULL,
  ADD COLUMN opportunity_draft_id BIGINT UNSIGNED NULL,
  ADD KEY idx_parse_tasks_due (status, next_retry_at),
  ADD CONSTRAINT fk_parse_async_job FOREIGN KEY (async_job_id) REFERENCES async_jobs (id),
  ADD CONSTRAINT fk_parse_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
  ADD CONSTRAINT ck_parse_review_decision CHECK (review_decision IS NULL OR review_decision IN ('approved', 'rejected'));

-- 3. 用户自行提交：用户＋机会最多一份当前记录。
--    老库可能存在重复行：冲突守卫先失败（同 V8 模式），不静默删除历史数据；
--    存在冲突时由人工清理后再重跑迁移。
CREATE TEMPORARY TABLE opp_v10_conflict_guard (
  conflict_count BIGINT NOT NULL,
  CONSTRAINT chk_opp_v10_no_conflicts CHECK (conflict_count = 0)
);
INSERT INTO opp_v10_conflict_guard
  SELECT COUNT(*) FROM (SELECT user_id, opportunity_id FROM user_opportunity_submissions
                        GROUP BY user_id, opportunity_id HAVING COUNT(*) > 1) dup_submissions;
DROP TEMPORARY TABLE opp_v10_conflict_guard;

ALTER TABLE user_opportunity_submissions
  ADD UNIQUE KEY uq_submissions_user_opportunity (user_id, opportunity_id);

-- 3.5 机会当前规则版本（与 opportunities.version 乐观锁分离；快照不可变）
ALTER TABLE opportunities
  ADD COLUMN rules_version INT UNSIGNED NOT NULL DEFAULT 0;

-- 4. 资格规则/材料要求不可变快照：规则修改只增版本，旧依据不回写
CREATE TABLE opportunity_rule_snapshots (
  id                        BIGINT UNSIGNED NOT NULL,
  opportunity_id            BIGINT UNSIGNED NOT NULL,
  rules_version             INT UNSIGNED NOT NULL COMMENT '规则版本（发布/资格/预检引用此版本）',
  rules_json                JSON NOT NULL COMMENT '资格规则数组快照',
  material_requirements_json JSON NOT NULL COMMENT '材料要求数组快照',
  created_by                BIGINT UNSIGNED NULL,
  created_at                DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_rule_snapshots (opportunity_id, rules_version),
  CONSTRAINT fk_rule_snapshots_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '机会规则与材料要求版本快照（不可变）';

-- 5. 资格匹配结果记录画像版本（解释历史结论用）
ALTER TABLE opportunity_eligibility_checks
  ADD COLUMN profile_version INT UNSIGNED NULL;
