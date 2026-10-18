# 材智通后端接口分工（4 人）

> 目的：把 206 条计划接口的后端开发任务，按业务域内聚拆给 4 人并行推进。
> 数据源：`campus-backend-api-v1.1-reviewed.md`（206 条）、`docs/backend/route-inventory.json`（机器可读清单）、`docs/superpowers/plans/2026-10-13-backend-server.md`（14 个任务）。

## 1. 总体进度与拆分原则

| 阶段 | 范围 | 接口数 | 状态 |
| --- | --- | --- | --- |
| 公共底座 | Task 1–4（工程、会话/权限/幂等/限流、微信身份、安全文件/材料） | 41 | 已完成，Task4 收尾中 |
| 业务模块 | Task 5–13 | 165 | 未开始，本次拆分 |

拆分原则：
- **内聚优先**：同一业务域（含其对应的 `admin` 管理端、`me/` 本人列表接口）分给同一人，共享实体与服务，避免同一张表两个人写。
- **依赖清晰**：4 人共用公共底座；彼此通过窄接口协作（见第 8 节），不重复造轮子。
- **复杂度补偿接口数**：A、B、C 的接口数不同，但 A 含 AI 解析与规则引擎、B 含并发防超卖、C 含匿名映射与治理闭环，复杂度对冲。

权限符号：`P` 公开 · `L` 登录 · `S` 学生（需认证）· `O` 所有者 · `A(code)` 管理员特定权限。

## 2. 公共底座（所有人共享，不重复开发）

以下能力已由 Task 1–4 提供，4 人一律调用，不另写一套：

- **统一响应**：`ApiResponse.ok(data, RequestIdFilter.requestId(request))`；DTO 里 ID/外键为十进制字符串，版本/计数/金额为数字；不返回 JPA 实体。
- **身份**：`CurrentActor.require()`（业务）、`requireStudent()`（学生）、`requirePermission(code)`（后台）、`requireAuthenticatedIdentity()`（仅本人处罚/申诉/数据权利）。权限每次从数据库重查。
- **会话**：学生 Bearer、后台 ADMIN 会话 + CSRF + TOTP；二进制上传用独立 Upload 凭证，不能用 Bearer 替代。
- **幂等**：业务 POST/PUT/DELETE/PATCH 必须 `Idempotency-Key`（8–128 可打印 ASCII），应用服务写方法标 `@BusinessWrite`；`@BusinessWrite` 只包数据库动作，远程换码/AI/扫描放事务外。
- **游标分页**：`CursorCodec` + `PageLimits`（默认 20，最大 100）；scope 由服务端从校园+过滤条件生成，不采信客户端。
- **限流**：`RateLimiter.check(bucket,max,window)`。
- **加密**：手机号/学号/匿名映射用 `SensitiveDataCrypto`（AES-GCM + HMAC 检索摘要）；文件对象键密文存储。
- **文件**：`AttachmentService.owner` / `readCleanOwner`、`LinkedAttachmentPolicy`（关联业务读桥）、`UploadService`、`FileCleanupWorker`；附件必须 `clean` 才能使用/下载/送 AI。
- **异步任务**：`async_jobs`（V8 已有 campus_id/owner_user_id/access_domain/claim_token）+ `GET /jobs/{jobId}`（D 提供）。

## 3. 4 人分工总览

| 负责人 | 领域 | 对应 Task | 接口数 | 关键复杂度 | 主要依赖 |
| --- | --- | --- | --- | --- | --- |
| **A** | 机会 · 通知 · AI · 资格 · 材料清单 | 5、6 | 27 | DeepSeek 解析、SSRF 防护、资格规则引擎、版本快照 | 文件、AI、`GET /jobs/{jobId}` |
| **B** | 资源预约 · 工单 · 校园问题 · 拥堵 | 7、8 | 40 | 并发防超卖、工单状态机、公开/私密边界 | 文件、Redis 锁、消息（预约提醒） |
| **C** | 互助 · 组队 · 匿名社区 · 治理 | 9、11 | 53 | 匿名映射、审核状态机、处罚/申诉闭环、调查最小权限 | 文件、身份、消息（屏蔽关系） |
| **D** | 会话消息 · 订阅 · 首页 · 我的 · 数据权利 · 管理运维 | 10、12、13 | 45 | 消息先落库再通知、数据导出/注销冷静期、集成隔离 | A/B/C 窄读取接口 |

合计 165 条待开发接口。

---

## 4. 负责人 A —— 机会 · 通知 · AI · 资格 · 材料清单（Task 5 + 6，27 条）

### 4.1 学生端（17 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/opportunities` | P | 公开机会列表（q/五类 category/deadline 区间/游标）；仅 published/expired |
| GET | `/api/v1/opportunities/{id}` | P | 详情 + notice/eligibilityRules/materialRequirements/teamCount/isFavorited |
| POST | `/api/v1/opportunities/{id}/eligibility-checks` | S | 202 异步资格检查（从服务器画像取条件） |
| GET | `/api/v1/opportunities/{id}/eligibility-checks/latest` | O | 本人最新匹配结果 |
| PUT | `/api/v1/opportunities/{id}/submission` | S | 本人自行提交时间，用户+机会最多一份当前记录 |
| PUT | `/api/v1/favorites/{targetType}/{targetId}` | L | 收藏 |
| DELETE | `/api/v1/favorites/{targetType}/{targetId}` | L | 取消收藏 |
| GET | `/api/v1/me/favorites` | L | 分页收藏；下架目标只返回不可用标识 |
| POST | `/api/v1/reminders` | L | 创建提醒（remindAt 不得早于当前） |
| DELETE | `/api/v1/reminders/{id}` | O | 取消提醒（仅 pending→cancelled） |
| GET | `/api/v1/me/reminders` | L | 分页提醒 |
| PUT | `/api/v1/opportunities/{id}/checklist` | S | 创建/取得本人清单 |
| GET | `/api/v1/checklists/{id}` | O | 清单详情 |
| POST | `/api/v1/checklists/{id}/items/{itemId}/update` | O | 清单项绑定/解绑本人材料版本，提升父清单版本 |
| POST | `/api/v1/materials/{id}/prechecks` | O | 单材料基础预检，202 AsyncJob |
| POST | `/api/v1/checklists/{id}/prechecks` | O | 整清单预检，202 AsyncJob |
| GET | `/api/v1/checklists/{id}/prechecks/latest` | O | 最新预检结果（含 checklistVersion/stale） |

### 4.2 管理端（10 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/admin/notices` | A(notice.read) | 分页通知 |
| POST | `/api/v1/admin/notices` | A(notice.write) | 创建通知（title/publisher/content/rawUrl/attachmentId 至少一种原文来源） |
| POST | `/api/v1/admin/notices/{id}/parse` | A(notice.parse) | 202 触发 AI 解析 |
| GET | `/api/v1/admin/notice-parse-tasks/{id}` | A(notice.read) | 解析任务（字段级 evidenceLocation/confidence/humanConfirmed） |
| POST | `/api/v1/admin/notice-parse-tasks/{id}/review` | A(notice.review) | 逐字段 review + 人工修正，approve 只生成 draft |
| GET | `/api/v1/admin/opportunities` | A(opportunity.read) | 分页机会 |
| POST | `/api/v1/admin/opportunities` | A(opportunity.write) | 创建机会（显式 draft） |
| POST | `/api/v1/admin/opportunities/{id}/update` | A(opportunity.write) | 编辑；已发布规则撤回后才能改；archived版本编辑后回draft，另需受控发布 |
| POST | `/api/v1/admin/opportunities/{id}/publish` | A(opportunity.publish) | 人工发布（校验规则快照+通知确认依据） |
| POST | `/api/v1/admin/opportunities/{id}/unpublish` | A(opportunity.publish) | 撤回（archived） |

### 4.3 关键实现点

- **AI 解析（DeepSeek）**：`integration/ai/` 生产 HTTP 适配，固定 `https://api.deepseek.com/chat/completions`，Bearer 密钥仅读 `DEEPSEEK_API_KEY`；`stream=false`、`response_format=json_object`、`max_tokens=8192`、连接 2s/总 60s/响应 1MiB；只认 `finish_reason=stop` + 非空 content，JSON 模式后仍二次校验固定 Schema；密钥未配置明确失败，无 fake 成功。
- **SSRF 防护（rawUrl）**：仅 HTTPS、拒 userinfo/非 443/片段、逐跳解析并验证 A/AAAA（拒内网/环回/链路本地/IPv4 映射 IPv6）、最多 3 跳、TLS 校验原主机；连接 2s/读取 15s/正文 2MiB。
- **资格引擎**：eligibilityRules 只接受 field/operator/value/severity/sortOrder，operator 限 eq/ne/in/contains/range；硬条件冲突 not_eligible、未知/歧义 possible、全满足 eligible；保存画像+规则版本快照，快照不随修改变化。
- **材料清单预检**：单文件检查 ≠ 机会材料齐备；保存 checklist/rules/input/model 版本与摘要快照；缺必交项即 missing，旧版本冲突拒绝。

### 4.4 依赖与交付

- 依赖：文件模块（附件 `clean`）、D 的 `GET /jobs/{jobId}`（异步收据）、DeepSeek（真实联调另验收）。
- 交付：22 条计划路由（含提前实现的 `GET /jobs/{jobId}`，见 D 节）真实业务行为 + 隔离测试；供 Task 6 复用的规则/材料快照窄接口签名。

---

## 5. 负责人 B —— 资源预约 · 工单 · 校园问题 · 拥堵（Task 7 + 8，40 条）

### 5.1 工单（4 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/work-orders` | S | 创建工单，初始 pending |
| GET | `/api/v1/me/work-orders` | L | 本人分页工单 |
| GET | `/api/v1/work-orders/{id}` | O/A(workorder.read) | 详情 + events |
| POST | `/api/v1/work-orders/{id}/ratings` | O | 评分 1–5，本人仅一次 |

### 5.2 校园问题（14 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/campus-issues` | S | 创建，初始 pending（review_required） |
| GET | `/api/v1/campus-issues` | P | 仅已审核允许公开的分页 |
| GET | `/api/v1/campus-issues/{id}` | P/所有者 | 公开详情；所有者可见自己未公开状态 |
| GET | `/api/v1/campus-issues/map` | P | bbox 地图点（limit≤200，truncated） |
| GET | `/api/v1/me/campus-issues` | L | 本人问题 |
| POST | `/api/v1/campus-issues/{id}/follows` | L | 关注（幂等） |
| DELETE | `/api/v1/campus-issues/{id}/follows` | L | 取关 |
| PUT | `/api/v1/campus-issues/{id}/vote` | S | 投票 ±1 |
| DELETE | `/api/v1/campus-issues/{id}/vote` | S | 撤销投票 |
| POST | `/api/v1/campus-issues/{id}/evidences` | S | 补证（content/attachmentIds 至少一项） |

### 5.3 拥堵（2 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/crowd/observations` | P | 分页观测（含 sourceFields） |
| POST | `/api/v1/crowd/observations` | S | 上报观测 |

### 5.4 资源与预约（11 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/resources` | P | 分页资源 |
| GET | `/api/v1/resources/{id}` | P | 详情 + bookingRules |
| GET | `/api/v1/resources/{id}/slots` | P | 某日时段 |
| GET | `/api/v1/resources/{id}/availability` | S | 可用座位 + canReserve/unavailableReason |
| GET | `/api/v1/resource-slots/{id}/availability` | S | 时段可用性 |
| POST | `/api/v1/reservations` | S | 预约（slotId + seatNo） |
| GET | `/api/v1/me/reservations` | L | 本人分页预约 |
| GET | `/api/v1/reservations/{id}` | O/A(resource.read) | 详情 |
| POST | `/api/v1/reservations/{id}/cancel` | O | 取消（释放座位） |
| POST | `/api/v1/reservations/{id}/check-in` | O | 签到（checkInToken） |

### 5.5 管理端（13 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/admin/work-orders` | A(workorder.read) | 分页工单 |
| POST | `/api/v1/admin/work-orders/{id}/assign` | A(workorder.assign) | 转办 |
| POST | `/api/v1/admin/work-orders/{id}/transition` | A(workorder.process) | 状态流转/关闭/无法处理 |
| GET | `/api/v1/admin/campus-issues` | A(issue.read) | 分页问题（敏感证据分级） |
| POST | `/api/v1/admin/campus-issues/{id}/decision` | A(issue.review) | publish/private/reject |
| POST | `/api/v1/admin/campus-issues/{id}/transition` | A(issue.process) | 流转（不改隐私可见性） |
| GET | `/api/v1/admin/resources` | A(resource.read) | 分页资源 |
| POST | `/api/v1/admin/resources` | A(resource.write) | 创建资源 |
| POST | `/api/v1/admin/resources/{id}/update` | A(resource.write) | 编辑 |
| POST | `/api/v1/admin/resources/{id}/slots` | A(resource.write) | 创建时段 |
| POST | `/api/v1/admin/resource-slots/{id}/update` | A(resource.write) | 改时段（不改已有预约时间含义） |
| GET | `/api/v1/admin/reservations` | A(resource.read) | 分页预约 |
| POST | `/api/v1/admin/reservations/{id}/decision` | A(resource.process) | 审核预约 |
| POST | `/api/v1/admin/reservations/{id}/check-in-token` | A(resource.process) | 演示签到凭证 |

### 5.6 关键实现点

- **并发防超卖**：Redis 短锁 + MySQL 事务行锁 + `resource_occupancies` 唯一约束最终防重；300 并发申请同一座位最多 1 成功。
- **工单状态机**：状态流转与 event 同事务；公开 DTO 过滤敏感内容；voteCount 非负，关注/投票唯一幂等。
- **公开/私密边界**：敏感问题不公开，处理员校园归属，跨校 404。
- **预约提醒**：过期/爽约产生解释性信用事件，可靠提醒投递走 D 的消息接口。

### 5.7 依赖与交付

- 依赖：文件（附件）、Redis 锁、D 的消息/通知（预约提醒、问题关注提醒）。
- 交付：版本冲突、权限、公开/私密边界、评价本人仅一次、300 并发防超卖测试。

---

## 6. 负责人 C —— 互助 · 组队 · 匿名社区 · 治理（Task 9 + 11，53 条）

### 6.1 互助（8 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/mutual-aid/posts` | P | 已审核公开分页 |
| GET | `/api/v1/mutual-aid/posts/{id}` | P/所有者 | 详情 + author + canContact/canEdit |
| POST | `/api/v1/mutual-aid/posts` | S | 创建，pending |
| POST | `/api/v1/mutual-aid/posts/{id}/update` | O | 编辑，内容变更重新审核 |
| POST | `/api/v1/mutual-aid/posts/{id}/actions` | O | 二手预留/完成、状态流转 |
| POST | `/api/v1/mutual-aid/posts/{id}/ratings` | 已确认交接参与人 | 双方评价，各自至多一次 |
| GET | `/api/v1/mutual-aid/posts/{id}/ratings` | 有权查看帖子 | 脱敏分页评价 |
| GET | `/api/v1/me/mutual-aid/posts` | L | 本人发布 |

### 6.2 失物 / 借用（5 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/lost-found/matches` | S | 本人帖子关联匹配 |
| POST | `/api/v1/lost-found/matches/{id}/actions` | 匹配双方 | 核验/处理 |
| POST | `/api/v1/mutual-aid/posts/{id}/borrow-requests` | S | 借用申请（borrow.kind=offer） |
| GET | `/api/v1/me/borrow-requests` | L | 分页借用 |
| POST | `/api/v1/borrow-requests/{id}/actions` | 双方按动作 | 交接/归还/超期 |

### 6.3 组队（11 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/teams` | P | 分页组队 |
| GET | `/api/v1/teams/{id}` | P | 详情 + openRoles |
| POST | `/api/v1/teams` | S | 创建 |
| POST | `/api/v1/teams/{id}/update` | 队长 | 编辑 |
| POST | `/api/v1/team-applications` | S | 申请入队 |
| GET | `/api/v1/teams/{id}/applications` | 队长 | 申请列表 |
| POST | `/api/v1/team-applications/{id}/decision` | 队长 | 受理/拒绝 |
| POST | `/api/v1/teams/{id}/leave` | 本队成员 | 退出 |
| POST | `/api/v1/teams/{id}/close` | 队长 | 关闭 |
| GET | `/api/v1/me/teams` | L | 本人参与的队 |
| GET | `/api/v1/me/team-applications` | L | 本人申请 |

### 6.4 匿名社区（9 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/community/posts` | S | 本校已审核分页 |
| GET | `/api/v1/community/posts/{id}` | S/所有者 | 详情 |
| POST | `/api/v1/community/posts` | S | 创建，pending |
| POST | `/api/v1/community/posts/{id}/update` | 作者 | 编辑，重新审核 |
| DELETE | `/api/v1/community/posts/{id}` | 作者 | 软删除 |
| GET | `/api/v1/community/posts/{id}/comments` | S | 已审核评论 |
| POST | `/api/v1/community/posts/{id}/comments` | S | 评论，pending |
| DELETE | `/api/v1/community/comments/{id}` | 评论作者 | 删除 |
| GET | `/api/v1/me/community/posts` | L | 本人帖子 |

### 6.5 举报 / 屏蔽 / 处罚 / 申诉（8 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/reports` | S | 举报 |
| GET | `/api/v1/me/reports` | L | 本人举报 |
| GET | `/api/v1/reports/{id}` | 举报人 | 详情（不返回被举报人身份） |
| PUT | `/api/v1/blocks/{targetType}/{targetId}` | L | 屏蔽 |
| DELETE | `/api/v1/blocks/{targetType}/{targetId}` | L | 解除屏蔽 |
| GET | `/api/v1/me/penalties` | L | 本人处罚 |
| POST | `/api/v1/appeals` | L | 申诉 |
| GET | `/api/v1/me/appeals` | L | 本人申诉 |

### 6.6 管理端（12 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/admin/moderation-tasks` | A(content.review) | 脱敏审核任务 |
| POST | `/api/v1/admin/moderation-tasks/{id}/claim` | A(content.review) | 原子领取 |
| POST | `/api/v1/admin/moderation-tasks/{id}/decision` | A(content.review) | approve/reject/restrict |
| GET | `/api/v1/admin/reports` | A(report.review) | 分页举报 |
| POST | `/api/v1/admin/reports/{id}/decision` | A(report.review) | 处理举报 |
| POST | `/api/v1/admin/penalties` | A(user.penalty) | 处罚 |
| POST | `/api/v1/admin/penalties/{id}/lift` | A(user.penalty) | 解除处罚 |
| GET | `/api/v1/admin/appeals` | A(appeal.review) | 分页申诉 |
| POST | `/api/v1/admin/appeals/{id}/decision` | A(appeal.review) | 处理申诉 |
| GET | `/api/v1/admin/credit-disputes` | A(credit.review) | 信用纠错申请 |
| POST | `/api/v1/admin/credit-disputes/{id}/decision` | A(credit.review) | 决策（通过追加修正事件，不抹原始事实） |
| POST | `/api/v1/admin/security-investigations` | A(security.investigate)+二次认证 | 安全调查（最小授权，不可变审计） |

### 6.7 关键实现点

- **匿名映射**：每帖独立假名，匿名 DTO 无真实 ID/可串联信息；作者维护归属；普通账号无法查真实作者；调查用独立数据源最小查询。
- **治理闭环**：审核待审不伪装成功发布；高风险私密队列；处罚/举报/申诉状态闭环；`content_moderator` 权限矩阵。
- **双方参与关系**：无付款/押金；状态前置 + version；人数/成员唯一；禁公开手机号/二维码引流。

### 6.8 依赖与交付

- 依赖：文件（附件）、身份（匿名映射）、D 的屏蔽关系持久化（Task10 提供，block 跨域协调）。
- 交付：越权/跨校/并发重复入队/归还与评价、泄露测试、调查最小权限验证。

---

## 7. 负责人 D —— 会话消息 · 订阅 · 首页 · 我的 · 数据权利 · 管理运维（Task 10 + 12 + 13，45 条）

### 7.1 会话与消息（5 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/conversations` | S | 按上下文建会话（校验双方，复用既有） |
| GET | `/api/v1/conversations` | L | 分页会话 |
| GET | `/api/v1/conversations/{id}/messages` | 会话成员 | 分页消息 |
| POST | `/api/v1/conversations/{id}/messages` | 会话成员且 S | 发消息（clientMessageId 唯一） |
| POST | `/api/v1/conversations/{id}/read` | 会话成员 | 已读游标 |

### 7.2 通知 / 订阅 / 实时（6 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/me/notifications` | L | 分页通知 |
| POST | `/api/v1/notifications/{id}/read` | 接收人 | 已读 |
| POST | `/api/v1/notifications/read-all` | L | 全部已读（beforeId，避免漏并发新消息） |
| POST | `/api/v1/subscriptions` | L | 订阅（templateId/scene/consentResult） |
| DELETE | `/api/v1/subscriptions/{id}` | 所有者 | 撤销订阅 |
| POST | `/api/v1/realtime/tickets` | L | 实时凭证（WebSocket 短时） |

### 7.3 首页 / 搜索 / AI 助手 / 异步任务（4 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/home/overview` | P/L | 首页聚合（deadlines/recommendations/hotIssues/nearbyPosts/unreadCount） |
| GET | `/api/v1/search` | P/L | 全局搜索 |
| POST | `/api/v1/ai/assistant/messages` | L | AI 助手，202 AsyncJob（scope=campus/opportunity/service） |
| GET | `/api/v1/jobs/{jobId}` | O/相应管理员 | 异步任务结果（A 提前依赖的收据路径） |

### 7.4 我的 / 成长 / 信用（11 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/me/dashboard` | L | 个人中心概览 |
| GET | `/api/v1/me/todos` | L | 待办聚合 |
| GET | `/api/v1/me/history` | L | 浏览历史 |
| DELETE | `/api/v1/me/history` | L | 清空历史 |
| GET | `/api/v1/me/growth-records` | L | 成长记录 |
| POST | `/api/v1/me/growth-records` | S | 新增成长记录（proofFileId） |
| POST | `/api/v1/me/growth-records/{id}/update` | O | 编辑 |
| DELETE | `/api/v1/me/growth-records/{id}` | O | 删除 |
| GET | `/api/v1/me/credit-events` | L | 信用事件（显示依据） |
| POST | `/api/v1/me/credit-events/{id}/disputes` | 本人 | 信用纠错（人工复核，不直接改积分） |

### 7.5 数据权利（8 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/me/authorizations` | L | 授权列表 |
| POST | `/api/v1/me/authorizations` | L | 新增授权 |
| POST | `/api/v1/me/authorizations/{id}/revoke` | O | 撤销授权 |
| GET | `/api/v1/me/privacy-settings` | L | 隐私设置 |
| POST | `/api/v1/me/privacy-settings/update` | L | 更新隐私设置 |
| POST | `/api/v1/me/data-exports` | L | 数据导出，202 DataRequest |
| POST | `/api/v1/me/account-deletion` | L | 注销，202 DataRequest |
| GET | `/api/v1/me/data-requests` | L | 数据请求列表 |
| GET | `/api/v1/me/data-requests/{id}` | O | 详情（导出成功含短期下载凭证） |
| POST | `/api/v1/me/data-requests/{id}/cancel` | O | 撤销（仅冷静期） |

### 7.6 管理运维（11 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/admin/campuses` | A(platform.campus.manage) | 创建校园 |
| GET | `/api/v1/admin/campus/configs` | A(campus.config.read) | 配置键值（不返回密钥） |
| PUT | `/api/v1/admin/campus/configs/{key}` | A(campus.config.write) | 写配置 |
| GET | `/api/v1/admin/statistics/overview` | A(statistics.read) | 聚合统计（不含私密/匿名身份） |
| GET | `/api/v1/admin/audit-logs` | A(audit.read) | 本校脱敏审计 |
| GET | `/api/v1/admin/integrations` | A(integration.read) | 集成列表 |
| PUT | `/api/v1/admin/integrations/{type}` | A(integration.write) | 集成配置（脱敏） |
| POST | `/api/v1/admin/integrations/{type}/test` | A(integration.write) | 连通测试，202 |
| POST | `/api/v1/admin/integrations/{type}/sync` | A(integration.sync) | 同步，202 |
| GET | `/api/v1/admin/integrations/{type}/errors` | A(integration.read) | 同步错误 |

### 7.7 关键实现点

- **消息可靠性**：先落库再通知，发送失败有限重试，不回滚已完成业务；WebSocket 认证不在 URL 传永久令牌，断线增量轮询；clientMessageId 唯一、未读游标单调。
- **数据权利**：授权撤销、数据导出/注销冷静期/撤销与清理队列；重认证、本人范围、短期下载；法定保留项解释。
- **AI 助手**：无依据明确未知，不编造；匿名社区不进成长档案。
- **集成隔离**：Demo/Manual 持久化提供器与 Official 接口隔离，SSRF 协议/IP 限制，超时/失败显式，不静默 demo 回退。

### 7.8 依赖与交付

- 依赖：A/B/C 的窄读取接口（首页聚合、搜索、消息触发）；身份（数据权利/注销）；文件（导出、成长证明）。
- 交付：消息隐私/可靠重试/去重、数据权利闭环、权限与幂等测试；向 A/B/C 提供消息/通知/屏蔽关系发送接口。

---

## 8. 跨人协作与依赖

| 提供方 | 能力 | 消费方 |
| --- | --- | --- |
| 公共底座 | 会话/权限/幂等/文件/加密/游标/限流 | 全体 |
| D | 消息/通知/订阅投递、屏蔽关系持久化、`GET /jobs/{jobId}` | B（预约/关注提醒）、C（审核/评价/屏蔽）、A（异步收据） |
| A | 机会规则版本/材料要求快照、公开机会窄读取 | D（首页/搜索聚合）、C（组队关联机会） |
| B | 资源/问题公开窄读取 | D（首页聚合） |
| C | 互助/社区公开窄读取 | D（首页聚合） |

协作约定：
- 写接口一律经 `@BusinessWrite` 幂等边界 + `CurrentActor` 权限，跨人调用不得绕过。
- 公开数据的窄读取接口只暴露脱敏字段，跨校/未审核/私密对象返回 404。
- 本人列表（`me/xxx`）跟随业务域归属，见各节。

## 9. 统一提交与验收约定

- 每人按各自章节领接口，TDD（先 RED 后 GREEN），隔离 H2 测试 + 真实 MySQL/Redis/集成单独记录，不把测试替身当真实联调。
- 真实外部依赖（微信、DeepSeek、S3、ClamD、学校接口）缺配置时明确失败，不 fake 成功；联调另行验收。
- 全部 4 人完成 + 独立审查通过后，统一提交 GitHub，不分批推半成品。
- 关键验收：206 接口全部有真实业务行为；300 并发防超卖；数据权利闭环；秘密扫描 + `git diff --check` + 打包验证。

## 10. 协作与提交约定（4 人并行怎么合）

> 这是 Maven 单体工程（一个 `backend/` 目录），不是 4 个独立服务。4 人并行开发时，各自 push 自己的分支，由一人收口合并，不能都往同一个分支推。

### 10.1 分支策略

1. **主干先停在「公共底座已通过」的干净点**（Task 1–4），所有人从这一点拉分支。
2. **每人一个 feature 分支**：

| 负责人 | 分支名 |
| --- | --- |
| A | `feature/opportunity` |
| B | `feature/resource` |
| C | `feature/community` |
| D | `feature/messaging` |

3. 只在自己的包下加文件（如 A 只加 `opportunity/`、`ai/` 包），不动别人的包；公共文件的改动用「最小追加」方式（见 10.3）。

### 10.2 Flyway 迁移版本号提前分配（不抢号）

当前已发布 V1–V9。待开发迁移按下表分配，各人从自己号段起，不重复：

| 负责人 | 迁移版本 | 内容 |
| --- | --- | --- |
| A | V10 | 机会/规则/资格（opportunity） |
| A | V11 | 材料清单/预检（checklist/precheck） |
| B | V12 | 资源/时段/预约（resource/reservation） |
| B | V13 | 工单/校园问题/拥堵（workorder/issue） |
| C | V14 | 互助/组队/借用（mutual-aid/team） |
| C | V15 | 匿名社区/治理/调查（community/moderation） |
| D | V16 | 会话/消息/订阅（messaging） |
| D | V17 | 个人/成长/数据权利（me/growth/privacy） |

> 只追加新版本，不改已发布的 V1–V9；缺了某号段就与主线冲突，合并前自查。

### 10.3 公共文件的最小改动约定

会撞车的公共文件，只做「追加自己的那一段」，避免整文件重写：

- `SecurityConfiguration` / `RoutePolicy`：各自追加自己模块的公开路径与安全规则，用注释标「所属模块 + 负责人」，不重排别人的行。
- `application.yml`：各自追加自己的配置键，不改公共键。
- `pom.xml`：除非确需新依赖，否则不动；需要加依赖先在分支内说明。

合并冲突时，由收口人按「保留各自追加段」处理，不删任何一人的内容。

### 10.4 契约先行（解决编译依赖）

编译依赖决定了不能完全各自独立。约定「契约（接口签名）先行」，先合入主干或先在团队内对齐：

| 提供方 | 先定下来的契约 | 依赖方 |
| --- | --- | --- |
| D | `GET /api/v1/jobs/{jobId}` 结果读取 | A（异步收据）、全体 |
| D | 消息/通知/订阅发送接口（服务方法签名） | B、C |
| D | 屏蔽关系持久化接口 | C |
| A | 机会规则版本/材料要求快照窄接口 | D（首页/搜索） |
| B、C | 公开数据窄读取接口 | D（首页聚合） |

> 契约先以「接口 + 方法签名 + DTO 字段」的形式定死，D 先落地 `GET /jobs/{jobId}` 和消息接口签名，A/B/C 才能对着它编译；实现后补。

### 10.5 合并收口流程

1. 每人写完自己分支、本地测试通过后，push 自己的分支。
2. **一人收口**：依次把 4 个分支合入主干，每次合并后跑 `mvn test`，保证主干始终可编译。
3. 全部合入 + 全量测试通过 + 独立审查通过后，**一次性 push 远程**（不分批推半成品）。

> 一句话：不是「4 人各自 push 完事」，而是「4 人各自 push 自己的分支 → 一人收口合并、跑通、统一 push」。
