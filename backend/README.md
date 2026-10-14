# 材智通后端

Java 21 / Spring Boot 4.1.1 模块化单体。当前提供统一响应、安全会话、动态权限、事务幂等和身份模块：服务器微信换码、手机号授权、人工学生认证，以及后台密码挑战、TOTP、角色与异步名单导入。微信真实服务联调和文件依赖接通尚未验收。

## 构建与测试

安装 Java 21 和 Maven 3.9 后，在仓库根目录运行：

```powershell
mvn -f backend/pom.xml test
mvn -f backend/pom.xml package
```

测试专用 H2 和固定测试密钥位于 `src/test`，不会进入生产 JAR。测试 profile 禁用 MySQL Flyway 迁移；H2 测试不能替代真实 MySQL 迁移与权限验收。

## 运行配置

部署前依照根目录 `DATABASE.md` 建库和配置逐表权限。业务与迁移账号必须不同：业务账号使用 `MYSQL_USER` / `MYSQL_PASSWORD`；迁移账号使用 `FLYWAY_USER` / `FLYWAY_PASSWORD`。`MYSQL_URL` 可覆盖默认本机连接；连接会将 MySQL 会话时区固定为 UTC，Hibernate 也按 UTC 保存业务时间。

Redis 使用 `REDIS_HOST`、`REDIS_PORT`、可选 `REDIS_USER` 以及必需 `REDIS_PASSWORD`（无密码本地实例应显式设置空值）。`SNOWFLAKE_WORKER_ID` 必需，每个并行运行实例取不同的 0～1023 编号。重启同一 worker 时应确保时间已超过上次 ID 时间；同毫秒超过 4096 个 ID 会等待时钟进入下一毫秒，最多 100ms，时钟停滞或回拨会明确拒绝，不生成重复 ID。

`APP_ENCRYPTION_KEY` 是 Base64 编码的 32 字节 AES 密钥；`APP_HMAC_KEY` 是独立生成的至少 32 字节 HMAC 密钥，禁止复用。密钥通过部署环境/密钥管理注入，不写进源码，不在缺失时生成随机生产密钥。密文格式为 `version(1 byte) + nonce(12 bytes) + ciphertext + tag(16 bytes)`，版本为 1，用途作为 GCM AAD；检索摘要为带用途和长度分隔的 HMAC-SHA256。密钥轮换须另行制定数据迁移方案，不能直接换密钥后继续读取旧密文。

```powershell
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Maven 打包时把根目录 `sql/V*__*.sql` 原样映射到 `classpath:db/migration`，当前为 V1～V8，不载入 `demo` 数据。`ddl-auto=validate`、禁止 Flyway clean、禁止自动 baseline。生产只支持 MySQL，生产 JAR 无 H2。

## 后续模块接入约定

控制器只返回明确字段的 DTO，并显式使用 `ApiResponse.ok(data, RequestIdFilter.requestId(request))`；ID/外键 ID 在 DTO 中转十进制字符串，版本、计数和其他数字保持数字。不得返回 JPA 实体。请求 DTO 拒绝未知字段，错误不回显原始输入或内部异常。

`BusinessException(int status, String code, String message, List<ApiFieldError> fieldErrors, boolean retryable)` 的 message 只接收可公开的业务提示。请求编号由服务端生成并放在响应正文和 `X-Request-Id` 头，入站同名头不被信任。

学生端仅接受 STUDENT namespace 的 Bearer 会话；后台仅接受 ADMIN namespace，会话权限每次从数据库读取。公开路径和凭据例外由 `RoutePolicy` 精确声明，未实现公开端点仍404。后台必须先密码挑战再TOTP；后台写操作保留CSRF，Cookie使用Secure、HttpOnly、SameSite=Strict。临时二进制上传入口保持关闭，文件模块必须安装独立的上传凭据校验。Actuator受默认拒绝安全链保护。

校园问题详情、互助帖子详情和互助帖子评价只允许精确GET进入公开读取边界；业务服务仍必须执行同校园及公开/所有者可见性检查，未审核、私密、跨校内容对无权者返回404。公开路由匹配不代表对象已公开，其他子路径和写方法不因该策略放开。

普通业务使用 `CurrentActor.require()`，学生业务使用 `requireStudent()`，后台使用 `requirePermission(code)`。这些方法拒绝非active用户；`requireAuthenticatedIdentity()`只供精确的本人处罚、申诉、数据导出/注销/数据请求端点使用，服务仍须校验对象归属。`phoneBound`同时要求已验证手机号和最新未撤销的phone授权；历史verified记录不能代替现行授权。平台跨校操作仍需要额外显式授权范围，不得把客户端campusId当作身份。

业务POST/PUT/DELETE/PATCH必须提供8～128位可打印ASCII的`Idempotency-Key`。过滤器将规范化JSON和查询参数的HMAC摘要绑定到请求，实际路由、方法、namespace、用户、校园一起确定幂等范围。Controller调用外部Spring代理的应用服务，服务写方法标记 `@BusinessWrite(student=true)` 或 `@BusinessWrite(permission="content.review")`；不能使用同类内调用、private/final方法或直接在Controller写数据库来绕过代理。本人权利写端点须标记 `ownRights=true`，仅精确白名单生效。

注解方法返回具体业务DTO（不可返回ApiResponse、泛型容器或认证令牌），DTO的ID为字符串。权限校验在结果重放前执行；如使用`@PreAuthorize`，它的拦截顺序也早于幂等拦截器。对象归属校验必须位于Controller或独立授权检查服务，不能仅放在被重放跳过的方法体里。`IdempotencyExecutor.execute(context,type,authorize,action)`支持显式的事务授权回调。幂等占位、真实业务写入、加密DTO结果使用同一事务；外层响应由Controller重新生成requestId。记录保存24小时，超期会重新执行命令，不替代业务本身的唯一约束。

`@BusinessWrite`仅包实际数据库动作；远程微信换码、AI请求、文件扫描不得放进注解事务。凭据例外不进入持久幂等缓存。Redis会话access900秒、refresh604800秒，刷新轮换两种token并一次性消费旧refresh。Redis失败返回503，不放行。`RateLimiter.check(bucket,max,window)`由调用方按端点与服务端身份选择bucket，不采信客户端限流身份；客户端IP若用于bucket，代理转发头须由部署层严格校验。

`CursorCodec`的scope必须由查询服务从当前校园和规范化过滤条件组成，客户端传来的scope不可直接使用；排序键须覆盖稳定排序的唯一ID。`PageLimits.resolve(null)`默认20，最大100。当前测试使用H2和测试目录的Redis adapter替身，未替代真实MySQL事务/迁移及Redis Lua并发实测。

## 身份模块运行与交接

微信使用部署环境中的 `WECHAT_APP_ID` / `WECHAT_APP_SECRET`。未配置时明确返回503；没有生产fake登录开关。服务器请求固定为微信HTTPS域名，连接2秒、整体请求8秒、响应256KiB上限，错误不返回上游URL或秘密。手机号与学号检索摘要分别使用 `phone` / `student` 用途，数据库CHAR(64)保存32字节HMAC的hex；密文使用相同用途。手机号检索值规范化为 `+countryCode+purePhoneNumber`。

V8需要MySQL 8.0.16以上；增加唯一约束前先检查历史多条有效认证/手机号，冲突直接失败，不能删历史或自动repair。微信app_id/openid改为utf8mb4_0900_bin，H2的大小写测试不等于生产collation验收。迁移账号需要 `CREATE TEMPORARY TABLES`；正常业务账号仅能读取管理员凭据并更新 `last_totp_step`，不能创建管理员或修改密码/MFA种子。

管理员初始化只有 `com.caizhitong.admin.identity.AdminBootstrap` CLI，没有HTTP注册。参数依次为显式operatorId、既有userId、campusId、roleCode；operator与目标用户必须同属显式指定的校园。环境必须提供 `MYSQL_URL`、`ADMIN_BOOTSTRAP_DB_USER`、`ADMIN_BOOTSTRAP_DB_PASSWORD`、`ADMIN_BOOTSTRAP_USERNAME`、`ADMIN_BOOTSTRAP_PASSWORD`、`ADMIN_BOOTSTRAP_MFA_SEED`、已有APP密钥及worker编号。使用受控的独立bootstrap数据库账号，至少授予users/roles读取、user_roles读取/插入及已有id的更新、admin_credentials插入、admin_audit_logs追加权限；初始化后回收该账号。密码为12字符以上且UTF-8不超过72字节，MFA种子由操作员安全生成并交付验证器，CLI不会生成默认秘密或打印秘密。运行时类路径需包含编译后的backend类及其依赖。

后台先GET `/api/v1/admin/auth/csrf`，取正文token配合安全Cookie，在写请求的 `X-ADMIN-CSRF` 提交。密码成功只给五分钟挑战；MFA成功才签发会话。JSON中的refreshToken始终为null，真实值仅通过 `ADMIN-REFRESH` 安全Cookie交付。refresh与学生logout由独立刷新凭据判断，不因附带过期access而抢先拒绝；普通业务及admin logout/reauth仍要求有效Bearer。reauth五分钟且绑定管理员和校园，DELETE撤权通过 `X-Reauth-Token` 而不是URL传令牌。

`IdentityFiles`只有 `validateAvatar(actor,id)`、`validateRoster(actor,id)` 与 `roster(actor,id,maxBytes)` 三个边界。Task4必须校验同校园、所有者、私密用途和clean扫描状态，并对读取再次校验；当前默认实现全部503。头像只保存attachmentId，不写入签名URL；User业务幂等DTO中的avatarUrl为null，响应在业务代理外补现有URL。Task4仍须接入真实头像读取URL解析，在代理事务外生成并验证，不能把临时签名写回users或幂等记录。

名单导入先以202登记pending任务与同事务审计。`async_jobs`已由V8新增campus_id、owner_user_id、access_domain、claim_token，Task4应复用；旧任务的未知归属保留NULL。`StudentRosterWorker.run()`每次领取最多一条学生名单任务，使用五分钟租约、三次尝试和30秒重试间隔；文件读取和UTF-8 CSV解析在事务外，有效行、脱敏摘要与succeeded状态在十秒事务中原子写入。生产每五秒轮询，测试仅关闭自动调度并手动驱动真实worker。CSV四列严格为studentNumber/college/grade/status，10MiB上限，status仅active/inactive；名单只提供人工核验线索，不授予或撤销学生权限。

当前未验收真实微信、Redis、MySQL V8迁移/最小授权、扫描文件存储或学校接口。学校认证提供器明确未配置，不开放猜测的认证回调。
