# 机会模块说明（负责人A · Task 5 + 6）

> 范围：通知 · 机会 · AI 解析 · 资格 · 收藏提醒 · 自行提交 · 材料清单 · 预检。
> 集成修复分支 `fix/abc-integration-20261015`；迁移 V10/V11 与追加的 V16（逐项规则、人工结构化复核标记）；测试替身只在 `src/test`。

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
- 只认 `finish_reason=stop` + 非空 content；JSON 模式后仍二次校验固定 Schema（title/organizer/summary/category/targetAudience/conditions/deadline/awards/materials/contact）；未知键与无效字段被过滤，全部无效则失败；每个有效字段必须有非空字符串 evidenceLocation 且逐字出现在原文中。证据出现不等于语义已被证实，仍需人工复核；扫描件无 OCR 明确转人工。
- 密钥未配置明确失败，无 fake 成功；401/402/422 不盲目重试，429/5xx/超时交给任务有界重试（≤3 次）。
- 原文来源优先级：content > rawUrl > attachment(PDF 提取文字)；提示模板版本 `notice-parse:v1` 记录在任务上。
- 60s 为从连接至正文读完的整次调用上限，成功与非 200 响应均关闭；响应仍限 1MiB。错误码直接保留适配器的 `AI_KEY_MISSING` 等，不重复加前缀。
- 最后一次解析租约过期时，任务与收据失败；仅最新解析代把仍在 parsing 的通知恢复为 pending，可重新发起解析，旧代不能重置新代。
- 人工确认的所有字段（包括人工新增的 conditions/materials/targetAudience/awards/contact）保留在复核接口的 humanValue 中。approve 只生成 draft，不自动把自由文本编成资格规则或合并为一个材料项。存在确认的条件/对象或材料文本时，发布前必须人工显式提交对应 eligibilityRules/materialRequirements；逐项补录真实要求，确认无附加要求时允许明确提交 `[]`。V16 分别持久化两类复核标记；更新记录规则快照、操作者与审计，发布仍单独要求 opportunity.publish 权限。

### 2.2 SSRF 防护（integration/notice/SafeUrlReader）

- 仅 HTTPS；拒 userinfo/非 443 端口/片段；逐跳解析并验证全部 A/AAAA（拒内网/环回/链路本地/ULA/保留/文档段/IPv4 映射 IPv6 绕过）；连接固定到已验证地址且 TLS 校验原主机；最多 3 次重定向且每跳重新检查；连接 2s/总读取 15s/正文 2MiB（解压后同样受限）；错误不回显完整上游 URL/响应；不信任系统代理，无测试绕过开关。
- 先严格解码 Content-Length/chunked 传输帧，再解 gzip/deflate 内容；压缩前后分别限 2MiB。提前 EOF、缺失零块、非法块边界、冲突长度/传输头及不支持的编码均失败。TLS 握手前设置读取超时，跳转继续使用同一读取期限。

### 2.3 资格规则引擎（opportunity/EligibilityEngine）

- 只接受 field/operator/value/severity/sortOrder；operator 限 eq/ne/in/contains/range；不执行表达式/SQL/脚本。
- 已知画像字段：college/grade（字符串）、skills/interests（标签数组）；range 仅 grade 且严格可解析才判断。
- 结论三档：已知硬条件冲突 not_eligible；未知/歧义/未满足软条件 possible；全部已知条件满足 eligible。空规则或未知字段一律 possible，绝不凭缺值得出 eligible；unknown 不编造 actual。
- 结果保存入队时的规则版本快照（opportunity_rule_snapshots）与画像快照，旧结果不随修改变化。

### 2.4 材料清单与预检（material/checklist + material/precheck）

- 单材料基础预检 ≠ 机会材料齐备；预检逐项 results 为 `[{itemKey,level,message,evidence}]`，level=passed/confirm/missing，另有 basicComplete；不声称主办方一定接受。
- 整清单预检每次保存不可变 inputSnapshot/checklistVersion/rulesVersion/inputDigest 与 AI 授权依据（aiConsentId）；替换材料后旧任务仍可查看但 stale=true。
- 单材料与清单均从所选 material_versions 的 attachment_id/object_key_encrypted/file_hash 读取，绝不回退到 material_files 最新对象。快照保留附件、散列、名称、类型、大小和有效期；读取时再次检查本人/校园、现行 AI 授权、用途及 clean/active，限量读取并核对散列。无安全附件的历史遗留版本需完成受控转换后才能预检。
- 清单完成时先锁父清单并比较版本；旧版本只保存自身历史结果。同一清单版本仅最新任务可以更新当前条目。重试耗尽或租约耗尽后，当前 prechecking 项恢复 uploaded/missing，允许重新发起。
- AI 处理材料必须先取得专门授权（authorizations type=ai,status=granted）。
- worker 全部租约/fencing 模式（claim_token + FOR UPDATE SKIP LOCKED + 过期租约回收），对象存储读取在事务外。

材料项 `validationRules` 可省略/null（通用基础检查），或为仅含以下可选字段的对象：`mimeTypes` 为现有 JPEG/PNG/PDF/DOCX MIME 的非空去重子集（最多4项）；`maxSizeBytes` 为 1～20971520 的整数；`fileNameContains` 为 1～128 字符非空文本（区分大小写的包含检查）。未知键、错类型、越界值拒绝 400；`required` 必须为 JSON 布尔值。规则保存到机会材料项、不可变规则快照、创建时清单项和预检输入快照，逐项应用到所选版本；仍保留 passed/confirm/missing findings 和 basicComplete，不保证主办方接受。

## 3. 供其他模块消费的窄接口

| 消费方 | 能力 | 签名要点 |
| --- | --- | --- |
| D（首页/搜索） | 公开机会窄读取 | `OpportunityService.list/detail/available`：跨校/未发布/已删除一律不可见 |
| D | 规则/材料要求快照读取 | `opportunity_rule_snapshots(opportunity_id, rules_version)` 不可变；`opportunities.rules_version` 为当前版本 |
| 全体 | 异步收据读取 | `GET /api/v1/jobs/{jobId}`（JobReadService）：学生域仅本人，管理员域同校管理员；payload/claim_token/对象键不返回 |
| B/C | 收藏/提醒目标可见性接入点 | FavoriteService.put / ReminderService.create 中按 targetType 明确分支，当前非 opportunity 类型返回 400 TARGET_NOT_CONNECTED |

## 4. 已知限制与交接

- 收藏 targetType=resource/mutual_aid_post/community_post、提醒 targetType=borrow/reservation 的可见性策略待 Task7/9/11 接入（当前明确拒绝，不搞同校即授权的通用 CRUD）。
- 真实 DeepSeek 付费调用与解析质量未验收；HTTP 正文超时使用本地受控 HTTP 服务测试，传输解码使用内存帧测试，TLS 握手超时使用受控 socket 测试，均不等于真实公网 HTTPS/证书链成功验收。
- 通知附件仅支持 PDF 文字提取；图片扫描件与 DOCX 明确转人工录入。
- 管理端异步收据经 `/admin/notice-parse-tasks/{id}` 读取；`GET /jobs/{jobId}` 学生域为主。
