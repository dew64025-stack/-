# 学生／管理员入口与学号人工审核

更新时间：2026-10-20。目录：`D:\材智通-原版手机适配-20261019`。

## 1. 从哪里进入

- 学生登录页：点击「教职工／管理员登录」。
- 「我的」页：点击「教职工／管理员入口」。
- 管理员完成「工号／管理员账号 + 密码」和「认证器 6 位动态码」两步验证后，进入学号人工审核工作台。
- 工作台支持待审核、已认证、已驳回筛选、分页、查看脱敏学号、通过／驳回和填写审核原因。本轮没有铺开其他管理模块。
- 学生提交认证后，认证页显示申请编号；审核完成后重新读取服务端状态，`verified` 显示为「已认证」。

**手机号、工号只是身份线索或登录名，不等于管理员权限。** 角色、校园范围和每次操作权限全部由后端裁定，前端不能自行切换角色。

## 2. 目前为什么不能直接登录管理员

当前 `config/api.js` 中 `adminBaseUrl` 留空，学生演示地址仍为本机 HTTP。管理员页面会显示「管理员安全连接尚未配置」，不发送账号、密码、动态码或管理 Cookie。

在 HTTPS 尚未配置时，已有学生点击管理员入口只查看提示，**不会先退出学生账号**；返回学生端会回到「我的」并保留原会话。HTTPS 配置好后的真实身份切换需要明确确认，服务端退出成功后才清理原身份并重建页面栈；退出服务暂不可用时保留可重试状态。

部署后在 `config/api.js` 填入自己的可信地址，例如：

```js
adminBaseUrl: 'https://你的实际后端域名/api/v1'
```

上面是占位说明，不能原样使用。地址需要有效 HTTPS 证书、实际可达的后端或反向代理，并在微信后台配置 request 合法域名。真机不能通过 `127.0.0.1` 访问电脑。

不要为本机演示关闭 CSRF、放宽角色检查、去掉 Cookie 的 Secure／HttpOnly／SameSite 属性，或改用明文 HTTP 发送管理员凭据。

## 3. 如何开通管理员

本轮**没有创建真实管理员、默认密码或 MFA 种子，也没有授予任何人角色**。

由授权负责人在受控环境使用后端现有 `com.caizhitong.admin.identity.AdminBootstrap` 初始化。必须明确操作者、目标用户、校园和角色；目标用户须符合后端有效用户与校园检查。初始化使用专门授权的数据库凭据，不能把开通权限交给普通小程序客户端。

账号、初始密码、TOTP 种子通过安全渠道配置和交付，不发聊天、不写前端、不进入 Git。该流程不是公开注册接口。忘记密码或丢失认证器需走学校管理员受控处理。

审核页要求 `identity.review`。当前后端权限矩阵中 `campus_admin` 包含此权限，但也包含多项其他校园管理权限；不能为了只审学号而随意给普通工作人员这一广泛角色。若需要专职审核员，应另行批准并实现最小权限角色。本轮没有改动角色矩阵。

## 4. 审核如何操作

1. 管理员先登录，页面调用 `/admin/me` 读取真实权限；没有 `identity.review` 不加载审核队列。
2. 在本校申请中核对申请编号、脱敏学号及可信身份凭据。`rosterMatched` 仅表示名单线索，不能证明申请人就是本人。
3. 通过之前必须明确确认已核实本人身份；通过和驳回都需填写真实原因。驳回原因不超过 255 字，通过原因不超过 512 字。
4. 提交原申请的 `version`。后端检查权限、校园、手机号授权、待审状态及版本，并记录审核人和原因。冲突时重新加载，失败不显示假成功。

当前接口没有向此页面提供完整申请凭证展示；审核人需通过学校可信核验流程确认身份。不能仅凭脱敏学号、用户口述或名单命中放行，不要收集学生账号密码或验证码。

## 5. 接口及会话边界

接口前缀为 `/api/v1`：

| 用途 | 方法与路径 |
| --- | --- |
| 取得 CSRF 信息 | GET `/admin/auth/csrf` |
| 密码验证／动态码验证 | POST `/admin/auth/login`、`/admin/auth/mfa/verify` |
| 刷新／退出 | POST `/admin/auth/token/refresh`、`/admin/auth/logout` |
| 管理员资料与权限 | GET `/admin/me` |
| 审核列表 | GET `/admin/student-verifications?status=pending&limit=20` |
| 审核决定 | POST `/admin/student-verifications/{id}/decision` |

决定载荷为 `decision`（approve／reject）、`reason`、`version`，不由客户端传入管理员角色或目标校园。列表分页沿用服务端游标。

管理员会话独立于学生会话：密码验证只产生短时挑战，MFA 成功才视为登录；管理令牌、刷新 Cookie、CSRF Cookie 和挑战仅保存在模块内存，不写入 wx Storage、页面数据或日志。应用重启后需重新登录。写请求保留 `X-ADMIN-CSRF` 与匹配 Cookie；刷新 Cookie 仅发送给刷新接口。同批过期请求合并刷新，旧账号的迟到响应不得污染新会话。

## 6. 本轮验证与未验收边界

- 前端完整测试：**80 通过，0 失败、0 跳过**，含 33 项新增管理员客户端／页面测试。
- 官方 WXML／WXSS 编译：**31 个注册页面通过**，已设置真实样式编译器，不靠缺配置跳过。
- 本机公开接口联调：**14 次请求通过**，未创建测试账号或业务数据。
- 后端 `IdentityHttpTest`、`SessionHttpBoundaryTest`：**44 通过，0 失败／错误／跳过**。这是 Spring HTTP 过滤链与 H2 测试，不是生产登录验收。
- 开发者工具检查范围：入口可见、HTTP 下安全提示、学生会话保留、未登录工作台不暴露申请。没有填写真实管理员凭据、通过真实学生申请或执行退出当前学生的测试。
- 独立只读复核：未发现重要缺陷；33 项针对性测试独立复跑通过。

仍需部署后使用授权测试账号验证：可信 HTTPS 的 Cookie／CSRF 实际传输、真实密码＋TOTP、会话刷新和撤销、跨校园拒绝、真实申请审核及重复／并发版本冲突、真机域名和证书。当前不能宣称管理员生产端到端验收完成。

复测方式：

```powershell
Set-Location 'D:\材智通-原版手机适配-20261019'
$env:WXSS_COMPILER = 'D:\微信开发者工具\微信web开发者工具\resources\app.asar.unpacked\node_modules\wcc-exec\wcsc.exe'
node --test tests/*.test.cjs
node tools/check-api-integration.cjs
node tools/live-api-check.cjs
```

最后一个命令需要现有本机后端已启动，只读检查公开业务及访客边界。

## 7. 文件与保存

- 新增：`utils/admin.js`、`utils/identity.js`、`pages/adminlogin/`、`pages/adminreview/`、`styles/admin.wxss`、`tests/admin-client.test.cjs`、`tests/admin-pages.test.cjs`、本文及实施计划。
- 修改：`config/api.js`、`app.json`、登录页和我的页 JS／WXML、`pages/verify/verify.wxml`、`utils/data.js`、`project.config.json`、`BACKEND_INTEGRATION.md`。
- 本轮不修改后端生产／测试源码，不提交或推送 GitHub。保留原字体、服务图片和五个底部导航。
- 初始备份位于 `C:\Users\19683\czt-backups\admin-entry-before-20261020-1610`，只包含修改前的 app.json、config/api.js、登录页和我的页各 JS／WXML，共六个文件，不是整项目快照。
- 实施记录：`docs/superpowers/plans/2026-10-20-admin-entry-and-verification.md`。

AppSecret 之前已在聊天中暴露，应在微信后台重置，再私下同步服务器配置；不要写进本文或前端。
