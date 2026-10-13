-- =============================================================
-- 材智通 · 校园一站式智能服务平台
-- V1 校园与身份（阶段 0/1）
-- 技术栈：MySQL 8.0 · Spring Boot + Spring Data JPA · Flyway
--
-- 通用约定：
--   · id 为应用层生成的全局唯一 BIGINT（雪花算法），非自增
--   · campus_id 为多校隔离键，核心业务表必含
--   · version 乐观锁，并发修改冲突返回 HTTP 409
--   · deleted_at 软删除（NULL 表示未删除）
--   · source_type：demo 演示 / manual 人工 / official 官方
--   · 手机号、学号检索值采用带密钥的 HMAC（库中只存检索值与密文）
--   · 同校归属用复合外键 (campus_id, user_id) → users(campus_id, id) 约束
-- =============================================================

SET NAMES utf8mb4;

-- 校园租户
CREATE TABLE campuses (
  id          BIGINT UNSIGNED NOT NULL,
  name        VARCHAR(128) NOT NULL,
  code        VARCHAR(64)  NOT NULL,
  status      VARCHAR(32)  NOT NULL DEFAULT 'active',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_campuses_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '校园租户';

-- 校园区域（楼栋 / 片区 / 楼层）
CREATE TABLE campus_regions (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  parent_id   BIGINT UNSIGNED NULL,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(32)  NOT NULL COMMENT 'building/area/floor',
  status      VARCHAR(32)  NOT NULL DEFAULT 'active',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_regions_campus (campus_id),
  KEY idx_regions_parent (parent_id),
  CONSTRAINT fk_regions_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '校园区域';

-- 校园组织（学院 / 部门 / 社团）
CREATE TABLE campus_organizations (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(32)  NOT NULL COMMENT 'college/department/club',
  status      VARCHAR(32)  NOT NULL DEFAULT 'active',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_organizations_campus (campus_id),
  CONSTRAINT fk_organizations_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '校园组织';

-- 校园配置（键值）
CREATE TABLE campus_configs (
  id           BIGINT UNSIGNED NOT NULL,
  campus_id    BIGINT UNSIGNED NOT NULL,
  config_key   VARCHAR(128) NOT NULL,
  config_value JSON        NOT NULL,
  version      INT UNSIGNED NOT NULL DEFAULT 0,
  created_at   DATETIME(3)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_configs_campus_key (campus_id, config_key),
  CONSTRAINT fk_configs_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '校园配置';

-- 用户（(campus_id, id) 唯一，供同校复合外键引用）
CREATE TABLE users (
  id          BIGINT UNSIGNED NOT NULL,
  campus_id   BIGINT UNSIGNED NOT NULL,
  nickname    VARCHAR(64)  NULL,
  avatar_url  VARCHAR(512) NULL,
  status      VARCHAR(32)  NOT NULL DEFAULT 'active',
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_campus_id (campus_id, id),
  CONSTRAINT fk_users_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '用户';

-- 微信身份
CREATE TABLE user_wechat_identities (
  id         BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  app_id     VARCHAR(64)  NOT NULL,
  openid     VARCHAR(128) NOT NULL,
  unionid    VARCHAR(128) NULL,
  created_at DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_wechat_app_openid (app_id, openid),
  KEY idx_wechat_user (user_id),
  CONSTRAINT fk_wechat_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '微信身份';

-- 手机号（密文 + HMAC 检索值）
CREATE TABLE user_phones (
  id              BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  phone_encrypted VARBINARY(255) NOT NULL,
  phone_search    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '带密钥 HMAC 检索值',
  verified        TINYINT(1) NOT NULL DEFAULT 0,
  created_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_phones_search (phone_search),
  KEY idx_phones_user (user_id),
  CONSTRAINT fk_phones_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '手机号（密文保存）';

-- 学生认证（校园 + 学号检索值 唯一；复合外键保证用户同校）
CREATE TABLE student_verifications (
  id                   BIGINT UNSIGNED NOT NULL,
  user_id              BIGINT UNSIGNED NOT NULL,
  campus_id            BIGINT UNSIGNED NOT NULL,
  student_id_encrypted VARBINARY(255) NOT NULL,
  student_id_search    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '带密钥 HMAC 检索值',
  status               VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/verified/rejected',
  verified_at          DATETIME(3) NULL,
  reviewer_id          BIGINT UNSIGNED NULL COMMENT '人工审核员',
  reject_reason        VARCHAR(255) NULL,
  version              INT UNSIGNED NOT NULL DEFAULT 0,
  created_at           DATETIME(3) NOT NULL,
  updated_at           DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_student_campus_search (campus_id, student_id_search),
  KEY idx_student_user (user_id),
  CONSTRAINT fk_student_campus_user FOREIGN KEY (campus_id, user_id) REFERENCES users (campus_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '学生认证';

-- 用户画像（用于机会推荐，用户主动填写）
CREATE TABLE user_profiles (
  id          BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  college     VARCHAR(128) NULL,
  grade       VARCHAR(32)  NULL,
  skills      JSON         NULL,
  interests   JSON         NULL,
  version     INT UNSIGNED NOT NULL DEFAULT 0,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_profiles_user (user_id),
  CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '用户画像';

-- 角色
CREATE TABLE roles (
  id          BIGINT UNSIGNED NOT NULL,
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(64) NOT NULL,
  description VARCHAR(255) NULL,
  created_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_roles_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '角色';

-- 用户角色（campus_id 为 0 表示平台级角色，非空保证唯一）
CREATE TABLE user_roles (
  id         BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  role_id    BIGINT UNSIGNED NOT NULL,
  campus_id  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示平台级角色',
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_user_roles (user_id, role_id, campus_id),
  KEY idx_user_roles_role (role_id),
  CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '用户角色';

-- 授权记录（手机号授权 / 隐私同意 / 订阅授权，可追溯版本）
CREATE TABLE authorizations (
  id         BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  type       VARCHAR(32)  NOT NULL COMMENT 'phone/privacy/subscription/location',
  scope      VARCHAR(128) NULL,
  status     VARCHAR(32)  NOT NULL DEFAULT 'granted' COMMENT 'granted/revoked',
  version    INT UNSIGNED NOT NULL DEFAULT 0,
  granted_at DATETIME(3)  NOT NULL,
  revoked_at DATETIME(3)  NULL,
  created_at DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_authorizations_user (user_id, type),
  CONSTRAINT fk_authorizations_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT '授权记录';

-- 必要初始化：角色（生产环境也需存在，不依赖演示种子）
INSERT INTO roles (id, code, name, description, created_at) VALUES
  (1, 'guest',                '游客',           '浏览公开机会、热点和公开互助信息',          UTC_TIMESTAMP(3)),
  (2, 'user',                 '已登录用户',     '收藏、建立待办、维护基础资料',              UTC_TIMESTAMP(3)),
  (3, 'student',              '已认证学生',     '发布、预约、报修、评论、组队、匿名社区',    UTC_TIMESTAMP(3)),
  (4, 'content_moderator',    '内容审核员',     '审核帖子、评论、举报与申诉',                UTC_TIMESTAMP(3)),
  (5, 'business_operator',    '业务处理员',     '处理报修和校园问题',                        UTC_TIMESTAMP(3)),
  (6, 'resource_admin',       '资源管理员',     '管理教室、座位、开放时间和预约',            UTC_TIMESTAMP(3)),
  (7, 'campus_admin',         '校园管理员',     '管理本校账号、通知、业务配置和统计',        UTC_TIMESTAMP(3)),
  (8, 'platform_admin',       '平台管理员',     '管理校园租户、系统配置和安全审计',          UTC_TIMESTAMP(3)),
  (9, 'security_investigator', '安全调查员',    '授权调查中查询匿名身份映射',                UTC_TIMESTAMP(3));
