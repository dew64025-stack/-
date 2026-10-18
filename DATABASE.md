# 材智通 · 数据库设计与连接信息

校园一站式智能服务平台数据库。依据 `2026-10-13-campus-one-stop-platform-design.md` 第 7 节数据设计，覆盖 10 个领域、共 **74 张表**，按 Flyway 拆为 V1~V6 建表脚本，演示种子独立放在 `demo/`。

> 逐表列级说明见 [数据字典 DATA_DICTIONARY.md](./DATA_DICTIONARY.md)。

运行时迁移需按版本继续执行至 V27；上述74张指 V1～V6 初始模型，不含后续运行表。V19 新增 team_open_roles/lost_found_events，并给 team_members/team_applications 增加可空 open_role_id。既有成员、申请、失物匹配和证据原样保留；不推断旧岗位或伪造旧确认。旧团队由队长按接口文档11节完成version更新岗位→拒绝无岗位旧pending→重新申请的修复流程。

## 一、连接信息

| 项目 | 值 |
| --- | --- |
| 数据库类型 | MySQL 8.0 |
| 数据库名 | `caizhitong` |
| 主机 | `127.0.0.1`（本机）或远程 MySQL IP |
| 端口 | `3306` |
| 迁移工具 | Flyway（生产按顺序执行 `sql/V1__` ~ `sql/V27__`） |
| 字符集 / 排序 | `utf8mb4` / `utf8mb4_unicode_ci` |

> 生产环境：MySQL 只允许后端服务器来源 IP，禁止对 `0.0.0.0/0` 开放；连接使用内网/VPN 或加密链路。数据库密码、AppSecret、AI 密钥一律进服务端密钥配置，不进源码。

## 二、部署顺序与账号权限

首次初始化**必须按以下顺序**执行——先建表、再逐表授权，避免对不存在的表授权失败。`<ip>` 为后端服务器来源 IP。

### 步骤 1：建库与建账号

```sql
CREATE DATABASE IF NOT EXISTS caizhitong
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'caizhitong_migrator'@'<ip>' IDENTIFIED BY '<password>';
CREATE USER 'caizhitong_app'@'<ip>' IDENTIFIED BY '<password>';
CREATE USER 'caizhitong_investigator'@'<ip>' IDENTIFIED BY '<password>';
```

### 步骤 2：迁移账号授权（含 REFERENCES，用于外键）

```sql
GRANT CREATE, ALTER, DROP, INDEX, REFERENCES,
      CREATE TEMPORARY TABLES, CREATE ROUTINE, ALTER ROUTINE, EXECUTE,
      SELECT, INSERT, UPDATE, DELETE
  ON caizhitong.* TO 'caizhitong_migrator'@'<ip>';
```

### 步骤 3：用迁移账号顺序执行 V1~V27

见第三节迁移与初始化。确认迁移历史到V27后再继续。V23 为数据请求/信用纠错增加生成列唯一键，执行前若已有重复的待处理申请会失败并阻止发布；V24 增加注销时匿名内容的 DEFINER 清除过程；V25 修正该过程的匿名内容定位、为机会补齐官方来源列并回填、回填一对一会话参与者键和审计校园。V25 会 DROP/CREATE `community_privacy_erase`，清除既有 EXECUTE 授权，执行后必须重新授予迁移账号和业务账号。V26 增加治理处置理由、待处理申诉唯一键和集成熔断字段。V27 保留旧版混合私聊的成员和消息，仅设置 `access_revoked_at` 撤销其访问，避免升级后仍能查看其他咨询者的历史。

升级 V27 前先备份并停止旧服务写入；执行迁移、补齐本节授权后启用新版本，不能只更新 jar。隔离会话不得直接清空 `access_revoked_at` 恢复访问，须先核对历史归属；用户可按参与者对创建新的独立会话。MySQL DDL 不支持整体事务回滚；迁移失败先检查 Flyway 历史与实际 schema，再按恢复流程处理，不能盲目重复 ALTER。回滚至旧代码会失去该隔离检查，必须配套恢复备份或关闭消息入口。

### 步骤 4：业务账号逐表授权（表已存在）

V18、V24 与 V25 必须由长期保留的 schema migrator 执行，过程默认为该账号 DEFINER。迁移完成后若收紧迁移权限，必须保留 `pseudo_identities` 的 SELECT/UPDATE/DELETE、`community_posts` 与 `community_comments` 的 SELECT/UPDATE，以及 `community_owner_check` / `community_owner_posts` / `community_privacy_erase` 各自的 EXECUTE；仅保留 SELECT 会使注销过程以 definer 身份执行时失败。V25 的 `DROP PROCEDURE` + `CREATE PROCEDURE` 会同时删除该过程已有的 EXECUTE 授权，执行后必须重新执行下面的迁移账号授权和业务账号授权；否则注销会显式失败。不得删除 definer 账号，不得将其密码配置为业务密码。创建过程只需 schema 的 CREATE ROUTINE/ALTER ROUTINE/EXECUTE，无需全局权限或 root definer。

```sql
GRANT SELECT, UPDATE, DELETE ON caizhitong.pseudo_identities TO 'caizhitong_migrator'@'<ip>';
GRANT SELECT, UPDATE ON caizhitong.community_posts TO 'caizhitong_migrator'@'<ip>';
GRANT SELECT, UPDATE ON caizhitong.community_comments TO 'caizhitong_migrator'@'<ip>';
GRANT EXECUTE ON PROCEDURE caizhitong.community_owner_check TO 'caizhitong_migrator'@'<ip>';
GRANT EXECUTE ON PROCEDURE caizhitong.community_owner_posts TO 'caizhitong_migrator'@'<ip>';
GRANT EXECUTE ON PROCEDURE caizhitong.community_privacy_erase TO 'caizhitong_migrator'@'<ip>';
```

业务账号按下面逐表白名单授权。材料版本和事件审计仅追加/读取，文件三表允许状态更新但不允许删除；`pseudo_identities` 仅 INSERT；**不授予 `security_investigations` 或 `file_key_migrations`**。上传、扫描和清理与业务使用同一主数据源，不存在另一个上传数据库账号：

```sql
-- 校园与身份
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campuses TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_regions TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_organizations TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_configs TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.users TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.user_wechat_identities TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.user_phones TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.student_verifications TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.user_profiles TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.roles TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.user_roles TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.authorizations TO 'caizhitong_app'@'<ip>';
-- V8: runtime can read credentials and advance replay state; controlled bootstrap owns INSERT.
GRANT SELECT, UPDATE (last_totp_step) ON caizhitong.admin_credentials TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE ON caizhitong.student_roster TO 'caizhitong_app'@'<ip>';

-- 机会与材料
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.notices TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.ai_parse_tasks TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.ai_parse_fields TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.opportunities TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.opportunity_eligibility_rules TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.opportunity_eligibility_checks TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.opportunity_material_items TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.opportunity_rule_snapshots TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.favorites TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.reminders TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.user_opportunity_submissions TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.material_files TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT ON caizhitong.material_versions TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.material_checklists TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.material_checklist_items TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.precheck_tasks TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.precheck_results TO 'caizhitong_app'@'<ip>';
-- V9: upload/scan/cleanup share the primary business datasource; versions remain immutable.
GRANT SELECT, INSERT, UPDATE ON caizhitong.file_uploads TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE ON caizhitong.file_attachments TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE ON caizhitong.file_cleanup_jobs TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.material_usage_records TO 'caizhitong_app'@'<ip>';

-- 服务与预约
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.work_orders TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.work_order_events TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.work_order_ratings TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_issues TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_issue_locations TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_issue_follows TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_issue_evidences TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.campus_issue_votes TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT ON caizhitong.campus_issue_events TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.resources TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.resource_slots TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.reservations TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.resource_occupancies TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.reservation_checkins TO 'caizhitong_app'@'<ip>';

-- 互助与社区（pseudo_identities 仅 INSERT）
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.mutual_aid_posts TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT ON caizhitong.mutual_aid_ratings TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.lost_found_matches TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.borrow_requests TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT ON caizhitong.borrow_events TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.teams TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.team_members TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.team_applications TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE ON caizhitong.team_open_roles TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT ON caizhitong.lost_found_events TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.community_posts TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.community_comments TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.reports TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.penalties TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.appeals TO 'caizhitong_app'@'<ip>';
GRANT INSERT ON caizhitong.pseudo_identities TO 'caizhitong_app'@'<ip>';
GRANT EXECUTE ON PROCEDURE caizhitong.community_owner_check TO 'caizhitong_app'@'<ip>';
GRANT EXECUTE ON PROCEDURE caizhitong.community_owner_posts TO 'caizhitong_app'@'<ip>';
GRANT EXECUTE ON PROCEDURE caizhitong.community_privacy_erase TO 'caizhitong_app'@'<ip>';

-- 消息与治理（审计仅追加，security_investigations 不授予业务账号）
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.conversations TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.conversation_members TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.messages TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.notifications TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.subscription_authorizations TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.message_send_records TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.moderation_tasks TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.moderation_rules TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.credit_events TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE ON caizhitong.credit_disputes TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, DELETE ON caizhitong.blocks TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT ON caizhitong.admin_audit_logs TO 'caizhitong_app'@'<ip>';

-- 集成与补充
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.external_systems TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.external_references TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.sync_cursors TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.sync_raw_digests TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.sync_errors TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.crowd_observations TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.growth_records TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.privacy_settings TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.browse_history TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.data_requests TO 'caizhitong_app'@'<ip>';
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.async_jobs TO 'caizhitong_app'@'<ip>';
```

### 步骤 5：安全调查账号授权

```sql
-- 调查只需目标映射密文/旧版明文用户列与校园定位列；无 users/phone/student 权限
GRANT SELECT (id, user_id, user_id_encrypted) ON caizhitong.pseudo_identities TO 'caizhitong_investigator'@'<ip>';
GRANT SELECT (id, campus_id, pseudo_identity_id) ON caizhitong.community_posts TO 'caizhitong_investigator'@'<ip>';
GRANT SELECT (id, post_id, pseudo_identity_id) ON caizhitong.community_comments TO 'caizhitong_investigator'@'<ip>';
GRANT SELECT, INSERT ON caizhitong.security_investigations TO 'caizhitong_investigator'@'<ip>';
```

> 匿名身份映射的运行时路径：业务账号仅 `INSERT pseudo_identities`。作者维护通过 V18 两个 SQL SECURITY DEFINER 过程，后端从当前会话计算带密钥 owner proof，过程限定 campus，仅返回 boolean 或本人的帖子ID；从不返回映射/真实用户ID。H2 替身仅在 src/test，生产无 JDBC SELECT fallback。

V19岗位表仅 SELECT/INSERT/UPDATE，无DELETE；停用保留历史关联。失物事件表仅 SELECT/INSERT，无UPDATE/DELETE；description是私密审计证据。归还先锁lost_found_matches父行，再用FOR SHARE读取当前事件，MySQL最小业务账号不需要获得事件修改权。团队写操作先锁teams，再读取岗位与成员的当前锁定行，避免认证/幂等已建立的REPEATABLE READ旧快照漏算容量。部署前应在新隔离库使用这里的逐表GRANT实际验证，不能用root作为应用账号替代。

调查部署须单独配置 `INVESTIGATION_DB_URL`、`INVESTIGATION_DB_USER`、`INVESTIGATION_DB_PASSWORD`（只从服务端秘密配置注入）。用户名必须与业务账号不同；缺失配置或专用数据库故障返回503，无业务连接回退。gateway 内部持有私有 DataSourceTransactionManager，不向 Spring 注册第二个 DataSource/事务管理器，不影响主业务自动配置。

调查幂等只缓存 `{investigationId}`；每次披露（包括重试）重新校验权限、目标校园、举报关联及新 `X-Reauth-Token`。专用事务每次追加 `security_investigations`，其 `result_json.investigationId` 关联稳定调查标识，`result_json.campusId/reportId` 记录范围；audit INSERT/commit 失败不返回身份。该账号不具有审计 UPDATE/DELETE。同key不同正文409；同正文返回同调查标识但必须换新二次认证凭证。

## 三、迁移与初始化

**生产迁移目录** `sql/`（对应 `classpath:db/migration`，打包时需映射复制）：

```text
sql/V1__campus_and_identity.sql         校园与身份（12 表 + 角色初始化）
sql/V2__opportunity_and_material.sql    机会与材料（17 表）
sql/V3__service_and_reservation.sql     服务与预约（13 表）
sql/V4__mutual_aid_and_community.sql    互助与社区（12 表）
sql/V5__messaging_governance_integration.sql  消息/治理/集成（16 表）
sql/V6__supplementary_models.sql        补充模型（4 表）
sql/V7__runtime_coordination.sql        运行时协调（幂等记录等）
sql/V8__identity_runtime.sql            身份运行时约束
sql/V9__files_and_jobs.sql              安全文件与任务
sql/V10__opportunity_runtime.sql        机会运行时（负责人A：解析租约/复核、提交唯一约束、规则快照）
sql/V11__checklist_precheck_runtime.sql 清单预检运行时（负责人A：任务租约、AI 授权依据）
sql/V14__mutual_aid_ratings.sql         互助评价（负责人C）
sql/V15__community_governance_runtime.sql 社区治理运行时（负责人C）
sql/V16__opportunity_review_repairs.sql 机会清单快照与资格复核修复（负责人A）
sql/V17__service_review_repairs.sql     资源预约模式修复（负责人B）
sql/V18__community_privacy_repairs.sql  社区隐私检查过程（负责人C）
sql/V19__mutual_team_review_repairs.sql 互助组队角色修复（负责人C）
sql/V20__messaging_runtime.sql          会话上下文与消息运行时（负责人D）
sql/V21__me_privacy_operations.sql      个人数据权利与运维支撑（负责人D）
sql/V22__privacy_authorization_versions.sql 授权文档版本（负责人D）
sql/V23__active_request_uniqueness.sql  待处理申请/纠错唯一键（负责人D）
sql/V24__anonymous_privacy_erasure.sql  注销匿名内容清除过程（负责人D）
sql/V25__review_repairs.sql             第二轮审查修复（来源列、会话参与者键、审计校园、注销过程定位）
sql/V26__governance_and_integration_repairs.sql 第三轮治理与集成修复
sql/V27__messaging_legacy_quarantine.sql 旧版混合私聊保留记录并撤销访问
```

**演示种子目录** `demo/`（仅演示环境执行，生产 Flyway 不加载）：

```text
demo/demo_seed.sql   校园/演示账号/通知/机会/资源/互助/问题/拥堵观测
```

> 生产与演示数据库必须隔离；演示种子只在演示环境手动执行。

### 旧库升级说明

本版 V1~V5 是按「全新空库」设计的初始迁移。若已存在由旧版本迁移过的数据库：

- 不要直接重写或 `repair` 旧迁移（`repair` 只改迁移历史，不会把旧表变成新表）。
- 保留已执行迁移，新增 `ALTER TABLE` 与数据转换迁移（新版本号，如 `V7__...`）。
- 升级前备份，并在副本上验证数据转换与约束建立后再上线。

### 旧账号权限升级说明

新建账号时逐表白名单方向正确，但**追加白名单不会自动撤销旧环境的宽授权**。部署人员须在维护窗口核对准确的账号和 host；检查已有表级授权及数据库级/全局通配授权：

1. 用 `SHOW GRANTS FOR 'caizhitong_app'@'<ip>'` 检查现有授权。
2. 按实际 `SHOW GRANTS` 结果逐项核对并撤销多余权限，特别是 `material_versions` 的 UPDATE/DELETE、文件三表 DELETE、`borrow_events`/其他仅追加事件表的 UPDATE/DELETE；数据库级、全局或继承角色的宽授权也会使表级收窄失效。
3. 再授予上述白名单，复查有效授权。不要盲目运行通用 `REVOKE ALL`，也不要删除重建账号或删除 V18 的 definer。
4. 用业务账号实测受限查询、UPDATE、DELETE 必须失败，再声明版本不可变。先备份与演练恢复，按核对后的精确账号/host 生成变更单；本次自动测试只改全新合成账号，不执行任何用户库授权变更。

不要默认删除或重建已有账号；操作前明确目标账号与环境。

## 四、通用字段约定

核心业务表统一包含：

- `id`：应用层生成全局唯一 `BIGINT`（雪花算法），非自增
- `campus_id`：多校隔离键（单校首发，底层预留）
- `status`：业务状态
- `version`：乐观锁，并发修改冲突返回 HTTP 409
- `created_by` / `updated_by`：操作人
- `created_at` / `updated_at`：`DATETIME(3)`
- `deleted_at`：软删除（`NULL` 表示未删除）
- `source_type`：`demo` 演示 / `manual` 人工 / `official` 官方

同校归属：关键关系用复合外键 `(campus_id, user_id) → users(campus_id, id)` 约束；不适合复合外键的链式关系（预约→时段→资源→校园、成员→会话→校园等）由服务端在事务内校验。

敏感数据约定：手机号、学号检索值采用带密钥 HMAC，密文保存；文件本体存 MinIO；匿名身份映射仅 INSERT（业务）与 SELECT（调查）分离。

## 五、表清单（按域）

| 域 | 表 |
| --- | --- |
| 校园 | `campuses` `campus_regions` `campus_organizations` `campus_configs` |
| 身份 | `users` `user_wechat_identities` `user_phones` `student_verifications` `user_profiles` `roles` `user_roles` `authorizations` |
| 机会 | `notices` `ai_parse_tasks` `ai_parse_fields` `opportunities` `opportunity_eligibility_rules` `opportunity_eligibility_checks` `opportunity_material_items` `favorites` `reminders` `user_opportunity_submissions` |
| 材料 | `material_files` `material_versions` `material_checklists` `material_checklist_items` `precheck_tasks` `precheck_results` `material_usage_records` |
| 服务 | `work_orders` `work_order_events` `work_order_ratings` `campus_issues` `campus_issue_locations` `campus_issue_follows` `campus_issue_evidences` `campus_issue_votes` `campus_issue_events` |
| 预约 | `resources` `resource_slots` `reservations` `resource_occupancies` `reservation_checkins` |
| 互助 | `mutual_aid_posts` `lost_found_matches` `lost_found_events` `borrow_requests` `borrow_events` `teams` `team_members` `team_applications` `team_open_roles` |
| 社区 | `community_posts` `community_comments` `pseudo_identities` `reports` `penalties` `appeals` |
| 消息 | `conversations` `conversation_members` `messages` `notifications` `subscription_authorizations` `message_send_records` |
| 治理 | `moderation_tasks` `moderation_rules` `credit_events` `admin_audit_logs` `security_investigations` |
| 集成 | `external_systems` `external_references` `sync_cursors` `sync_raw_digests` `sync_errors` |
| 补充 | `crowd_observations` `growth_records` `data_requests` `async_jobs` |

## 六、Spring Boot 配置

Flyway 使用迁移账号，业务数据源使用业务账号：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/caizhitong?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: caizhitong_app
    password: ${MYSQL_PASSWORD}
  flyway:
    enabled: true
    locations: classpath:db/migration
    user: ${FLYWAY_USER}
    password: ${FLYWAY_PASSWORD}
```

`FLYWAY_USER` 为 `caizhitong_migrator`，`FLYWAY_PASSWORD` 为其密码；账号密码均通过外部密钥配置注入，不写入源码。若部署流程手动执行迁移，则可省略 Flyway 账号配置。

## 七、连接方式

### V7 运行协调表增量授权

执行 `sql/V7__runtime_coordination.sql` 后，只为业务账号追加该表授权：

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON caizhitong.api_idempotency_records TO 'caizhitong_app'@'<ip>';
```

幂等记录和业务写入使用同一业务数据源、同一事务。记录保存24小时，结果采用服务端加密，不能缓存登录令牌；过期记录可由受控清理任务删除。Redis只协调会话与限流，不承担业务正确性的最终保证。

**命令行**：

```powershell
mysql -h 127.0.0.1 -P 3306 -u caizhitong_app -p caizhitong
```

**MySQL Workbench**：新建连接，主机 `127.0.0.1`、端口 `3306`、账号 `caizhitong_app`。

**JDBC**（Java / Spring Boot）：

```text
jdbc:mysql://127.0.0.1:3306/caizhitong?serverTimezone=Asia/Shanghai
```
