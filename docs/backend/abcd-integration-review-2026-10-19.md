# A/B/C/D 四模块前后端联调审查（2026-10-19）

## 结论

- **后端四模块已经具备联调条件**：在本机把 `main`（A/B/C）与 `feature/messaging`（D）合成到
  `review/abcd-integration` 后，计划清单中的 **206 条接口全部存在实现**，只多出 1 条后台
  CSRF 辅助接口；全量测试 **Tests run: 341 / Failures: 0 / Errors: 0 / Skipped: 39**。
- **前端仍不能做真实联调**：GitHub 仓库中没有可运行的微信小程序源码，只有
  `stitch_web_repository_frontend_designer/_1`～`_21` 的静态 HTML/PNG 原型；原型中没有
  `wx.request`、`wx.uploadFile` 或 `fetch` 调用。本机 `D:/project` 是另一个独立的材料预检
  Mock 演示工程，使用 `wx.getStorageSync`/`setStorageSync`，没有调用本后端。因此当前只能验证
  “接口契约与后端实现”，不能宣称“真机页面已联调”。
- 本次修复了三个真实联调缺陷：**错误码与文档契约不一致**、**队长看不到待处理入队申请**、
  **预约事件不产生 D 模块通知**；另为本地模型联调补了 **Ollama / Qwen3 8B 配置入口**。
- 仍有跨模块缺口：**工单、校园问题、组队/交接等其他 B/C 事件尚未写入 D 的通知**。
  该缺口不阻塞现有接口测试，但会直接影响“我的通知”的完整性，列为后续优先项。

## 审查基线

| 项目 | 值 |
| --- | --- |
| GitHub 仓库 | `https://github.com/dew64025-stack/-` |
| `main` | `64369bd936f36cb615bd0c95c9adda3d9429d2f0`（A/B/C） |
| `feature/messaging` | `2b43aa9`（D，基于上述 main，可快进合并） |
| 本机整合分支 | `review/abcd-integration`（未推送） |
| 构建环境 | JDK 21.0.12 / Maven 3.9.9 |
| 测试命令 | `mvn -B -ntp -f backend/pom.xml test` |

## 四模块状态

| 模块 | 范围 | 路由/实现 | 联调结论 |
| --- | --- | --- | --- |
| A | 机会、通知、AI 解析、资格、材料清单 | 已实现，含 DeepSeek 适配与 SSRF 防护 | 公共读取/异步任务契约与前端原型一致；真实 DeepSeek/公网 TLS 未验收 |
| B | 资源预约、工单、校园问题、拥堵 | 已实现，含并发防超卖与公开/私密边界 | 接口契约完整；预约创建/取消/审批已触发 D 通知，其余 B 事件待接入 |
| C | 互助、失物/借用、组队、匿名社区、治理 | 已实现，含匿名映射与双方确认闭环 | 与 D 的会话上下文（互助帖/借用/组队）已打通；无真实微信/存储联调 |
| D | 会话消息、订阅、首页/搜索/AI 助手、我的、数据权利、管理运维 | 44 条新路由，复用 A 的 `GET /jobs/{jobId}` | 首页/搜索/待办聚合已读取 A/B/C；D 分支尚未合入远端 main |

### 路由核对

以 `docs/backend/route-inventory.json` 的 206 条计划接口为准，对四个模块所有
`*Controller.java` 的类级/方法级映射做归一化（`{id}` 等占位符统一）比较：

```text
planned 206, implemented 207, missing 0, extra 1
extra: GET /api/v1/admin/auth/csrf
```

即没有“文档有、后端没有”的接口；多出的是后台登录所需的 CSRF 辅助接口。

## 本次修复

### 1. 错误码与前端契约对齐

文档第 17 节要求客户端识别 `AUTH_REQUIRED`、`PERMISSION_DENIED`、`RESOURCE_NOT_FOUND`、
`PHONE_BIND_REQUIRED`、`STUDENT_VERIFICATION_REQUIRED`、`ACCOUNT_RESTRICTED` 等错误码；
后端此前返回 `UNAUTHENTICATED`、`FORBIDDEN`、`NOT_FOUND`，前端按文档写分支会匹配不到。

修复：

- 新增 `common/ContractErrorCodes`，在 HTTP 边界统一映射：
  - `UNAUTHENTICATED` → `AUTH_REQUIRED`
  - `FORBIDDEN` → `PERMISSION_DENIED`
  - `NOT_FOUND` → `RESOURCE_NOT_FOUND`
  - `INVALID_IDEMPOTENCY_KEY` → `INVALID_REQUEST`
  - `IDEMPOTENCY_CONFLICT` → `IDEMPOTENCY_KEY_REUSED`
- `CurrentActor` 增加精确的认证阶段错误：
  - 非 active → `ACCOUNT_RESTRICTED`
  - 未绑定手机号 → `PHONE_BIND_REQUIRED`
  - 未完成学生认证 → `STUDENT_VERIFICATION_REQUIRED`
  - 非学生/无权限 → `PERMISSION_DENIED`
- `JsonSecurityErrors`、`FilterErrors`、`GlobalExceptionHandler` 均走同一映射，避免过滤器和
  Controller 两条错误出口不一致。
- 更新对应 HTTP 测试断言。

### 2. `/me/todos` 与 `/me/dashboard` 补齐队长视角

`TeamReadBridge` 原先只查询“我作为申请人”的待审申请，队长待处理的入队申请不会出现在
`GET /me/todos`，`todoCount` 也偏小。现补充：

- `pendingReviewTodos` / `pendingReviewCount`：按 `teams.leader_id + campus_id + pending` 查询。
- `MeService` 将队长视角纳入聚合，并新增 `MeHttpTest` 用例（申请人 + 队长双视角）。

### 3. 预约事件接入 D 的通知（B→D 首条真实联调链路）

文档第 9.3 节要求预约流程“同事务登记提醒和通知任务”。原实现只写预约与占用，D 的
`notifications` 中没有预约事件。现在在 `ResourceService` 中接入 D 的
`MessagingService.issueNotification`：

- 创建预约 → `reservation` 通知（目标类型 `reservation`，目标 ID 为预约 ID）
- 取消预约 → 取消通知
- 后台审批 → 确认/拒绝通知
- 通知与预约写入处于同一数据库事务；`dedup_key` 防止幂等重放产生重复通知
- 隔离服务测试传 `null` 时跳过通知，不改变原有并发/租约测试的语义
- `ServiceHttpTest` 增加通知数量和目标归属断言

### 4. AI 本地模型入口（Ollama / Qwen3 8B）

- `DeepSeekClient` 保留 DeepSeek 默认行为，新增 `AI_PROVIDER=ollama` 配置：
  - 使用 Ollama 原生 `/api/chat` 端点（不是 `/v1/chat/completions`）
  - 请求带 `think:false` + `format:"json"`，避免 Qwen3 把输出预算耗在 reasoning 上
  - 默认模型 `qwen3:8b`；本机 Ollama 不需要真实密钥，使用占位 Bearer
  - 可通过 `app.ai.timeout-seconds` 放宽 CPU 推理超时
- 新增 `backend/src/main/resources/application-ollama.yml`，可用
  `--spring.profiles.active=ollama` 启动。
- 新增客户端协议测试，校验 Ollama 原生请求体；另加 `OLLAMA_LIVE_TEST=1` 才运行的真实
  Qwen3 调用测试，本机已通过（约 1.2 秒，RTX 4060 Laptop 8GB）。

## 未关闭事项（按优先级）

1. **前端源码缺失（阻塞真实联调）**：仓库需补入原生微信小程序工程（`app.json`、
   `project.config.json`、`miniprogram/`、统一 `wx.request` 封装），否则无法验证页面参数、
   令牌刷新、幂等键、上传/轮询和真机行为。现有 HTML 原型只能作为设计参考。
2. **B/C→D 通知覆盖仍不完整（P2）**：预约创建/取消/审批已接入 D 的通知并同事务写入；
   工单状态流转、校园问题审核/关注、组队申请/交接等事件尚未通知。建议按同样方式接入
   `MessagingService.issueNotification`，并补对应 HTTP 测试。
3. **D 分支未进入远端 main**：本机只做了 `review/abcd-integration` 快进整合，没有推送。
   是否合并、何时推送由仓库负责人决定。
4. **401 未细分 `TOKEN_EXPIRED`**：当前过期/无效会话统一返回 `AUTH_REQUIRED`；客户端按 401
   串行刷新一次并重放安全请求即可，不阻塞联调。
5. **真实依赖未验收**：38 项跳过用例覆盖真实 MySQL/Redis/S3/ClamD 等；真实微信登录、
   学校接口、WebSocket、对象存储和生产 TLS 仍未联调。Ollama 本机调用已验证通过，
   配置与复现命令见 `docs/backend/ollama-local.md`。

## 验证记录

```text
# 整合分支全量测试（最终，含本次全部修复）
Tests run: 341, Failures: 0, Errors: 0, Skipped: 39
BUILD SUCCESS

# 本次修复的聚焦测试
MeHttpTest                     5/5
ServiceHttpTest               32/32
ApiBoundaryTest                4/4
SecurityBoundaryTest           3/3
SessionHttpBoundaryTest       10/10
IdentityHttpTest              34/34
DeepSeekClientTest             6/6
OllamaLiveClientTest            1/1（OLLAMA_LIVE_TEST=1，真实 Qwen3）

# 打包
mvn -B -ntp -f backend/pom.xml -DskipTests package
BUILD SUCCESS；JAR 内含 application-ollama.yml 与 V1～V27 迁移。
```

> 39 项跳过 = 既有 38 项显式 opt-in 的真实 MySQL/Redis/S3/ClamD 用例 + 1 项
> `OLLAMA_LIVE_TEST=1` 才启用的本地 Qwen3 真实调用；不是失败。该真实调用已单独执行并通过：
> `OLLAMA_LIVE_TEST=1 mvn -Dtest=OllamaLiveClientTest test`（1 项通过，约 1.2 秒）。最终日志：
> `D:/caizhitong-review/mvn-test-abcd-final4.log`。
