# Task 3 实现报告

Status: DONE_WITH_CONCERNS。身份域实现与测试完成；真实微信、MySQL/Redis、学校接口及Task4文件依赖尚未验收，不能将真实头像/名单全流程算作已联通。

工作区：`C:/Users/19683/source/repos/caizhitong`；分支：`feature/backend-server`；BASE：`1c1be6440cdeb4240a7c38fd9ee126c773ad24a4`。唯一需求依据为本目录最新task-3-brief.md及主控跨校对象404澄清。未改V1～V7，未启动或修改现有MySQL84/Redis/文件服务，未读取其他凭据，未push。

## 实现与共享接口

- 微信：`WechatGateway.Identity(appId,openid,unionid)` / `Phone(appId,countryCode,nationalNumber)`；生产`WechatHttpGateway`固定微信HTTPS域名，`JavaWechatTransport`连接2秒、整体请求8秒、256KiB订阅器上限、禁止重定向。换码在数据库事务外；登录code摘要短期去重与限流，openid原样精确查找，不以unionid合并。唯一键竞争回滚后重读已有身份，既有校园由服务器确定。
- 手机：仅采用微信手机号结果及watermark.appid；检索值规范化为`+countryCode+purePhoneNumber`。锁users后更新唯一当前号码、撤销旧phone授权并追加新授权；归属冲突返回安全409，不回显归属。phone/student HMAC返回32字节，CHAR(64)使用HexFormat；密文按`phone`/`student`用途加密。
- DTO：`IdentityInputs`每路由独立record，字段用Jackson 3 JsonNode保留缺失/null/真实JSON类型；构造器拒绝数字ID/code/学号、非整型或负version、错误数组元素及空更新。现有mapper拒绝未知属性。仅college/grade/avatarAttachmentId允许显式null清除。数组最多32项、元素最多64字符并去重；version使用非负可表示int。密码额外拒绝超过72 UTF-8字节。
- 资料：users.version和user_profiles.version独立。无画像返回空数组/version=0；首次更新锁users，创建version=1；并发首次更新只成功一次。college校验本校active college组织，grade校验本校profile.allowedGrades，无允许值拒绝设置。
- 人工认证：未授权手机号不能申请；未命中名单同样pending，命中仅显示rosterMatched线索。被拒历史保留，可重新申请；同学号待审不占verified归属。审批验证本校权限、pending、版本与最新有效phone授权，认证决定、追加审计和业务幂等在同事务中；跨校/不存在目标均404。
- 后台：独立BCrypt凭据和加密MFA种子；密码只返回五分钟挑战，Redis摘要挑战一次消费，6位TOTP/30秒/±1步窗，数据库last_totp_step条件更新防重放。停用凭据拒绝登录/MFA/刷新；失败不签发会话。JSON refreshToken为null，真实值只进Secure/HttpOnly/SameSite=Strict Cookie。CSRF包括登录/MFA/刷新；admin logout/reauth仍需active管理员。
- `OneTimeSecrets.claim(namespace,digest,value,ttl)` / `consume(namespace,digest)`：生产Redis NX与GETDEL，键中仅放摘要；存储故障安全503。reauth随机token、摘要、五分钟，绑定admin/campus并一次消费。角色操作先于幂等重放校验对象与授权，实际执行再次锁目标检查，不可超过操作者权限；撤销后Task2 ActorLoader立即失去权限。
- `AdminProvisioning.Input(operatorId,userId,campusId,roleCode,username,password,mfaSeed)`：仅`AdminBootstrap`受控CLI调用，无HTTP注册/默认账号。敏感输入来自显式环境变量，用户/操作员/校园/角色显式传入，事务写凭据、角色与审计；CLI异常不带秘密原因。
- `SchoolIdentityProvider.configured(campusId)`明确false，仅声明未来接入位置，不提供猜测的callback或伪verified。

## Task4文件/任务交接（尚未联通）

`IdentityFiles`窄接口为`validateAvatar(Actor,id)`、`validateRoster(Actor,id)`、`roster(Actor,id,maxBytes)`；默认`UnavailableIdentityFiles`三个方法均503。Task4须替换此实现，校验服务器校园、所有者、私密用途、clean扫描状态、受限读取；读取时再次授权。不得以fake clean/伪URL/伪成功替代。

头像写入只保存attachmentId，业务幂等User DTO强制avatarUrl=null，响应在业务代理外补既有URL。Task4仍需真正读取头像并在代理事务外生成授权访问URL，禁止将临时签名持久化到users或幂等记录。目前真实头像赋值因文件依赖缺失而503；昵称更新及显式清除可用。

`StudentRosterService.register`先在BusinessWrite中登记pending与追加审计，HTTP202不执行导入。V8已新增`async_jobs.campus_id/owner_user_id/access_domain/claim_token`，Task4复用；旧未知归属保留NULL，不猜填或开放读取。

`StudentRosterWorker.run()`每次限量claim一条student_roster/admin且可信归属非NULL任务；短事务领取，五分钟租约、三次尝试、30秒重试间隔，失效领取回收。事务外读取/解析CSV；最终重新验证owner权限，以claim_token fencing，在十秒事务中写有效行、摘要与succeeded。失败仅保存IMPORT_FAILED/IMPORT_LEASE_EXPIRED，无CSV/学号。生产每五秒调度；只有测试profile关闭自动调度并手动驱动真实worker。

CSV限制10MiB、严格UTF-8，四列表头studentNumber/college/grade/status；校内college、allowedGrades以及active/inactive白名单，拒绝格式/重复/非法行，摘要最多20条行号与错误码。学号HMAC与密文、学院/年级密文持久化。inactive不自动撤销认证，任何名单均不自动授予学生能力。测试已通过模拟clean私密源；生产源未接通，导入入口当前在预验证时503，不能宣称文件全流程完成。

## 26路由矩阵（均相对/api/v1）

| 方法/路径 | 实现范围与状态 |
| --- | --- |
| POST /auth/wechat/login | 服务器换码、摘要去重/限流、身份唯一竞争、服务器校园；真实微信未联调 |
| GET /campuses | 仅开放校园 |
| GET /campus/regions | 服务器登录校园优先；游客开放campusCode |
| GET /campus/organizations | 同上，组织type白名单 |
| POST /auth/token/refresh | STUDENT刷新凭据轮换；旧access Header不抢先拒绝 |
| POST /auth/logout | 有效学生access或refresh撤销；旧access加有效refresh可退出 |
| POST /auth/phone/bind | 真实微信手机号、水印、唯一归属、新有效授权；真实微信未联调 |
| GET /me | 当前用户与脱敏手机/认证状态 |
| POST /me/update | 用户版本、昵称/清除；真实头像赋值503，等待Task4 |
| GET /me/profile | 独立画像/空画像默认值 |
| POST /me/profile/update | 字段白名单、校内配置、独立版本与并发首建 |
| POST /student-verifications | 手机授权前置、加密学号、人工pending、拒绝后重申请 |
| GET /me/student-verification | 本人最新认证与脱敏学号 |
| POST /admin/auth/login | 密码限流/统一失败、五分钟挑战；必须CSRF |
| POST /admin/auth/mfa/verify | 一次挑战/TOTP防重放，ADMIN会话/安全Cookie；必须CSRF |
| POST /admin/auth/token/refresh | Cookie/CSRF轮换，旧access不阻断；有效凭据仍必需 |
| POST /admin/auth/logout | 有效active ADMIN、撤销会话与清Cookie |
| POST /admin/auth/reauth | 有效active ADMIN/TOTP、五分钟绑定一次token |
| GET /admin/me | 角色、校园scope与现行permissions |
| GET /admin/student-verifications | 本校脱敏历史队列、签名/绑定scope游标 |
| POST /admin/student-verifications/{id}/decision | 校内审批/版本/手机有效、审计+幂等原子性，不可见对象404 |
| POST /admin/student-roster/imports | 202 pending与审计；真实文件源未接入时503；真实worker已实现 |
| GET /admin/users | 本校脱敏用户、真实phone授权状态、签名游标 |
| POST /admin/users/{id}/roles | 权限包含、同校园、reauth、事务审计/幂等；不可见对象404 |
| DELETE /admin/users/{id}/roles/{roleCode} | X-Reauth-Token，不在URL传token，撤权即时生效 |
| GET /admin/auth/csrf | 额外安全辅助路由，仅当前请求token与安全Cookie |

Task4文件路由和Task13校园创建/配置/统计未包含在此任务。角色的scope/权限越权403；在已授权校园范围内，不存在/外校对象相同404。

## TDD命令与实际证据

以下命令工作目录均为`backend`，先设置：

```powershell
$env:JAVA_HOME='C:/Users/19683/AppData/Local/caizhitong-devtools/jdk-21.0.12.1+1'
```

所有mvn命令均通过`C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd`执行。表中为该可执行文件后实际参数；未用测试跳过选项。

| RED参数/阶段 | 实际失败输出（实现前） |
| --- | --- |
| -Dtest=IdentityHttpTest test，首批 | 9 tests / 9 failures / 0 errors；公开/资料/申请等expected 200/400/403 but was 404，CSRF expected 200 but was 401 |
| 同上，微信/手机号/MFA | 12 tests / 4 failures / 0 errors；登录/手机expected 200 but 404，密码挑战expected 200 but 401 |
| 同上，角色/名单/队列 | 16 tests / 3 failures / 0 errors；赋权200但403、导入202但503、rejected队列重复返回当前pending id |
| -Dtest=WechatHttpGatewayTest test | 3 tests / 2 failures / 0 errors；configured真实请求仍抛未配置，502错误预期但503 |
| IdentityHttpTest#controlledProvisioningHashesPasswordEncryptsSeedAndAuditsOperator | 1 test / 1 failure / 0 errors；实际无AdminProvisioning bean |
| IdentityHttpTest#credentialLoginNeedsCsrfAndWrongPasswordDoesNotIssueSession | 密码UTF-8越界expected 400 but 401，随后在DTO字节边界拒绝 |
| SessionHttpBoundaryTest#expiredAccessHeaderDoesNotPreemptValidStudentRefreshOrLogout | 1 test / 1 failure / 0 errors；旧access加有效refresh expected 200 but 401 |
| IdentityHttpTest#passwordChallengeThenMfaIsOneTimeAndCookieOnlyRefresh+adminRefreshStoreFailureIsSafe503 | 2 tests / 2 failures / 0 errors；停用凭据刷新401但200，Redis异常未转安全503 |
| IdentityHttpTest#adminUserListReportsRealCurrentPhoneAuthorization+meReadDoesNotHoldAConnectionWhileIssuingNestedQueries+profileNameUpdateReplaysVersionButNeverCachesAvatarSignature | 2026-10-14 17:52:21：3 tests / 3 failures / 0 errors；phoneBound true但false、单连接池266ms借连接超时、缓存包含签名 |
| IdentityHttpTest#crossCampusReviewAndUnavailableFilesAreDenied+wrongOwnerExpiredReauthAndExternalCampusRoleAreDenied | 17:56:07：2 tests / 2 failures / 0 errors；跨校对象expected 404 but 403 |

每个带`#`选择器均作为`'-Dtest=上列完整选择器' test`传入Maven。最初RED夹具曾误用H2保留字current_user，修正后重新取得9项纯断言失败；微信测试将未配置异常包入assertDoesNotThrow后取得2项纯断言失败。一次独立PowerShell计算下一步RFC fixture漏了int位移，修正并校准279037/637009后，生产TOTP未因该fixture调整。

GREEN聚焦实际命令：

```powershell
& 'C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd' '-Dtest=IdentityHttpTest,SessionHttpBoundaryTest,RedisOneTimeSecretsTest,JavaWechatTransportTest,WechatHttpGatewayTest' test
```

2026-10-14 17:57:38：42 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS。含26项身份HTTP行为测试、10项真实安全链会话边界及6项Redis/微信适配边界测试。

最终唯一一次全量：

```powershell
& 'C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd' package
```

2026-10-14 18:00:33：exit0；94 tests / 0 failures / 0 errors / 0 skipped；BUILD SUCCESS，22.152s；生成`backend/target/backend-0.0.1-SNAPSHOT.jar`。原底座配置拒绝负例产生预期WARN，不是未知测试错误。未以排除真实安全链或删除用例取得通过。

## 自查、文件与剩余边界

- 对象授权在代理前完成，写入代理内再次检查；BusinessWrite返回具体DTO，Controller不写数据库。远程换码/扫描/读取不持有数据库事务。审批审计失败证明认证、审计及幂等占位同时回滚；重放只有一次审计。
- 首建画像并发只有200/409各一次；pending不占学号、verified归属冲突；最新phone撤销无法审批；名单命中仍pending。跨校认证/角色目标与不存在目标同404。
- 新Controller替换旧404探针及测试logout探针，所有原安全断言保留。旧csrf()测试工具会替换Cookie仓库，已改实际安全Cookie+masked header；新增身份/会话套件使用独立H2库，不依赖测试顺序。旧access通过轮换撤销模拟失效，不冒称实际Redis TTL实测。
- JAR检查含V8；没有application-test、identity-schema、H2 JAR或Test类。V1～V7与BASE diff为空；git diff --check无空白错误。未新增运行依赖。
- 新增：identity的Inputs/Views/Controller/Service/WechatIdentityService/IdentityFiles/UnavailableIdentityFiles/OneTimeSecrets/RedisOneTimeSecrets；campus的Controller/Service；integration/wechat的Gateway/HttpGateway/Transport/JavaTransport；integration/school的Provider/UnconfiguredProvider；admin/identity的AuthController/AuthService/IdentityController/RoleService/Provisioning/Bootstrap/BootstrapService/Totp/StudentRosterService/StudentRosterWorker/RosterScheduling。
- 新增测试：IdentityHttpTest、RedisOneTimeSecretsTest、WechatHttpGatewayTest、JavaWechatTransportTest、identity-schema.sql。调整SessionHttpBoundaryTest与测试profile调度配置。
- 其他改动：SecurityConfiguration、BearerSessionFilter、RoutePolicy、application.yml、V8__identity_runtime.sql、DATABASE.md最小授权及backend/README.md交接。报告即本文件。主控implementation-status.md、主计划、deployment-prerequisites.md均不纳入checkpoint。
- 未执行真实微信密钥联调、真实Redis NX/GETDEL/TTL/并发、MySQL V8迁移/冲突/NO PAD collation/最小授权验收、受控CLI对真实数据库初始化、扫描私密文件/头像URL接入、10MiB高吞吐导入实测或学校协议验证。本地HTTP替身只位于src/test；H2字符比较不能替代真实MySQL collation，默认文件依赖仍failclosed。

本地checkpoint SHA以最终回报为准；后续独立审查由主控组织。
