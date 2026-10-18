# A/B/C 审查修复交付记录

## 2026-10-18 最终审查统一修复波（进行中）

最终审查确认 I1–I5 五项问题；本节随真实验证更新，下面原 Task5 的 289 项和旧包散列是历史证据，不能代表本波源码。当前已修复 I1/I5；借用RR、清单并发与撤回再发布仍在处理，尚未交付发布。

I1：固定已验证 IP 的 TCP 连接，再用原逻辑主机创建 layered SSLSocket；JSSE 标准 HTTPS endpoint identification 同时核对证书链/主机，保留 SNI、绝对握手/读取截止、逐跳 SSRF、传输与解压上限。I5：只允许合格 native global IPv6，保守拒绝 site-local、multicast、非2000::/3、IPv4转换范围、2001::/23特殊段及文档地址，保留普通公网 IPv6。

cwd 与 JDK/Maven 环境同下。2026-10-18 16:46:18，`mvn -B -ntp -f backend/pom.xml '-Dtest=SafeUrlReaderTlsTest,SafeUrlReaderTest#nonPublicIpv6IsRejectedBeforeConnectionAndPublicIpv6RemainsUsable' test` exit1：4项/1失败/1错误/0跳过；可信匹配 TLS 在旧生产默认 hostname 路径触发 TLS_HOST，`fec0::1` 校验意外通过。运行期 keytool 创建临时密钥/PKCS12，真实 loopback443 JSSE 服务；测试仅反射提供已验证 Target，使用 null verifier 的生产校验路径，无宽松 verifier、密钥提交或新依赖；这不代表公网 TLS/DNS 验收。

2026-10-18 16:51:10，相同环境执行 `mvn -B -ntp -f backend/pom.xml '-Dtest=SafeUrlReaderTlsTest,SafeUrlReaderTest' test` exit0，15项全部通过：可信匹配正文完整成功、可信错误主机拒绝、系统信任不认可的临时证书拒绝；既有固定地址、SSRF、重定向、解码和握手绝对截止回归保留。已明确延期 M1 清单租约反向锁序与通用附件 JSON 扫描性能，完整 Ruling18–20/cost 会在本波最终证据中补齐。

完成日期：2026-10-17（Asia/Shanghai）。本记录对应隔离分支 `fix/abc-integration-20261015`；原始 main `6e87d5172b9aca6e9885d483be18493ed399a11f`、feature/opportunity `6e560629a7b9f4be2b33860ad90e6b16268aedee`，合并基线 `347adc0`。修复范围是已有 A/B/C 行为；206 条计划接口没有全部实现，未完成模块仍见 [implementation-status.md](implementation-status.md)。

本 Task5 已完成精确授权原生证明、289项完整B+C组合与包核验。独立 Task5 门禁、全分支最终审查、发布前 fetch/integrate 与 atomic 双分支普通推送由控制器完成，此记录不把它们预先标为通过。

## 修复内容与落点

| 范围 | 解决的问题 | 主要文件/迁移 |
| --- | --- | --- |
| A 材料预检 | 按选定不可变版本读取其附件/散列/元信息，核对同校所有者、clean 和当前授权；任务过期/旧结果不能覆盖新版本；规则进入快照 | material/precheck 下 VersionPrecheckFiles、两个 Worker、规则；ChecklistService；V16 |
| A 通知和机会 | 传输/压缩有界解码，TLS/重定向绝对时限；AI 整体调用期限与错误码、证据片段；人工复核保留并要求明确结构化确认；解析恢复统一锁顺序 | SafeUrlReader、DeepSeekClient、NoticeParseSchema/Worker、AdminNoticeService、AdminOpportunityService、OpportunityInputs；V16 |
| B 资源与预约 | UNSIGNED/日期类型；公开可见性；显式 reservationMode，兼容旧占位；manual pending 到期释放、重叠防超卖、审批当前读；签到时间窗与一次凭证 | resource 包、MysqlServiceRegressionTest；V17 |
| B 问题与工单 | 私密/审核状态当前锁定行授权，投票/关注实时计数；地图范围/筛选；工单审核到接单状态、原因事件与处理人；平坦来源/附件 DTO | issue/workorder 包、ServiceSources、ServiceHttpTest；V17 |
| C 匿名与调查 | app 不读身份映射；受控 owner 过程；独立 investigator、每次新 reauth 与提交审计、幂等仅缓存 receipt，失败关闭；异常日志脱敏 | community、governance、ApiExceptionHandler、调查 gateway；V18 |
| C 附件隐私 | 只有真实已关联可读对象的 clean 图片可生成预览；材料/头像/名单不泛化共享；业务对象权限用于图片，普通 file 路由保留独立权限 | BusinessLinkedAttachmentPolicy、AttachmentService、业务 DTO 与隐私测试 |
| C 互助与团队 | 平坦字段/来源，稳定岗位ID与总容量当前读、历史角色手动修复；失物双方事件、证据保留；regionId 明确400；借用原模型保持 | MutualAidService/Views、TeamService/Views、CommunityHttpTest；V19 |
| 最终授权与卫生 | 文件三表补齐、版本不可变、borrow_events 追加读取、最小账号原生正反证明；sourceName 字符串/null；移除唯一生成归档 | DATABASE.md、files-and-materials.md、MysqlCommunityPrivacyTest、run-abc-acceptance.ps1、根 .gitignore |

A/B/C 各任务已经过独立任务门禁。最终全分支仍须从原 main 审查全部合并与修复，不能只审查最后一提交。没有更换 Java21 / Spring Boot4.1.1 / Maven / JDBC / MySQL / Redis、依赖、路由或既有命名，没有测试专用生产旁路。

## 本次可复现验证

cwd：`C:/Users/19683/source/repos/caizhitong-abc-repairs`。进程内 JDK：`C:/Users/19683/AppData/Local/caizhitong-devtools/jdk-21.0.12.1+1`；Maven：`C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd`；`MAVEN_OPTS=-Duser.language=en -Duser.country=US`。

`backend/run-abc-acceptance.ps1` 每次核验独占容器 `czt-abc-review-20261015` 的 label `com.caizhitong.plan=abc-review-repairs-20261015`、配置及运行时唯一127.0.0.1动态 binding；本次为64150，拒绝3306/13306。只新建 czt_abc_b_*/czt_abc_c_* 合成库；未连接用户MySQL84或原13306。临时随机密码仅在进程内传递并清除，不输出容器Env或账号SQL。

```powershell
./backend/run-abc-acceptance.ps1 -Tests 'MysqlCommunityPrivacyTest#minimumAppFileMetadataSupportsUploadScanCleanupAndImmutableVersions+minimumAppCannotMutateVersionsAuditEventsOrReadConverterState+minimumAppBorrowRequiresBothPeopleForHandoverAndReturn'
./backend/run-abc-acceptance.ps1 -Tests 'MysqlCommunityPrivacyTest,CommunityHttpTest'
./backend/run-abc-acceptance.ps1 -Combined -Tests '*'
```

第一条在文档修复前真实 RED：2026-10-17 11:06:11 exit1，3项/1失败/2错误，版本UPDATE未被拒绝、file_uploads INSERT denied、borrow_events INSERT denied；修复后11:07:25 exit0，3/0/0/0。新增断言只是已有行为的补充 GREEN，没有制造历史 RED。另有初始测试辅助方法 checked exception 编译错误，以及错误假定校园2无其他合成数据导致的中间断言失败；两者仅修测试，不计生产行为 RED。

第二条最终11:10:18 exit0、BUILD SUCCESS，46项全部通过（18原生C+28H2社区），0失败/0错误/0跳过。原生C由 schema 迁移账号执行 V1–V19，随后收紧 migrator 并保留 V18 definer 的三表SELECT与两过程EXECUTE。B原生既有套件使用自己的合成库与root执行V1–V16→旧占位夹具→V17；B证明并发SQL/旧数据兼容，不充当最小账号证明。最小账号证明来自C独立业务/迁移/调查账号。

## 最小权限与部署动作

| 账号/对象 | 允许与已验证 | 拒绝与边界 |
| --- | --- | --- |
| app / material_versions | INSERT/SELECT合成历史版本 | UPDATE/DELETE拒绝 |
| app / file_uploads、file_attachments、file_cleanup_jobs | SELECT/INSERT/UPDATE；登记、clean元数据、取消孤儿任务 | DELETE拒绝；仅SQL元数据，非真实S3/ClamD |
| app / borrow_events | SELECT/INSERT；借用批准后双人handover、双人return，同人重复不替代对方 | UPDATE/DELETE拒绝，原事件模型不变 |
| app / campus_issue_events、lost_found_events | SELECT/INSERT；真实事件追加/当前读/双人流程 | UPDATE/DELETE拒绝 |
| app / team_open_roles | SELECT/INSERT/UPDATE；岗位申请/审批/离开、RR最后名额及历史总容量 | DELETE拒绝 |
| app / pseudo_identities | INSERT；两条明确owner过程EXECUTE，真作者维护/列表 | 直接SELECT拒绝、错proof/跨校/他人不可维护 |
| app / security_investigations、file_key_migrations | 无权限 | SELECT拒绝 |
| app / admin_audit_logs、admin_credentials | 审计SELECT/INSERT；凭据SELECT和UPDATE(last_totp_step) | 审计UPDATE/DELETE、凭据DELETE及MFA列UPDATE拒绝 |
| investigator | 必要映射/校园列SELECT、security_investigations SELECT/INSERT；每次披露提交审计 | users读取/审计修改拒绝；缺配置503、审计失败503，主业务连接不被替换 |
| durable migrator | schema迁移所需DDL/DML与routine权限；V18 DEFINER必须保留 | 不要删除账号或把密码用于app；无需全局SUPER/root definer |

部署前备份并在副本演练；按顺序追加执行 V16、V17、V18、V19，不重写V1–V15、不Flyway repair绕过。V17有歧义的旧预约保持原占位并booking_blocked，管理员核对容量/模式后修复；V19历史成员和申请不伪造岗位、失物确认不倒填。旧团队须由队长按version维护岗位、拒绝旧null-role pending，再由申请者重新申请，历史保留。

实际授权以 [DATABASE.md](../../DATABASE.md) 的逐条白名单为准。上传/扫描/清理与业务同主数据源。新GRANT不会撤销旧权限；DBA须在维护窗口用SHOW GRANTS审查准确账号/host、表级UPDATE/DELETE、schema/global wildcard及角色继承，形成精确撤权变更单、复核有效权限并测试拒绝，再声明版本不可变。不能盲目REVOKE ALL，不删除definer。本次只改新合成账号，未动用户库。

调查需单独注入 INVESTIGATION_DB_URL/USER/PASSWORD，用户名与业务不同；每次披露均需新 X-Reauth-Token、权限/校园/举报关联及独立审计。APP_ENCRYPTION_KEY/HMAC、微信、DeepSeek、对象存储、ClamD等仍由外部秘密配置注入。

## 归档与扫描

只删除根 tracked `CONNECTION.zip`：91,292,135字节、573entries、1jar、374个内部.superpowers/target/.git路径、119Java条目。检查仅元数据。根ignore仅新增 `/CONNECTION.zip`。可从Git blob `ca6e65d142118ea9b31f84b9b53f82a2217ee7c7` 或原feature提交恢复，全部历史保留，其他原型/源码/文档未删除。

tracked配置扫描仅报脱敏结论：2个yml/properties等配置、0个候选，未发现确认泄露。此有限模式扫描不保证全历史或任意格式无秘密；未打印敏感值，未执行真实凭据轮换。原application.yml的APP_SECRET占位符不视为泄露。

## 验证边界与审查遗留

真实部分：隔离MySQL8.4.11、Connector/J原生UNSIGNED结果、迁移权限/routine definer、数据库授权拒绝、RR锁/并发、独立调查审计。H2 HTTP/会话/限流/reauth仓库、对象存储和AI协议替身/本地HTTP/TLS端点仍是隔离测试；不能算真实Redis、S3/ClamD、DeepSeek付费质量、公网TLS成功链路、微信/学校接口、生产压力/备份恢复验收。官方预约提供器未配置时失败关闭。未实现消息WebSocket、首页成长隐私注销等后续模块。

Task4的sourceName minor已以字符串/null补充断言覆盖。Task3 BusinessLinkedAttachmentPolicy 同校园images_json加载再筛选的性能minor交最终whole-branch reviewer分诊；没有擅自改查询或索引。已知历史迁移 integer-display-width、编译deprecated和预期负例日志继续披露，输出不称warning-free/pristine。

## 最终组合、包与字节证明

2026-10-17 11:13:25，`./backend/run-abc-acceptance.ps1 -Combined -Tests '*'` exit0，BUILD SUCCESS，总289项：286通过、0失败、0错误、3跳过。38份新鲜XML写入时间11:11:18.0445939–11:13:25.1840724+08:00。B原生9/9和C原生18/18在同一次完整运行中同时启用；C schema `czt_abc_c_b39705e6e3d84c6992140ad7f8eaa5f8`，B schema `czt_abc_b_91c45d5b448b4affad0cf737183127d1`。旧文件MySQL2项（硬编码旧13306）和LiveFileIntegration1项（需独立外部服务）显式禁用并跳过，不能算通过。默认不启用B/C时会另跳过这27项原生用例，不能把默认运行误报为本次组合证明。

```powershell
$env:JAVA_HOME='C:/Users/19683/AppData/Local/caizhitong-devtools/jdk-21.0.12.1+1'
$env:MAVEN_OPTS='-Duser.language=en -Duser.country=US'
& 'C:/Users/19683/AppData/Local/caizhitong-devtools/apache-maven-3.9.16/bin/mvn.cmd' -B -ntp -f backend/pom.xml -DskipTests package
```

11:13:51 exit0、BUILD SUCCESS；Surefire明确Tests are skipped，未重复执行未改测试。JAR `backend/target/backend-0.0.1-SNAPSHOT.jar` SHA256 `a4f28ac5a292b1211966e26a621880a0fd79fe2b3798b305937383b9a1509b29`。19份打包迁移与检出源文件SHA256逐一一致，0个application-test/H2/test-classes/.superpowers/CONNECTION.zip测试内部产物。

迁移审计命令 `./.superpowers/sdd/2026-10-15-abc-review-repairs/task-5-audit.ps1 -Package` exit0；该脚本为本次本地ignored scratch（检查点9d20202保留Git恢复），正式结果全部记录在这里。历史15份比较26个实际存在的原始头blob，并全部对合并347adc0核对。原main缺V10/V11，原feature缺V12/V13，不能假设原main单独有15份。V16–V19分别与首次新增提交比较。全部Git保存的blob一致、正常Git过滤后的工作树内容一致；Windows检出i/lf、w/crlf，下面checkoutSHA256是实际检出/打包字节，不声称等于Git LF原始字节。未编辑冻结迁移、未repair或修改迁移历史。

| 迁移 | Git blob（与基线完全一致） | 实际检出/JAR SHA256 |
| --- | --- | --- |
| V1__campus_and_identity.sql | `2829be9b2b4a0087233132cf06e211ce6030c28e` | `251b4721f9ba6cc05828fb936b25131240aedafe17d9867e15f6f0a205850f4f` |
| V2__opportunity_and_material.sql | `c7ee7f9413adcdc63d704c31fc13743aa5bbcd60` | `01f96e4a4c427160ad071c7dbb9c771c463d5a07b0760afb4c3e5c222f160431` |
| V3__service_and_reservation.sql | `a64190ba2f19236560f6e1499c355e3411437a16` | `5b7593b3cf7ce1de3460d9763d6d8ffbc0e2cdc05dfd2cf73176ccdec7326460` |
| V4__mutual_aid_and_community.sql | `c32666d42a56fd6abb3b004b5ba9893b9f66c367` | `006641ffb1f475178e259e5f7da23522044bea49199a119664d05ff6a6b5b104` |
| V5__messaging_governance_integration.sql | `2a47704302a6f4b46475dc5c5043f6fde8370cda` | `bd131aac7ee433a1279d54571d5e77629f61ae9bed9f7e457b0712257d9f8d96` |
| V6__supplementary_models.sql | `e24da69d40f3dd78cf022ca812ddfcab5cc28a5c` | `490f206359f3a5b9648935285fa39d999eab9020cbedaca182cfbd6f71b1eb71` |
| V7__runtime_coordination.sql | `aed158970f43602fe31a629101bd7d355c717f4f` | `459706187d1aa873cbb9bd4047815e5303baccd0b6b443c5edf23d36d4fe740b` |
| V8__identity_runtime.sql | `691c5d5461100460ff8c392b84a4ce06d69c991e` | `c01e9ac34b05ff07156f342eb651d8d86225c53b235648aaac8277165b6b56b5` |
| V9__files_and_jobs.sql | `6c79e767491d32b5cf40705fc2d3fe8ef9b8fbaf` | `6699d0868484d44424da030e7ec23e748fd39033583ea750ded15ac11a45c8d0` |
| V10__opportunity_runtime.sql | `a1c1703bb05b2821ffa7c9583dd299f384ddf9e3` | `2fdb90e8855696984c80f1661419079d873a31cc1677d1f07986fd37c2a25909` |
| V11__checklist_precheck_runtime.sql | `b3a92545a231bc42b60c51a7e0693c84fafa0ace` | `84f52f443251333b5ab10ad292d9ccb289c0a981d05df6a681a4a9bf258bc420` |
| V12__resource_reservation_runtime.sql | `93925c3e04c0aac183da618a8752c97319c6987d` | `fda92898e39a291ee67dd73e58c69a60c511e4cc652789bf0bae4ec8b81a320f` |
| V13__workorder_issue_runtime.sql | `4767d33c74818bad28448f5b76e3ba8b502d9cc7` | `a5370e0193ca8c221f21d7a91be44df9dc4a61b0ea1f77382a25965ce0b785dc` |
| V14__mutual_aid_ratings.sql | `91b4eceff6a3f94b90eaa23f5ab7a50b40888496` | `bb21740ddfebf3c7a5fad1ef59f9a9c1d112ec214cc850519d8ac94a4d6334a2` |
| V15__community_governance_runtime.sql | `30da0b06f0244192bd3b16c6b09cddb0ad861def` | `b08c0e6877dd4dd246b72c8cb9a3d93fc83f68ea05dda30bd35886ce66097a12` |
| V16__opportunity_review_repairs.sql | `39a86ddab0116d58e23570c7c577ba115123b6ed` | `0f50e45a3985b1f63fff22b5a270912eed0179d2e82dda2f5372bff52eeef962` |
| V17__service_review_repairs.sql | `e37aefefed8746be2e434ab69cccdf281be26396` | `744fc499944775eeea095e02a2b322c5a07cf513fbcaaa23c7a51b03a1a5459a` |
| V18__community_privacy_repairs.sql | `cbf5c8e7ef14f3295cdc617ef5a1852ec5bd1952` | `432cf84f1c513350ceb44bcf4b01a5fa70a70129eb6864af80b73feb0423f640` |
| V19__mutual_team_review_repairs.sql | `3c728ad61e082cc6f082069ddb0658ec19361102` | `9e819b7c5ef27c9e9acfb1e75b83ea3f6867b049ec6be14b4767cb524a5e568d` |

V16–V19首次新增基线依次为：`V16__opportunity_review_repairs.sql: 303bb617d355ab5df2be558dc67ed38d36e8abde`、`V17__service_review_repairs.sql: c52fc6161d63013b886461b03f09827928548262`、`V18__community_privacy_repairs.sql: 4fee18c01a53d41932b96688103ebd6d392e7ba0`、`V19__mutual_team_review_repairs.sql: 3bffbc890f04bb799c89490f274bfa4dd058436e`。

逐套结果（tests/failures/errors/skips）：

| Suite | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| ai.notice.NoticeParseSchemaTest | 1 | 0 | 0 | 0 |
| common.ApiBoundaryTest | 4 | 0 | 0 | 0 |
| common.ConfigurationTest | 3 | 0 | 0 | 0 |
| common.CryptoTest | 5 | 0 | 0 | 0 |
| common.idempotency.IdempotencyExecutorTest | 9 | 0 | 0 | 0 |
| common.pagination.CursorCodecTest | 3 | 0 | 0 | 0 |
| common.PrivacySafeExceptionLoggingTest | 1 | 0 | 0 | 0 |
| common.ratelimit.RateLimiterTest | 2 | 0 | 0 | 0 |
| common.SecurityBoundaryTest | 3 | 0 | 0 | 0 |
| common.SnowflakeIdsTest | 5 | 0 | 0 | 0 |
| community.BusinessAttachmentPrivacyTest | 7 | 0 | 0 | 0 |
| community.CommunityHttpTest | 28 | 0 | 0 | 0 |
| community.MysqlCommunityPrivacyTest | 18 | 0 | 0 | 0 |
| identity.IdentityHttpTest | 34 | 0 | 0 | 0 |
| identity.RedisOneTimeSecretsTest | 2 | 0 | 0 | 0 |
| integration.ai.DeepSeekClientTest | 5 | 0 | 0 | 0 |
| integration.notice.SafeUrlReaderTest | 11 | 0 | 0 | 0 |
| integration.scanner.ClamdScannerTest | 1 | 0 | 0 | 0 |
| integration.storage.LegacyFileKeyConverterTest | 2 | 0 | 0 | 0 |
| integration.storage.StorageProtocolTest | 1 | 0 | 0 | 0 |
| integration.wechat.JavaWechatTransportTest | 1 | 0 | 0 | 0 |
| integration.wechat.WechatHttpGatewayTest | 3 | 0 | 0 | 0 |
| material.files.FileFormatTest | 5 | 0 | 0 | 0 |
| material.files.FileHttpTest | 13 | 0 | 0 | 0 |
| material.files.LiveFileIntegrationTest | 1 | 0 | 0 | 1 |
| material.files.MysqlFileRegressionTest | 2 | 0 | 0 | 2 |
| material.files.UploadLifecycleTest | 3 | 0 | 0 | 0 |
| material.precheck.MaterialPrecheckRulesTest | 5 | 0 | 0 | 0 |
| opportunity.EligibilityEngineTest | 6 | 0 | 0 | 0 |
| opportunity.OpportunityHttpTest | 35 | 0 | 0 | 0 |
| resource.ServiceSourcesTest | 1 | 0 | 0 | 0 |
| security.CurrentActorTest | 5 | 0 | 0 | 0 |
| security.DatabaseActorLoaderTest | 3 | 0 | 0 | 0 |
| security.OpaqueSessionTest | 8 | 0 | 0 | 0 |
| security.RedisSessionStoreTest | 2 | 0 | 0 | 0 |
| security.SessionHttpBoundaryTest | 10 | 0 | 0 | 0 |
| service.MysqlServiceRegressionTest | 9 | 0 | 0 | 0 |
| service.ServiceHttpTest | 32 | 0 | 0 | 0 |

测试覆盖的生产/测试/迁移/pom与检查点9d20202相同，完整证明后只改文档、根ignore、归档及scratch的Git跟踪状态；`git diff --name-only 9d20202 -- backend/src/main backend/src/test sql backend/pom.xml`输出空，sourceChangedAfterProof=false。每项旧文件和live跳过均保留，没有宣称真实存储/扫描重新通过。

## 全部过程裁定与成本

以下原文来自本次恢复台账，完整保留17项Ruling及cost；属于本次范围决定，不代表新增生产功能授权。

- Ruling: Native worktree tool returned Not a git repository for this projectless chat, so use user-approved git worktree fallback — isolates original checkout; cost if wrong: app cannot auto-manage this fallback directory, use Git recovery and manual attachment only if supported.
- Ruling: Treat user authorization as approval of the two written review repair recommendations and existing API/database contract, not permission to redesign the product — preserve dependencies and routes; cost if wrong: a necessary contract adjustment may need further user review, record it explicitly.
- Ruling: Source archive CONNECTION.zip is newly added generated build/internal-workspace material; remove only that tracked bundle during final hygiene, retaining all Git history — cost if wrong: user can recover that exact file from 6e56062; no prototype/source deletion authorized.
- Ruling: Current undeveloped modules and removed HTML prototype texts are out of scope; fix actual existing A/B/C behavior — cost if wrong: user-visible missing planned functionality remains, must state it in final status.
- Ruling: B reservationMode and source must be explicit and truthful, following API lines 409 and 464; manual pending needs an expiry rather than hardcoded demo confirmation — cost if wrong: existing tests/UI assuming instant confirmation need state-aware updates, which is preferable to forged confirmation.
- Ruling: Include the existing unexpected-error raw Throwable logger in Task 3 privacy fixes — JDBC exceptions can contain SQL and private input, contrary to the user security rules; cost if wrong: safe type/correlation logs carry less debugging detail, so sensitive diagnosis must stay local and controlled.
- Ruling: Demo check-in has no documented grace window, so require slot start <= Asia/Shanghai now < end and cap token life at five minutes/end — follows the explicit first-release timezone and avoids invented grace; cost if wrong: a future early/late policy needs an explicit configurable extension and matching tests/docs.
- Ruling: Preserve the API's maximum bbox-area rule using a configurable positive server query-area limit, default100km², without pretending to know school boundaries — merely finite/ordered coordinates would miss the binding contract; cost if wrong: unusually large campus views may need administrators to adjust the documented bound.
- Ruling: Permit a one-time legacy reservation-mode backfill, but derive compatibility from historical slot capacity/occupied seat numbers, not parent capacity; ambiguous or incompatible cases stop new booking without deleting/reseating old records — preserves existing occupancy while runtime uses explicit mode; cost if wrong: mixed legacy configurations require administrator repair instead of automatic conversion.
- Ruling: User explicitly requested saving before quota/power loss, so checkpoint tracked code/requirements/proof and back up the isolated working branch to the same GitHub repository without advancing unfinished main/feature — avoids losing progress without representing unreviewed work as complete; cost if wrong: an extra in-progress branch remains and must not be mistaken for a release.
- Ruling: Keep investigation command idempotency but never cache subject identity in the business response store; require fresh high-risk authorization and completed audits for every disclosure, including replay — API2.6 requires deduplicated same-key results while the actual generic executor replays before the method's reauth and caches its identity DTO; cost if wrong: clients retrying disclosure must obtain fresh reauth and the narrow receipt/restricted-idempotency implementation adds tests and an explicit security boundary rather than a broad command exemption.
- Ruling: Allow exact INSERT privilege fault injection only on this run's fresh synthetic investigator and czt_abc_c_* security_investigations table, with verified owned container and finally exact restore/check — proves real audit-failure fail-closed and cached-receipt retry without a production bypass; cost if wrong: abrupt termination may leave that disposable test account unable to INSERT, never a user/app/migrator/global permission change.
- Ruling: Cross-domain attachment regression uses only trusted persisted/imported mutual_aid/issue/workorder fixtures; do not add notice_document sharing or new admin upload purposes — actual admin intents support only notice_document/student_roster and expanding A file exposure merely to satisfy a test exceeds Task3; cost if wrong: students still cannot download administrator notice originals, which needs a separate explicit sharing policy/review rather than an implicit public grant.
- Ruling: Business DTO linked images inherit the actual workorder.read/issue.read object grant without additionally requiring generic file.read, while /admin/files keeps its original file.read and extra guards — existing business_operator lacks file.read and otherwise cannot view authorized object images, contradicting Task3 authorized linked reads; cost if wrong: business readers can view that object's linked clean images, so any desired separate image permission needs an explicit policy change, not a broad role grant or generic-file bypass.
- Ruling: Legacy teams use explicit captain versioned role repair, reject the old null-role pending application and let the student reapply to a stable role; unknown legacy members consume total recruitment capacity without fabricated role assignments — preserves real membership/application history and avoids a permanently blocked pending uniqueness slot — cost if wrong: captains/applicants must complete a manual repair/reapplication and legacy occupancy can conservatively reduce vacancies.
- Ruling: Explicitly reject mutual-aid regionId with 400 and document the unsupported filter, rather than pretend exchangeArea text is a modeled region or add an unrelated schema design — the approved Task4 permits explicit rejection and current storage lacks a region relationship — cost if wrong: location filtering remains unavailable until a separately modeled field and migration are approved.
- Ruling: Compare V1–V15 committed blob bytes against every original branch where each file exists and the merged347adc0 union, not the plan's impossible single-main inventory; report Windows checkout CRLF separately without editing migrations — originalmain lacksV10/V11 and originalfeature lacksV12/V13, so a single-main lookup fails before any migration proof — cost if wrong: future audits must retain both original SHAs and distinguish committed immutable bytes from checkout line-ending conversion, rather than claiming raw working bytes equal Git LF blobs.
