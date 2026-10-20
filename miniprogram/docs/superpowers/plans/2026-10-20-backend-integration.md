# 小程序接入本机后端实施计划

> **For agentic workers:** 本轮在当前会话逐项执行，使用 executing-plans 流程记录检查点。

**Goal:** 当前手机适配版通过真实 HTTP API 读写已有后端，消除本地模拟成功。

**Architecture:** 原生微信小程序，新增统一请求、会话与错误处理工具；页面按后端 DTO 绑定数据。保留现有字体、按钮、服务图标和五个 tab。

**Tech Stack:** WXML/WXSS/JavaScript、wx.request/wx.uploadFile、Spring Boot /api/v1。

**Spec:** 当前用户指令“前端接入后端”；契约以 D:/caizhitong-review/backend/src/main/java/com/caizhitong 各 Controller、Inputs、Views 为准。

## Global Constraints

- 后端本机地址 http://127.0.0.1:8080/api/v1，真实设备需配置可达 HTTPS 域名。
- ID 保留字符串；不得在前端放 AppSecret、数据库密码或伪造登录令牌。
- 写请求带 Idempotency-Key，重试沿用原 key；刷新令牌并发合并且最多重试一次。
- 网络失败和未登录不得以样例数据或本地成功代替；保留错误码、字段错误和 requestId。
- 后端工作区已有用户修改，保留既有改动；仅为本人材料预览补充 Version.attachmentId 字符串字段，并验证原有文件授权测试。

## 任务与检查点

- [x] 1. config/api.js、utils/request.js、utils/session.js、utils/ui.js：统一请求、微信登录、并发刷新、幂等、错误提示；node --test tests/api-request.test.cjs 验证会话边界。
- [x] 2. app.js 与登录/手机号/学生认证：wx.login code 换会话，getPhoneNumber code 绑定，学生申请只提交真实 studentNumber，结果展示 pending/approved/rejected。
- [x] 3. 首页/机会/互助：公开列表、分页、搜索、分类、真实详情；机会收藏、资格核验、材料清单和互助联系均从真实 ID 发起。
- [x] 4. 服务/报修/问题/预约：保留六个彩色入口，获取真实资源时段与座位；提交预约、取消、扫码签到和评价使用服务端状态/version。
- [x] 5. 我的/设置/材料：真实用户、成长/信用事件、授权撤回、隐私开关、导出与注销确认；上传必须扫描 clean 才绑定材料。
- [x] 6. 发布/聊天/预检：真实发帖、借用、会话、消息；上传和预检任务轮询有超时与退出取消，不显示伪造凭证。
- [x] 7. 验收：Node 请求层行为测试、所有页面 JS/JSON/WXML/WXSS 编译、真实公开 API 与未授权边界测试；输出接口覆盖和待真实微信验证项到 BACKEND_INTEGRATION.md。

## 执行命令

```powershell
node --test tests/api-request.test.cjs
node tools/check-api-integration.cjs
```

微信模板/样式编译器位于 D:/微信开发者工具/微信web开发者工具/resources/app.asar.unpacked/node_modules/wcc-exec/。

## 本轮收尾结果（2026-10-20）

上述勾选表示接入代码、约定的本地检查及交付说明已完成，不代表每个需要微信身份或学校接入的业务都已真实验收。

- 前端：47 项回归测试通过，失败 0，跳过 0；29 个注册页面官方 WXML/WXSS 编译通过。
- 真实本机后端：14 次 wx.request 适配请求完成公开页面和未登录边界检查，没有创建账号或业务数据。
- 微信开发者工具：实际验证首页、机会与详情、互助、服务资源及时段、我的与设置，未捕获运行异常；与 Node 模拟接口测试分开计数。
- 后端最小兼容改动：材料版本补充 attachmentId；FileHttpTest 13 项通过、0 跳过；新版 jar 已打包启动。
- 明确预留：学校身份接口、学校签到令牌、真实微信授权/手机号、生产 HTTPS、发布版本的材料 AI 授权说明、互助收藏以及未提供后端接口的材料打包/模板下载。
- 详细边界、配置、修改清单和复测命令见根目录 BACKEND_INTEGRATION.md。

- 独立只读复核发现跨账号旧请求重放风险，已加入会话代次隔离及旧成功响应拦截，复核确认可按现有验收边界交付；新增图片/PDF/ZIP回归后47项前端测试再次全通过。
