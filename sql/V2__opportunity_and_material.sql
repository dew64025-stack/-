-- =============================================================
-- 材智通 · V2 机会与材料（阶段 2）
-- 覆盖：通知原文、AI 解析、机会、资格规则、收藏提醒、
--       材料保险箱、材料清单、预检任务与结果
-- 说明：material_versions 为不可变版本，清单项绑定具体版本；
--       precheck_tasks 持久化不可变输入快照，旧结果不得回写覆盖新状态
-- =============================================================

SET NAMES utf8mb4;

-- 通知原文（外部来源，保留原文与来源标识）
CREATE TABLE notices (
  id                 BIGINT UNSIGNED NOT NULL,
  campus_id          BIGINT UNSIGNED NOT NULL,
  title              VARCHAR(256) NOT NULL,
  content            MEDIUMTEXT   NULL,
  raw_url            VARCHAR(1024) NULL,
  publisher          VARCHAR(128) NULL COMMENT '发布主体',
  published_at       DATETIME(3)  NULL,
  source_type        VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT 'demo/manual/official',
  source_name        VARCHAR(128) NULL,
  external_id        VARCHAR(128) NULL,
  official_reference VARCHAR(512) NULL,
  is_stale           TINYINT(1)   NOT NULL DEFAULT 0,
  status             VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/parsing/reviewing/published/archived',
  version            INT UNSIGNED NOT NULL DEFAULT 0,
  created_by         BIGINT UNSIGNED NULL,
  updated_by         BIGINT UNSIGNED NULL,
  created_at         DATETIME(3)  NOT NULL,
  updated_at         DATETIME(3)  NOT NULL,
  deleted_at         DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_notices_campus_status (campus_id, status),
  KEY idx_notices_external (external_id),
  CONSTRAINT fk_notices_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '通知原文';

-- AI 解析任务
CREATE TABLE ai_parse_tasks (
  id           BIGINT UNSIGNED NOT NULL,
  notice_id    BIGINT UNSIGNED NOT NULL,
  model        VARCHAR(64)  NULL,
  status       VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/processing/succeeded/failed',
  result_json  JSON         NULL,
  confidence   DECIMAL(5,4) NULL,
  error        VARCHAR(1024) NULL,
  input_digest CHAR(64)     NULL,
  created_at   DATETIME(3)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_parse_tasks_notice (notice_id),
  KEY idx_parse_tasks_status (status),
  CONSTRAINT fk_parse_tasks_notice FOREIGN KEY (notice_id) REFERENCES notices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT 'AI 解析任务';

-- AI 解析字段（字段级原文证据）
CREATE TABLE ai_parse_fields (
  id                BIGINT UNSIGNED NOT NULL,
  parse_task_id     BIGINT UNSIGNED NOT NULL,
  field_key         VARCHAR(64)  NOT NULL,
  field_value       TEXT         NULL,
  evidence_location VARCHAR(512) NULL COMMENT '原文定位',
  confidence        DECIMAL(5,4) NULL,
  human_confirmed   TINYINT(1)   NOT NULL DEFAULT 0,
  human_value       TEXT         NULL,
  created_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_parse_fields_task (parse_task_id),
  CONSTRAINT fk_parse_fields_task FOREIGN KEY (parse_task_id) REFERENCES ai_parse_tasks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT 'AI 解析字段';

-- 机会
CREATE TABLE opportunities (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  notice_id   BIGINT UNSIGNED NULL,
  title       VARCHAR(256) NOT NULL,
  category    VARCHAR(32)  NOT NULL COMMENT 'scholarship/competition/research/internship/volunteer',
  organizer   VARCHAR(128) NULL,
  summary     TEXT         NULL,
  deadline    DATETIME(3)  NULL,
  status      VARCHAR(32)  NOT NULL DEFAULT 'published' COMMENT 'draft/parsing/reviewing/published/expired/archived',
  source_type VARCHAR(16)  NOT NULL DEFAULT 'manual',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_by  BIGINT UNSIGNED NULL,
  updated_by  BIGINT UNSIGNED NULL,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_opportunities_campus_status (campus_id, status, deadline),
  KEY idx_opportunities_category (category),
  CONSTRAINT fk_opportunities_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_opportunities_notice FOREIGN KEY (notice_id) REFERENCES notices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '机会';

-- 资格规则
CREATE TABLE opportunity_eligibility_rules (
  id             BIGINT UNSIGNED NOT NULL,
  opportunity_id BIGINT UNSIGNED NOT NULL,
  field_key      VARCHAR(64) NOT NULL COMMENT 'college/grade/skill/...',
  operator       VARCHAR(16) NOT NULL COMMENT 'eq/ne/in/contains/range',
  value_json     JSON        NOT NULL,
  severity       VARCHAR(16) NOT NULL DEFAULT 'hard' COMMENT 'hard/soft',
  sort_order     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at     DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_eligibility_rules_opportunity (opportunity_id, sort_order),
  CONSTRAINT fk_eligibility_rules_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '资格规则';

-- 资格匹配结果（记录规则版本与画像快照，便于解释历史结论）
CREATE TABLE opportunity_eligibility_checks (
  id               BIGINT UNSIGNED NOT NULL,
  opportunity_id   BIGINT UNSIGNED NOT NULL,
  user_id          BIGINT UNSIGNED NOT NULL,
  conclusion       VARCHAR(16) NOT NULL COMMENT 'eligible/possible/not_eligible',
  reason_json      JSON        NULL COMMENT '冲突条件说明',
  rules_version    INT UNSIGNED NULL COMMENT '规则版本',
  profile_snapshot JSON        NULL COMMENT '个人画像快照',
  created_at       DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_eligibility_checks_opportunity (opportunity_id),
  KEY idx_eligibility_checks_user (user_id),
  CONSTRAINT fk_eligibility_checks_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id),
  CONSTRAINT fk_eligibility_checks_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '资格匹配结果';

-- 机会材料清单项
CREATE TABLE opportunity_material_items (
  id             BIGINT UNSIGNED NOT NULL,
  opportunity_id BIGINT UNSIGNED NOT NULL,
  name           VARCHAR(128) NOT NULL,
  description    VARCHAR(512) NULL,
  required       TINYINT(1)   NOT NULL DEFAULT 1,
  sort_order     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_opp_material_items_opportunity (opportunity_id, sort_order),
  CONSTRAINT fk_opp_material_items_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '机会材料清单项';

-- 收藏
CREATE TABLE favorites (
  id          BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'opportunity/resource/mutual_aid_post/community_post',
  target_id   BIGINT UNSIGNED NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_favorites (user_id, target_type, target_id),
  CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '收藏';

-- 提醒
CREATE TABLE reminders (
  id          BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'opportunity/material/borrow/reservation',
  target_id   BIGINT UNSIGNED NOT NULL,
  remind_at   DATETIME(3)  NOT NULL,
  status      VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/sent/cancelled',
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_reminders_user_status (user_id, status),
  KEY idx_reminders_time (remind_at),
  CONSTRAINT fk_reminders_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '提醒';

-- 用户提交记录（"已自行提交"待办，不代表平台代交）
CREATE TABLE user_opportunity_submissions (
  id             BIGINT UNSIGNED NOT NULL,
  user_id        BIGINT UNSIGNED NOT NULL,
  opportunity_id BIGINT UNSIGNED NOT NULL,
  status         VARCHAR(32) NOT NULL DEFAULT 'submitted',
  submitted_at   DATETIME(3) NOT NULL,
  created_at     DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_submissions_user (user_id),
  KEY idx_submissions_opportunity (opportunity_id),
  CONSTRAINT fk_submissions_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_submissions_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '用户提交记录';

-- 材料文件元数据（文件本体存 MinIO；含有效期与标签）
CREATE TABLE material_files (
  id          BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  object_key  VARCHAR(512) NOT NULL COMMENT '对象存储键',
  file_hash   CHAR(64)     NOT NULL COMMENT '内容散列，用于去重',
  size_bytes  BIGINT UNSIGNED NOT NULL,
  mime_type   VARCHAR(128) NOT NULL,
  file_name   VARCHAR(255) NULL,
  expires_at  DATETIME(3)  NULL COMMENT '材料有效期',
  tags_json   JSON         NULL COMMENT '标签',
  encrypted   TINYINT(1)   NOT NULL DEFAULT 0,
  scan_status VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/clean/infected',
  status      VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT 'active/deleted',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_material_files_user (user_id),
  KEY idx_material_files_hash (file_hash),
  CONSTRAINT fk_material_files_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '材料文件元数据';

-- 材料文件版本（不可变：只允许 INSERT，业务层不得 UPDATE 已有版本）
CREATE TABLE material_versions (
  id         BIGINT UNSIGNED NOT NULL,
  file_id    BIGINT UNSIGNED NOT NULL,
  version_no INT UNSIGNED NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_material_versions (file_id, version_no),
  CONSTRAINT fk_material_versions_file FOREIGN KEY (file_id) REFERENCES material_files (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '材料文件版本';

-- 材料清单（某机会对应用户的材料保险箱视图）
CREATE TABLE material_checklists (
  id             BIGINT UNSIGNED NOT NULL,
  user_id        BIGINT UNSIGNED NOT NULL,
  opportunity_id BIGINT UNSIGNED NOT NULL,
  status         VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT 'draft/ready',
  version        INT UNSIGNED NOT NULL DEFAULT 0,
  created_at     DATETIME(3) NOT NULL,
  updated_at     DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_checklists (user_id, opportunity_id),
  CONSTRAINT fk_checklists_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_checklists_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '材料清单';

-- 材料清单项（绑定不可变的文件版本）
CREATE TABLE material_checklist_items (
  id                  BIGINT UNSIGNED NOT NULL,
  checklist_id        BIGINT UNSIGNED NOT NULL,
  item_name           VARCHAR(128) NOT NULL,
  required            TINYINT(1)   NOT NULL DEFAULT 1,
  material_version_id BIGINT UNSIGNED NULL COMMENT '绑定的文件版本（不可变）',
  status              VARCHAR(32)  NOT NULL DEFAULT 'missing' COMMENT 'missing/uploaded/prechecking/needs_fix/basic_complete',
  created_at          DATETIME(3)  NOT NULL,
  updated_at          DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_checklist_items_checklist (checklist_id),
  CONSTRAINT fk_checklist_items_checklist FOREIGN KEY (checklist_id) REFERENCES material_checklists (id),
  CONSTRAINT fk_checklist_items_version FOREIGN KEY (material_version_id) REFERENCES material_versions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '材料清单项';

-- 预检任务（版本字段必填 + 不可变输入快照）
CREATE TABLE precheck_tasks (
  id                   BIGINT UNSIGNED NOT NULL,
  checklist_id         BIGINT UNSIGNED NOT NULL,
  user_id              BIGINT UNSIGNED NOT NULL,
  status               VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/succeeded/failed',
  result_json          JSON        NULL,
  checklist_version    INT UNSIGNED NOT NULL COMMENT '清单版本',
  rules_version        INT UNSIGNED NOT NULL COMMENT '规则版本',
  input_digest         CHAR(64)    NOT NULL COMMENT '输入摘要',
  input_snapshot       JSON        NOT NULL COMMENT '不可变输入快照（清单项+文件版本+规则）',
  model_config_version VARCHAR(64) NULL COMMENT '模型配置版本',
  created_at           DATETIME(3) NOT NULL,
  updated_at           DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_precheck_tasks_checklist (checklist_id),
  KEY idx_precheck_tasks_user (user_id),
  CONSTRAINT fk_precheck_tasks_checklist FOREIGN KEY (checklist_id) REFERENCES material_checklists (id),
  CONSTRAINT fk_precheck_tasks_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '预检任务';

-- 预检结果（逐项，关联具体清单项）
CREATE TABLE precheck_results (
  id                BIGINT UNSIGNED NOT NULL,
  precheck_task_id  BIGINT UNSIGNED NOT NULL,
  checklist_item_id BIGINT UNSIGNED NOT NULL COMMENT '关联材料清单项',
  item_key          VARCHAR(64)  NOT NULL COMMENT '检查项键（兼容）',
  level             VARCHAR(16)  NOT NULL COMMENT 'passed/confirm/missing',
  message           VARCHAR(512) NULL,
  evidence_json     JSON         NULL,
  created_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_precheck_results_task (precheck_task_id),
  CONSTRAINT fk_precheck_results_task FOREIGN KEY (precheck_task_id) REFERENCES precheck_tasks (id),
  CONSTRAINT fk_precheck_results_item FOREIGN KEY (checklist_item_id) REFERENCES material_checklist_items (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '预检结果';

-- 材料使用记录
CREATE TABLE material_usage_records (
  id           BIGINT UNSIGNED NOT NULL,
  file_id      BIGINT UNSIGNED NOT NULL,
  used_in_type VARCHAR(32) NOT NULL COMMENT 'opportunity/checklist/...',
  used_in_id   BIGINT UNSIGNED NOT NULL,
  created_at   DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_material_usage_file (file_id),
  CONSTRAINT fk_material_usage_file FOREIGN KEY (file_id) REFERENCES material_files (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '材料使用记录';
