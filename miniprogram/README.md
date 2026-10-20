# 材智通 · 当前主前端

本目录为 2026-10-20 整理的最新原生微信小程序源码，共 31 个注册页面，已接入仓库 backend/ 的 HTTP API。保留微软雅黑声明、五个自定义底部导航、六个服务图片入口和个人页图标。

## 导入与配置

1. 微信开发者工具导入本目录（包含 app.json、project.config.json），不要导入 pages/ 或仓库根目录。
2. 沿用你有开发权限的 AppID；不要把 AppSecret 填进小程序。当前项目公开 AppID 为 wx3b3b713ab4afce40。
3. config/api.js 的 baseUrl 当前为 http://127.0.0.1:8080/api/v1，需要电脑本机后端已启动。真机不能用这个地址访问电脑，发布需配置可信 HTTPS 与合法域名。
4. 修改后使用完整「编译」。本机生成的 project.private.config.json 不入库。

## 接入范围

- 首页聚合与搜索；AI 通栏位于今日截止事项上方，点击展开独立提问区，失败不会伪造回答。
- 机会、通知、资格、提醒、组队、材料上传与预检任务。
- 报修、校园问题、资源时段与预约。
- 互助发布、借还、匿名交流与站内消息。
- 学生登录／手机号授权／学号认证，个人成长／信用、材料、隐私、导出与注销申请。
- 管理员独立登录和人工审核；需要后端预先授权、密码＋TOTP 以及 HTTPS，不自动按工号或手机号授予角色。

详情、功能限制与已验证范围见 [BACKEND_INTEGRATION.md](BACKEND_INTEGRATION.md)；管理员配置见 [ADMIN_GUIDE.md](ADMIN_GUIDE.md)。

学校官方数据继续预留，不把演示校园信息称作学校真实数据；AI 服务未配置、上传扫描未完成或账号无权限时，应显示真实限制，不显示假成功。

## 回归测试

需要 Node.js 和微信开发者工具自带的 WXML／WXSS 编译器，无需安装新的前端 npm 依赖。

```powershell
# 本机路径示例，安装位置不同请使用实际编译器路径
$env:WXSS_COMPILER = 'D:\微信开发者工具\微信web开发者工具\resources\app.asar.unpacked\node_modules\wcc-exec\wcsc.exe'
node --test tests/*.test.cjs
node tools/check-api-integration.cjs
node tools/live-api-check.cjs
```

在本目录执行。tools/check-api-integration.cjs 的编译器目录采用同一台开发机的默认安装位置；其他电脑需据实调整。最后一个命令连接真实本机公开接口，要求后端已运行，不创建测试账号或业务数据。

旧的 CONVERSION_SPEC.md、交接资料和手机适配说明记录的是早期转换／演示阶段，其页数、目录和数据说明不代表当前版本。当前入口与验收边界以上述接入文档及仓库交付清单为准。
