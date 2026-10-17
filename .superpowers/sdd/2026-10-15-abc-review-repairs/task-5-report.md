# Task 5 — integration acceptance

完成日期：2026-10-17（Asia/Shanghai）。状态：实施中；最终组合测试、package、字节核验与最终 MD 待补。

## 范围与环境

- cwd `C:/Users/19683/source/repos/caizhitong-abc-repairs`；branch `fix/abc-integration-20261015`；起点 `204c7b8f63419e4c2135d15c0d194d2d3ed87263`。不改原工作区、用户数据库、业务生产源码或冻结迁移。
- process-only `JAVA_HOME=C:/Users/19683/AppData/Local/caizhitong-devtools/jdk-21.0.12.1+1`，Maven `C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd`，`MAVEN_OPTS=-Duser.language=en -Duser.country=US`。
- 容器 `czt-abc-review-20261015`，label `com.caizhitong.plan=abc-review-repairs-20261015`，运行中，配置与运行 binding 均检查为唯一 127.0.0.1 ephemeral 端口，当前 `64150`；拒绝 3306/13306。只新建 czt_abc_c_*/czt_abc_b_* 合成库及随机账号；owner 只负责 provision，C 使用 schema migrator 执行 V1–V19、收紧后保留三表 SELECT/两过程精确 EXECUTE。
- runner 显式 `CZT_MYSQL_FILES_TEST=false`、`CZT_LIVE_FILES_TEST=false`；focused 时 B=false，Combined 时 B/C=true 且互相独立 schema。root/password/容器 Env/账号 SQL 均不输出。
- 普通 exec 被 Windows carveout 拒绝启动；后续命令均用 require_escalated 的精确授权操作。未执行拒绝动作、用户账号授权或 Docker 全局变更。

## TDD 与聚焦验证

新增测试捕获的具体破坏：文档授权放开版本修改、缺文件登记权限、缺借用双方事件权限。此次只修文档授权，无新增生产实现。

精确命令（仓库根目录；runner 内 Maven 为 `mvn -B -ntp -f backend/pom.xml "-Dtest=$Tests" test`）：

```powershell
./backend/run-abc-acceptance.ps1 -Tests 'MysqlCommunityPrivacyTest#minimumAppFileMetadataSupportsUploadScanCleanupAndImmutableVersions+minimumAppCannotMutateVersionsAuditEventsOrReadConverterState+minimumAppBorrowRequiresBothPeopleForHandoverAndReturn'
```

- 初始夹具错误：11:05:18 编译失败，lambda 内辅助函数声明 checked Exception，4 errors，无测试运行；修为 valueToTree。不是行为 RED。初始凭据断言列名亦在执行该分支前按 V8 实际定义修正，无假 RED。
- RED，11:06:11，exit1，`task-5-red.log`：3 / 1 failure / 2 errors / 0 skip。`material_versions UPDATE must be denied` 实际没有异常；原生 SQL 对 file_uploads 与 borrow_events 的 INSERT 返回 command denied。schema `czt_abc_c_b5289eb4022445e49039fd9185fada3c`，Flyway 19 migrations applied。授权错误是本次真实部署缺口，不是 SQL 拼写错误。
- GREEN，11:07:25，exit0，`task-5-green.log`：3 / 0 failure / 0 error / 0 skip，BUILD SUCCESS。schema `czt_abc_c_27a14c5592054ea1b9a67517283abd0f`，19 migrations applied。最小账号成功登记 upload/cleanup/attachment，更新三表状态，INSERT/SELECT material_versions；禁止其 UPDATE/DELETE 与 converter 表读取；borrow 从 pending→confirmed→双方 handover→双方 return，同一人重复确认不能替代对方。
- 新增 sourceName 字符串/空值、owner 错 proof/跨校断言属于补充 GREEN 覆盖，不制造历史 RED；验证待下面聚焦结果补入。

## 实施内容

- DATABASE.md：版本仅 SELECT/INSERT，文件三表 SELECT/INSERT/UPDATE，borrow_events SELECT/INSERT；补充旧权限维护窗口核对，覆盖旧表级、schema/global wildcard、角色继承，禁止盲目 REVOKE ALL。
- files-and-materials.md 与根权限文档统一，同主业务数据源；原生元数据证明不宣称真实存储扫描。
- MysqlCommunityPrivacyTest：3 项新增文件/权限矩阵/真实借用测试，补强真实 ownership 跨校与错 proof；保持独立调查与业务事务测试。
- CommunityHttpTest：sourceName JSON 非空为 string、缺提供器时 null 的补充断言。
- run-abc-acceptance.ps1：验证容器标签与唯一动态回环 binding，合成库保护；可一次启用 B+C，旧文件/live 始终禁用；退出码沿用 Maven，finally 清临时密码。

## 首个一致性检查点（11:10）

- `./backend/run-abc-acceptance.ps1 -Tests 'MysqlCommunityPrivacyTest,CommunityHttpTest'`，最终 `task-5-focused-green.log`，11:10:18，exit0，BUILD SUCCESS，46 / 0 failure / 0 error / 0 skip（18 原生 C + 28 H2 社区）。包含 sourceName 字符串/null、错 proof/跨校受限 owner、调查配置缺失503及独立审计失败关闭。
- 中间 `task-5-focused.log` 11:08:37 exit1，46 / 1 failure / 0 error：补充断言错误假设校园2列表必为空，而其他用例确已创建该校园合成帖子。已改为当前目标 ID 不得跨校出现；这是假设错误，不是生产缺陷，也不宣称行为 RED。后续完整组合前没有生产代码修改。
- 迁移初始审计工具误以为原始 main 包含 V1–V15；实际 main 缺 V10/V11、原始 feature 缺 V12/V13，按控制器 Ruling 改为每个文件比较所有存在的原始头 blob，加合并347adc0的全部15项；V16–V19对首次新增提交。另正常 Windows checkout i/lf w/crlf，Git blob 与 checkout SHA256分列，不改迁移来消除换行。

## 已确认边界与待控制器事项

真实 MySQL/Connector-J 与迁移权限是原生验证；会话/reauth/存储及 AI 外部边界仍含 test-only 替身。没有真实 DeepSeek、公网 TLS、微信、S3/ClamD、Redis 容量/故障/生产验收承诺。

Task3 generic linked-attachment images_json 全校园扫描性能 minor 必须交最终全分支 reviewer 分诊。已知 migration integer display width、编译 deprecated 与预期拒绝日志须保留，不能声称 output pristine。Task4 sourceName minor 由本次补充断言闭合。独立 Task5 门禁、全分支审查（baseline 6e87d5172b9aca6e9885d483be18493ed399a11f）、最终 fetch/merge、普通 atomic 双目标推送与清理属于控制器，当前均未执行。

## 自查与提交

仅测试、文档、runner 与唯一授权 archive hygiene；未新增依赖/路由/生产 bypass，无迁移改写。提交及最终 proof 后源码变更情况、哈希、包构建、扫描与每套 skip 将在完成时追加。
