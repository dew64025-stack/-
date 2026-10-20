# 材智通前后端接入与联调说明

更新时间：2026-10-20

## 1. 当前结论

本轮已把手机适配版的业务页面改为调用现有后端 HTTP 接口，不再用写死的账号、预约成功、聊天消息或材料检测结果代替真实服务端结果。

- 前端目录：`D:\材智通-原版手机适配-20261019`。
- 后端目录：`D:\caizhitong-review`。
- 当前接口根地址：`http://127.0.0.1:8080/api/v1`。
- 当前小程序项目 AppID 保持 `wx3b3b713ab4afce40`，没有用历史聊天中的其他 AppID 覆盖。
- 保留已有自定义底部导航、服务六个彩色图片入口、个人页材料保险箱图片与设置入口。业务卡片根据真实接口结果显示；没有数据时显示空状态。
- 本轮没有提交或推送 GitHub；后端原有未提交改动保留。

**“接入代码完成”不等于“所有线上功能验收通过”。** 微信真实授权、本人身份下的写业务、学校官方数据与生产部署仍需后续验收，见第 5 节。

## 2. 功能与接口对应

以下接口均以 `/api/v1` 为前缀。表中“已接入”指前端调用、参数与返回值按当前源码接好，不表示全部已用真实微信账号联调。

| 功能 | 前端文件/目录 | 主要后端接口与行为 |
| --- | --- | --- |
| 微信登录、绑定手机号、学生认证 | pages/login、phonebind、verify | /campuses、/auth/wechat/login、/auth/phone/bind、/student-verifications、/me；只发送微信授权 code 和真实学号，认证结果依服务端 pending/verified/rejected |
| 首页、搜索、AI 助手 | pages/home | /home/overview、/search、/ai/assistant/messages、/jobs/{id}；异步回答等待任务结果，不显示编造回答 |
| 机会、通知、资格匹配、提醒 | pages/opportunity、opportunitydetail | /opportunities、/opportunities/{id} 及资格检查/提醒等子接口；实习志愿分别查询再合并，ID 始终保留字符串 |
| 组队 | pages/teams | /teams 及详情/申请；联系通过业务上下文创建站内会话 |
| 材料与预检 | pages/vault、precheck | /materials、/files、机会 checklist、/checklists/{id} 及预检任务；上传、安全扫描、版本、清单绑定、预检、正式提交标记分开处理 |
| 服务、报修与评价 | pages/service、workorder | /work-orders、/me/work-orders 及事件/评价；后端确认提交后才显示成功 |
| 场地和座位预约 | pages/seatreserve、seatplan、reserveconfirm、voucher | /resources、资源 slots、时段 availability、/reservations 及取消/签到；使用真实 slotId、seatNo、version |
| 校园问题与拥堵 | pages/campusissues、service | /campus-issues、/crowd/observations 及投票/关注；保留学校接入与演示来源边界 |
| 互助、发布与借还 | pages/mutual、mutualdetail、postcreate、borrows | /mutual-aid/posts、/borrow-requests 等；失物、二手、教材、技能、借用使用后端各自 detail 结构；借用双方确认由后端判断 |
| 匿名交流 | pages/community | /community/posts 及评论；匿名身份由服务器管理，不由前端伪造 |
| 消息与通知 | pages/chat、activity | /conversations、会话 messages/read、/me/notifications；轮询读消息，发送失败保留输入，更早消息不被刷新清掉 |
| 我的、隐私、导出和注销 | pages/profile、settings、activity | /me/dashboard、/me/profile、成长/信用/待办/预约/工单、授权、隐私设置、数据申请；注销先确认并创建申请，冷静期使用服务端返回值 |

个人信息、材料、聊天、申请、预约等受保护接口必须有有效会话。未登录不自动注入测试用户、不签发伪造令牌。

原有 neterror、maintenance、denied、success 是独立状态展示页；正常业务没有把它们当作后端成功凭据。

## 3. 修改清单

### 公共接入层

- `config/api.js`：服务器地址、默认校园、超时、材料 AI 授权说明配置。
- `utils/request.js`：统一响应与错误、会话刷新、并发合并、写入防重标识、最多一次 401 重试。
- `utils/session.js`：本机会话缓存与清理。
- `utils/ui.js`：加载/操作状态、错误提示、登录拦截、过期请求结果保护。
- `utils/data.js`：字段展示、校园查询条件、异步任务轮询。
- `utils/files.js`：选择文件、上传凭证、完成上传、扫描轮询、下载预览。
- `utils/consent.js`：使用已有材料 AI 授权，或在真实授权版本配置后由用户明确同意。
- `templates/api-state.wxml`、`styles/api.wxss`：加载、错误、空状态及接入页面的基础布局。
- `app.js`、`app.json`、`app.wxss`、`project.config.json`：启动会话、注册新增页面、导入样式、排除测试/工具/文档打包。

### 页面

改动现有 home、opportunity、service、mutual、profile、settings、login、phonebind、verify、seatreserve、seatplan、reserveconfirm、voucher、vault、precheck、postcreate、mutualdetail、chat 的 JS/WXML。

新增 opportunitydetail、teams、activity、workorder、campusissues、community、borrows 页面及各自 JS/WXML/JSON/WXSS。共 29 个注册页面完成编译检查（含原有状态展示页）。

### 测试与工具

- `tests/api-request.test.cjs`：请求、刷新、防重与错误边界。
- `tests/api-files.test.cjs`：图片/PDF 分流、临时文件类型、ZIP 分享失败处理。
- `tests/api-pages.test.cjs`：真实页面控制器的契约和异常路径测试；外部微信与 HTTP 边界使用模拟。
- `tests/tabbar.test.cjs`：适配页面引入公共模块后的测试装载方式，保留原导航断言。
- `tools/check-api-integration.cjs`：JS/JSON、事件方法、资源引用及官方 WXML/WXSS 编译。
- `tools/live-api-check.cjs`：实际请求本机后端，校验公开页面、空搜索与未授权边界。

### 后端最小兼容修复

仅为材料版本安全预览补充字段，不增加越权下载通道：

1. `backend/src/main/java/com/caizhitong/material/files/FileViews.java`：Version 增加字符串 attachmentId。
2. 同目录 `MaterialService.java`：版本查询返回 attachment_id。
3. `backend/src/test/java/com/caizhitong/material/files/FileHttpTest.java`：断言 currentVersion.attachmentId。

下载仍需调用已有 /files/{attachmentId}/download-url，由原有服务端所有权与授权检查决定。新版后端 jar 已打包并在本机启动。

## 4. 已验证的内容

| 检查 | 本轮结果 | 能证明什么 / 不能证明什么 |
| --- | --- | --- |
| 前端全部现有测试 | **47 通过，0 失败，0 跳过** | 包含请求/页面边界和导航回归；不等于真实微信授权验收 |
| 官方模板和样式编译 | **29 个注册页面通过** | JS/JSON、事件方法、资源引用、WXML/WXSS；不能代替真机布局/权限验收 |
| 本机 HTTP 联调 | **14 次 wx.request 适配调用通过** | 连接真实后端与当前数据库数据；首页、搜索、机会/详情、互助/详情、资源/时段、访客隔离；没有创建测试账号和业务数据 |
| 微信开发者工具 | **实际页面检查通过** | 首页、机会列表与详情、互助列表、服务六入口及图书馆资源时段、我的进入设置、未登录提示；本轮检查期间未捕获运行异常 |
| 后端 FileHttpTest | **13 通过，0 失败/错误/跳过** | Spring HTTP 层与 H2 测试环境的文件/材料授权及版本回归；不代表真实微信上传+对象存储+扫描链路已验收 |

47 项前端测试中，样式编译测试需要 WXSS_COMPILER。最终执行时已显式设置真实编译器路径，**不是因为缺配置而跳过**。没有删除、屏蔽测试。

这不是旧报告“全后端测试”的重跑；本轮没有把过去记录的全量后端测试数当作当前证据。打包阶段在上述测试通过后使用 -DskipTests 避免重复执行，与本轮测试结果的 skipped=0 分开说明。

独立只读复核指出的跨账号重试风险已修复，并再次通过复核：登录/退出增加会话代次，刷新不改变代次；旧账号的 401 不得用新账号重发，旧成功响应也不得回填新会话。聚焦 11 项测试独立复跑通过，0 失败/跳过。

额外修复并验证：分类快速切换导致旧响应污染分页游标、编辑表单后重用旧防重标识、刷新后仍 401 未结束会话、聊天刷新清掉更早消息、会话列表摘要被缺省文字遮盖、图片材料误用文档预览、临时 PDF 文件缺少预览类型、ZIP 分享失败未传播。

## 5. 预留、配置与待验证项目

### 微信登录与手机号

- 必须让后端 WECHAT_APP_ID 与当前 project.config.json 中的 AppID 一致。
- AppSecret 只放在服务器私有环境配置，不放前端、不提交 Git、不发送聊天。
- 2026-10-20 后续已配置：微信 AppID/AppSecret 存于后端根目录私有 .env.wechat.local，run-local.bat 启动时加载；两个文件均被 Git 忽略且没有被 Git 跟踪。当前前端 AppID 与其一致，微信官方 stable_token 接口已接受该配置，访问令牌和密钥未输出。已停止旧项目启动器并重启后端；一次性无效 code 的装载探针已不再返回 WECHAT_UNCONFIGURED，而是预期的 WECHAT_FAILED（探针本身不是有效微信登录码，不能据此宣称登录成功）。公开接口联调再次通过。本轮仍未执行学生账号真实换码及手机号授权。
- 密钥曾经出现在聊天中，建议立即在微信后台重置；生产环境只使用重置后的密钥，并同步更新本机私有文件。不要将真实值写入报告。
- 微信手机号能力还需实际主体、账号权限及真机授权验证。前端不以手填手机号、伪造验证码替代授权。
- 学校认证接口继续预留，当前人工复核路径保留；不能把 pending 显示成自动通过。

### 材料 AI 与真实文件

`config/api.js` 的 materialAiDocumentVersion 与 materialAiPolicyText 当前留空，属于有意保留的发布前置条件：

- 先在后台发布真实校园授权文案和版本，再在配置填入匹配版本与向学生展示的完整说明。
- 当前后端未提供学生端“获取最新待签授权版本”的接口，不能临时编造版本号。
- 已存在有效 material_ai 授权时可继续使用；新授权前没有配置就明确提示不可提交 AI 预检。
- 文件必须扫描为 clean 才允许绑定，不关闭病毒扫描。
- 尚需真实微信账号验证文件选择、wx.uploadFile multipart、上传令牌、对象存储、ClamAV、签名下载与实际 AI 处理全过程。
- AI 服务商密钥、数据库/Redis/对象存储密钥只留后端。DeepSeek 或本地服务是否可用以当前后端配置与实际任务为准。

### 当前后端没有完整能力的地方

- **互助收藏**：FavoriteService 当前只接入 opportunity，其他目标返回 TARGET_NOT_CONNECTED。互助入口明确标为“收藏（待开放）”，不再发送必定失败请求；机会收藏仍按现有接口接入。
- **材料打包 ZIP、模板下载**：当前没有对应业务接口，未保留假下载成功。个人数据导出 ZIP 是另一条已有接口链路，不混淆。
- **学校官方数据、校内签到**：学校数据适配仍预留；预约签到需要真实校内 checkInToken，不能由前端随机生成签到码。
- **订阅消息/实时 WebSocket**：本轮使用站内消息轮询，没有把订阅消息后台投递或 WebSocket 生产可用性算作已验收。

### 真机与上线

- 127.0.0.1 仅指运行客户端所在设备；手机不能用它访问电脑后端。
- 上线前把 baseUrl 改为真实可达 HTTPS 域名，并在微信后台配置 request、uploadFile、downloadFile 等合法域名；签名下载域名也需正确配置。
- 开发者工具原有 urlCheck=false 只用于本机 HTTP 演示，不是线上安全方案；发布前恢复校验、核对证书、来源域名及隐私声明。
- 需使用真实认证账户验收：权限与跨校园隔离、预约竞争/取消/签到、报修评价、借用交接/归还、团队申请、消息收发、材料预检、导出和注销冷静期。
- 个人信息导出/注销会真实改变账号状态，验收应使用专用测试账号，不能直接拿日常账号做不可逆测试。
- 本轮未进行完整生产负载/ABCD 并发压测；前端测试中的并发会话刷新、分类响应乱序不等于服务器压测。

## 6. 怎么运行与复测

后端依赖使用当前已有本机 MySQL、Redis、对象存储与扫描服务；数据库仍使用现有 caizhitong_run，不重新建表、不覆盖数据。

1. 依赖未启动时，按后端现有部署说明启动对应服务；不要再开一套冲突端口。
2. 从私有配置准备后端所需环境，再运行 `D:\caizhitong-review\backend\run-local.bat`。启动脚本已被 Git 忽略；其中私密值不要复制到报告或版本库。
3. 微信开发者工具导入前端**根目录**，不是 pages 子目录。
4. 修改代码后使用完整“编译”。仅 reLaunch 页面有时仍用到旧 JS 模块，不能据此判定改动没生效。
5. 本机公开页面可以先直接演示；受保护功能需要真实微信登录，失败提示应保留请求编号方便排查。

PowerShell 复测：

```powershell
Set-Location 'D:\材智通-原版手机适配-20261019'
$env:WXSS_COMPILER = 'D:\微信开发者工具\微信web开发者工具\resources\app.asar.unpacked\node_modules\wcc-exec\wcsc.exe'
node --test tests/*.test.cjs
node tools/check-api-integration.cjs
node tools/live-api-check.cjs
```

三个命令分别检查回归、官方编译与真实本机 API。live-api-check 需要后端已在配置地址启动，失败时应排查服务/依赖，不能改回假数据让它通过。

本轮后端回归：

```powershell
Set-Location 'D:\caizhitong-review'
$env:JAVA_HOME = 'C:\Users\19683\devtools\jdk-21.0.12.1+1'
& 'C:\Users\19683\devtools\apache-maven-3.9.9\bin\mvn.cmd' -f backend/pom.xml '-Dtest=FileHttpTest' test
```

## 7. 保存与后续顺序

- 改动前源文件备份：`C:\Users\19683\czt-backups\frontend-before-api-20261020`。它是改动前复制的源码/配置备份，不承诺包含后续生成文件或所有隐藏文件。
- 实际开发者工具预览截图位于 `C:\Users\19683\czt-backups\api-*-preview.png`。
- 实施计划：`docs/superpowers/plans/2026-10-20-backend-integration.md`。
- 建议下一步依次完成：后端私下配置匹配的微信 AppID/AppSecret → 真实账号登录/手机号 → 已认证测试账号的写业务 → 上传/扫描/AI → 学校接口与上线域名验收。

## 8. 我的页面图标补充（2026-10-20）

使用内置图片生成工具新增透明 PNG：assets/profile-icons/growth-archive.png（提示词主题：薄荷绿立体档案夹、成长图表与幼苗）和 credit-review.png（提示词主题：蓝紫立体盾牌、核验勾号与记录单）。均以128×128像素保存，页面显示48rpx×48rpx，位置与材料保险箱标题图标一致。

仅改 pages/profile/profile.wxml、profile.wxss 增加图片与尺寸，不改业务JS；29页官方编译与5项样式回归通过，并已检查开发者工具中的实际显示。

## 9. 学生／管理员入口与人工审核（2026-10-20 后续）

新增登录页与「我的」页管理员入口、独立两阶段管理员登录和本校学号人工审核工作台。复用现有密码＋TOTP、CSRF、安全 Cookie、校园隔离及版本审核接口，不按输入的手机号／工号自动授予管理员权限。本轮不改后端生产或测试源码，不自动创建真实管理员。

详细入口、账号开通、权限与审核责任见同目录 `ADMIN_GUIDE.md`。新增 `config/api.js` 的 `adminBaseUrl` 需为真实 HTTPS；当前留空且学生地址为 HTTP，因此管理凭据提交被阻止。现有学生查看未配置的管理入口不会先被退出，返回时保留学生会话。

后续最终复测：**80 项前端测试通过，0 失败、0 跳过；31 页官方编译通过；14 次本机公开接口调用通过；后端 IdentityHttpTest／SessionHttpBoundaryTest 共 44 项通过，0 失败／错误／跳过**。上文 47／29 是此前接入里程碑，不代表当前总数。

开发者工具仅验证入口、HTTPS 缺失提示、学生会话保留和未登录队列边界。真实管理员密码＋MFA、HTTPS Cookie／CSRF、真实审核及权限撤销、并发审核和真机仍须部署后使用专用授权账号验收。未进行真实学生审批、创建管理员、推送仓库或放宽安全检查。

## 10. 首页 AI 通栏与点击反馈（2026-10-20 后续）

仅调整首页 `home.wxml`、`home.wxss`、`home.js`，并新增 `tests/home-assistant.test.cjs`。AI 助手改为「今日截止事项」上方、与内容区同宽的横标，保留手机两侧边距；点击展开／收起独立问题框。顶部搜索及图书馆、报修快捷搜索不变，不再把搜索框内容当作 AI 问题。

空问题或超过 2000 字立即提示；未登录引导微信登录；提交／整理期间显示状态并禁止重复提交。错误展示在提问区，保留草稿。只有后端任务返回实际 `answer` 才展示回答，不以等待文字、JSON 或成功状态充当回答。`ASSISTANT_AI_KEY_MISSING` 明确显示服务尚未配置，可继续使用普通搜索；不根据前端猜测模型配置状态。

网络提交结果不确定时复用防重标识，已接受的任务重查原任务；后台明确失败后允许新任务重试。离开首页后停止继续轮询／回填迟到结果。AI 缓存绑定登录会话：退出／换账号清除旧问题、回答和任务；访客从 AI 入口首次登录保留未提交草稿。刷新会话已失效时结束等待并提示重新登录，不取消跨账号响应校验。没有添加前端密钥、假回答或修改后端源码、全局样式、底部导航。

本阶段前端 **96 项通过，0 失败、0 跳过**（新增 16 项 AI 交互／异常回归）；31 页官方 WXML／WXSS 编译通过；14 次本机公开接口检查通过。开发者工具实测通栏位置与宽度、展开／收起、空输入提示和原搜索栏；未捕获运行异常。密钥缺失与任务回答的回归使用模拟微信 HTTP 边界，未提交真实 AI 问题，不能算真实模型调用验收。实际回答仍需后端配置模型后联调，密钥不要放前端或聊天。

首页修改前备份：`C:\Users\19683\czt-backups\home-ai-before-20261020-1730`（仅三个首页文件）。实际页面截图：`C:\Users\19683\czt-backups\home-ai-banner-collapsed.png`、`home-ai-banner-empty-prompt.png`。没有推送 GitHub。
