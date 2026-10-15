# 材智通校园平台后端接口文档

> 版本：v1.1 仓库对照修订稿（尚无运行实现）
>
> 日期：2026-10-13
>
> 技术方案：Java 21 LTS、Spring Boot 4.1.x、Spring Security、Spring Data JPA、Flyway、MySQL、Redis、MinIO/S3；模块化单体
>
> 对照仓库：[dew64025-stack/-](https://github.com/dew64025-stack/-)
>
> 对照提交：`f1a33c36f99c334f48613f2f3d6f871540310634`

## 1. 文档用途与实现状态

本文件定义微信小程序、管理后台与后端之间的请求和响应约定，覆盖首页、机会、服务、互助、我的五个入口及运营后台。

当前仓库包含数据库脚本和设计文档，没有可供核验的后端代码。因此本文件中的路由均为**计划实现接口**，不是已经部署、可以直接调用的服务清单。示例数据均为虚构数据。

依据顺序：项目规划书决定功能和主要路由；最新SQL决定现存字段、约束和数据库权限；数据字典用于解释字段。三者存在冲突时必须记录，不以本接口文档擅自覆盖仓库要求。接口返回业务DTO，不能直接返回JPA实体；计算字段、应用层存储和待补模型在第19、21节单独标注。本稿保留原v1.0文件，并修正路径、方法和契约缺口。

## 2. 环境与通用约定

### 2.1 地址

| 环境 | 示例地址 | 说明 |
| --- | --- | --- |
| 本机调试 | http://127.0.0.1:8080/api/v1 | 仅本机工具调试；不是手机可访问地址 |
| 演示 | https://demo-api.example.com/api/v1 | 示例域名，部署时替换 |
| 正式 | https://api.example.com/api/v1 | 示例域名，必须配置合法请求域名 |
| 管理接口 | 同一 Base URL 下的 /admin | 独立管理员认证和权限 |

下文接口表中的路径相对于 `/api/v1`。开发者工具中的 localhost 不能作为真机或正式上线地址。正式请求、上传、下载和 WebSocket 域名按微信平台要求分别配置。

### 2.2 请求头

```http
Content-Type: application/json
Authorization: Bearer <accessToken>
Idempotency-Key: <client-generated-uuid>
X-Client-Version: 1.0.0
X-Reauth-Token: <admin-high-risk-token-if-required>
```

- Authorization：需要登录的接口必填。
- Idempotency-Key：所有业务写接口（POST/PUT/DELETE）必填，重复点击和网络重试复用同一键。登录、刷新、手机号凭证、二次认证、临时签名/票据接口采用自身一次性凭证机制；二进制上传使用uploadId＋内容摘要防重复。例外不允许扩大到预约、发帖、申请、评价、注销等业务命令。
- 访问令牌放请求头，不能进入URL；管理员高危操作使用X-Reauth-Token。刷新凭证仅出现在指定认证请求/安全Cookie中；临时上传凭证使用Authorization: Upload <uploadToken>。不得在日志记录完整凭证。接口表中reauthToken若出现在输入说明，均指该请求头，不是查询参数或正文属性。
- 服务端生成 requestId，不信任客户端传入的身份、权限、校园和操作人。

### 2.3 类型规则

- 所有 BIGINT ID 一律用十进制**字符串**传输，避免 JavaScript 数字精度丢失。
- JSON 属性采用 camelCase；数据库字段的 snake_case 不直接暴露。
- 日期时间采用 RFC 3339，例如 `2026-10-13T18:00:00+08:00`。
- DATETIME 业务时间统一按 UTC 存储；资源 slotDate、startTime、endTime 表示校园当地日期与时间，首发时区 Asia/Shanghai。
- 金额使用两位小数的十进制字符串，例如 `"25.00"`，不得依赖二进制浮点金额运算。
- 未知或不适用值用 null，不用空字符串代替日期、数字或 ID。
- JSON 中所有出现的枚举必须校验；未声明字段拒绝写入，禁止批量赋值到数据库实体。

### 2.4 统一响应

成功，HTTP 200/201/202：

```json
{
  "success": true,
  "data": {
    "id": "10001"
  },
  "requestId": "req_01JDEMO",
  "timestamp": "2026-10-13T18:00:00+08:00"
}
```

失败，使用对应的非 2xx HTTP 状态：

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "请检查填写的信息",
    "fieldErrors": [
      {
        "field": "endTime",
        "code": "TIME_RANGE_INVALID",
        "message": "结束时间必须晚于开始时间"
      }
    ],
    "retryable": false
  },
  "requestId": "req_01JDEMO",
  "timestamp": "2026-10-13T18:00:00+08:00"
}
```

fieldErrors 为数组，没有字段错误时返回空数组。错误中不得包含堆栈、SQL、密钥、手机号明文或他人信息。后文的业务响应 JSON 示例展示 data 内部内容，实际 HTTP 响应统一包裹在本节结构中。

### 2.5 分页与搜索

通用查询：`cursor` 可选，`limit` 默认 20、最大 100。接口特殊限额另行说明。

```json
{
  "items": [],
  "nextCursor": null,
  "hasMore": false
}
```

游标由后端签名生成，绑定查询条件与校园；不能传入其他筛选条件继续复用。列表默认 `createdAt DESC, id DESC`；机会截止排序为 `deadline ASC, id ASC`，截止为空的数据放最后。排序字段必须在服务端白名单内。

关键字 q：1～100 字符，作为普通搜索数据处理，不拼接 SQL 或脚本。

### 2.6 幂等与并发修改

- 幂等范围：账号、校园、HTTP 方法、路由和 Idempotency-Key。
- 同键同请求重放原业务结果；同键不同请求返回 409 IDEMPOTENCY_KEY_REUSED。
- 幂等记录设计保留 24 小时；预约占用、消息去重等业务约束仍长期生效。
- 支持version的对象在更新或状态命令中携带当前version；使用POST /对象路径/update明确表示部分字段更新，条件更新并增加版本，冲突返回409 VERSION_CONFLICT。微信原生wx.request的官方方法类型不包含PATCH，本学生端契约不依赖PATCH。
- 没有 version 的关联表使用事务、行锁和状态前置条件，不捏造已存在的乐观锁字段。
- 业务写入与异步任务登记处于同一数据库事务；模型、微信或文件服务的远程调用不长期占用数据库事务。

### 2.7 数据来源

资源、预约、通知、机会、工单、问题、拥堵和互助对象在顶层附带来源字段：

```json
{
  "sourceType": "demo",
  "sourceName": "校园演示提供器",
  "updatedAt": "2026-10-13T17:00:00+08:00",
  "isStale": false,
  "officialReference": null,
  "externalId": null
}
```

严格保留规划书第9.2节的顶层字段sourceType、sourceName、updatedAt、isStale、officialReference、externalId；sourceType为demo/manual/official，其余来源信息允许null或明确过期。数据由提供器与外部映射组合，不在所有表重复保存。表格中的sourceFields是这六个字段的文档缩写，不是实际JSON属性。前端持续显示demo标记；官方数据失败不得静默回退假数据。

### 2.8 访问级别

| 标记 | 含义 |
| --- | --- |
| P | 游客可访问，只能得到当前开放校园的已公开内容 |
| L | 有效学生端登录会话 |
| S | 已登录、已绑定手机号、学生认证通过、账号未受限 |
| O | S/L 基础上的对象所有者或明确参与人，具体见接口 |
| A(permission) | 管理员会话及指定权限，仅所属校园范围 |
| I | 受控服务间接口，不开放给小程序和普通管理账号 |

校园不是随意传入的过滤条件。游客接口统一可接受campusCode，省略时使用首发开放校园；登录接口后的校园从会话读取。跨校或无权访问的具体对象通常返回 404，避免泄露存在性。

## 3. 通用业务对象

以下为业务DTO最小返回结构；id、外键ID均为十进制字符串，版本和计数为整数，时间用RFC3339。带?为可空，未带?为必返。sourceFields展开为第2.7节的六个顶层字段，不是嵌套属性。

| 对象 | 基础字段 |
| --- | --- |
| User | id:string,campusId:string,nickname:string?,avatarUrl:string?,status:string,phoneBound:boolean,phoneMasked:string?,verificationStatus:string,version:int |
| Profile | userId:string,college:string?,grade:string?,skills:string[],interests:string[],version:int |
| Opportunity | id:string,title:string,category:string,organizer:string?,summary:string?,deadline:datetime?,status:string,version:int,sourceFields |
| Material | id:string,fileName:string?,mimeType:string,sizeBytes:int,scanStatus:string,status:string,expiresAt:datetime?,tags:string[],currentVersion:{id:string,versionNo:int},version:int |
| Checklist | id:string,opportunityId:string,status:string,version:int,items:ChecklistItem[] |
| ChecklistItem | id:string,itemName:string,required:boolean,materialVersionId:string?,status:string |
| WorkOrder | id:string,category:string,location:string?,description:string,urgency:string,status:string,serviceStage:string,images:Attachment[],assigneeDisplayName:string?,version:int,sourceFields,createdAt:datetime |
| WorkOrderEvent | id:string,fromStatus:string?,toStatus:string,comment:string?,operatorDisplayName:string?,createdAt:datetime |
| CampusIssue | id:string,title:string,category:string,description:string?,status:string,followCount:int,voteCount:int,location:IssueLocation?,version:int,sourceFields,createdAt:datetime |
| IssueLocation | name:string?,latitude:number,longitude:number |
| Resource | id:string,type:string,name:string,regionId:string?,capacity:int?,equipment:object?,reservationMode:string,status:string,version:int,sourceFields |
| Slot | id:string,resourceId:string,slotDate:date,startTime:time,endTime:time,capacity:int?,status:string,remaining:int?,version:int,sourceFields |
| Reservation | id:string,resource:{id:string,name:string,type:string},slot:Slot,seatNo:int,status:string,version:int,sourceFields,createdAt:datetime |
| MutualAidPost | id:string,category:string,title:string,content:string?,detail:object?,images:Attachment[],price:decimal-string?,exchangeArea:string?,status:string,version:int,sourceFields,createdAt:datetime |
| BorrowRequest | id:string,postId:string,borrowerDisplayName:string,startTime:datetime?,endTime:datetime?,status:string,createdAt:datetime,updatedAt:datetime |
| Team | id:string,opportunityId:string?,title:string,goal:string?,leader:{id:string,nickname:string?},status:string,members:TeamMember[],version:int |
| TeamMember | id:string,userId:string?,role:string?,skillTags:string[],status:string |
| TeamApplication | id:string,teamId:string,applicant:{id:string,nickname:string?},message:string?,status:string,createdAt:datetime |
| CommunityPost | id:string,title:string,content:string?,pseudonym:string,status:string,version:int,canEdit:boolean,createdAt:datetime |
| CommunityComment | id:string,postId:string,pseudonym:string,content:string,status:string,canDelete:boolean,createdAt:datetime |
| Report | id:string,targetType:string,targetId:string,reason:string,status:string,resolution:string?,createdAt:datetime,updatedAt:datetime |
| Appeal | id:string,penaltyId:string?,reason:string,status:string,reviewedAt:datetime?,decisionReason:string?,createdAt:datetime |
| Conversation | id:string,type:string,title:string?,unreadCount:int,lastMessage:Message?,updatedAt:datetime |
| Message | id:string,conversationId:string,sender:{id:string,nickname:string?},content:string,status:string,clientMessageId:string,createdAt:datetime |
| Notification | id:string,type:string,title:string,content:string,targetType:string?,targetId:string?,readAt:datetime?,createdAt:datetime |
| CreditEvent | id:string,eventType:string,reason:string,points:int,relatedType:string?,relatedId:string?,createdAt:datetime |
| GrowthRecord | id:string,type:string,title:string,description:string?,proofFileId:string?,confirmed:boolean,occurredAt:datetime?,version:int |
| AsyncJob | id:string,jobType:string,status:string,result:object?,error:{code:string,message:string}?,createdAt:datetime,updatedAt:datetime |
| DataRequest | id:string,type:string,status:string,requestedAt:datetime,processedAt:datetime?,retentionBasis:string?,result:object? |
| Attachment | id:string,thumbnailUrl:string?,urlExpiresAt:datetime? |

枚举及字段限制见各业务章节；开放读取默认不返回内部任务payload、个人画像快照、完整手机号/学号、匿名真实身份或对象存储凭证。公开匿名对象不允许嵌入普通author/User DTO。图片通过后端附件元数据关联，不能由客户端直接填写任意objectKey。当前SQL缺少的DTO字段以第19节为依赖声明，不能声称已有对应数据库列。

## 4. 微信登录与学生身份

### 4.1 微信登录

`POST /auth/wechat/login`，P，HTTP 200。

```json
{
  "code": "wx-login-one-time-code",
  "campusCode": "demo-univ"
}
```

code 必填，最多 256 字符；campusCode 必须为开放校园。已有账号按服务器保存的校园归属处理，不允许通过修改此字段随意切换学校。

```json
{
  "accessToken": "opaque-access-token",
  "expiresIn": 900,
  "refreshToken": "opaque-refresh-token",
  "refreshExpiresIn": 604800,
  "user": {
    "id": "10001",
    "campusId": "1",
    "nickname": null,
    "avatarUrl": null,
    "status": "active",
    "phoneBound": false,
    "phoneMasked": null,
    "verificationStatus": "unverified",
    "version": 0
  }
}
```

后端用自己的 AppID/AppSecret 换取微信身份。客户端不得提交 openid、角色或 userId 作为登录依据。以上令牌期限为本系统设计值；刷新令牌每次轮换并撤销旧值，服务端仅存令牌摘要。若同一凭证被重复换取，按微信结果和平台会话规则处理。

### 4.2 其他身份接口

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /campuses | P | 无；只返回开放校园 | [{id,name,code}] |
| GET | /campus/regions | P | campusCode?；登录后忽略外校值 | 区域树 [{id,name,type,parentId?}] |
| GET | /campus/organizations | P | type?=college/department/club | [{id,name,type}] |
| POST | /auth/token/refresh | P | {refreshToken} | 新访问/刷新令牌及期限 |
| POST | /auth/logout | L或有效刷新会话 | {refreshToken?} | {revoked:true}；访问令牌过期时仍可撤销绑定的刷新会话 |
| POST | /auth/phone/bind | L | {phoneCode}，微信手机号一次性凭证 | {phoneBound:true,phoneMasked:"138****0000"} |
| GET | /me | L | 无 | User |
| POST | /me/update | L | {nickname?,avatarAttachmentId?,version:用户版本} | User |
| GET | /me/profile | L | 无 | Profile |
| POST | /me/profile/update | L | {college?,grade?,skills?,interests?,version:画像版本} | Profile |
| POST | /student-verifications | L＋手机号已绑定 | {studentNumber}，最长32；不记录明文学号日志 | {id,status,rejectReason?,createdAt}；名单匹配失败进入人工复核 |
| GET | /me/student-verification | L | 无 | 当前认证状态及脱敏学号，不返回完整学号 |

nickname 1～32字符；skills/interests 每类最多20项，每项1～32字符；college/grade 校验本校允许值。用户基础资料与画像分开更新，分别使用 users.version 和 user_profiles.version，不能将两个版本混用。头像必须来自已扫描通过、属于用户的附件。

学生名单比对需要学校名单、认证服务或独立配置；当前 SQL 没有完整名单表，不能假设任意填写学号即认证通过。人工拒绝后可重新申请，不允许恶意占用别人的学号。未认证用户仍可浏览公开内容。

## 5. 首页与 AI 校园助手

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /home/overview | P/L | campusCode?,regionId?；主动定位时latitude/longitude/radiusMeters? | {deadlines:[],recommendations:[],hotIssues:[],nearbyPosts:[],nearbyMode,unreadCount:int} |
| GET | /search | P/L | q*,types?，cursor,limit | 分页：[{type,id,title,summary,sourceFields}] |
| POST | /ai/assistant/messages | L | {question,scope?}，scope=campus/opportunity/service | 202：AsyncJob |
| GET | /jobs/{jobId} | O或相应管理员 | 无 | AsyncJob |

游客首页的个人截止事项、未读消息和个人推荐为空。定位只在用户主动使用附近功能时取得；radiusMeters建议范围100～3000，同一请求优先使用区域或经纬度之一。nearbyMode=coordinates/region/unavailable；缺坐标数据时只能返回区域匹配或不可用标识，不能编造距离。当前互助表没有结构化坐标，需要独立位置索引或受控区域映射。AI问题1～2000字符。回答任务 result 结构：

```json
{
  "answer": "这是基于已审核通知的材料准备建议。",
  "aiGenerated": true,
  "citations": [
    {
      "title": "报名通知",
      "url": "https://school.example.edu.cn/notice/123",
      "sourceType": "official",
      "updatedAt": "2026-10-13T10:00:00+08:00"
    }
  ],
  "uncertain": false,
  "disclaimer": "以学校或主办方最新通知为准"
}
```

知识检索先过滤校园和访问权限；没有依据时明确表示资料不足。AI 只能生成建议，不批准预约、发布机会或查询匿名身份。默认不建立公开聊天记录，不将提问自动写入成长档案。jobs 状态为 pending/running/succeeded/failed；业务模块内部 processing 状态由任务适配层映射。

## 6. 机会、通知、资格匹配

category：scholarship/competition/research/internship/volunteer。

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /opportunities | P | q?,category?,deadlineFrom?,deadlineTo?,cursor,limit | 分页 Opportunity |
| GET | /opportunities/{id} | P | 无 | Opportunity + {notice,eligibilityRules,materialRequirements,teamCount,isFavorited} |
| POST | /opportunities/{id}/eligibility-checks | S | {}；从服务器画像取条件 | 202：AsyncJob |
| GET | /opportunities/{id}/eligibility-checks/latest | O | 无 | 当前用户最新匹配结果 |
| PUT | /favorites/{targetType}/{targetId} | L | 无 | {favorited:true} |
| DELETE | /favorites/{targetType}/{targetId} | L | 无 | {favorited:false} |
| GET | /me/favorites | L | targetType?,cursor,limit | 分页收藏，目标已下架时只返回不可用标识 |
| POST | /reminders | L | {targetType,targetId,remindAt} | {id,targetType,targetId,remindAt,status} |
| DELETE | /reminders/{id} | O | 无 | {cancelled:true} |
| GET | /me/reminders | L | status?,cursor,limit | 分页提醒 |
| PUT | /opportunities/{id}/submission | S | {submittedAt}，用户自行提交时间 | {id,opportunityId,status:"submitted",submittedAt} |

资格检查异步 result：{id,opportunityId,conclusion,reasons,rulesVersion,profileVersion?,checkedAt}。conclusion=eligible/possible/not_eligible；reasons 为 [{field,expected,actual?,result,message,evidence?}]。未知条件不能自动判为符合。画像快照属于私密数据，不通过公开接口返回。

提醒不得早于当前时间；创建前检查目标归属和可见性。favorites 的 targetType 为 opportunity/resource/mutual_aid_post/community_post；reminders 的 targetType 为 opportunity/material/borrow/reservation。PUT 自行提交由事务保证用户＋机会最多一份当前记录；需要在现有表上补充对应唯一约束。

## 7. 文件、材料保险箱与预检

### 7.1 安全上传流程

```text
申请上传 → 文件进入隔离区 → 确认上传 → 扫描任务
→ clean 后建立可用附件/材料版本 → 选择材料 → 预检
```

首版采用后端临时上传入口，客户端通过wx.uploadFile上传，Authorization: Upload <uploadToken>；上传意向绑定账号、校园、purpose、uploadId、文件约束及有效期，不能用别人的上传凭证确认文件。完成登记使用本人正常Bearer会话。服务端流式写入MinIO隔离区，图片移除非必要EXIF元数据。

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /materials/upload-intents | L | {fileName,mimeType,sizeBytes,sha256?}；purpose固定material | {uploadId,uploadUrl,uploadToken,expiresAt,maxSizeBytes} |
| POST | /files/upload-intents | L；业务用途可能要求 S | {purpose,fileName,mimeType,sizeBytes,sha256?} | {uploadId,uploadUrl,uploadToken,expiresAt,maxSizeBytes} |
| POST | /files/uploads/{uploadId} | 上传凭证 | multipart 的 file 字段 | 201：{uploadId,received:true} |
| POST | /files/uploads/{uploadId}/complete | L＋上传所有者 | {} | 202：{attachmentId,scanStatus:"pending",jobId} |
| GET | /files/{attachmentId} | O/有权查看关联对象 | 无 | {id,fileName,mimeType,sizeBytes,scanStatus} |
| POST | /files/{attachmentId}/download-url | O/有权查看关联对象 | {} | {url,expiresAt}；私密材料链接120秒过期 |

purpose=material/avatar/workorder/issue/mutual_aid/community/message；必须在实际支持的业务用途内使用。材料单文件上限20MiB；图片5MiB；学生端仅JPEG/PNG、PDF和DOCX，头像仅JPEG/PNG；管理名单上传通过独立管理员意向接口允许CSV，通知附件通过notice_document用途；学生不能申请这些管理用途。最多9张业务图片。服务端验证文件头、大小、压缩后解包大小与扫描结果，拒绝可执行文件、伪造类型和压缩炸弹。扫描 pending/infected 文件不能下载、展示或送入 AI。

### 7.2 材料接口

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /materials | L | {attachmentId,expiresAt?,tags?}；附件必须 clean | 201：Material |
| GET | /materials | L | q?,tag?,cursor,limit | 当前用户的分页 Material |
| GET | /materials/{id} | O | 无 | Material + versions |
| POST | /materials/{id}/update | O | {expiresAt?,tags?,version} | Material |
| POST | /materials/{id}/versions | O | {attachmentId,version}；追加版本，不改旧版本 | 201：Material |
| DELETE | /materials/{id} | O | version 查询参数 | {deleted:true,cleanupStatus:"pending"} |
| PUT | /opportunities/{id}/checklist | S | {}；创建或取得本人清单 | Checklist |
| GET | /checklists/{id} | O | 无 | Checklist |
| POST | /checklists/{id}/items/{itemId}/update | O | {materialVersionId:null或本人版本ID,version:清单版本} | Checklist；提升父清单版本 |
| POST | /materials/{id}/prechecks | O | {materialVersionId,version,aiConsentId}；仅本人版本 | 202：AsyncJob，单材料基础预检 |
| POST | /checklists/{id}/prechecks | O | {version:清单版本,aiConsentId} | 202：AsyncJob |
| GET | /checklists/{id}/prechecks/latest | O | 无 | {id,status,checklistVersion,stale,results,checkedAt?} |

tags 最多20项，每项1～32字符。预检逐项 results 为 [{checklistItemId,itemKey,level,message,evidence}]，level=passed/confirm/missing。result 额外标识 basicComplete:boolean；不声称主办方一定接受。

单材料预检使用async_jobs记录jobType=material_precheck以及受控输入和结果，检查格式、大小、命名、有效期与基础识别，不宣称机会材料已完整。机会清单预检使用precheck_tasks/results。清单任务中的每次预检保存不可变的inputSnapshot、checklistVersion、rulesVersion和文件版本。替换材料后旧任务仍可查看，但 stale=true，不能覆盖当前状态。删除材料先确认引用和保留义务，再通过可重试任务删除对象；审计和必要历史不能因级联删除丢失。

预检按所选不可变版本的 clean 附件、对象和散列读取，并在处理前重新检查本人/校园与现行 AI 授权；不能使用材料的最新对象替代历史版本。同一清单版本中较旧的任务不能覆盖较新任务的当前状态；最终失败恢复上传/缺失状态，不长期停在 prechecking。

填写字段和上传文件错误由前端即时提示，后端始终重新验证。需要 AI 处理材料时必须先取得专门授权。

## 8. 报修、问题广场、地图和拥堵

### 8.1 报修

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /work-orders | S | WorkOrderCreate | 201：WorkOrder，初始 pending |
| GET | /me/work-orders | L | status?,cursor,limit | 本人的分页 WorkOrder |
| GET | /work-orders/{id} | O/A(workorder.read) | 无 | WorkOrder + events |
| POST | /work-orders/{id}/ratings | O | {score,comment?}；score整数1～5 | {id,score,comment?,createdAt} |

WorkOrderCreate：{category,location,description,urgency,attachmentIds}。category=dorm/facility/other；location 1～255字符；description 1～1024字符；urgency=normal/urgent；图片最多9张且必须属于本人、clean。

SQL保留pending/reviewing/accepted/processing/completed/rated/rejected。规划书要求“待审核→待受理→处理中→待评价→已完成”，不能把数据库completed直接显示为整项已完成。WorkOrder额外返回serviceStage：pending/reviewing→pending_review；审核通过等待受理→pending_acceptance；accepted未开始处理→accepted；processing→processing；数据库completed且无评价→awaiting_rating；rated或completed且已有评价→completed；rejected→rejected。待受理阶段需持久化明确的审核通过依据。评价只允许提交人、处于awaiting_rating时写入，评分记录与状态迁移同事务完成。关闭、无法处理、转办仍须补齐状态/事件模型并同步字典，不能把它们冒充普通completed。用户不能设置处理人或服务状态。未接官方系统的工单显示“平台处理”。

现有运行实现：管理员以 `toStatus=reviewing`、非空 `comment` 记录审核通过，保存 `review_approved_at` 和审核事件，此时 `serviceStage=pending_acceptance`；随后才可 accepted→processing→completed，评价后 rated。历史 reviewing 且无审核通过依据仍为 pending_review，不能直接受理。详情字段平铺在 data 内，并附 events；分派和评价也保留事件。附件仅返回已校验关联的 `{id,thumbnailUrl:null,urlExpiresAt:null}`，下载仍由文件服务的狭窄关联策略授权。

### 8.2 校园问题

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /campus-issues | S | {title,category,description,location?,attachmentIds?} | 201：{id,status:"pending",displayPolicy:"review_required"} |
| GET | /campus-issues | P | q?,category?,regionId?,cursor,limit | 仅已审核允许公开的分页 CampusIssue |
| GET | /campus-issues/{id} | P或所有者 | 无 | 公开 CampusIssue；所有者可看到自己的未公开审核状态 |
| GET | /campus-issues/map | P | bbox?,category?,limit≤200 | {points:[{id,title,category,status,latitude,longitude}],truncated:boolean} |
| GET | /me/campus-issues | L | status?,cursor,limit | 本人问题 |
| POST | /campus-issues/{id}/follows | L | 无 | {following:true,followCount} |
| DELETE | /campus-issues/{id}/follows | L | 无 | {following:false,followCount} |
| PUT | /campus-issues/{id}/vote | S | {value:1或-1} | {value,voteCount} |
| DELETE | /campus-issues/{id}/vote | S | 无 | {value:null,voteCount} |
| POST | /campus-issues/{id}/evidences | S | {content?,attachmentIds?}，至少一项 | 201：{id,reviewStatus:"pending"} |

title 1～256字符；category=repair/canteen/express/other；description 最多1024；补充证据最多512字符。location={name?,latitude:number,longitude:number}，经纬度范围分别[-90,90]/[-180,180]。bbox 为 minLng,minLat,maxLng,maxLat，必须符合范围和最大校园查询面积。

现有运行实现：创建问题另接受可选 regionId，必须是当前校园有效区域，用于列表 regionId 筛选。bbox 坐标必须有限且严格有序；球面矩形面积默认最多100 km²，可由服务端 `app.issue-map.max-area-km2` 或 `APP_ISSUE_MAP_MAX_AREA_KM2` 配置为有限正数。此值约束单次查询面积，不代表学校真实地理边界；当前没有学校边界模型。无 bbox 时仍限校园与1～200条结果。公开授权同时要求 visibility=public 及 published/processing/completed 状态；审核决定及理由持久化，补充证据保留 pending 审核状态，不能随原问题公开。

公开内容脱敏：手机号、学号、身份证、门牌、人脸和个人纠纷证据必须按审核结果处理。补充证据也需审核，不能作为绕过原帖审核的入口。公开接口不能仅凭 processing/completed 状态判断可见性，必须同时检查独立的公开授权结果；当前表需补充 visibility 或等价的受控发布信息。

按数据字典voteCount为非负的有效投票总数，不重新解释成可能为负的净票数。需要分别展示时，返回agreeCount:int和disagreeCount:int，由campus_issue_votes按value聚合计算；与写入、取消投票的计数更新保持事务一致。不会为了本接口文档擅自要求修改当前UNSIGNED字段。

### 8.3 拥堵

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /crowd/observations | P | locationType?,locationName?,cursor,limit | 分页 [{id,locationType,locationName,crowdLevel,observedAt,sourceFields}] |
| POST | /crowd/observations | S | {locationType,locationName,crowdLevel} | 201：观测对象 |

locationType=canteen/express；crowdLevel=low/medium/high；locationName 必须来自本校受控地点。时间由服务器设置，不允许用户伪造实时观测时间。过期后 isStale=true，默认有效期15分钟，可按地点配置。限制同人同地点提交频率并防止恶意刷数据。

## 9. 图书馆与教室预约

### 9.1 接口清单

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /resources | P | type?,regionId?,cursor,limit | 分页 Resource |
| GET | /resources/{id} | P | 无 | Resource + bookingRules |
| GET | /resources/{id}/slots | P | date*，YYYY-MM-DD | [{Slot}] |
| GET | /resources/{id}/availability | S | date*，slotId?；必须属于该资源 | {resource:Resource,date,slots:[{slot:Slot,availableSeatNos:[],canReserve,unavailableReason?}]} |
| GET | /resource-slots/{id}/availability | S | 无 | {slot:Slot,reservationMode,availableSeatNos:[],canReserve,unavailableReason?} |
| POST | /reservations | S | {slotId,seatNo} | 201：Reservation |
| GET | /me/reservations | L | status?,dateFrom?,dateTo?,cursor,limit | 本人分页 Reservation |
| GET | /reservations/{id} | O/A(resource.read) | 无 | Reservation |
| POST | /reservations/{id}/cancel | O | {version,reason?} | Reservation |
| POST | /reservations/{id}/check-in | O | {version,checkInToken} | Reservation + checkedInAt |

type=library_seat/classroom/equipment；reservationMode=exclusive/capacity，由后端受控配置提供。exclusive 的 seatNo 只能为0；capacity 为1～有效容量。容量缺失或配置非法时停止预约，不默认无限容量。seatNo 由用户选择，不能由客户端指定预约状态。

### 9.2 预约请求与响应

```http
POST /api/v1/reservations
Authorization: Bearer <accessToken>
Idempotency-Key: 97ff39c3-0718-4c5f-b02a-78ad06d0fd99
```

```json
{
  "slotId": "20001",
  "seatNo": 12
}
```

```json
{
  "id": "30001",
  "resource": {
    "id": "200",
    "name": "图书馆三楼自习区",
    "type": "library_seat"
  },
  "slot": {
    "id": "20001",
    "resourceId": "200",
    "slotDate": "2026-10-14",
    "startTime": "08:00:00",
    "endTime": "10:00:00",
    "capacity": 40,
    "status": "available",
    "version": 0,
    "remaining": 39,
    "sourceType": "demo",
    "sourceName": "校园演示提供器",
    "updatedAt": "2026-10-13T18:00:00+08:00",
    "isStale": false,
    "officialReference": null,
    "externalId": null
  },
  "seatNo": 12,
  "status": "confirmed",
  "version": 0,
  "createdAt": "2026-10-13T18:00:00+08:00",
  "sourceType": "demo",
  "sourceName": "校园演示提供器",
  "updatedAt": "2026-10-13T18:00:00+08:00",
  "isStale": false,
  "officialReference": null,
  "externalId": null
}
```

确认规则：demo 按演示规则自动确认；需要人工或外部确认时返回 pending。pending 是否占位由统一规则决定；首版占位并设置超时释放，禁止无限等待。官方接口超时不得生成 confirmed 结果。

现有运行实现：reservationMode 为必填管理配置，持久化为 exclusive/capacity；运行时不根据容量是否为空猜模式。capacity 时段缺省容量继承已显式配置的父资源容量，缺失有效容量则拒订。人工预约保持 manual 来源，pending 占位15分钟；30秒轮询释放超时占位（`app.resources.expiry-poll-ms`），预约写入时也在资源锁内清理。官方集成未接入时返回503，不能产生假 confirmed。新增时段拒绝同资源同日重叠；历史重叠时段按同一资源、相交时间、同座位检查有效占用。

V17 对历史数据仅做一次兼容回填：依据旧 resource_slots.capacity，一致的空容量时段保留 exclusive，一致的非空容量时段保留 capacity；混合语义、不相容座位或无明确容量的无时段资源设置 booking_blocked，返回409 RESERVATION_CONFIGURATION_INVALID。保留原预约、seatNo与占用，不重排；管理员须在不破坏占用前提下显式修复模式。

### 9.3 服务端必须执行的步骤

1. 验证用户认证、校园归属、信用限制、资源和时段开放状态。
2. 在事务内按统一顺序锁定资源和时段，检查重叠时间、容量与座位合法性。
3. 写入预约与相匹配的有效占用；唯一约束冲突转换为 RESERVATION_CONFLICT。
4. 同事务登记提醒和通知任务；远程确认通过可靠任务处理。
5. 取消、拒绝和超时必须同时更新预约并释放占用。
6. 一条单席位预约仅能有一条有效占用；占用的 slotId、seatNo 必须与预约一致。

签到不能仅凭用户提交坐标判断。演示环境由后台生成短期演示签到令牌；正式环境需要资源管理员二维码、学校签到接口或经过授权的其他方式。防止重复签到和令牌跨资源复用。

现有演示签到策略：仅 demo 来源且 confirmed 的预约，在 Asia/Shanghai 校园本地时间 `startTime <= now < endTime` 内允许签发和使用；令牌最长5分钟且不超过时段结束时间，无提前/迟到宽限。令牌绑定预约和校园；先锁定并校验版本、状态、窗口，再消耗提交令牌对应的一次性记录。错误令牌或版本不消耗正确令牌，重放返回409。人工和官方预约不能借用演示令牌绕过正式签到规则。

## 10. 二手、失物、教材、技能与借用

### 10.1 发布与查询

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /mutual-aid/posts | P | q?,category?,regionId?,cursor,limit | 已审核公开的分页 MutualAidPost |
| GET | /mutual-aid/posts/{id} | P/所有者 | 无 | MutualAidPost + author:{id,nickname},canContact,canEdit |
| POST | /mutual-aid/posts | S | MutualAidCreate | 201：MutualAidPost，pending |
| POST | /mutual-aid/posts/{id}/update | O | 可编辑字段＋version | MutualAidPost，内容变更重新审核 |
| POST | /mutual-aid/posts/{id}/actions | O | {action,version} | MutualAidPost |
| POST | /mutual-aid/posts/{id}/ratings | 已确认交接参与人 | {score:1～5,comment?} | 201：{id,score,comment?,createdAt}；双方各自至多评价一次 |
| GET | /mutual-aid/posts/{id}/ratings | 有权查看帖子 | cursor,limit | 脱敏分页评价，无手机号与证件 |
| GET | /me/mutual-aid/posts | L | category?,status?,cursor,limit | 本人分页发布 |
| GET | /lost-found/matches | S | postId?；仅本人帖子关联匹配 | 分页 [{id,lostPostId,foundPostId,similarity?,status}] |
| POST | /lost-found/matches/{id}/actions | 匹配双方 | {action,description?} | {id,status,confirmationStatus} |

MutualAidCreate={category,title,content?,detail?,attachmentIds?,price?,exchangeArea?}。title 1～256、content最多2048、交接区域最多128。category=lost_found/secondhand/textbook/skill/borrow。公开价格范围0～99999999.99，非交易类别 price 必须为null。

detail 按 category 单独校验：

- lost_found：{kind:lost/found,occurredAt,place,itemType?,privateProofHint?}。privateProofHint 只供认领核验，不公开。
- secondhand：{condition:new/like_new/good/used,itemType}。
- textbook：{bookTitle,edition?,isbn?,course?,condition}。
- skill：{offeredSkills:[],wantedSkills:[],availableTimes?}。
- borrow：{kind:offer/request,itemName,availableFrom?,availableTo?}。

发布状态机：pending→reviewing→published；审核失败 rejected；published→communicating/reserved→completed，或转 cancelled。action=reserve/complete/cancel，仅允许符合当前状态的转换。完成前检查相关借用/交接记录，不允许发布者用状态转换覆盖他人的未完成借用。

失物动作 action=verify/confirm_return/ignore，保存各方确认事件；双方完成确认才转 returned。认领证据私密保存、限制访问，不能把完整校园卡或证件照片发到公开详情。

不包含任何支付、押金、退款或代收接口；线下交接由双方自行完成。平台不建议抵押学生证、身份证等证件。

### 10.2 借用

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /mutual-aid/posts/{id}/borrow-requests | S | {startTime,endTime}；仅 borrow.kind=offer | 201：BorrowRequest |
| GET | /me/borrow-requests | L | side=borrower/owner,status?,cursor,limit | 分页 BorrowRequest |
| POST | /borrow-requests/{id}/actions | 双方按动作 | {action} | BorrowRequest |

action=approve/cancel/confirm_handover/confirm_return。物主批准；双方各自确认交接和归还；状态为 pending→confirmed→handed_over→returned 或 cancelled。申请人不能借自己物品。服务端锁定记录检查重复、时段冲突、当前状态和角色；借用需求帖子先通过站内沟通撮合，不直接建立物主不明确的借用单。

当前 borrow_requests 没有双方确认事件字段，实施前需增加事件记录或等价模型。不能把单方点击直接作为双方归还事实。

## 11. 组队与同伴匹配

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /teams | P | q?,opportunityId?,skill?,cursor,limit | 分页 Team |
| GET | /teams/{id} | P | 无 | Team + openRoles |
| POST | /teams | S | {title,goal?,opportunityId?,openRoles?} | 201：Team |
| POST | /teams/{id}/update | 队长 | {title?,goal?,openRoles?,version} | Team |
| POST | /team-applications | S | {teamId,message?,openRoleId?} | 201：TeamApplication |
| GET | /teams/{id}/applications | 队长 | status?,cursor,limit | 分页 TeamApplication |
| POST | /team-applications/{id}/decision | 队长 | {decision:approve/reject} | TeamApplication |
| POST | /teams/{id}/leave | 本队成员 | {} | {left:true} |
| POST | /teams/{id}/close | 队长 | {version} | Team |
| GET | /me/teams | L | cursor,limit | 本人参与的 Team |
| GET | /me/team-applications | L | status?,cursor,limit | 本人 TeamApplication |

title 1～256，goal最多1024，message最多512；openRoles=[{role,skillTags,quantity}]，最多10种、每种数量1～20。队长自动加入团队；同一用户不能重复加入；队长不能直接退出，需转交队长或关闭团队。批准申请时锁定团队和角色缺口，避免并发超员。

## 12. 匿名关系与情感社区

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /community/posts | S | q?,cursor,limit | 本校已审核的分页 CommunityPost |
| GET | /community/posts/{id} | S/所有者 | 无 | CommunityPost |
| POST | /community/posts | S | {title,content} | 201：CommunityPost，pending |
| POST | /community/posts/{id}/update | 作者 | {title?,content?,version} | CommunityPost，重新审核 |
| DELETE | /community/posts/{id} | 作者 | version 查询参数 | {deleted:true}，软删除 |
| GET | /community/posts/{id}/comments | S | cursor,limit | 已审核分页 CommunityComment |
| POST | /community/posts/{id}/comments | S | {content} | 201：CommunityComment，pending |
| DELETE | /community/comments/{id} | 评论作者 | 无 | {deleted:true} |
| GET | /me/community/posts | L | status?,cursor,limit | 本人分页 CommunityPost |

title 1～256字符，帖子正文1～5000字符，评论1～2048字符。此模块定位为学生匿名交流社区，不提供心理诊断或专业咨询承诺。

昵称由服务端生成，每个帖子内的匿名身份不与其他帖子串联。普通响应禁止返回userId、pseudoIdentityId、学号、手机号或可跨帖串联作者的稳定标识；同一帖子内允许独立匿名显示身份，不能跨帖复用。作者权限由受控身份服务判断，不能让客户端自行声明 canEdit 或作者身份。

最新仓库已允许业务账号仅INSERT pseudo_identities，并在帖子/评论保存author_name。公开pseudonym由author_name映射产生，不读取真实身份表。发布事务预生成帖子和匿名身份ID，先插入帖子，再插入映射；任一步失败均回滚。评论也需建立该评论匿名身份到用户的映射。业务账号没有SELECT映射权限，“我的匿名发布”和作者修改/删除仍需受控归属检查机制；目前SQL没有提供该机制，不能仅凭会话userId直接宣称已可校验，也不能让普通业务服务借调查账号批量读映射。独立安全调查使用调查数据源，SELECT映射并追加security_investigations；普通数据源无该表权限。

高风险内容不公开扩散；通过举报和复核流程处理。编辑已发布内容后重新审核。状态= pending/published/reported/restricted/deleted；reported 不代表违法或自动公开，是否可见依据审核结果。

## 13. 举报、屏蔽、处罚与申诉

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /reports | S | {targetType,targetId,reason} | 201：Report |
| GET | /me/reports | L | status?,cursor,limit | 本人的分页 Report |
| GET | /reports/{id} | 举报人 | 无 | Report，不返回被举报人的身份 |
| PUT | /blocks/{targetType}/{targetId} | L | 无 | {blocked:true} |
| DELETE | /blocks/{targetType}/{targetId} | L | 无 | {blocked:false} |
| GET | /me/penalties | L | cursor,limit | [{id,type,reason,status,expiresAt?}] |
| POST | /appeals | L | {penaltyId,reason} | 201：Appeal |
| GET | /me/appeals | L | cursor,limit | 分页 Appeal |

举报 targetType=community_post/community_comment/mutual_aid_post/message；reason 1～512字符。屏蔽 targetType=user/community_post/mutual_aid_post；匿名帖子屏蔽不自动跨帖追踪作者。只允许申诉本人的处罚；同一处罚只保留一个待处理申诉，防止刷队列。账号受限不能阻止其查看处罚、申诉或申请删除数据。

## 14. 站内消息、订阅通知与 WebSocket

### 14.1 REST 接口

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /conversations | S | {contextType,contextId} | Conversation；按上下文校验双方，复用既有会话 |
| GET | /conversations | L | cursor,limit | 分页 Conversation |
| GET | /conversations/{id}/messages | 会话成员 | cursor,limit | 分页 Message |
| POST | /conversations/{id}/messages | 会话成员且 S | {content,clientMessageId} | 201：Message |
| POST | /conversations/{id}/read | 会话成员 | {lastReadMessageId} | {unreadCount} |
| GET | /me/notifications | L | unreadOnly?,cursor,limit | 分页 Notification |
| POST | /notifications/{id}/read | 接收人 | {} | Notification |
| POST | /notifications/read-all | L | {beforeId} | {updatedCount}；只读到指定消息，避免漏掉并发新消息 |
| POST | /subscriptions | L | {templateId,scene,consentResult} | {id,status} |
| DELETE | /subscriptions/{id} | 所有者 | 无 | {revoked:true} |
| POST | /realtime/tickets | L | {} | {ticket,expiresAt,websocketUrl} |

contextType=mutual_aid_post/borrow_request/team；用户不能任意传成员列表创建骚扰群聊。消息 content 1～2000字符；clientMessageId 为客户端唯一字符串，最大64字符。服务器从会话和身份推导 senderId。消息先审核，违规内容不分发；被屏蔽或受限用户不能继续向对方发送。

订阅 scene=deadline/workorder/reservation/borrow。consentResult=accept/reject，仅代表客户端本次授权结果记录；不能凭提交 accept 绕过微信侧真实授权或可发送次数。模板ID必须来自后台白名单，发送失败不伪造成功。

### 14.2 WebSocket 协议

客户端先经已登录 REST 接口取得30秒有效、一次性的 ticket，再连接返回的 wss URL。URL 可以包含这一临时票据，但不能包含长期 accessToken/refreshToken；代理和应用日志必须移除票据。连接后每次操作仍校验会话成员，不能依靠“已连接”跳过权限检查。

服务端事件统一：

```json
{
  "eventId": "evt_1001",
  "type": "message.created",
  "data": {
    "id": "40001",
    "conversationId": "50001",
    "sender": {
      "id": "10001",
      "nickname": "同学A"
    },
    "content": "可以在食堂门口交接吗？",
    "status": "sent",
    "clientMessageId": "client-123",
    "createdAt": "2026-10-13T18:00:00+08:00"
  }
}
```

事件 type=message.created/notification.created/job.updated/reservation.updated/workorder.updated。首版消息发送通过 REST，WebSocket负责事件接收和心跳；ping 每30秒，60秒无有效心跳关闭连接。断线后按消息游标补拉，不能把 WebSocket 通知作为数据库事实的唯一来源。

## 15. 我的、成长档案、信用与隐私

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /me/dashboard | L | 无 | {user,todoCount,unreadCount,postCount,reservationCount,workOrderCount} |
| GET | /me/todos | L | cursor,limit | 分页 [{type,targetId,title,dueAt?,status,action}]，聚合提醒与业务状态 |
| GET | /me/history | L | cursor,limit | 用户主动启用的浏览历史 |
| DELETE | /me/history | L | 无 | {cleared:true} |
| GET | /me/growth-records | L | type?,cursor,limit | 分页 GrowthRecord |
| POST | /me/growth-records | S | {type,title,description?,proofFileId?,occurredAt?,confirmed} | 201：GrowthRecord |
| POST | /me/growth-records/{id}/update | O | 可编辑字段＋version | GrowthRecord |
| DELETE | /me/growth-records/{id} | O | version 查询参数 | {deleted:true} |
| GET | /me/credit-events | L | cursor,limit | 分页 CreditEvent，显示具体依据 |
| POST | /me/credit-events/{id}/disputes | 本人 | {reason}；1～512字符 | 201：{id,status:"pending"}，人工复核，不直接改积分 |
| GET | /me/authorizations | L | 无 | [{id,type,scope,status,documentVersion?,grantedAt,revokedAt?}] |
| POST | /me/authorizations | L | {type,scope,documentVersion} | 授权对象 |
| POST | /me/authorizations/{id}/revoke | O | {} | 授权对象 |
| GET | /me/privacy-settings | L | 无 | {personalization,historyEnabled,nearbyEnabled} |
| POST | /me/privacy-settings/update | L | 上述 boolean 字段 | 隐私设置 |
| POST | /me/data-exports | L | {}；导出本人数据 | 202：DataRequest，type=export |
| POST | /me/account-deletion | L | {confirm:true}；明确确认注销 | 202：DataRequest，type=deletion |
| GET | /me/data-requests | L | cursor,limit | 分页 DataRequest |
| GET | /me/data-requests/{id} | O | 无 | DataRequest；导出成功含短期下载凭证申请方式 |
| POST | /me/data-requests/{id}/cancel | O | {}，仅删除冷静期内 | DataRequest；持久化撤销结果，当前状态枚举需补齐cancelled或等价撤销记录 |

成长 type=competition/research/volunteer/mutual_aid；title最多256，description最多1024；证明材料必须属于本人。信用不可由用户修改，记录事件和纠错依据，不使用AI生成不可解释的人格评分。

授权 type=privacy/phone/subscription/location/material_ai；documentVersion 必须匹配发布的协议版本。系统令牌有效不等于已经授权非必要信息处理。撤回手机号授权不应抹除依法需要保留的历史证据，但应停止不必要处理并限制依赖该能力的功能。

删除冷静期由运营配置给出，7天只是原稿建议值，不是仓库已确定的要求。执行前再次检查未完成借用、预约和保留义务；未解决事项告知原因，不无限期拖延删除。删除后撤销登录会话，清理或匿名化非必要数据。导出只包含本人可取得的数据，不包含其他用户隐私、匿名映射或内部安全调查内容。

## 16. 管理后台

### 16.1 管理员登录与权限

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| POST | /admin/auth/login | 管理员登录入口 | {username,password} | {challengeId,mfaRequired:true,expiresAt}，不签发完整后台会话 |
| POST | /admin/auth/mfa/verify | 有效挑战 | {challengeId,code} | {accessToken,expiresIn,refreshToken,refreshExpiresIn,admin,permissions} |
| POST | /admin/auth/token/refresh | 管理员刷新凭证 | 浏览器使用安全刷新Cookie及CSRF凭证 | 轮换后的后台令牌 |
| POST | /admin/auth/logout | 管理员 | {} | {revoked:true} |
| POST | /admin/auth/reauth | 管理员 | {mfaCode} | {reauthToken,expiresAt}，5分钟内可用于指定高危动作 |
| GET | /admin/me | 管理员 | 无 | {id,nickname,roles,campusScopes,permissions} |

学生端令牌不能直接使用管理员接口。后台认证凭证使用安全哈希保存密码，MFA密钥加密保存；管理员账号只能通过受控初始化或授权流程创建。刷新令牌不得进入 URL。浏览器令牌优先使用内存与 Secure/HttpOnly 刷新 Cookie；使用 Cookie 的写接口必须验证 CSRF。小程序令牌不能复用后台Cookie。

管理员认证响应也使用第2节统一包装；浏览器 refreshToken 通过安全 Cookie 交付，JSON 中该字段为 null，小程序没有管理员认证入口。权限按 roles.code 映射，业务查询与操作同时校验校园范围。平台管理员的跨校操作必须明确指定 campusId，记录审计；不能将普通校园管理员提交的 campusId 作为授权依据。

### 16.2 学生认证、校园配置与统计

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /admin/student-verifications | A(identity.review) | status?,cursor,limit | 脱敏认证队列 |
| POST | /admin/student-verifications/{id}/decision | A(identity.review) | {decision:approve/reject,reason,version} | 认证对象 |
| POST | /admin/files/upload-intents | A(file.upload) | {purpose:student_roster/notice_document,fileName,mimeType,sizeBytes} | 上传意向，二进制上传使用第7节临时上传凭证 |
| POST | /admin/files/uploads/{uploadId}/complete | A(file.upload)＋上传所有者 | {} | 202：{attachmentId,scanStatus:"pending",jobId} |
| GET | /admin/files/{attachmentId} | A(file.read)且有业务归属权限 | 无 | 脱敏文件元数据与scanStatus |
| POST | /admin/files/{attachmentId}/download-url | A(file.read)且有业务归属权限 | {} | 短期下载凭证，记录敏感名单访问 |
| POST | /admin/student-roster/imports | A(identity.import) | {attachmentId}，私密CSV名单 | 202：AsyncJob，result={successCount,errorCount,errors摘要} |
| GET | /admin/users | A(user.read) | q?,status?,cursor,limit | 脱敏分页用户 |
| POST | /admin/users/{id}/roles | A(user.roles) | {roleCode,campusId,reauthToken,reason} | {assigned:true} |
| DELETE | /admin/users/{id}/roles/{roleCode} | A(user.roles) | campusId,reauthToken,reason | {revoked:true} |
| POST | /admin/campuses | A(platform.campus.manage) | {name,code} | 校园对象 |
| GET | /admin/campus/configs | A(campus.config.read) | 无 | 白名单配置键值，不返回密钥 |
| PUT | /admin/campus/configs/{key} | A(campus.config.write) | {value,version,reason} | 配置对象 |
| GET | /admin/statistics/overview | A(statistics.read) | from?,to? | 聚合统计，不包含私密咨询或匿名身份 |

名单上传使用独立管理用途 student_roster，只允许受控UTF-8 CSV，10MiB上限，列为 studentNumber、college、grade、status；扫描、规范化和重复检查后导入。原始名单私密保存、限时清理，日志不打印学号。审批与角色分配不能提升超过操作者可授予的权限，不能授予外校权限。

### 16.3 通知、AI解析、机会发布

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /admin/notices | A(notice.read) | status?,q?,cursor,limit | 分页通知 |
| POST | /admin/notices | A(notice.write) | {title,publisher?,content?,rawUrl?,attachmentId?}，至少一种原文来源 | 201：{id,status:"pending",version} |
| POST | /admin/notices/{id}/parse | A(notice.parse) | {version} | 202：AsyncJob |
| GET | /admin/notice-parse-tasks/{id} | A(notice.read) | 无 | {id,status,fields:[{key,value,evidenceLocation,confidence,humanConfirmed,humanValue}],model,error?} |
| POST | /admin/notice-parse-tasks/{id}/review | A(notice.review) | {fields:[{key,value}],decision:approve/reject,reason} | {reviewed:true,opportunityDraftId?} |
| GET | /admin/opportunities | A(opportunity.read) | status?,category?,cursor,limit | 分页机会 |
| POST | /admin/opportunities | A(opportunity.write) | OpportunityDraft | 201：Opportunity，draft |
| POST | /admin/opportunities/{id}/update | A(opportunity.write) | 可编辑字段＋version | Opportunity，撤回公开前不得直接改已发布规则 |
| POST | /admin/opportunities/{id}/publish | A(opportunity.publish) | {version,reason} | Opportunity |
| POST | /admin/opportunities/{id}/unpublish | A(opportunity.publish) | {version,reason} | Opportunity，archived |

OpportunityDraft={title,category,organizer?,summary?,deadline?,noticeId?,eligibilityRules,materialRequirements}。

- eligibilityRules=[{field,operator,value,severity,sortOrder}]，operator=eq/ne/in/contains/range，severity=hard/soft。
- materialRequirements=[{name,description?,required,sortOrder,validationRules?}]。
- validationRules 可省略或为 null（通用检查）；对象只允许 mimeTypes、maxSizeBytes、fileNameContains。mimeTypes 为 image/jpeg、image/png、application/pdf、application/vnd.openxmlformats-officedocument.wordprocessingml.document 的非空去重子集（最多4项）；maxSizeBytes 为 1～20971520 整数；fileNameContains 为 1～128 字符非空文本，按区分大小写包含检查。未知键、错类型或越界返回400；required 仅接受 JSON 布尔值。规则随材料要求、规则版本快照、创建的清单项和预检输入快照保存并执行。
- 规则更新形成规则版本，旧资格匹配和预检不得失去原始依据。

创建 category 必填；parse/publish/unpublish 的 version 必填且为非负 JSON 整数。解析字段缺失/null/非字符串/原文外 evidenceLocation 均不作为有效抽取。人工确认的自由文本持续通过复核接口 humanValue 展示；当确认了 materials 或 conditions/targetAudience，发布前须人工显式提交对应 materialRequirements 或 eligibilityRules。真实要求须逐项补录；原文明确无附加要求时可有意提交 []，系统保存各自的人工复核标记、规则快照和审计。approve 仍只创建草稿，发布权限独立。

原文URL导入防SSRF：仅HTTPS，禁止内网、环回和链路本地地址；重定向后再次检查；限制正文大小和时间。AI输出按固定Schema校验，管理员逐字段确认后才能发布。仅在演示提供器中写入 demo 标识，人工输入不能伪装成 official 来源。

URL 正文先处理长度/分块传输，再解 gzip/deflate，压缩字节与解压正文各限2MiB；截断或非法帧失败。TLS 握手前设置读取超时，重定向共享15秒读取期限；保留 DNS 地址固定及原主机证书校验。解析最终租约耗尽时，任务/收据失败，最新通知恢复 pending，旧代不重置新代。DeepSeek 整次调用（包括正文）限60秒，响应1MiB，缺密钥错误码为 AI_KEY_MISSING。

### 16.4 工单、校园问题与资源

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /admin/work-orders | A(workorder.read) | status?,assigneeId?,cursor,limit | 分页 WorkOrder |
| POST | /admin/work-orders/{id}/assign | A(workorder.assign) | {assigneeId,version,reason} | WorkOrder |
| POST | /admin/work-orders/{id}/transition | A(workorder.process) | {toStatus,version,comment} | WorkOrder |
| GET | /admin/campus-issues | A(issue.read) | status?,cursor,limit | 分页问题，敏感证据按权限分级 |
| POST | /admin/campus-issues/{id}/decision | A(issue.review) | {decision:publish/private/reject,version,reason} | 问题审核结果 |
| POST | /admin/campus-issues/{id}/transition | A(issue.process) | {toStatus,version,comment} | CampusIssue，不改变既有隐私可见性 |
| GET | /admin/resources | A(resource.read) | type?,cursor,limit | 分页 Resource |
| POST | /admin/resources | A(resource.write) | {type,name,regionId?,capacity?,equipment?,reservationMode} | 201：Resource |
| POST | /admin/resources/{id}/update | A(resource.write) | 可编辑字段＋version | Resource |
| POST | /admin/resources/{id}/slots | A(resource.write) | {slotDate,startTime,endTime,capacity?} | 201：Slot |
| POST | /admin/resource-slots/{id}/update | A(resource.write) | {status?,capacity?,version,reason} | Slot；不能改动已有预约的时间含义 |
| GET | /admin/reservations | A(resource.read) | status?,resourceId?,date?,cursor,limit | 分页 Reservation |
| POST | /admin/reservations/{id}/decision | A(resource.process) | {decision:approve/reject,version,reason} | Reservation |
| POST | /admin/reservations/{id}/check-in-token | A(resource.process) | {} | {checkInToken,expiresAt}，演示模式可用 |

处理人必须属于本校且有对应权限。工单状态转换执行前置条件，写入事件、操作人和理由。资源容量减少时检查有效占用；不能将容量改小导致已确认预约失效。官方提供器下资源由官方数据驱动，管理端不能直接覆盖官方预约事实。

### 16.5 内容治理、安全调查与审计

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /admin/moderation-tasks | A(content.review) | status?,targetType?,cursor,limit | 脱敏审核任务及待审内容 |
| POST | /admin/moderation-tasks/{id}/claim | A(content.review) | {} | {id,status:"assigned"}，原子领取 |
| POST | /admin/moderation-tasks/{id}/decision | A(content.review) | {decision:approve/reject/restrict,reason} | 审核结果，重复决策返回状态冲突 |
| GET | /admin/reports | A(report.review) | status?,cursor,limit | 分页 Report |
| POST | /admin/reports/{id}/decision | A(report.review) | {decision:resolve/dismiss,reason,action?} | Report，反馈给举报人 |
| POST | /admin/penalties | A(user.penalty) | {userId,type,reason,expiresAt?,relatedReportId?} | 处罚对象 |
| POST | /admin/penalties/{id}/lift | A(user.penalty) | {reason} | 处罚对象 |
| GET | /admin/appeals | A(appeal.review) | status?,cursor,limit | 分页 Appeal |
| GET | /admin/credit-disputes | A(credit.review) | status?,cursor,limit | 分页信用纠错申请 |
| POST | /admin/credit-disputes/{id}/decision | A(credit.review) | {decision:approve/reject,reason} | 决策对象；通过时追加修正事件，不抹除原始信用事实 |
| POST | /admin/appeals/{id}/decision | A(appeal.review) | {decision:approve/reject,reason} | Appeal |
| POST | /admin/security-investigations | A(security.investigate)＋二次认证 | {targetType,targetId,reportId,reason,reauthToken} | {investigationId,subject:{userId},audited:true}；不擅自增加调查账号无权读取的手机号 |
| GET | /admin/audit-logs | A(audit.read) | action?,from?,to?,cursor,limit | 本校范围脱敏审计；平台级访问另校需授权 |

处罚 type=remind/rate_limit/take_down/mute/ban。封禁、身份追溯不能由模型直接决定。普通内容审核员只能看到匿名身份；安全调查需明确案件理由并留下受限记录，审计写入失败时不得返回真实身份。调查结果不可进入普通日志和默认统计。普通数据源不得访问security_investigations。调查服务使用专用数据源SELECT映射并INSERT调查记录，提交受限记录后才返回身份；普通审计仅追加脱敏案件摘要。任何必需审计失败都不返回身份。应用安全调查角色与数据库账号是两层控制；如果未来确需查询手机号，必须另行配置受控、脱敏的授权路径，本稿不默认赋予该权限。

### 16.6 学校系统适配与同步

| 方法 | 路径 | 权限 | 输入 | data |
| --- | --- | --- | --- | --- |
| GET | /admin/integrations | A(integration.read) | 无 | [{id,type,provider,status,lastSyncedAt?,lastError?}] |
| PUT | /admin/integrations/{type} | A(integration.write) | {provider,config,version,reason} | 脱敏集成配置 |
| POST | /admin/integrations/{type}/test | A(integration.write) | {} | 202：AsyncJob |
| POST | /admin/integrations/{type}/sync | A(integration.sync) | {} | 202：AsyncJob |
| GET | /admin/integrations/{type}/errors | A(integration.read) | cursor,limit | 脱敏分页同步错误 |

type=notice/library/classroom/repair/crowd；provider=demo/manual/official。config只保存允许字段和secretRef，不接收、回显或保存明文密钥。切换official需先通过连接测试并确认学校授权；通过参数不能跳过授权。提供器调用有超时、重试和熔断；业务模块只接收标准对象，不直接依赖学校原始字段。

未来官方推送入口可预留 `POST /internal/integrations/{integrationId}/events`，I；事件包含 {eventId,externalId,type,occurredAt,payload}。外部系统使用服务间认证、签名、时间窗口和nonce防重放，按eventId幂等。学校未提供接口规范前，此入口不对公网开放，也不假设任何学校已经支持上述结构。

## 17. 统一错误码

| HTTP | code | 含义与前端处理 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | JSON、查询参数或协议格式错误 |
| 401 | AUTH_REQUIRED | 没有有效登录，转登录 |
| 401 | TOKEN_EXPIRED | 访问令牌过期，串行刷新一次再重放安全请求 |
| 401 | REFRESH_TOKEN_INVALID | 清理会话，重新登录，不无限重试 |
| 401 | WECHAT_CODE_INVALID | 微信凭证无效或过期，重新获取凭证 |
| 403 | PHONE_BIND_REQUIRED | 此功能需要手机号授权，保留已有表单 |
| 403 | STUDENT_VERIFICATION_REQUIRED | 引导学生认证 |
| 403 | ACCOUNT_RESTRICTED | 展示限制原因及申诉入口 |
| 403 | PERMISSION_DENIED | 无权限，禁止继续写操作 |
| 403 | MFA_REQUIRED | 高危后台操作需要二次认证 |
| 404 | RESOURCE_NOT_FOUND | 不存在或当前用户不可见 |
| 409 | VERSION_CONFLICT | 展示数据已更新并重新加载，不覆盖 |
| 409 | STATE_CONFLICT | 当前状态不允许此操作 |
| 409 | IDEMPOTENCY_KEY_REUSED | 同键请求内容不同，客户端检查逻辑 |
| 409 | RESERVATION_CONFLICT | 时段/座位已被占用，刷新空闲信息 |
| 409 | CAPACITY_EXCEEDED | 容量已满或队伍角色已满 |
| 409 | ALREADY_EXISTS | 已加入、已申请或重复业务关联 |
| 413 | FILE_TOO_LARGE | 上传前提示大小上限 |
| 415 | FILE_TYPE_UNSUPPORTED | 不支持或伪造文件类型 |
| 422 | VALIDATION_FAILED | 显示对应字段错误 |
| 422 | MATERIAL_NOT_READY | 未扫描通过、缺必要材料或输入版本失效 |
| 422 | CONTENT_REJECTED | 内容未通过发布安全检查 |
| 429 | RATE_LIMITED | 按 Retry-After 等待，不持续轮询 |
| 502 | UPSTREAM_FAILED | 微信/学校/AI服务失败，显示可重试提示 |
| 503 | SERVICE_UNAVAILABLE | 服务暂不可用，保留草稿 |
| 500 | INTERNAL_ERROR | 展示 requestId，不显示内部异常 |

字段校验失败不返回200伪装成功。异步任务处理失败通过 jobs.status=failed 表达；查询任务本身成功时仍返回200，不将正常的“预检发现缺项”误当成服务器异常。

## 18. 前端调用与联调示例

### 18.1 最小请求示例

以下仅用于说明接口调用，不是完整生产请求封装。生产封装还需实现超时、单次刷新、令牌安全保存、幂等键和取消请求。

```javascript
const baseUrl = 'https://demo-api.example.com/api/v1';

function requestApi(path, method, data, accessToken, idempotencyKey) {
  const header = {'Content-Type': 'application/json'};
  if (accessToken) header.Authorization = `Bearer ${accessToken}`;
  if (idempotencyKey) header['Idempotency-Key'] = idempotencyKey;
  return new Promise((resolve, reject) => {
    wx.request({
      url: baseUrl + path,
      method,
      data,
      header,
      timeout: 10000,
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300 && res.data.success) {
          resolve(res.data.data);
        } else {
          reject({status: res.statusCode, error: res.data.error, requestId: res.data.requestId});
        }
      },
      fail() { reject({error: {code: 'NETWORK_ERROR', message: '网络连接失败'}}); }
    });
  });
}

// 以下 accessToken 来自登录响应，slotId 始终保留字符串。
// 幂等键在用户首次确认时生成；同一次提交的网络重试继续使用该键。
requestApi('/reservations', 'POST', {slotId: '20001', seatNo: 12}, accessToken, requestKey);
```

### 18.2 登录、手机号、认证顺序

```text
wx.login获取code
→ POST /auth/wechat/login
→ 浏览公开内容
→ 用户主动点击手机号授权，取得phoneCode
→ POST /auth/phone/bind
→ POST /student-verifications
→ 认证通过后开放发布、预约和私信
```

不要把AppSecret写进小程序；不要让前端直接访问MySQL。手机访问的是后端HTTPS，后端访问数据库、对象存储、微信和AI。

### 18.3 上传、预检、通知联调顺序

```text
上传意向 → wx.uploadFile → 完成登记 → 轮询扫描任务
→ 创建材料 → 取得机会清单 → 绑定版本 → 发起预检
→ 轮询预检任务 → 显示逐项结果 → 设置截止提醒
```

jobs 首次2秒后查询，未完成时按2/3/5/10秒退避，最长10秒间隔；页面离开停止轮询，恢复后查询最新状态。不得每100毫秒查询一次；任务超时后可重试但保留原任务记录。

### 18.4 接口与数据库对应关系

| 接口领域 | 主要表 |
| --- | --- |
| 登录与身份 | users、user_wechat_identities、user_phones、student_verifications、user_profiles、user_roles、authorizations |
| 机会与AI解析 | notices、ai_parse_tasks、ai_parse_fields、opportunities、opportunity_eligibility_rules/checks |
| 材料与预检 | material_files、material_versions、material_checklists/items、precheck_tasks/results、material_usage_records |
| 报修与问题 | work_orders/events/ratings、campus_issues/locations/follows/evidences/votes |
| 预约 | resources、resource_slots、reservations、resource_occupancies、reservation_checkins |
| 互助与组队 | mutual_aid_posts、lost_found_matches、borrow_requests、teams、team_members、team_applications |
| 匿名与治理 | community_posts/comments、pseudo_identities、reports、penalties、appeals、moderation_tasks |
| 消息与提醒 | conversations/members、messages、notifications、reminders、subscription_authorizations、message_send_records |
| 个人中心 | favorites、growth_records、credit_events、data_requests |
| 集成与任务 | external_systems/references、sync_cursors/raw_digests/errors、async_jobs |

## 19. 实施前必须补齐的数据与服务支持

当前74张表不能直接兑现本契约全部能力。以下是明确的工程依赖，需要用增量迁移、Redis或受控服务落实；本次仅写文档，没有修改数据库。

| 能力 | 缺口或责任 |
| --- | --- |
| 登录与后台认证 | 可撤销会话、刷新令牌摘要、管理员密码/MFA与挑战存储；不得假设users已含密码 |
| 学生名单匹配 | 私密名单、导入任务、认证历史与防冒领机制 |
| 协议授权 | documentVersion与material_ai用途；不能把乐观锁version当协议版本 |
| 幂等 | 原子领取、请求摘要和结果保存；跨实例可重放一致结果 |
| 异步任务权限 | 保存服务端写入的创建人、校园与访问范围；不把可编辑payload中的userId当作唯一授权依据 |
| 通用文件 | 上传意向、隔离扫描、purpose、业务归属、签名与清理任务；不能让图片绕过材料文件安全机制 |
| 校园问题隐私 | 独立visibility、证据审核、待受理/待评价/转办关闭事件；投票按原有非负总数定义，不强加净票模型 |
| 预约 | reservationMode、超时占位、签到凭证；最新SQL已具备有效占用与预约复合一致性及单预约唯一约束，仍需事务锁、校园链路与容量校验 |
| 双方交接 | 借用与失物的各方确认事件及确认时间 |
| 互助评价与信用纠错 | 参与人确认、双方评价记录、人工纠错案件与修正事件；当前未有完整专用模型 |
| 团队 | 缺口数量、申请关联角色、成员加入/退出和队长转交规则 |
| 匿名 | 最新SQL已支持仅INSERT映射、author_name展示和受限调查日志；作者列表及修改归属仍需受控机制；原规划的映射加密与当前明文FK存在冲突，须记录处理方案 |
| 屏蔽与历史 | 业务屏蔽关系、隐私设置和可删除的浏览历史 |
| 消息 | 会话上下文去重、clientMessageId与lastReadMessageId/稳定消息序号，避免并发读写丢未读 |
| 规则版本 | 资格规则和材料预检规则的不可变版本与人工修改记录 |
| 处罚与申诉 | 案件关联、申诉决策理由和纠错历史 |
| 数据删除 | 冷静期、对象清理、保留义务和日志脱敏，不能直接级联清空全部历史 |

当前数据库的version字段只提供条件更新基础，JPA和业务代码必须实际使用它。枚举注释、不可变注释和审计注释也不是自动执行的安全规则。

## 20. 验证、交付与版本管理

### 20.1 必测用例

- 登录、令牌过期刷新、旧刷新凭证重放与退出撤销。
- 手机号授权拒绝仍可使用公开功能；名单认证不允许冒领。
- 修改ID、校园参数和目标对象不能访问他人材料、外校资源或匿名映射。
- 同键重试不生成两条预约、帖子、工单、申请或消息。
- 预约并发不超卖，取消释放，重叠时段和独占资源规则正确。
- AI任务输入版本变化后，旧结果不会覆盖新清单。
- 投稿、评论、私信和问题补充证据不能绕过审核。
- 调查记录和审计权限真实生效，正常匿名发布仍能工作。
- 恶意文件、伪造类型、过期签名、越权下载和超限上传被拒绝。
- 多实例任务领取、失败重试、WebSocket重连补拉不重复或丢业务事实。
- 注销和导出只作用于本人数据，生产配置不加载演示种子。

### 20.2 交付标准

实现时输出与本契约一致的 OpenAPI 3 文件、接口错误码、Postman/Apifox可导入集合和独立测试环境地址。管理员端和学生端分别生成契约测试；变更路由、必填字段、状态或权限时更新文档与调用方。

破坏性变更使用 `/api/v2` 或明确迁移版本；非破坏性增加可选字段仍需记录更新。接口文档、数据库迁移和服务版本必须能够对应到同一提交。

### 20.3 参考

- [当前数据库说明](https://github.com/dew64025-stack/-/blob/f1a33c36f99c334f48613f2f3d6f871540310634/DATABASE.md)
- [当前数据字典](https://github.com/dew64025-stack/-/blob/f1a33c36f99c334f48613f2f3d6f871540310634/DATA_DICTIONARY.md)
- [当前建表脚本](https://github.com/dew64025-stack/-/tree/f1a33c36f99c334f48613f2f3d6f871540310634/sql)
- [Spring Boot数据库初始化与Flyway配置](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

微信能力和合法域名的具体可用性、手机号接口额度、订阅模板权限等，以实施时的小程序后台和官方开发文档为准；本文件不假设当前AppID已经具备所有权限。


## 21. 仓库一致性、字段校验与上线验收

### 21.1 对照依据与主要路由

本稿按提交 f1a33c36f99c334f48613f2f3d6f871540310634 对照；仓库没有AGENTS.md、现成Controller或OpenAPI文件，README也未给额外接口约束。直接依据为规划书第5、8～14节，DATABASE.md的部署/权限规则和sql/V1～V6的真实字段。当前74张表、103个外键、5个CHECK。

下列31条主要路由已在正文定义；辅助列表、更新、管理、文件和任务接口属于对规划功能的设计细化，不代表仓库已部署这些路由。

| 规划书主要路由 | 正文模块 |
| --- | --- |
| `POST /api/v1/auth/wechat/login` | 第4节 |
| `POST /api/v1/auth/token/refresh` | 第4节 |
| `POST /api/v1/auth/phone/bind` | 第4节 |
| `POST /api/v1/student-verifications` | 第4节 |
| `GET /api/v1/home/overview` | 第5节 |
| `POST /api/v1/ai/assistant/messages` | 第5节 |
| `GET /api/v1/opportunities` | 第6节 |
| `GET /api/v1/opportunities/{id}` | 第6节 |
| `POST /api/v1/opportunities/{id}/eligibility-checks` | 第6节 |
| `POST /api/v1/materials/upload-intents` | 第7节 |
| `POST /api/v1/materials/{id}/prechecks` | 第7节 |
| `POST /api/v1/work-orders` | 第8节 |
| `GET /api/v1/work-orders/{id}` | 第8节 |
| `GET /api/v1/campus-issues/map` | 第8节 |
| `POST /api/v1/campus-issues/{id}/follows` | 第8节 |
| `GET /api/v1/resources/{id}/availability` | 第9节 |
| `POST /api/v1/reservations` | 第9节 |
| `POST /api/v1/reservations/{id}/cancel` | 第9节 |
| `POST /api/v1/reservations/{id}/check-in` | 第9节 |
| `GET /api/v1/mutual-aid/posts` | 第10节 |
| `POST /api/v1/mutual-aid/posts` | 第10节 |
| `POST /api/v1/team-applications` | 第11节 |
| `GET /api/v1/conversations` | 第14节 |
| `GET /api/v1/community/posts` | 第12节 |
| `POST /api/v1/community/posts` | 第12节 |
| `POST /api/v1/reports` | 第13节 |
| `POST /api/v1/appeals` | 第13节 |
| `GET /api/v1/me/dashboard` | 第15节 |
| `GET /api/v1/me/authorizations` | 第15节 |
| `POST /api/v1/me/data-exports` | 第15节 |
| `POST /api/v1/me/account-deletion` | 第15节 |

### 21.2 更新接口字段白名单

所有/update接口使用POST并携带Idempotency-Key。正文中“可编辑字段”严格指下表，不允许直接把请求映射到实体。至少修改一个白名单字段；version为整数且非负；id、campusId、createdBy、计数、审核结果和数据来源由服务端决定。

| 更新路径（相对/api/v1） | 允许修改字段 | 版本依据 |
| --- | --- | --- |
| /me/update | nickname,avatarAttachmentId | users.version |
| /me/profile/update | college,grade,skills,interests | user_profiles.version |
| /materials/{id}/update | expiresAt,tags | material_files.version |
| /checklists/{id}/items/{itemId}/update | materialVersionId，允许null解绑 | 父material_checklists.version，锁定父清单并增版本 |
| /mutual-aid/posts/{id}/update | title,content,detail,attachmentIds,price,exchangeArea | mutual_aid_posts.version；category创建后不变 |
| /teams/{id}/update | title,goal,openRoles | teams.version，关联成员/缺口同步事务 |
| /community/posts/{id}/update | title,content | community_posts.version，重新审核 |
| /me/growth-records/{id}/update | type,title,description,proofFileId,occurredAt,confirmed | growth_records.version |
| /me/privacy-settings/update | personalization,historyEnabled,nearbyEnabled | 独立隐私设置尚需持久化，不冒用users.version |
| /admin/opportunities/{id}/update | title,organizer,summary,deadline,eligibilityRules,materialRequirements | opportunities.version与规则快照；已发布内容先撤回 |
| /admin/resources/{id}/update | name,regionId,capacity,equipment,reservationMode,status | resources.version；不更改type |
| /admin/resource-slots/{id}/update | status,capacity,reason | resource_slots.version；日期和时间创建后不可原位改写 |

ID校验：1～20位十进制正整数，按对应列/雪花算法范围校验；ID比较不能用JavaScript Number。日期解析必须拒绝不存在的日期与无时区datetime。分页limit为1～100，地图另限200。相似度与置信度为0～1。所有数组有长度上限并去重；附件、材料版本、团队角色、学校地区和引用机会均校验存在性、用途、所有权与校园。

创建机会强制draft，即使SQL默认published也不能自动公开。创建通知最多256字符标题、128字符主办/发布主体、1024字符URL；rawUrl必须是经过SSRF检查的HTTPS。创建资源名称1～128，容量为正整数；locationName/区域名称最长128。评论和人工理由长度不超过目标SQL列限制。管理员reason/comment没有在接口表重复注明限制时，按512字符处理。头像、二维码、证件等图片只按授权用途处理。

DELETE类操作不在URL传令牌或敏感个人资料；reauthToken用X-Reauth-Token，原因可放JSON正文或改为明确撤销命令，服务端路由必须固定，不能依赖任意方法覆盖头。

### 21.3 原稿遗漏的能力与映射

| 能力 | 本稿补正 | 剩余依赖 |
| --- | --- | --- |
| 附近互助 | 首页明确定位输入、区域回退和nearbyMode | 互助位置数据/索引与授权机制 |
| 材料完整性 | 区分单材料基础检查和机会清单完整性，保留规划主路由 | 清单规则、版本与异步访问范围 |
| 校园服务进度 | 数据库status与面向学生的serviceStage区分 | 待受理、待评价、转办/关闭与公开问题处理模型 |
| 身份调查 | 普通/调查数据源分开，返回最小身份，不默认能读手机号 | 作者自助归属机制和映射加密设计 |
| 管理上传 | 独立名单/通知上传意向与用途校验 | 受控附件元数据和名单导入存储 |
| 来源标识 | 顶层六字段与demo持续标识 | sourceName/externalId从提供器映射产生，不伪造 |
| 接口覆盖 | 保留规划31条路径，其余为辅助路由 | OpenAPI/Controller尚未实现 |
| 双方评价 | 增加互助评价与信用纠错，防AI人格分 | 评价记录、参与关系、信用申诉模型 |

### 21.4 仓库内部仍需处理的冲突

1. 规划书规定“待评价→已完成”，SQL工单只有completed/rated等取值且没有独立待受理/转办/关闭信息。当前用serviceStage避免误显示，但完整闭环仍需模型/字典同步。
2. 规划书要求匿名映射加密保存，当前pseudo_identities用明文user_id外键；权限隔离已改进但不等同加密，不能把该要求直接删除。
3. 数据库说明称作者可按会话user_id校验归属，但业务账号无法SELECT映射，帖子/评论也没有可用于安全归属判断的独立凭据。该路径需受控服务或等价方案，不得以调查账号开放批量查询来补。
4. 取消删除申请、双方交接、历史与屏蔽、私密反馈等功能尚缺相关数据或状态。第19节是实施依赖清单，不是修改数据库的授权或“这些能力已完成”的证明。
5. DATETIME不存时区，演示脚本同时含UTC_TIMESTAMP与无时区日期字面量。本稿要求API带时区；实施时须统一ORM、导入、后台和种子时间转换，不能假设所有现有字面量已经是UTC。

### 21.5 性能与恢复门槛

严格保留规划书目标：3万注册用户、3000日活、300并发；普通查询P95≤500ms，普通写入P95≤800ms；AI任务提交≤3s；300人争抢同一资源不重复成功；月可用性99.9%；RPO≤1小时、RTO≤4小时。当前没有运行数据，不声称目标已达标。

记录requestId、接口耗时、任务积压和上游错误，但不记录原始微信凭证、文件正文、完整手机号或匿名映射。健康检查/指标只能对运维网络或受控管理员开放，禁止把Actuator敏感接口直接暴露公网。每小时数据库增量、每日全量备份，定期恢复数据库和文件一致性。

### 21.6 合规与配置边界

沿用仓库要求的备案、合法域名、服务类目、隐私政策、用户协议、第三方SDK与数据收集说明、举报和注销机制，AI显式标识、人工发布门禁以及数据最小化。此文档不重新判断法定资质或宣称审核一定通过。正式开放前核对微信后台能力和运营主体，模拟服务不得冒充学校官方渠道。

文件大小、令牌时长、心跳、提醒轮询和删除冷静期等数值是接口设计的初始配置建议，仓库未逐项确定这些数值；部署应固定并通过可查询的安全配置公开必要限额。不得将原稿示例值误认成学校规则或法定期限。


### 21.7 本次文档校验范围

校验31条仓库主要路由是否全部定义、微信请求方法、重复路由、JSON示例结构、BIGINT字符串、来源字段、版本依据、数据源权限和仓库缺项声明。没有后端实现或运行服务器，因此不声称接口测试、迁移测试、压力测试、微信真机测试或安全验收已通过。

参考微信官方API类型：[wx.request类型定义](https://api.github.com/repos/wechat-miniprogram/api-typings/contents/types/wx/lib.wx.api.d.ts)。本稿按该定义使用GET/POST/PUT/DELETE，不假设原生小程序支持PATCH。
