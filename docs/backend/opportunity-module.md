# 机会模块说明（负责人A · Task 5 + 6）

> 范围：通知 · 机会 · AI 解析 · 资格 · 收藏提醒 · 自行提交 · 材料清单 · 预检。
> 分支 `feature/opportunity`；迁移 V10（机会运行时）/ V11（清单预检运行时）；测试替身只在 `src/test`。

## 1. 路由清单（28 条，全部有真实业务行为）

### 1.1 学生端（18 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | /api/v1/opportunities | P | 公开列表（仅 published/expired 本校未删除）；q(1-100)/category 五类/deadlineFrom~To 带时区且有序/游标；排序 deadline ASC,id ASC，NULL 最后；游客需 campusCode（全站仅一个开放校园时自动采用），登录后不可换校 |
| GET | /api/v1/opportunities/{id} | P | 详情 + notice(已获发布依据的安全原文与来源) + eligibilityRules + materialRequirements + teamCount + isFavorited |
| POST | /api/v1/opportunities/{id}/eligibility-checks | S | 202 AsyncJob：入队时保存服务器读取的当前画像+profileVersion+规则版本 |
| GET | /api/v1/opportunities/{id}/eligibility-checks/latest | O | 本人最新匹配结果（结论三档+逐条 reasons）；画像快照不返回 |
| PUT | /api/v1/opportunities/{id}/submission | S | 自行提交时间（RFC3339 带时区）；用户+机会唯一（V10 约束+事务），重复并发明确失败；不声称平台代交 |
| PUT | /api/v1/favorites/{targetType}/{targetId} | L | 收藏（幂等）；仅 opportunity 策略已实现，resource/mutual_aid_post/community_post 明确拒绝（待 Task7/9/11 接入） |
| DELETE | /api/v1/favorites/{targetType}/{targetId} | L | 取消收藏（幂等） |
| GET | /api/v1/me/favorites | L | 分页收藏；下架/未接入目标只返回不可用标识 |
| POST | /api/v1/reminders | L | 创建提醒（remindAt 不得早于服务器当前时间；目标先验可见性/本人归属）；opportunity/material 已实现，borrow/reservation 待 Task7 |
| DELETE | /api/v1/reminders/{id} | O | 仅 pending→cancelled，不伪造 sent |
| GET | /api/v1/me/reminders | L | 分页提醒（status 过滤） |
| PUT | /api/v1/opportunities/{id}/checklist | S | 创建或取得本人清单（按当时机会材料要求复制条目；并发由唯一约束收口） |
| GET | /api/v1/checklists/{id} | O | 清单详情 |
| POST | /api/v1/checklists/{id}/items/{itemId}/update | O | 绑定/解绑本人不可变材料版本（null 解绑）；父清单版本提升；使用记录留痕 |
| POST | /api/v1/materials/{id}/prechecks | O | 单材料基础预检，202 AsyncJob（async_jobs，jobType=material_precheck） |
| POST | /api/v1/checklists/{id}/prechecks | O | 整清单预检，202 AsyncJob（async_jobs 收据 + precheck_tasks/results 执行记录） |
| GET | /api/v1/checklists/{id}/prechecks/latest | O | 最新预检结果；替换材料后 stale=true，不回写 |
| GET | /api/v1/jobs/{jobId} | O | 异步收据（Task5 提前实现，Task12 复用）；学生域仅本人；未知归属旧任务 404；payload/租约/对象键永不返回 |

### 1.2 管理端（10 条）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | /api/v1/admin/notices | A(notice.read) | 分页通知 |
| POST | /api/v1/admin/notices | A(notice.write) | 创建（title/publisher/content/rawUrl/attachmentId 至少一种原文来源）；rawUrl 创建时即 SSRF 校验；不接收 sourceType/状态/校园/创建人 |
| POST | /api/v1/admin/notices/{id}/parse | A(notice.parse) | 202：解析登记与审计原子提交；同通知仅一个进行中的解析代；失败回到可人工处理状态 |
| GET | /api/v1/admin/notice-parse-tasks/{id} | A(notice.read) | 字段级解析结果（evidenceLocation/confidence/humanConfirmed/humanValue） |
| POST | /api/v1/admin/notice-parse-tasks/{id}/review | A(notice.review) | 逐字段复核（固定 Schema 键、每键一次、类型/长度校验）；approve 只生成 draft；reject 退回人工 |
| GET | /api/v1/admin/opportunities | A(opportunity.read) | 分页机会（全状态） |
| POST | /api/v1/admin/opportunities | A(opportunity.write) | 创建（显式 draft；规则/材料结构校验并落库初始快照） |
| POST | /api/v1/admin/opportunities/{id}/update | A(opportunity.write) | 白名单字段 title/organizer/summary/deadline/eligibilityRules/materialRequirements；已发布/已截止先撤回；规则变化独立 rulesVersion+新快照 |
| POST | /api/v1/admin/opportunities/{id}/publish | A(opportunity.publish) | 人工发布（校验版本/规则快照/通知人工确认依据）；无 noticeId 人工草稿显式发布并写不可变审计 |
| POST | /api/v1/admin/opportunities/{id}/unpublish | A(opportunity.publish) | 撤回（archived） |

## 2. 关键实现点

### 2.1 AI 解析（integration/ai + ai/notice）

- DeepSeek 生产 HTTP 适配：固定 `https://api.deepseek.com/chat/completions`，Bearer 密钥仅读 `DEEPSEEK_API_KEY`，模型 `DEEPSEEK_MODEL`（默认 deepseek-flash）；不跟随重定向、不接收请求中 baseUrl；连接 2s/总 60s/响应 1MiB；`stream=false`、`thinking.type=disabled`、`max_tokens=8192`、`response_format=json_object`。
- 只认 `finish_reason=stop` + 非空 content；JSON 模式后仍二次校验固定 Schema（title/organizer/summary/category/targetAudience/conditions/deadline/awards/materials/contact）；未知键/错类型/超限/无原文依据（evidenceLocation 必须逐字出现在原文中）一律拒绝；扫描件无 OCR 明确转人工。
- 密钥未配置明确失败，无 fake 成功；401/402/422 不盲目重试，429/5xx/超时交给任务有界重试（≤3 次）。
- 原文来源优先级：content > rawUrl > attachment(PDF 提取文字)；提示模板版本 `notice-parse:v1` 记录在任务上。

### 2.2 SSRF 防护（integration/notice/SafeUrlReader）

- 仅 HTTPS；拒 userinfo/非 443 端口/片段；逐跳解析并验证全部 A/AAAA（拒内网/环回/链路本地/ULA/保留/文档段/IPv4 映射 IPv6 绕过）；连接固定到已验证地址且 TLS 校验原主机；最多 3 次重定向且每跳重新检查；连接 2s/总读取 15s/正文 2MiB（解压后同样受限）；错误不回显完整上游 URL/响应；不信任系统代理，无测试绕过开关。

### 2.3 资格规则引擎（opportunity/EligibilityEngine）

- 只接受 field/operator/value/severity/sortOrder；operator 限 eq/ne/in/contains/range；不执行表达式/SQL/脚本。
- 已知画像字段：college/grade（字符串）、skills/interests（标签数组）；range 仅 grade 且严格可解析才判断。
- 结论三档：已知硬条件冲突 not_eligible；未知/歧义/未满足软条件 possible；全部已知条件满足 eligible。空规则或未知字段一律 possible，绝不凭缺值得出 eligible；unknown 不编造 actual。
- 结果保存入队时的规则版本快照（opportunity_rule_snapshots）与画像快照，旧结果不随修改变化。

### 2.4 材料清单与预检（material/checklist + material/precheck）

- 单材料基础预检 ≠ 机会材料齐备；预检逐项 results 为 `[{itemKey,level,message,evidence}]`，level=passed/confirm/missing，另有 basicComplete；不声称主办方一定接受。
- 整清单预检每次保存不可变 inputSnapshot/checklistVersion/rulesVersion/inputDigest 与 AI 授权依据（aiConsentId）；替换材料后旧任务仍可查看但 stale=true。
- AI 处理材料必须先取得专门授权（authorizations type=ai,status=granted）。
- worker 全部租约/fencing 模式（claim_token + FOR UPDATE SKIP LOCKED + 过期租约回收），对象存储读取在事务外。

## 3. 供其他模块消费的窄接口

| 消费方 | 能力 | 签名要点 |
| --- | --- | --- |
| D（首页/搜索） | 公开机会窄读取 | `OpportunityService.list/detail/available`：跨校/未发布/已删除一律不可见 |
| D | 规则/材料要求快照读取 | `opportunity_rule_snapshots(opportunity_id, rules_version)` 不可变；`opportunities.rules_version` 为当前版本 |
| 全体 | 异步收据读取 | `GET /api/v1/jobs/{jobId}`（JobReadService）：学生域仅本人，管理员域同校管理员；payload/claim_token/对象键不返回 |
| B/C | 收藏/提醒目标可见性接入点 | FavoriteService.put / ReminderService.create 中按 targetType 明确分支，当前非 opportunity 类型返回 400 TARGET_NOT_CONNECTED |

## 4. 已知限制与交接

- 收藏 targetType=resource/mutual_aid_post/community_post、提醒 targetType=borrow/reservation 的可见性策略待 Task7/9/11 接入（当前明确拒绝，不搞同校即授权的通用 CRUD）。
- 真实 DeepSeek 付费调用与解析质量未验收（无密钥）；TLS 成功路径的密闭网络测试需带公网 DNS 的受控环境（本机所有地址均属被拒保留段）。
- 通知附件仅支持 PDF 文字提取；图片扫描件与 DOCX 明确转人工录入。
- 管理端异步收据经 `/admin/notice-parse-tasks/{id}` 读取；`GET /jobs/{jobId}` 学生域为主。
