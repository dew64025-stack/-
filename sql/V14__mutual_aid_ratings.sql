-- =============================================================
-- 材智通 · V14 互助运行时（阶段 9）
-- 覆盖：互助交接双方评价、借用交接事件
-- =============================================================

SET NAMES utf8mb4;

CREATE TABLE mutual_aid_ratings (
  id         BIGINT UNSIGNED NOT NULL,
  post_id    BIGINT UNSIGNED NOT NULL,
  rater_id   BIGINT UNSIGNED NOT NULL,
  score      TINYINT UNSIGNED NOT NULL COMMENT '1..5',
  comment    VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_mutual_aid_ratings_post_rater (post_id, rater_id),
  KEY idx_mutual_aid_ratings_post (post_id),
  CONSTRAINT chk_mutual_aid_ratings_score CHECK (score BETWEEN 1 AND 5),
  CONSTRAINT fk_mutual_aid_ratings_post FOREIGN KEY (post_id) REFERENCES mutual_aid_posts (id),
  CONSTRAINT fk_mutual_aid_ratings_rater FOREIGN KEY (rater_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '互助双方评价';

-- 借用交接事件（双方各自确认，不能以单方点击作为双方交接/归还事实）
CREATE TABLE borrow_events (
  id                 BIGINT UNSIGNED NOT NULL,
  borrow_request_id  BIGINT UNSIGNED NOT NULL,
  actor_id           BIGINT UNSIGNED NOT NULL,
  action             VARCHAR(32) NOT NULL COMMENT 'approve/cancel/confirm_handover/confirm_return',
  created_at         DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_borrow_events_request (borrow_request_id),
  CONSTRAINT fk_borrow_events_request FOREIGN KEY (borrow_request_id) REFERENCES borrow_requests (id),
  CONSTRAINT fk_borrow_events_actor FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '借用交接事件';

-- 组队开放角色缺口（{role,skillTags,quantity}）
ALTER TABLE teams
  ADD COLUMN open_roles_json JSON NULL COMMENT '开放角色缺口';
