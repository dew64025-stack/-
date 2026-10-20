# 小程序学生与管理员入口 Implementation Plan

> **For agentic workers:** 本轮在当前会话逐项执行 executing-plans 流程，记录测试检查点；前端不是 Git 仓库，不创建无意义工作树、不提交后台已有用户改动。

**Goal:** 在同一小程序中提供学生与管理员入口，以及本校学号人工审核页面。

**Architecture:** 学生继续使用微信会话；管理员复用现有密码、MFA、安全 Cookie 与 CSRF 接口，用独立、仅内存保存的客户端处理。审核操作沿用服务端权限、校园、状态、version 与审计检查，不新增手机号/工号自动授予管理员的规则。

**Tech Stack:** 原生微信 WXML/WXSS/JavaScript，Node test，现有 Spring Boot 管理员 API。

**Spec:** 用户已批准：同一入口、后台授权、学生/管理员分开会话；先实现学号人工审核，不铺开其他管理模块。

## Global Constraints

- 前端 D:/材智通-原版手机适配-20261019；后端 D:/caizhitong-review。后端工作树已有用户改动，本轮不更改后端生产认证代码。
- 管理员地址必须为 HTTPS，优先 config/api.js 的 adminBaseUrl，未填时仅在 baseUrl 本身为 HTTPS 时可复用。当前 HTTP 演示地址不能发送管理密码、MFA、会话 Cookie。
- 保留密码+6位 TOTP、ADMIN-XSRF-TOKEN Cookie 与 X-ADMIN-CSRF 头、ADMIN-REFRESH 安全 Cookie；不关闭或豁免 CSRF。
- 管理员令牌/Cookie/挑战仅内存保存，不写 wx Storage、日志或 Page.data；密码/验证码只在输入时短暂使用，提交后清空。
- 请求仅能访问 /admin 下的相对 API；学生请求不携带管理员令牌；身份真实切换先退出原会话，再 reLaunch 清空页面栈；HTTPS未配置时仅查看提示，不退出学生会话。
- 不自动创建管理员或固定测试密码，不真实审核用户申请；已有账号需由授权人员受控初始化。
- 学号队列仅展示服务端返回的脱敏学号；rosterMatched 是线索，不能代替核验本人身份。
- 学生端原有视觉、字体、图标和五个 tab 保留；新增页沿用现有低饱和蓝、白色卡片、统一按钮，不添加依赖。

## Task 1: 独立管理员客户端

Files: 新建 utils/admin.js、tests/admin-client.test.cjs；配置 config/api.js 增加 adminBaseUrl。

Produces:
- admin.login(username,password) -> 密码挑战到期时间，不表示已登录。
- admin.verify(code) -> 服务端管理员资料；必须完成 MFA 后才保存管理员访问状态。
- admin.get(path,query)、admin.post(path,data,key)；不接收学生令牌。
- admin.current() 返回资料、权限与到期时间，不返回令牌/Cookie；admin.hasSession()、admin.clear()、admin.logout()。
- admin.secureConfigured() 用于展示 HTTPS 未配置状态。

- [x] 写失败测试：HTTP 禁止发密码、密码阶段不能获得权限、MFA 与 CSRF/Cookie 正确绑定、受保护请求使用独立 Bearer、刷新只用安全刷新 Cookie。
- [x] 运行 node --test tests/admin-client.test.cjs，确认新增能力缺失导致失败。
- [x] 实现最小管理员客户端：HTTPS 校验、仅白名单 Cookie、CSRF 获取合并、密码挑战、MFA、刷新合并、最多一次 401 重试、防重 key、会话代次保护。
- [x] 测试旧会话响应/401不能污染新账号，403/503不伪造成功、刷新失败不清学生会话，Cookie不得发到学生路径。
- [x] 运行聚焦测试，确认通过后再进行页面接入。

Fixture example:
```js
assert.equal(admin.hasSession(), false);
await admin.login('teacher-001', 'test-only-password');
assert.equal(admin.hasSession(), false); // challenge不是会话
await admin.verify('123456');
assert.equal(admin.hasSession(), true);
```

## Task 2: 入口、切换与人工审核页面

Files:
- 新建 utils/identity.js，pages/adminlogin/adminlogin.{js,wxml,json,wxss}，pages/adminreview/adminreview.{js,wxml,json,wxss}。
- 修改 pages/login/login.{js,wxml}、pages/profile/profile.{js,wxml}、app.json。
- 新建 tests/admin-pages.test.cjs。

Consumes: Task 1 管理员客户端，现有 student session/request、ui.load/action/confirm 和 data.date。

- [x] 写失败测试：取消身份切换不退出，退出失败不能假装切换成功；学生退出后清本地会话并 reLaunch；密码阶段不跳管理页；MFA成功才跳转。
- [x] 写失败测试：审核页先 GET /admin/me，缺 identity.review 不加载队列；只读取当前管理员校园，由后端裁定。
- [x] 写失败测试：通过需确认已核验本人、两种决策均需原因；POST 带 decision/reason/version；失败不显示成功，409刷新真实状态，重复提交不重复生成防重 key。
- [x] 实现入口：登录页、我的页增加教职工/管理员入口；管理员切学生退出并重建页面栈，不复用旧页面数据。
- [x] 实现管理员登录：工号/账号、密码、TOTP两阶段，HTTPS未配置明确提示，密码不持久化，离开页清输入并拦截迟到结果。
- [x] 实现管理员工作台：服务端管理员昵称/校园范围、待审/通过/驳回筛选、脱敏申请列表、分页、核验说明、选择申请后填写原因通过/驳回；无权限或退出时清除队列。
- [x] 新增页配置与样式；保持现有主导航不变。
- [x] 运行 node --test tests/admin-pages.test.cjs 和现有学生回归。

审核载荷：
```json
{"decision":"approve","reason":"已核对本人提供的可信学生身份凭据","version":0}
```
审核路径：
```text
GET /api/v1/admin/me
GET /api/v1/admin/student-verifications?status=pending&limit=20
POST /api/v1/admin/student-verifications/{id}/decision
```

## Task 3: 验证、复核与使用说明

Files: 新建 ADMIN_GUIDE.md，更新 BACKEND_INTEGRATION.md 和本计划。

- [x] 设置真实 WXSS_COMPILER 后运行 node --test tests/*.test.cjs；不得删除测试或因缺配置意外跳过。
- [x] 运行 node tools/check-api-integration.cjs：全部注册页面、事件、资源、官方 WXML/WXSS 编译。
- [x] 后端运行 IdentityHttpTest 和 SessionHttpBoundaryTest，证明现有人工审核、CSRF、MFA、会话与校园边界未放宽；与真实登录验收区分。
- [x] 运行 node tools/live-api-check.cjs，确保原学生公开页面不回退。
- [x] 微信开发者工具完整编译，检查学生入口到管理员登录、HTTPS缺失提示和未登录管理页不暴露申请数据；不在真实环境填写假管理员或通过真实学生申请。
- [x] 按代码复核技能进行一次聚焦只读审查，修复重要问题后复测。
- [x] ADMIN_GUIDE.md 说明实际入口、HTTPS配置、账号由受控AdminBootstrap开通、工号只作为登录名、TOTP配置、审核核验责任、当前不含真实申请人证据展示与部署后验收项；不得写密码或MFA种子。
- [x] 报告文件与测试结果；不推送GitHub、不声称完成尚未具备HTTPS/管理员凭据的真实手机验收。

## 检查点

实施已保存。初始6文件备份：C:/Users/19683/czt-backups/admin-entry-before-20261020-1610（不是整项目快照）。

- 最终前端：80通过、0失败、0跳过；31页官方WXML/WXSS编译通过；本机公开API 14次请求通过。
- 后端HTTP边界：IdentityHttpTest 34项、SessionHttpBoundaryTest 10项，共44通过、0失败/错误/跳过；本轮不修改后台源码。
- 独立只读复核：33项针对性测试通过，未发现重要缺陷。
- 实际开发者工具：完整编译后，从我的进入管理员HTTP提示仍保留学生身份，返回后为pages/profile/profile且会话保留；直接进入adminreview为needsLogin=true、申请0条，未捕获运行异常。截图：C:/Users/19683/czt-backups/admin-login-https-required.png。
- 不创建管理员、不审批真实申请、不提交或推送GitHub。工作目录保留。
- 尚未验收：真实HTTPS Cookie/CSRF、管理员密码/TOTP、真实审核/撤权/并发与真机。需用户配置可信HTTPS并明确授权管理员账号后再做，不能将模拟测试当作生产验收。
