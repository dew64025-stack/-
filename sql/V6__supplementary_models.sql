-- =============================================================
-- 材智通 · V6 补充数据模型（跨阶段）
-- 覆盖：食堂/快递拥堵观测、成长档案、数据权利（导出/注销）、
--       可靠异步任务
-- =============================================================

SET NAMES utf8mb4;

-- 食堂/快递点拥堵观测（管理后台维护 + 学生反馈）
CREATE TABLE crowd_observations (
  id            BIGINT UNSIGNED NOT NULL,
  campus_id     BIGINT UNSIGNED NOT NULL,
  location_type VARCHAR(32) NOT NULL COMMENT 'canteen/express',
  location_name VARCHAR(128) NOT NULL,
  crowd_level   VARCHAR(16) NOT NULL COMMENT 'low/medium/high',
  source_type   VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT 'demo/manual/official',
  observed_at   DATETIME(3) NOT NULL COMMENT '观测时间',
  is_stale      TINYINT(1)  NOT NULL DEFAULT 0,
  created_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_crowd_campus_location (campus_id, location_type, observed_at),
  CONSTRAINT fk_crowd_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '拥堵观测';

-- 成长档案（用户主动确认的经历，排除匿名社区内容）
CREATE TABLE growth_records (
  id            BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  type          VARCHAR(32) NOT NULL COMMENT 'competition/research/volunteer/mutual_aid',
  title         VARCHAR(256) NOT NULL,
  description   VARCHAR(1024) NULL,
  proof_file_id BIGINT UNSIGNED NULL COMMENT '证明文件',
  confirmed     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户主动确认',
  occurred_at   DATETIME(3) NULL COMMENT '发生时间',
  version       INT UNSIGNED NOT NULL DEFAULT 0,
  created_at    DATETIME(3) NOT NULL,
  updated_at    DATETIME(3) NOT NULL,
  deleted_at    DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_growth_user (user_id),
  CONSTRAINT fk_growth_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_growth_proof FOREIGN KEY (proof_file_id) REFERENCES material_files (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '成长档案';

-- 数据权利（个人数据导出 / 注销删除申请）
CREATE TABLE data_requests (
  id              BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  type            VARCHAR(32) NOT NULL COMMENT 'export/deletion',
  status          VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/processing/completed/rejected',
  requested_at    DATETIME(3) NOT NULL,
  processed_at    DATETIME(3) NULL,
  retention_basis VARCHAR(255) NULL COMMENT '保留依据',
  result_json     JSON        NULL,
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_data_requests_user (user_id),
  KEY idx_data_requests_status (status),
  CONSTRAINT fk_data_requests_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '数据权利申请';

-- 可靠异步任务（AI 解析、消息发送、同步等通用任务调度）
CREATE TABLE async_jobs (
  id            BIGINT UNSIGNED NOT NULL,
  job_type      VARCHAR(64) NOT NULL COMMENT '任务类型',
  payload_json  JSON        NOT NULL,
  status        VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/succeeded/failed',
  dedup_key     CHAR(64)    NULL COMMENT '业务去重键',
  retry_count   INT UNSIGNED NOT NULL DEFAULT 0,
  max_retries   INT UNSIGNED NOT NULL DEFAULT 3,
  next_retry_at DATETIME(3) NULL,
  picked_at     DATETIME(3) NULL COMMENT '领取时间',
  result_json   JSON        NULL,
  error         VARCHAR(1024) NULL,
  created_at    DATETIME(3) NOT NULL,
  updated_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_async_jobs_dedup (dedup_key),
  KEY idx_async_jobs_status (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '异步任务';
