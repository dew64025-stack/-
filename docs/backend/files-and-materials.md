# 文件与材料

上传先进入私密隔离区。原始字节通过真实 ClamD 扫描、格式校验后才可使用，图片随后重新编码以移除来源元数据。pending、failed、infected 不可下载或交给 AI。文件ID是字符串，大小和版本是数字；所有写入拒绝未知字段及隐式类型转换。

## 配置

环境变量：FILES_S3_ENDPOINT、FILES_S3_ACCESS_KEY、FILES_S3_SECRET_KEY、FILES_S3_BUCKET、FILES_CLAMD_HOST、FILES_CLAMD_PORT。S3 region 默认 us-east-1，其他值通过 app.files.s3.region 配置。缺少存储配置时读写/签名返回503，缺少扫描配置时扫描失败并有限重试，没有生产假clean实现。端点来自服务器配置，禁止HTTP重定向。

bucket必须预先设为私密，关闭匿名读取/目录列举，只给应用所需对象操作权限。生产使用受控网络/TLS；禁止SDK HTTP trace及记录Authorization、完整签名URL。本地联调使用回环地址。

ClamD不公开暴露。连接超时2秒、读取超时30秒、回复最多4KiB；只接受本次stream: OK。FOUND为infected，其他结果/超时/断连为failed。StreamMaxLength应覆盖最大20MiB上传，开启超限告警、病毒库自动更新及过期检查。容器healthy不等于业务流程验收。

上传凭证10分钟有效，随机32字节、仅存HMAC摘要，绑定用户/校园/namespace/用途/文件约束。后台先通过ADMIN会话和CSRF申请意向；二进制统一走/files/uploads/{uploadId}，只接受Authorization: Upload，原子消费一次，重新读取当前用户/用途权限；Bearer不能替代。

材料20MiB、图片5MiB、名单10MiB；头像只接受图片。PDF最多500页，图片先读尺寸、最多2500万像素。DOCX最多1000条entry、展开总量64MiB、单entry20MiB、压缩比100；拒绝重复entry、穿越、加密、宏、外部关系、XML DTD/外部实体。名单只接受UTF-8 CSV和受控表头。

扫描/清理每5秒轮询一次。扫描使用5分钟租约、最多3次尝试、30秒重试间隔和claim token防止旧worker提交。清理失败保留任务并退避重试；需监控长期pending/扫描failed。app.files.scheduling-enabled=false只暂停调度，不能放行未扫描文件。所有对象IO、格式解析、扫描都在数据库事务外。

上传开始前同事务登记孤儿清理；完成登记原子创建attachment和scan job并取消原孤儿任务。重编码候选对象也先登记清理，成功切换后取消候选任务并清理原始隔离对象。材料删除同事务登记清理，不物理删除不可变版本行。

## 共享接口

IdentityFileAccess是唯一生产IdentityFiles实现，校验同校园、所有者、namespace、私密用途和clean；名单有界读取再次授权并核对散列，真实StudentRosterWorker读取clean CSV。AvatarUrls在业务写代理之外生成120秒URL，只保存attachmentId，临时签名不进入users或持久幂等结果。

AttachmentService.owner供业务关联前校验，readCleanOwner提供限量且核对散列的读取。LinkedAttachmentPolicy是后续关联业务读取桥，必须查询真实关联对象/参与人/审核公开状态，单纯同校园不能授予访问。没有业务策略时只有所有者可读；材料、头像、名单不接受这种泛化授权。匿名读取只能由具体公开业务入口显式接入，当前文件路由仍要求身份。

管理员读取名单要file.read，下载还要identity.import并先写敏感访问审计。材料删除检查清单、材料使用记录、成长证明和MaterialRetentionPolicy；后续AI/历史模块引用版本时必须登记保留义务或实现该接口。共享附件在锁定后重新检查其他有效材料引用，避免误清理。

对象所有者与当前权限检查先于外部Spring幂等代理调用，缓存重放也会重新验证。所有短签和临时上传凭证排除在持久幂等缓存之外。

## V9与权限

V1–V8未修改。V9复用V8的async_jobs校园/所有者/访问域/claim token，增加file_attachment_id；未知历史任务不猜测归属。业务账号对file_uploads/file_attachments/file_cleanup_jobs使用SELECT/INSERT/UPDATE，material_versions只用SELECT/INSERT；禁止版本UPDATE/DELETE，不授予file_key_migrations权限。其他关联表保留必要的读写权限，须在独立MySQL验收。

逐条可执行授权统一见根目录 DATABASE.md；这三张文件表与上传、扫描、清理使用同一个主业务连接。已有账号必须先 SHOW GRANTS，在维护窗口审查旧表级、数据库级、全局及角色宽授权；仅追加较窄 GRANT 不会撤销版本 UPDATE/DELETE。按实际账号/host 精确协调撤权，不能直接套用 REVOKE ALL。本次隔离 MySQL 回归覆盖合成文件元数据的登记、状态变更和不可变版本权限，不等于真实 S3/ClamD 上传扫描验收。

新写入仅存加密对象键；HTTP不返回对象键。V9保留旧material_files/material_versions.object_key，不自动补归属/扫描/附件关联，不将旧记录视为新clean附件。

## 受控遗留转换

LegacyFileKeyConverter是独立CLI，不注册为Bean或在启动时执行。先冻结相关历史写入、备份并演练恢复，记录备份编号。只用专用czt_file_key_operator账号：对两张材料表授予SELECT和显式UPDATE(object_key,object_key_encrypted)，对file_key_migrations授予SELECT/INSERT/UPDATE。工具先核对CURRENT_USER、列级UPDATE及审计权限。不能把版本UPDATE授权给业务账号。

在操作进程提供MYSQL_URL、LEGACY_FILES_DB_USER、LEGACY_FILES_DB_PASSWORD及既有APP_ENCRYPTION_KEY/APP_HMAC_KEY，使用backend及依赖的类路径：

```text
java -cp <类路径> com.caizhitong.integration.storage.LegacyFileKeyConverter --backup-confirmed <备份编号> material_files <行ID> convert
java -cp <类路径> com.caizhitong.integration.storage.LegacyFileKeyConverter --backup-confirmed <相同编号> material_files <行ID> restore
```

material_versions用同样方式显式指定。每行短事务内加密、回读解密核对、记审计，最后才清除该行明文；失败全部回滚。restore要求备份编号、当前密文和日志一致，恢复明文并记恢复时间，不改原encrypted字段含义。恢复后再次转换前需人工审查/归档原记录，工具不会覆盖审计。本次仅内存数据库验证，未对真实数据库执行。加密键转换不替代旧文件归属、扫描及版本附件关联的后续迁移。

## 验证范围

默认测试用H2和src/test的外部边界替身/本地协议服务器。LiveFileIntegrationTest只有CZT_LIVE_FILES_TEST=true才运行，从测试进程的CZT_LIVE_REDIS_PASSWORD、CZT_LIVE_S3_ACCESS_KEY、CZT_LIVE_S3_SECRET_KEY读取临时开发凭据，源码不含值。真实测试覆盖生产S3/ClamD/Redis、上传扫描、签名HTTP下载、匿名拒绝、清理及EICAR，SQL仍是H2。真实MySQL V9/最小权限、崩溃恢复和上线病毒库/容量限制另行验收，未连接既有MySQL84。
