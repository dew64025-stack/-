-- =============================================================
-- 材智通 · V4 互助与社区（阶段 4）
-- 覆盖：失物/二手/教材/技能/借用、组队、站内沟通、
--       匿名关系社区、举报、处罚、申诉
-- 安全说明：pseudo_identities 保存匿名到真实身份的映射，
--       仅安全调查账号可读，普通业务账号无 SELECT 权限（见 DATABASE.md）
-- =============================================================

SET NAMES utf8mb4;

-- 互助发布（失物/二手/教材/技能/借用，按 category 区分）
CREATE TABLE mutual_aid_posts (
  id           BIGINT UNSIGNED NOT NULL,
  campus_id    BIGINT UNSIGNED NOT NULL,
  user_id      BIGINT UNSIGNED NOT NULL,
  category     VARCHAR(32) NOT NULL COMMENT 'lost_found/secondhand/textbook/skill/borrow',
  title        VARCHAR(256) NOT NULL,
  content      VARCHAR(2048) NULL,
  detail_json  JSON         NULL COMMENT '分类结构化详情（失物类型/发生时间地点等）',
  images_json  JSON         NULL,
  price        DECIMAL(10,2) NULL COMMENT '二手/教材价格，空表示不涉及',
  exchange_area VARCHAR(128) NULL COMMENT '交接区域',
  status       VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/reviewing/published/communicating/reserved/completed/cancelled/rejected',
  source_type  VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT 'demo/manual/official',
  version      INT UNSIGNED NOT NULL DEFAULT 0,
  created_by   BIGINT UNSIGNED NULL,
  updated_by   BIGINT UNSIGNED NULL,
  created_at   DATETIME(3) NOT NULL,
  updated_at   DATETIME(3) NOT NULL,
  deleted_at   DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_mutual_posts_campus_category (campus_id, category, status),
  KEY idx_mutual_posts_user (user_id),
  CONSTRAINT chk_mutual_posts_price CHECK (price IS NULL OR price >= 0),
  CONSTRAINT fk_mutual_posts_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_mutual_posts_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '互助发布';

-- 失物匹配
CREATE TABLE lost_found_matches (
  id            BIGINT UNSIGNED NOT NULL,
  lost_post_id  BIGINT UNSIGNED NOT NULL,
  found_post_id BIGINT UNSIGNED NOT NULL,
  similarity    DECIMAL(5,4) NULL,
  status        VARCHAR(32) NOT NULL DEFAULT 'suggested' COMMENT 'suggested/verified/returned/ignored',
  created_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_lost_found_lost (lost_post_id),
  KEY idx_lost_found_found (found_post_id),
  CONSTRAINT fk_lost_found_lost FOREIGN KEY (lost_post_id) REFERENCES mutual_aid_posts (id),
  CONSTRAINT fk_lost_found_found FOREIGN KEY (found_post_id) REFERENCES mutual_aid_posts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '失物匹配';

-- 借用申请
CREATE TABLE borrow_requests (
  id          BIGINT UNSIGNED NOT NULL,
  post_id     BIGINT UNSIGNED NOT NULL,
  borrower_id BIGINT UNSIGNED NOT NULL,
  start_time  DATETIME(3) NULL,
  end_time    DATETIME(3) NULL,
  status      VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/confirmed/handed_over/returned/cancelled',
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_borrow_requests_post (post_id),
  KEY idx_borrow_requests_borrower (borrower_id),
  CONSTRAINT chk_borrow_time CHECK (end_time IS NULL OR start_time IS NULL OR end_time > start_time),
  CONSTRAINT fk_borrow_requests_post FOREIGN KEY (post_id) REFERENCES mutual_aid_posts (id),
  CONSTRAINT fk_borrow_requests_borrower FOREIGN KEY (borrower_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '借用申请';

-- 组队
CREATE TABLE teams (
  id             BIGINT UNSIGNED NOT NULL,
  campus_id      BIGINT UNSIGNED NOT NULL,
  opportunity_id BIGINT UNSIGNED NULL COMMENT '关联竞赛机会，可空',
  leader_id      BIGINT UNSIGNED NOT NULL,
  title          VARCHAR(256) NOT NULL,
  goal           VARCHAR(1024) NULL,
  status         VARCHAR(32) NOT NULL DEFAULT 'recruiting' COMMENT 'recruiting/full/closed',
  version        INT UNSIGNED NOT NULL DEFAULT 0,
  created_at     DATETIME(3) NOT NULL,
  updated_at     DATETIME(3) NOT NULL,
  deleted_at     DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_teams_campus_status (campus_id, status),
  KEY idx_teams_opportunity (opportunity_id),
  CONSTRAINT fk_teams_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_teams_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id),
  CONSTRAINT fk_teams_leader FOREIGN KEY (leader_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '组队';

-- 团队成员 / 角色缺口（同一用户不重复成为同一团队成员）
CREATE TABLE team_members (
  id         BIGINT UNSIGNED NOT NULL,
  team_id    BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NULL COMMENT '为空表示待补的角色缺口',
  role       VARCHAR(64) NULL,
  skill_tags JSON        NULL,
  status     VARCHAR(32) NOT NULL DEFAULT 'member' COMMENT 'member/open(缺口)',
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_team_members_team_user (team_id, user_id),
  KEY idx_team_members_user (user_id),
  CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams (id),
  CONSTRAINT fk_team_members_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '团队成员';

-- 组队申请
CREATE TABLE team_applications (
  id         BIGINT UNSIGNED NOT NULL,
  team_id    BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  message    VARCHAR(512) NULL,
  status     VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_team_applications_team (team_id),
  KEY idx_team_applications_user (user_id),
  CONSTRAINT fk_team_applications_team FOREIGN KEY (team_id) REFERENCES teams (id),
  CONSTRAINT fk_team_applications_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '组队申请';

-- 匿名社区帖子（前台匿名，pseudo_identity_id 为每帖独立随机身份）
CREATE TABLE community_posts (
  id                 BIGINT UNSIGNED NOT NULL,
  campus_id          BIGINT UNSIGNED NOT NULL,
  pseudo_identity_id BIGINT UNSIGNED NOT NULL,
  author_name        VARCHAR(64)  NOT NULL COMMENT '随机匿名昵称（展示用，每帖独立）',
  title              VARCHAR(256) NOT NULL,
  content            TEXT         NULL,
  status             VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT 'pending/published/reported/restricted/deleted',
  is_high_risk       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '高风险内容转专门队列',
  version            INT UNSIGNED NOT NULL DEFAULT 0,
  created_at         DATETIME(3)  NOT NULL,
  updated_at         DATETIME(3)  NOT NULL,
  deleted_at         DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_community_posts_campus_status (campus_id, status),
  CONSTRAINT fk_community_posts_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '匿名社区帖子';

-- 匿名社区评论
CREATE TABLE community_comments (
  id                 BIGINT UNSIGNED NOT NULL,
  post_id            BIGINT UNSIGNED NOT NULL,
  pseudo_identity_id BIGINT UNSIGNED NOT NULL,
  author_name        VARCHAR(64)  NOT NULL COMMENT '随机匿名昵称（展示用）',
  content            VARCHAR(2048) NOT NULL,
  status             VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/published/deleted',
  created_at         DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_community_comments_post (post_id),
  CONSTRAINT fk_community_comments_post FOREIGN KEY (post_id) REFERENCES community_posts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '匿名社区评论';

-- 匿名身份映射（仅安全调查账号可读，见 DATABASE.md 账号授权）
CREATE TABLE pseudo_identities (
  id          BIGINT UNSIGNED NOT NULL,
  post_id     BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  random_name VARCHAR(64) NOT NULL COMMENT '每帖独立随机身份',
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_pseudo_identities_post (post_id),
  KEY idx_pseudo_identities_user (user_id),
  CONSTRAINT fk_pseudo_identities_post FOREIGN KEY (post_id) REFERENCES community_posts (id),
  CONSTRAINT fk_pseudo_identities_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '匿名身份映射（受限）';

-- 举报
CREATE TABLE reports (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  reporter_id BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'community_post/community_comment/mutual_aid_post/message',
  target_id   BIGINT UNSIGNED NOT NULL,
  reason      VARCHAR(512) NOT NULL,
  status      VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/reviewing/resolved/dismissed',
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_reports_target (target_type, target_id),
  KEY idx_reports_campus_status (campus_id, status),
  CONSTRAINT fk_reports_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_reports_campus_reporter FOREIGN KEY (campus_id, reporter_id) REFERENCES users (campus_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '举报';

-- 处罚
CREATE TABLE penalties (
  id          BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  penalty_type VARCHAR(32) NOT NULL COMMENT 'remind/rate_limit/take_down/mute/ban',
  reason      VARCHAR(512) NOT NULL,
  expires_at  DATETIME(3) NULL,
  status      VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT 'active/lifted',
  created_by  BIGINT UNSIGNED NOT NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_penalties_user (user_id),
  CONSTRAINT fk_penalties_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '处罚';

-- 申诉
CREATE TABLE appeals (
  id          BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  penalty_id  BIGINT UNSIGNED NULL,
  reason      VARCHAR(512) NOT NULL,
  status      VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
  reviewed_by BIGINT UNSIGNED NULL,
  reviewed_at DATETIME(3) NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_appeals_user (user_id),
  KEY idx_appeals_status (status),
  CONSTRAINT fk_appeals_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_appeals_penalty FOREIGN KEY (penalty_id) REFERENCES penalties (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '申诉';
