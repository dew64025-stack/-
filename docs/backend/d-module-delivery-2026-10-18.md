# D 模块交付记录（Task 10 + 12 + 13）

分支 `feature/messaging`，已推送。范围为 [接口分工](../../backend-接口分工-4人.md) 第 7 节的 45 条计划路由：D 新增 44 条，复用 A 已实现的 `GET /api/v1/jobs/{jobId}`。独立审查缺陷已修复：329 项 H2 隔离测试和打包通过；真实 MySQL、Redis、存储、AI、WebSocket 和学校集成未验收。

## 实现内容

### 会话与消息（11 条）

| 文件 | 作用 |
| --- | --- |
| `messaging/MessagingController.java` | 会话、消息、已读、通知、订阅、实时凭证路由 |
| `messaging/MessagingService.java` | 上下文会话复用、消息落库、clientMessageId 重放、未读游标、通知与订阅；审核规则命中时消息转审核队列，模板白名单按校园配置失败关闭 |
| `messaging/ReminderNotificationWorker.java` | 到期提醒转通知，使用 dedup_key，并回写提醒为已发送 |
| `messaging/MessagingScheduling.java` | 提醒轮询开关 |
| `common/messaging/ConversationContext.java` | 允许的会话上下文类型 |

### 首页、搜索、AI 助手（4 条）

| 文件 | 作用 |
| --- | --- |
| `discovery/DiscoveryController.java` | `/home/overview`、`/search` |
| `discovery/HomeService.java` | 校区范围、区域校验、个人截止提醒、隐私同意后的推荐、未读数、热门问题 |
| `discovery/SearchService.java` | issue/mutual_aid/opportunity/resource/team 合并搜索与游标分页 |
| `discovery/DiscoveryScope.java` | 登录用户校区、公开 campusCode、首个开放校园 |
| `assistant/AssistantController.java` | `POST /ai/assistant/messages`，返回 202 AsyncJob |
| `assistant/AssistantService.java` | 学生校验、限流、落库 pending 任务 |
| `assistant/AssistantWorker.java` | 事务外调用 DeepSeek，错误落库，不伪造答案 |

### 我的、成长、信用（10 条）

| 文件 | 作用 |
| --- | --- |
| `me/MeController.java` | dashboard、todos、history、growth-records、credit-events 路由 |
| `me/MeService.java` | 个人聚合、历史记录（按浏览时间倒序的复合游标）、成长记录版本控制、信用纠错 |
| `me/HistoryRecorder.java` | 浏览历史写入；匿名社区内容不写入 |
| `me/HistoryRecordingConfiguration.java` | 历史记录织入配置 |

### 数据权利（10 条）

| 文件 | 作用 |
| --- | --- |
| `privacy/DataRightsController.java` | 授权、隐私设置、导出、注销、数据请求路由 |
| `privacy/DataRightsService.java` | 文档版本校验、导出任务、冷静期、撤销、短期下载凭证 |
| `privacy/DataRightsWorker.java` | 导出 JSON 写入对象存储，密文保存对象键与 SHA-256；注销时清理联系方式与社区内容，匿名映射经 DEFINER 过程清除 |
| `community/AnonymousErasure.java`、`community/RoutineAnonymousErasure.java` | 注销清除匿名映射的窄接口与 DEFINER 过程调用，业务账号不读取映射 |
| `privacy/DataExportBuilder.java` | 本人数据导出，排除匿名社区、匿名映射、调查资料和他人隐私 |

### 管理运维（10 条）

| 文件 | 作用 |
| --- | --- |
| `admin/operations/AdminOpsController.java` | 校园、配置、统计、审计、集成路由 |
| `admin/operations/AdminOpsService.java` | 平台/校园管理员范围、配置白名单、集成脱敏、提供器枚举校验、跨校园审计、配置指纹门禁 |
| `admin/operations/IntegrationWorker.java` | official 连接测试执行 SSRF 安全 HTTPS 探测，成功指纹匹配后才激活；无同步适配器时显式失败并写 sync_errors |
| `admin/operations/IntegrationScheduling.java` | 集成任务轮询开关 |
| `common/jobs/JobReadService.java` | assistant 与 integration 错误码映射 |

### A/B/C 只读桥

`campus/CampusReadBridge`、`campus/CampusAdminBridge`、`community/CommunityReadBridge`、`identity/IdentityReadBridge`、`issue/IssueReadBridge`、`material/files/MaterialProofBridge`、`mutualaid/MutualAidReadBridge`、`opportunity/OpportunityReadBridge`、`opportunity/ReminderReadBridge`、`resource/ResourceReadBridge`、`team/TeamReadBridge`、`workorder/WorkOrderReadBridge`；共享投影在 `common/search` 和 `common/todos`。

## 迁移与配置

| 文件 | 内容 |
| --- | --- |
| `sql/V20__messaging_runtime.sql` | 会话上下文、消息 clientMessageId 唯一键、通知 dedup_key、订阅唯一键；重复订阅数据由临时表 CHECK 显式失败 |
| `sql/V21__me_privacy_operations.sql` | 成长索引、数据请求字段、privacy_settings、browse_history、审计 campus_id 及回填 |
| `sql/V22__privacy_authorization_versions.sql` | authorizations.document_version |
| `sql/V23__active_request_uniqueness.sql` | 数据请求、信用纠错的待处理生成列唯一键，防止并发重复申请；存在历史重复数据时迁移显式失败 |
| `sql/V24__anonymous_privacy_erasure.sql` | 注销匿名内容清除的 DEFINER 过程；业务账号只需 EXECUTE，不获得映射表 SELECT |

`application.yml` 新增 `MESSAGING_*`、`REALTIME_WEBSOCKET_URL`、`ASSISTANT_*`、`WEB_BASE_URL`、`PRIVACY_*`、`INTEGRATIONS_*`。其中 `MESSAGING_TEMPLATE_WHITELIST` 提供默认订阅模板白名单，校园 `campus_configs.messaging.templateWhitelist` 优先且解析失败时拒绝订阅。密钥、AppSecret、AI key 只从本地环境配置读取。

## 测试与打包

```text
JAVA_HOME=/d/Java_jdk <maven> -B -ntp -f backend/pom.xml test
Tests run: 329, Failures: 0, Errors: 0, Skipped: 32
BUILD SUCCESS

JAVA_HOME=/d/Java_jdk <maven> -B -ntp -f backend/pom.xml -DskipTests package
BUILD SUCCESS
```

| 测试 | 覆盖 |
| --- | --- |
| `MessagingHttpTest` | 会话生命周期、重放、成员/校区隔离、通知已读、订阅撤销与模板白名单、审核队列、仅登录用户订阅、实时未配置 503 |
| `DiscoveryHttpTest` | 公开/登录首页、区域与坐标校验、不伪造距离、跨类型搜索排序与游标、提醒批量转通知与失效用户跳过、AI 任务 pending |
| `MeHttpTest` | dashboard/todos 聚合、历史按浏览时间排序与复合游标、成长版本冲突、跨用户 404、信用事件与纠错 |
| `PrivacyHttpTest` | 文档版本、授权类型白名单、本人撤销、设置默认关闭、导出 202/重复 409/跨用户 404、注销冷静期、未完成义务、匿名内容清除 |
| `AdminOpsHttpTest` | 平台建校、配置白名单与版本、统计范围、审计分页/脱敏与跨校园权限、集成脱敏/SSRF 拒绝、official 配置指纹测试与激活、同步显式失败 |
| `CommunityHttpTest` | 社区审核决策；新增消息审核放行增加未读、拒绝改为 blocked |

32 项跳过为既有 opt-in 真实服务测试，不属于 D 模块失败。

## 安全约束核验

- 跨校园或不存在的资源返回 404；列表按当前校区过滤；跨校园审计仅 `platform.campus.manage` 可读。
- 匿名社区内容不进入成长记录、搜索、首页聚合和导出；注销通过 DEFINER 过程清除匿名映射，业务账号不读取映射。
- `secretRef` 读取时返回 `***`；未知配置键、非 demo/manual/official 提供器、http 端点、内网/环回/保留地址被拒绝。
- official 提供器先以 inactive 保存；连接测试结果绑定 provider/type/endpoint/secretRef/timeout/授权标志指纹，配置变更后旧测试失效，成功后才激活。
- 导出不返回明文对象键；下载只返回短期签名地址。
- 集成同步无适配器、实时未配置、AI 未配置均显式失败，不返回伪造成功；消息审核命中时先挂起，不投递。
- 审计日志只追加；D 服务不提供修改/删除审计的入口。

## 未验证的真实依赖

1. MySQL 8.4 上执行 V20～V24、回填、V23 生成列唯一键冲突保护与 V24 DEFINER 过程授权；本轮仅 H2 MySQL 模式，V23 历史重复数据会阻止迁移。
2. 真实 Redis 会话、限流、TTL、多实例调度；测试使用 InMemorySessionStore 和固定 RateCounter。
3. WebSocket 服务端握手与断线补偿；`REALTIME_WEBSOCKET_URL` 为空时接口按设计返回 `REALTIME_UNAVAILABLE`。
4. 真实 DeepSeek 付费调用、答案质量与超时/限流错误；本轮验证到任务入队和错误映射。
5. 真实 Garage/S3 导出对象写入、签名下载和过期；本轮未执行真实存储链路。
6. 真实学校集成端点与连接测试；本轮用受控读取器替身验证成功/失败和配置指纹绑定，未连接任何校外系统，sync 没有提供器适配器。
7. 消息审核规则的真实内容策略、审核积压和通知投递；本轮验证规则命中挂起、放行增加未读、拒绝拦截。
8. 管理员浏览器 CSRF、真实管理员登录/MFA 链路；本轮使用已签发会话和 MockMvc。
9. 生产并发、压测、崩溃恢复和备份恢复。

## 提交与推送

`feature/messaging` 上依次提交只读桥、messaging、me、privacy、admin、discovery/assistant、运行时配置、D 测试和交付文档；分支已推送。独立审查修复（数据清理、提醒饥饿、权限对齐、并发唯一键、历史游标、模板白名单、跨校园审计、SSRF、导出孤儿清理、授权类型、消息审核、集成成功路径）另提交后推送。
