# 后端接口文档严格仓库对照审查记录

- 日期：2026-10-13
- 仓库：[dew64025-stack/-](https://github.com/dew64025-stack/-)
- 对照提交：[`f1a33c36f99c334f48613f2f3d6f871540310634`](https://github.com/dew64025-stack/-/commit/f1a33c36f99c334f48613f2f3d6f871540310634)
- 原稿：`campus-backend-api-v1.md`（本地保留，本次不提交）
- 修订稿：[campus-backend-api-v1.1-reviewed.md](campus-backend-api-v1.1-reviewed.md)
- 范围：规划书、DATABASE.md、DATA_DICTIONARY.md、sql/V1～V6及演示种子，与原稿的所有业务章节、接口表、JSON示例和调用约定对照。
- 仓库没有AGENTS.md、Controller或OpenAPI文件。没有后端服务器可进行真实接口调用。

## 一、审查结论

原稿存在实质偏差，不能只凭“JSON能解析、没有重复路径”认定符合仓库。主要问题是9条主要路由被改名或遗漏、12个PATCH方法不适合微信原生请求、数据来源字段不一致，以及部分权限和状态说明过期或自行解释了仓库字段。

本次生成独立v1.1修订稿，保留v1.0原稿。修订稿已覆盖仓库31条主要路由，并同步最新匿名权限和预约复合约束。运行实现、数据库迁移、真机兼容、容量与安全测试仍未执行。

## 二、逐项发现与处理

| 编号 | 原稿问题 | 对照依据 | 本次处理 |
| --- | --- | --- | --- |
| A01 | 对照提交仍为c2fb463，未包含最新匿名/占用修改 | 最新提交f1a33c3 | 更新版本引用与缺项说明 |
| A02 | 9条规划主要路由缺失或被改名 | 规划书8.3 | 全部恢复规划中的方法和路径，辅助接口单独说明 |
| A03 | 12个更新接口使用PATCH | 微信官方RequestOption.method不包含PATCH | 改为POST /对象/update，更新同样携带幂等键与版本 |
| A04 | 外部来源返回嵌套source且没有externalId | 规划书9.2 | 改为顶层sourceType/sourceName/updatedAt/isStale/officialReference/externalId |
| A05 | 幂等仅要求多数POST，遗漏PUT/DELETE等写入 | 规划书8.1“写接口携带” | 扩大到所有业务写操作，认证及临时凭证单独定义例外 |
| A06 | voteCount被自行解释成净票数，并要求改SQL | 数据字典非负投票数、SQL为UNSIGNED | 按有效投票总数定义，需要赞反数时从投票行聚合，不擅自改仓库字段 |
| A07 | 工单completed被描述为待评价，前端容易提前显示完成 | 规划状态机与SQL状态存在冲突 | 保留SQL status，增加明确serviceStage映射；完整转办/关闭和待受理依据仍待仓库补齐 |
| A08 | 单材料预检与机会清单预检没有清楚区分 | 规划材料主路由、precheck_tasks要求checklist_id | 保留单材料主路由，单材料任务使用async_jobs；清单预检使用precheck_tasks/results |
| A09 | CSV名单导入没有完整的管理员上传确认路径 | 原稿上传用途与管理员导入互相矛盾 | 添加管理上传意向/确认/查询/签名，学生用途不允许导入名单 |
| A10 | 昵称/调查路径仍基于旧权限，调查响应默认含手机号 | 最新DATABASE.md与V4/V5 | pseudonym映射author_name；业务仅INSERT映射，专用调查数据源SELECT映射/INSERT记录，不默认读取手机号 |
| A11 | 返回对象类型省略过多，Resource/Slot缺version等 | SQL字段与客户端版本控制 | 重新定义DTO基础类型、必返/可空字段和所有/update字段白名单 |
| A12 | 首页没有定位输入和明确区域回退 | 规划5.1附近互助 | 增加定位/区域输入与nearbyMode，不在缺坐标时编造距离 |
| A13 | 撤销删除请求没有持久化状态，7天冷静期被写成固定规则 | data_requests枚举、规划未规定天数 | 标明撤销状态需补齐，冷静期为配置型建议 |
| A14 | 未明确互助评价、信用纠错闭环 | 规划互助评价、信用可解释与纠错 | 补评价/纠错接口，明确仍缺相应持久化模型 |
| A15 | 头像/材料文件只提杀毒，漏写EXIF清理 | 规划11.2 | 补充图片移除非必要EXIF |
| A16 | 缺少规划性能、恢复门槛的接口验收对应 | 规划13、14 | 补P95、并发、AI提交时间、RPO/RTO和监控范围 |
| A17 | 管理高危reauthToken可能被理解为URL参数 | 原稿“令牌不进URL”与DELETE输入不清 | 固定X-Reauth-Token请求头，限制字段与查询位置 |
| A18 | 泛化的“可编辑字段”可能造成批量赋值和越权 | 规划权限/校验规则 | 按12个更新路由列明确字段白名单、版本来源和修改限制 |

## 三、9条主要路由纠正表

| 原稿 | 仓库规定并在修订稿保留 |
| --- | --- |
| POST /ai/answers | POST /api/v1/ai/assistant/messages |
| 只有POST /files/upload-intents | POST /api/v1/materials/upload-intents；通用文件入口作为辅助接口保留 |
| 只有POST /checklists/{id}/prechecks | POST /api/v1/materials/{id}/prechecks；清单入口作为辅助接口保留 |
| PUT /campus-issues/{id}/follow | POST /api/v1/campus-issues/{id}/follows |
| 只有GET /resource-slots/{id}/availability | GET /api/v1/resources/{id}/availability；时段入口作为辅助接口保留 |
| POST /teams/{id}/applications | POST /api/v1/team-applications，正文包含teamId |
| GET /me/conversations | GET /api/v1/conversations，仅返回当前用户会话 |
| POST /me/data-requests，type=export | POST /api/v1/me/data-exports |
| POST /me/data-requests，type=deletion | POST /api/v1/me/account-deletion |

由于仓库没有已实现的旧路由，本次不要求为了原稿的任意命名新增兼容别名。

## 四、仓库自身仍有的待处理事项

这些不能通过接口文档措辞宣称已经解决，也不应反过来擅自修改仓库。

1. 工单规划要求待评价后才完成，当前SQL只有completed/rated等状态，待受理、转办、无法处理和关闭信息不完整。修订稿增加业务阶段映射，但该闭环还需要模型和字典统一。
2. 匿名作者维护与“我的发布”依赖归属查询，业务账号却无SELECT映射权限，帖子/评论也没有独立归属凭据。当前仅INSERT与author_name已解决发布和昵称展示，不等于作者维护已解决。
3. 规划要求匿名身份映射加密，当前user_id外键仍为可关联的明文ID。权限隔离不等同于加密；需制定兼容当前约束的方案。
4. 会话去重、已读位置、通用附件、双方交接、隐私设置、屏蔽、评价、信用纠错和注销撤销等字段或模型不完整。修订稿保留功能要求，逐项注明工程依赖。
5. DATETIME没有时区，演示时间字面量和UTC_TIMESTAMP需统一解释与转换，不能假设现有全部数据已经是UTC。
6. 原稿许多返回字段只能由业务查询或适配层计算，没有对应列。修订稿将DTO计算字段与真实数据库字段分开，不要求每个DTO属性新增一列。

## 五、静态验证证据

| 检查 | 结果 |
| --- | --- |
| 仓库主要路由 | 31/31覆盖 |
| 正文接口定义（含单独定义的微信登录） | 206条；不是已实现数量 |
| 微信请求方法 | 接口表仅使用GET/POST/PUT/DELETE，无PATCH定义 |
| 同方法同路径重复 | 0 |
| JSON示例 | 10个全部可解析 |
| BIGINT示例 | 未发现ID字段使用JSON数字 |
| Markdown代码块 | 32个围栏，成对闭合 |
| 来源字段 | 示例使用顶层六字段，与规划9.2一致 |
| 旧提交引用 | 修订稿引用最新f1a33c3 |
| 仓库SQL结构 | 74张表、103个外键、5个CHECK；未视为应用测试通过 |

这些检查只能证明文档部分结构和仓库覆盖关系，不证明业务逻辑、Controller、权限或数据库迁移可运行。

## 六、下一步使用方式

前后端开发以v1.1为修订契约，规划31条主要路由保持稳定；首先解决第四节的模型/权限冲突，再逐模块生成OpenAPI和实现。新增辅助接口的必填字段、版本、幂等和访问范围必须落实到代码与测试，不能把第19节的依赖表当作已完成实现。

当前不具备将接口文档标为“联调已通过”或“正式上线已验收”的依据。

## 七、引用

- [项目规划书](https://github.com/dew64025-stack/-/blob/f1a33c36f99c334f48613f2f3d6f871540310634/2026-10-13-campus-one-stop-platform-design.md)
- [数据库部署与权限](https://github.com/dew64025-stack/-/blob/f1a33c36f99c334f48613f2f3d6f871540310634/DATABASE.md)
- [数据字典](https://github.com/dew64025-stack/-/blob/f1a33c36f99c334f48613f2f3d6f871540310634/DATA_DICTIONARY.md)
- [SQL](https://github.com/dew64025-stack/-/tree/f1a33c36f99c334f48613f2f3d6f871540310634/sql)
- [微信官方wx.request类型](https://api.github.com/repos/wechat-miniprogram/api-typings/contents/types/wx/lib.wx.api.d.ts)
