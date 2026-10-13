-- =============================================================
-- 材智通 · V3 服务与预约（阶段 3）
-- 覆盖：报修工单、校园问题广场、地图、评价、
--       资源（图书馆/教室）、时段、预约、占用、签到
--
-- 预约占用模型：
--   · reservations 只保存预约历史（含 campus_id、user_id、slot_id、seat_no）
--   · resource_occupancies 只保存「当前有效占用」；取消/拒绝时删除本行释放
--   · 资源从 slot_id 派生，不在占用表冗余 resource_id，避免关联不一致
--   · 独占资源 seat_no 恒为 0；容量资源 seat_no 为 1..capacity
--   · UNIQUE(slot_id, seat_no) 是防超卖的最终约束；容量上限由服务端在
--     锁定 slot 行（SELECT ... FOR UPDATE）的事务内校验并写入
--   · 重叠时段由服务端对同一资源加锁后校验，(resource_id, slot_date, start_time) 防完全重复
-- =============================================================

SET NAMES utf8mb4;

-- 报修工单
CREATE TABLE work_orders (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  assignee_id BIGINT UNSIGNED NULL COMMENT '当前处理人',
  category    VARCHAR(32) NOT NULL COMMENT 'dorm/facility/other',
  location    VARCHAR(255) NULL,
  description VARCHAR(1024) NOT NULL,
  urgency     VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT 'normal/urgent',
  status      VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/reviewing/accepted/processing/rated/completed/rejected',
  images_json JSON        NULL COMMENT '图片对象键列表',
  source_type VARCHAR(16) NOT NULL DEFAULT 'manual',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_by  BIGINT UNSIGNED NULL,
  updated_by  BIGINT UNSIGNED NULL,
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  deleted_at  DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_work_orders_campus_status (campus_id, status),
  KEY idx_work_orders_user (user_id),
  KEY idx_work_orders_assignee (assignee_id),
  CONSTRAINT fk_work_orders_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_work_orders_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '报修工单';

-- 工单状态流转记录（转办 / 关闭原因）
CREATE TABLE work_order_events (
  id            BIGINT UNSIGNED NOT NULL,
  work_order_id BIGINT UNSIGNED NOT NULL,
  from_status   VARCHAR(32) NULL,
  to_status     VARCHAR(32) NOT NULL,
  operator_id   BIGINT UNSIGNED NULL,
  comment       VARCHAR(512) NULL,
  created_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_work_order_events_order (work_order_id),
  CONSTRAINT fk_work_order_events_order FOREIGN KEY (work_order_id) REFERENCES work_orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '工单状态流转';

-- 工单评价
CREATE TABLE work_order_ratings (
  id            BIGINT UNSIGNED NOT NULL,
  work_order_id BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  score         TINYINT UNSIGNED NOT NULL COMMENT '1~5',
  comment       VARCHAR(512) NULL,
  created_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_work_order_ratings (work_order_id, user_id),
  CONSTRAINT chk_work_order_ratings_score CHECK (score BETWEEN 1 AND 5),
  CONSTRAINT fk_work_order_ratings_order FOREIGN KEY (work_order_id) REFERENCES work_orders (id),
  CONSTRAINT fk_work_order_ratings_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '工单评价';

-- 校园问题（公开问题广场）
CREATE TABLE campus_issues (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  title       VARCHAR(256) NOT NULL,
  category    VARCHAR(32)  NOT NULL COMMENT 'repair/canteen/express/other',
  description VARCHAR(1024) NULL,
  status      VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/reviewing/published/hidden/processing/completed',
  follow_count INT UNSIGNED NOT NULL DEFAULT 0,
  vote_count   INT UNSIGNED NOT NULL DEFAULT 0,
  source_type VARCHAR(16)  NOT NULL DEFAULT 'manual',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_issues_campus_status (campus_id, status),
  KEY idx_issues_category (category),
  CONSTRAINT fk_issues_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_issues_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '校园问题';

-- 问题地图位置
CREATE TABLE campus_issue_locations (
  id        BIGINT UNSIGNED NOT NULL,
  issue_id  BIGINT UNSIGNED NOT NULL,
  name      VARCHAR(128) NULL,
  latitude  DECIMAL(10,7) NOT NULL,
  longitude DECIMAL(10,7) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_issue_locations_issue (issue_id),
  CONSTRAINT fk_issue_locations_issue FOREIGN KEY (issue_id) REFERENCES campus_issues (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '问题地图位置';

-- 问题关注
CREATE TABLE campus_issue_follows (
  id         BIGINT UNSIGNED NOT NULL,
  issue_id   BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_issue_follows (issue_id, user_id),
  CONSTRAINT fk_issue_follows_issue FOREIGN KEY (issue_id) REFERENCES campus_issues (id),
  CONSTRAINT fk_issue_follows_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '问题关注';

-- 问题补充证据
CREATE TABLE campus_issue_evidences (
  id          BIGINT UNSIGNED NOT NULL,
  issue_id    BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  content     VARCHAR(512) NULL,
  images_json JSON         NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_issue_evidences_issue (issue_id),
  CONSTRAINT fk_issue_evidences_issue FOREIGN KEY (issue_id) REFERENCES campus_issues (id),
  CONSTRAINT fk_issue_evidences_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '问题补充证据';

-- 问题投票
CREATE TABLE campus_issue_votes (
  id         BIGINT UNSIGNED NOT NULL,
  issue_id   BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  value      TINYINT NOT NULL DEFAULT 1 COMMENT '1 赞同 / -1 反对',
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_issue_votes (issue_id, user_id),
  CONSTRAINT chk_issue_votes_value CHECK (value IN (1, -1)),
  CONSTRAINT fk_issue_votes_issue FOREIGN KEY (issue_id) REFERENCES campus_issues (id),
  CONSTRAINT fk_issue_votes_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '问题投票';

-- 资源（图书馆座位 / 教室 / 设备）
CREATE TABLE resources (
  id            BIGINT UNSIGNED NOT NULL,
  campus_id     BIGINT UNSIGNED NOT NULL,
  region_id     BIGINT UNSIGNED NULL,
  type          VARCHAR(32) NOT NULL COMMENT 'library_seat/classroom/equipment',
  name          VARCHAR(128) NOT NULL,
  capacity      INT UNSIGNED NULL,
  equipment_json JSON       NULL COMMENT '设备/设施列表',
  status        VARCHAR(32) NOT NULL DEFAULT 'active',
  source_type   VARCHAR(16) NOT NULL DEFAULT 'manual',
  version       INT UNSIGNED NOT NULL DEFAULT 0,
  created_at    DATETIME(3) NOT NULL,
  updated_at    DATETIME(3) NOT NULL,
  deleted_at    DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_resources_campus_type (campus_id, type),
  CONSTRAINT fk_resources_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_resources_region FOREIGN KEY (region_id) REFERENCES campus_regions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '资源';

-- 资源时段
CREATE TABLE resource_slots (
  id          BIGINT UNSIGNED NOT NULL,
  resource_id BIGINT UNSIGNED NOT NULL,
  slot_date   DATE          NOT NULL,
  start_time  TIME          NOT NULL,
  end_time    TIME          NOT NULL,
  capacity    INT UNSIGNED  NULL,
  status      VARCHAR(32)   NOT NULL DEFAULT 'available' COMMENT 'available/maintenance',
  version     INT UNSIGNED  NOT NULL DEFAULT 0,
  created_at  DATETIME(3)   NOT NULL,
  updated_at  DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_slots_resource_date_start (resource_id, slot_date, start_time),
  CONSTRAINT chk_slots_time CHECK (end_time > start_time),
  CONSTRAINT fk_slots_resource FOREIGN KEY (resource_id) REFERENCES resources (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '资源时段';

-- 预约（历史记录；资源从 slot_id 派生，不在本表冗余）
CREATE TABLE reservations (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  slot_id     BIGINT UNSIGNED NOT NULL,
  seat_no     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 独占，1..N 容量座位',
  status      VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/confirmed/checked_in/completed/cancelled/no_show/rejected',
  source_type VARCHAR(16) NOT NULL DEFAULT 'demo',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  deleted_at  DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_reservations_id_slot_seat (id, slot_id, seat_no),
  KEY idx_reservations_user (user_id),
  KEY idx_reservations_slot (slot_id),
  CONSTRAINT fk_reservations_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_reservations_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id),
  CONSTRAINT fk_reservations_slot FOREIGN KEY (slot_id) REFERENCES resource_slots (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '预约';

-- 资源有效占用（仅有效行，取消/拒绝时删除本行释放）
CREATE TABLE resource_occupancies (
  id             BIGINT UNSIGNED NOT NULL,
  slot_id        BIGINT UNSIGNED NOT NULL,
  reservation_id BIGINT UNSIGNED NOT NULL,
  seat_no        INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 独占，1..N 容量座位',
  created_at     DATETIME(3) NOT NULL,
  updated_at     DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_occupancies_slot_seat (slot_id, seat_no),
  UNIQUE KEY uq_occupancies_reservation (reservation_id),
  CONSTRAINT fk_occupancies_slot FOREIGN KEY (slot_id) REFERENCES resource_slots (id),
  CONSTRAINT fk_occupancies_reservation_slot_seat FOREIGN KEY (reservation_id, slot_id, seat_no) REFERENCES reservations (id, slot_id, seat_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '资源有效占用';

-- 预约签到
CREATE TABLE reservation_checkins (
  id             BIGINT UNSIGNED NOT NULL,
  reservation_id BIGINT UNSIGNED NOT NULL,
  checked_in_at  DATETIME(3) NOT NULL,
  created_at     DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_checkins_reservation (reservation_id),
  CONSTRAINT fk_checkins_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '预约签到';
