# 材智通后端

Java 21 / Spring Boot 4.1.1 模块化单体。当前底座提供统一响应、错误处理、请求编号、敏感字段加密、雪花 ID、Redis opaque 会话、数据库动态权限、签名游标、事务幂等和原子限流；真实登录凭据交换由身份模块接入。

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

Maven 打包时把根目录 `sql/V*__*.sql` 原样映射到 `classpath:db/migration`，当前为 V1～V7，不载入 `demo` 数据。`ddl-auto=validate`、禁止 Flyway clean、禁止自动 baseline。生产只支持 MySQL，生产 JAR 无 H2。

## 后续模块接入约定

控制器只返回明确字段的 DTO，并显式使用 `ApiResponse.ok(data, RequestIdFilter.requestId(request))`；ID/外键 ID 在 DTO 中转十进制字符串，版本、计数和其他数字保持数字。不得返回 JPA 实体。请求 DTO 拒绝未知字段，错误不回显原始输入或内部异常。

`BusinessException(int status, String code, String message, List<ApiFieldError> fieldErrors, boolean retryable)` 的 message 只接收可公开的业务提示。请求编号由服务端生成并放在响应正文和 `X-Request-Id` 头，入站同名头不被信任。

学生端仅接受 STUDENT namespace 的 Bearer 会话；后台仅接受 ADMIN namespace，会话权限每次从数据库读取。公开路径和凭据例外由 `RoutePolicy` 精确声明，未实现公开端点仍404。后台认证入口在真实密码与MFA接入前保持关闭；后台写操作保留CSRF，Cookie使用Secure、HttpOnly、SameSite=Strict。临时二进制上传入口保持关闭，文件模块必须安装独立的上传凭据校验。Actuator受默认拒绝安全链保护。

校园问题详情、互助帖子详情和互助帖子评价只允许精确GET进入公开读取边界；业务服务仍必须执行同校园及公开/所有者可见性检查，未审核、私密、跨校内容对无权者返回404。公开路由匹配不代表对象已公开，其他子路径和写方法不因该策略放开。

普通业务使用 `CurrentActor.require()`，学生业务使用 `requireStudent()`，后台使用 `requirePermission(code)`。这些方法拒绝非active用户；`requireAuthenticatedIdentity()`只供精确的本人处罚、申诉、数据导出/注销/数据请求端点使用，服务仍须校验对象归属。`phoneBound`同时要求已验证手机号和最新未撤销的phone授权；历史verified记录不能代替现行授权。平台跨校操作仍需要额外显式授权范围，不得把客户端campusId当作身份。

业务POST/PUT/DELETE/PATCH必须提供8～128位可打印ASCII的`Idempotency-Key`。过滤器将规范化JSON和查询参数的HMAC摘要绑定到请求，实际路由、方法、namespace、用户、校园一起确定幂等范围。Controller调用外部Spring代理的应用服务，服务写方法标记 `@BusinessWrite(student=true)` 或 `@BusinessWrite(permission="content.review")`；不能使用同类内调用、private/final方法或直接在Controller写数据库来绕过代理。本人权利写端点须标记 `ownRights=true`，仅精确白名单生效。

注解方法返回具体业务DTO（不可返回ApiResponse、泛型容器或认证令牌），DTO的ID为字符串。权限校验在结果重放前执行；如使用`@PreAuthorize`，它的拦截顺序也早于幂等拦截器。对象归属校验必须位于Controller或独立授权检查服务，不能仅放在被重放跳过的方法体里。`IdempotencyExecutor.execute(context,type,authorize,action)`支持显式的事务授权回调。幂等占位、真实业务写入、加密DTO结果使用同一事务；外层响应由Controller重新生成requestId。记录保存24小时，超期会重新执行命令，不替代业务本身的唯一约束。

`@BusinessWrite`仅包实际数据库动作；远程微信换码、AI请求、文件扫描不得放进注解事务。凭据例外不进入持久幂等缓存。Redis会话access900秒、refresh604800秒，刷新轮换两种token并一次性消费旧refresh。Redis失败返回503，不放行。`RateLimiter.check(bucket,max,window)`由调用方按端点与服务端身份选择bucket，不采信客户端限流身份；客户端IP若用于bucket，代理转发头须由部署层严格校验。

`CursorCodec`的scope必须由查询服务从当前校园和规范化过滤条件组成，客户端传来的scope不可直接使用；排序键须覆盖稳定排序的唯一ID。`PageLimits.resolve(null)`默认20，最大100。当前测试使用H2和测试目录的Redis adapter替身，未替代真实MySQL事务/迁移及Redis Lua并发实测。
