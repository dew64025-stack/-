# 材智通后端服务器实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 实现仓库接口契约定义的完整后端，全部模块和验收完成后统一推送，不把占位接口作为实现。

**Architecture:** Java 模块化单体，按业务域组织 Controller、应用服务、JPA实体和仓储。Redis保存不透明会话和短时协调状态，MySQL事务和约束决定最终业务结果。外部微信、AI、扫描、对象存储与学校提供器通过显式接口隔离。

**Tech Stack:** Java 21 LTS、Spring Boot 4.1.1、Spring MVC/Security/Data JPA、Flyway、MySQL 8.0、Redis、MinIO/S3、Maven、Docker Compose、Nginx。

**Spec:** ../../../2026-10-13-campus-one-stop-platform-design.md；../../../campus-backend-api-v1.1-reviewed.md；../../../DATABASE.md；../../../DATA_DICTIONARY.md。

## Global Constraints

- API前缀为 /api/v1；BIGINT为十进制字符串，金额为十进制字符串，时间为RFC3339。
- 默认按校园隔离；角色、对象归属、业务状态和version均由服务端判断，跨校对象返回404。
- 所有业务写入要求Idempotency-Key；同键不同请求409；原业务结果可重放。
- 微信登录必须真实换码，生产不提供任意openid或开发万能令牌。
- 手机号、学号、匿名映射AES-GCM加密，检索使用独立HMAC密钥；日志禁止明文凭据及个人信息。
- 不修改已发布V1～V6；新增表或状态通过V7及后续迁移补充，现有数据库不得自动baseline或repair。
- 数据源demo/manual/official及六个顶层来源字段保持一致，官方失败禁止回退演示。
- 文件隔离、扫描失败关闭；AI只能给建议，不自动发布、审批、封禁。
- 管理员独立认证，授权调查独立数据源；业务账号不读取pseudo_identities。
- 用户要求：全部后端完成后再统一提交GitHub。允许保留未推送的本地检查点。
- 未完成实现、跳过的外部集成、未执行压力/恢复测试必须如实报告；不宣称可上线。

## 文件与共享接口

工程放backend/，包名com.caizhitong；领域包按规划书第6.3节。部署放deploy/；操作脚本放scripts/；追加迁移放sql/。backend/pom.xml在打包时将根sql/映射到classpath:db/migration，不重复维护V1～V6。
common提供ApiResponse、ApiError、BusinessException、SnowflakeIds、SensitiveDataCrypto；security提供Actor与CurrentActor。所有控制器显式DTO，不提供任意表名CRUD或Map批量更新。

## Task 1: 工程底座、响应、加密与ID

**Files:** backend/pom.xml；backend/src/main/java/com/caizhitong/CaizhitongApplication.java；common/和config/；backend/src/main/resources/application.yml；backend/src/test/java/com/caizhitong/common/；backend/README.md；.gitignore。

**Interfaces:** ApiResponse<T>有success/data/error/requestId/timestamp，null data/error不输出；ApiError有code/message/fieldErrors/retryable。BusinessException携带HttpStatus及稳定错误码，业务异常不泄露内部信息。SnowflakeIds(Clock,int workerId).nextId()线程安全、回拨拒绝、workerId范围0..1023。SensitiveDataCrypto(byte[] encryptionKey,byte[] hmacKey).encrypt(String purpose,String text)、decrypt(String purpose,byte[] cipher)、searchHash(String purpose,String text)。

- [ ] 配置Maven Java21和Boot4.1.1、MVC/Security/Validation/JPA/Redis/Flyway/MySQL/Actuator、测试H2/JUnit。不添加无用途依赖。
- [ ] RED：创建CryptoTest、SnowflakeIdsTest，运行mvn -f backend/pom.xml test；不存在的实现应导致预期失败，保存输出。
- [ ] GREEN：用AES-256-GCM随机12字节nonce、purpose作为AAD，格式version+nonce+ciphertext+tag；HMAC-SHA256独立密钥。错误不得回传密钥/原文。ID epoch为2026-01-01T00:00:00Z，41/10/12位，不使用数据库自增。
- [ ] GREEN：统一异常处理包含BeanValidation、未知字段拒绝、类型错误、409版本冲突、403/401，不输出堆栈/SQL。RequestIdFilter生成req_随机UUID，不信任入站requestId。
- [ ] 实现应用配置：UTC JDBC/Hibernate时区，ddl-auto=validate；Flyway使用独立迁移账号、baseline-on-migrate=false、clean-disabled=true；测试profile不运行MySQL迁移，禁止生产H2。
- [ ] 验证AES篡改/错purpose/错密钥拒绝、密文不相同、HMAC跨purpose不同、ID并发唯一和时钟回拨。
```java
@Test void tamperedCipherIsRejected() {
    var crypto = new SensitiveDataCrypto(new byte[32], new byte[32]);
    byte[] cipher = crypto.encrypt("phone", "13800000000");
    cipher[cipher.length - 1] ^= 1;
    assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("phone", cipher));
}
```
- [ ] 运行完整已存在测试与mvn package；本地提交检查点，不推送。

## Task 2: Redis会话、权限、幂等与限流

**Files:** backend/src/main/java/com/caizhitong/security/；backend/src/main/java/com/caizhitong/common/idempotency/；backend/src/main/java/com/caizhitong/common/pagination/；backend/src/main/java/com/caizhitong/common/ratelimit/；sql/V7__runtime_coordination.sql；backend/src/test/java/com/caizhitong/security/及common对应子包。
**Consumes:** Task1 ApiResponse/BusinessException/SnowflakeIds/SensitiveDataCrypto，生产DataSource/Redis与Clock。
**Produces:**
- Actor(long userId,long campusId,Set<String> permissions,boolean phoneBound,boolean studentVerified,String status,SessionKind kind)，kind=STUDENT/ADMIN。
- CurrentActor.require()、optional()、requireStudent()、requirePermission(String)；非active拒绝。requireStudent同时要求手机号已验证和认证verified。
- ActorLoader.load(long userId,long campusId,SessionKind kind): Optional<Actor>。实现通过JPA EntityManager参数化native读取现有users/phones/verifications/roles；管理员namespace只有合法管理员角色，业务处理身份动态重查，不接受Redis缓存的客户端权限。
- SessionTokens(accessToken,expiresIn,refreshToken,refreshExpiresIn)；SessionTokenService.issue(long userId,long campusId,SessionKind)、authenticate(String,SessionKind)、refresh(String,SessionKind)、revoke(String,SessionKind)。JWT不是本项目方案，token使用SecureRandom 32字节base64url。
- CursorCodec.encode(CursorPayload)、decode(String token,String scope)：payload含排序键和scope，签名HMAC，客户端不能跨校园/过滤条件复用。
- @BusinessWrite应用服务注解与IdempotencyContext；IdempotencyExecutor在一个MySQL事务内执行真实业务及幂等结果持久化，Controller业务命令不得直接绕过此服务边界。
- RateLimiter.check(String bucket,int max,Duration window)，原子Redis计数，超限429，Redis失效503。

**事务与失败规则：** 幂等范围含namespace、user、campus、method、真实路由及key摘要。过滤器只验证业务写头并绑定规范化请求摘要，不在过滤器结束后另存已经提交的业务结果。应用服务@BusinessWrite在事务里先占唯一记录、执行业务、保存加密DTO结果后一起提交；异常一起回滚。重复提交返回相同业务data，但外层requestId是当前请求的新ID。同键不同摘要409；竞争中已提交的结果可读，未完成或锁超时409/503可重试；不能catch后强行提交回滚事务。权限检查在幂等重放之前（服务@PreAuthorize或Controller明确检查），不能因重放绕过当前撤权。注解只用于实际业务写入，不包远程微信换码/AI模型/文件扫描调用。

**会话规则：** Redis只存token摘要和userId/campusId/kind/sessionId，access900秒、refresh604800秒。refresh Lua一次性消费并轮换整个会话，撤旧access和refresh，两个并发刷新只成功一次。namespace不可互换。access认证每次加载当前数据库状态，找不到/已删/非active用户拒绝；权限配置由roles.code静态矩阵映射，不接受请求roles。撤销同会话token。JSON和日志禁止原始token。Redis不可用不放行。未知/过期token401。

**HTTP边界：** 只解析Bearer认证，客户端userId/campusId/roles字段不产生身份。学生端stateless bearer请求独立安全链；admin链隔离，学生token不通过。admin cookie写操作CSRF不能关闭，cookie Secure/HttpOnly/SameSite，具体管理员登录和MFA在Task3接入。公开GET仅允许文档P/P-L的特定路径，未实现路由仍404，不能通配允许所有写。临时Upload二进制凭据由Task4独立验证，不用Bearer当上传凭据。默认生产不输出生成的Spring默认密码。

**幂等例外精确边界：** 登录/refresh/phone一次性换码/logout、admin认证/MFA/reauth、临时upload-intents/download-url/check-in-token及二进制上传有各自凭据机制；其他POST/PUT/DELETE业务都必需Idempotency-Key，8～128可打印ASCII字符，超过限制400。认证动作不产生持久幂等缓存里的原始token。固定路由例外列表，不接受客户端选择例外。

**角色矩阵：** user/guest不给admin权限；student认证由phone+verification决定而非只靠role。content_moderator有content.review/report.review/appeal.review；business_operator有workorder.read/process、issue.read/process；resource_admin有resource.read/write/process；campus_admin拥有本校运营权限（除security.investigate及platform.campus.manage）；platform_admin可配置校园管理但跨校操作仍需显式scope；security_investigator只有security.investigate/audit.read，不能仅因其他管理员角色获得真实匿名身份。新增operator赋权范围留Task3受控管理。

**数据库：** V7创建api_idempotency_records，应用生成BIGINT主键；唯一scope_digest+key_digest；request_digest、response_encrypted MEDIUMBLOB、response_type、status、created_at/expires_at，campus/user复合FK，范围索引。业务账号授权在DATABASE说明追加，不能自动给出数据库级全权。演示种子不得加载到生产，V1～V6不改。

- [ ] RED：OpaqueSessionTest验证伪造token无效、过期401、学生token不能admin、刷新重放拒绝、两次刷新仅一次成功、撤销旧access即时无效。
- [ ] RED：CurrentActorTest验证未登录401、未绑定/未认证403、跨namespace拒绝、当前DB已禁用用户不可凭旧会话访问。
- [ ] RED：IdempotencyExecutorTest在H2实际事务库验证同键同输入只创建一次业务记录、异请求409、业务异常不留下成功记录、权限撤销后不得重放敏感结果。
- [ ] RED：CursorCodecTest验证篡改、过滤或校园scope变化、过长游标及非法base64拒绝；分页上限100、默认20。
- [ ] RED：RateLimiterTest验证最大次数后429、窗口更新、Redis错误503。外部Redis使用适当adapter测试替身，Lua真实集成检查单独记录；不把替身测试称为Redis并发实测。
```java
@Test void refreshRejectsReusedToken() {
    SessionTokens first = sessions.issue(10001L, 1L, SessionKind.STUDENT);
    SessionTokens next = sessions.refresh(first.refreshToken(), SessionKind.STUDENT);
    assertThrows(BusinessException.class,
        () -> sessions.refresh(first.refreshToken(), SessionKind.STUDENT));
    assertTrue(sessions.authenticate(first.accessToken(), SessionKind.STUDENT).isEmpty());
    assertTrue(sessions.authenticate(next.accessToken(), SessionKind.STUDENT).isPresent());
}
```
- [ ] GREEN：按上述接口实现Redis adapter、Lua原子轮换与计数、数据库幂等服务、filter/aspect、安全链及当前用户工具。
- [ ] GREEN：聚焦测试修正后完整mvn test/package，记录RED/GREEN与未执行的真实Redis/MySQL检查，本地检查点不推送。

## Task 3: 微信身份、手机号、学生认证与名单

**Files:** identity/、campus/、integration/wechat/、admin/identity/；sql/V8__identity_runtime.sql；test相同域。
**Interfaces:** 微信Jscode2session只服务器交换；手机号一次性code换取真实号码；AppID从环境配置。个人基础资料与画像分别用version。名单保存HMAC学号与密文字段。用户现已确认：学校身份接口先预留，当前保留人工复核；名单命中也不自动授予学生权限。拒绝后允许重新申请，不允许占用他人已验证学号。学校接口未提供协议/可信凭据时不能伪造verified结果。
- [ ] RED：API第4节全部身份/校园接口，测试未知字段、跨校登录、更换campusCode、重复微信身份、手机号归属冲突、未绑定手机认证拒绝、名单不匹配pending、拒绝后重申请、画像版本409。
- [ ] 微信HTTP适配设置超时、可信固定域名，微信失败不创建虚假身份；测试用本地HTTP替身，不对生产提供fake开关。学校认证定义提供器接口与未接入状态，不开放猜测协议的公网回调。
- [ ] 追加名单和运行时模型/最小权限授权；管理员认证/名单导入和复核记录审计。
- [ ] GREEN：完整身份闭环及隔离测试，本地检查点。

## Task 4: 安全文件与材料基础

**Files:** material/files/、integration/storage/、integration/scanner/；sql/V9__files_and_jobs.sql；test。
- [ ] RED：第7节上传意向、Upload认证二进制上传、完成、元信息、授权下载、材料版本接口；测试越权、大小/MIME/文件头/摘要不一致、一次性凭据重放、未扫描下载拒绝、扫描失败不clean。
- [ ] MinIO隔离对象键服务器生成，短期签名；ClamAV扫描或显式受控扫描服务，不提供假clean实现。JPEG/PNG移除EXIF，PDF页数限制。
- [ ] 材料版本只追加，不允许更新已被清单引用的版本。文件删除登记可靠任务，与元信息事务一致。
- [ ] GREEN：本地文件服务替身集成测试，注明真实对象存储/扫描验证范围，本地检查点。

## Task 5: 通知、机会、AI解析与资格

**Files:** opportunity/、ai/notice/、integration/providers/、admin/opportunity/；追加迁移；test。
- [ ] RED：第6、16.3节全部相关路由，公开仅published/ended可见，人工发布门禁，解析字段证据与置信度、资格unknown不判eligible、用户自行提交唯一。
- [ ] 真实持久化收藏/提醒/画像快照，规则辅助判定；后台通知上传/审核/发布/截止/归档状态机。
- [ ] AI适配只接固定Schema，外部内容不作为指令；失败转人工，不伪造解析结论。
- [ ] GREEN：跨校读写、版本和幂等、发布审批测试，本地检查点。

## Task 6: 材料清单与完整性预检

**Files:** material/checklist/、material/precheck/、ai/material/；test。
- [ ] RED：第7节清单增删项、材料版本选择、单材料检查与整清单预检、任务结果、复用记录；缺必交项即missing，旧版本冲突拒绝。
- [ ] 保存checklist/rules/input/model版本与摘要快照，文件字段错误尽早返回；不把单文件通过等同机会材料齐备。
- [ ] GREEN：规则集、任务重试、本人结果访问和版本测试，本地检查点。

## Task 7: 资源预约与签到

**Files:** resource/、integration/providers/resource/、admin/resource/；test。
- [ ] RED：第9、16.5节资源、时段、availability、预约/取消/签到/历史与后台占用；容量、重复时段、跨校、未认证、越界seatNo、取消释放及重复签到测试。
- [ ] Redis短锁加MySQL事务行锁，resource_occupancies唯一约束最终防重；预约resource从slot导出，遵循复合FK。
- [ ] 过期/爽约产生解释性信用事件和可靠提醒；始终标模拟预约。
- [ ] GREEN：300个并发申请同一座位最多1个成功，取消后可重订，记录真实执行环境，本地检查点。

## Task 8: 工单、问题地图、拥堵与评价

**Files:** workorder/、integration/providers/repair/、admin/workorder/；追加状态与转办模型；test。
- [ ] RED：第8、16.4节所有路由，敏感问题不公开、处理员校园归属、工单转办/关闭/无法处理、待评价和rated逻辑。
- [ ] 状态机和event同事务，公开DTO过滤敏感内容；voteCount为非负票数，关注/投票唯一幂等。经纬度范围、拥堵时效与可信度明确。
- [ ] GREEN：版本冲突、权限、公开/私密边界、评价只能本人且仅一次，本地检查点。

## Task 9: 互助、失物、借用与组队

**Files:** mutualaid/、team/、credit/ratings/、admin/mutualaid/；补交接与评价模型；test。
- [ ] RED：第10、16.6节全部路由，失物核验不公开关键特征、二手预留/完成、借用交接/归还/超期、双方评价、队伍申请受理/拒绝/退出。
- [ ] 禁公开手机号、二维码引流；无付款/押金接口。明确双方参与关系、状态前置和version；人数/成员唯一。
- [ ] GREEN：越权、跨校、并发重复入队、归还与评价测试，本地检查点。

## Task 10: 会话、消息、订阅与可靠通知

**Files:** messaging/、integration/wechat/subscription/、admin/messaging/；补消息去重/已读游标/屏蔽模型；test。
- [ ] RED：第12、16.8节路由及WebSocket，成员权限、clientMessageId唯一、未读游标单调、屏蔽后发送拒绝、订阅未授权不发送。
- [ ] 消息先落库再通知，发送失败有限重试，不回滚已完成业务。WebSocket认证不在URL传永久令牌，断线增量轮询。
- [ ] GREEN：消息隐私、可靠重试、去重测试，本地检查点。

## Task 11: 匿名社区、审核、举报、申诉与调查

**Files:** community/、moderation/、admin/governance/、security/investigation/；追加匿名归属与加密映射迁移；test。
- [ ] RED：第11、16.7/16.10节路由，每帖独立假名、匿名DTO无真实ID/可串联信息、作者维护归属、普通账号无法查询真实作者。
- [ ] 迁移设计保留已有数据，不静默删除明文映射；专门转换流程备份/验证后去明文，调查专用数据源只能授权最小查询，每次写不可变审计。
- [ ] 审核待审不伪装成功发布，高风险私密队列，处罚/举报/申诉恢复状态闭环。
- [ ] GREEN：泄露/越权测试、调查最小权限验证，仍需执行的外部权限检查必须记录，本地检查点。

## Task 12: 首页、我的、成长与数据权利

**Files:** home/、identity/me/、credit/、privacy/；补历史/信用纠错/注销状态；test。
- [ ] RED：第5、13～15、16.9节全部路由；游客无私人数据、附近缺位置不伪造距离、匿名社区不进成长档案、AI答案无依据明确未知。
- [ ] 授权撤销、数据导出/注销冷静期/撤销与清理队列，重认证、本人范围、短期下载；法定保留项解释。
- [ ] GREEN：数据权利闭环、权限与幂等测试，本地检查点。

## Task 13: 管理后台、提供器与运维

**Files:** admin/remaining/、integration/、deploy/、scripts/、backend/README.md、docs/backend/。
- [ ] RED：接口第16～18节剩余管理/集成路由，管理员cookie/CSRF/MFA、权限矩阵、跨校拒绝、统计脱敏、配置凭据不返回。
- [ ] Demo/Manual持久化提供器与Official接口隔离，SSRF协议/IP限制、超时/失败显式、不静默demo回退。
- [ ] Docker Compose、Nginx HTTPS示例、Windows一键启动、备份/恢复脚本；默认端口不覆盖已有MySQL。
- [ ] GREEN：启动/健康/退出脚本行为测试，部署最小权限和监控暴露验证，本地检查点。

## Task 14: 全量验收与统一提交

**Files:** docs/backend/implementation-status.md、docs/backend/test-report.md、backend/src/test/.../ContractCoverageTest.java、README.md。
- [ ] 从v1.1文档提取206个计划接口，对照实际Spring映射和实现测试；31主路由全部有真实业务行为，额外接口不计替代。
- [ ] 执行全套单元/HTTP/数据库/Redis/文件集成、关键安全、预约并发、核心链路；执行真实环境压力和恢复检查后才认定对应门槛通过。
- [ ] 文档列出每个已实现路由、配置、启动、测试命令、已知限制和需要运营者准备的外部配置；不把缺凭据的真实联调列为通过。
- [ ] 独立全分支审查并修复关键问题，秘密扫描、git diff --check、打包验证。
- [ ] 用户授权的统一提交：仅完成并通过验收的代码推送到dew64025-stack/-，非强制推送；读远端SHA与文件核验。若仍存在核心未完成项，禁止推送并如实说明。
