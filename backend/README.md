# 材智通后端

Java 21 / Spring Boot 4.1.1 模块化单体。当前底座提供统一响应、错误处理、请求编号、敏感字段加密和雪花 ID；业务接口和会话认证由后续模块实现。

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

Maven 打包时把根目录 `sql/V1__*.sql`～`V6__*.sql` 原样映射到 `classpath:db/migration`，不载入 `demo` 数据。`ddl-auto=validate`、禁止 Flyway clean、禁止自动 baseline。生产只支持 MySQL，生产 JAR 无 H2。

## 后续模块接入约定

控制器只返回明确字段的 DTO，并显式使用 `ApiResponse.ok(data, RequestIdFilter.requestId(request))`；ID/外键 ID 在 DTO 中转十进制字符串，版本、计数和其他数字保持数字。不得返回 JPA 实体。请求 DTO 拒绝未知字段，错误不回显原始输入或内部异常。

`BusinessException(int status, String code, String message, List<ApiFieldError> fieldErrors, boolean retryable)` 的 message 只接收可公开的业务提示。请求编号由服务端生成并放在响应正文和 `X-Request-Id` 头，入站同名头不被信任。

基础安全链拒绝全部路由，不启用默认账号或登录页面。后续会话模块必须显式配置公开路由和认证规则后才可开放接口；Actuator 也受安全链保护。
