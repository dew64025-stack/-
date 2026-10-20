# 材智通 · 校园一站式服务小程序

通过「首页、机会、服务、互助、我的」五个入口，连接校园机会、材料准备、服务办理、同伴互助和个人成长记录。

## 先看这里

- **最新可导入前端：[`miniprogram/`](miniprogram/)**。微信开发者工具导入包含 `app.json` 和 `project.config.json` 的这一层，不要只导入 `pages/`。
- **后端：[`backend/`](backend/)**，Java 21 / Spring Boot 4；数据库迁移在 [`sql/`](sql/)。
- **参赛介绍：[`docs/competition/作品参赛介绍.md`](docs/competition/作品参赛介绍.md)**，作品简介、创意来源、运行环境各不超过 150 字。
- **本次交付清单与验证边界：[`docs/delivery/completed-delivery-2026-10-20.md`](docs/delivery/completed-delivery-2026-10-20.md)**。

## 功能范围

| 入口 | 已接入的功能范围 |
| --- | --- |
| 首页 | 截止事项、机会推荐、热点与互助聚合、搜索、通栏 AI 校园助手入口 |
| 机会 | 机会／通知详情、资格检查、提醒、竞赛组队、材料清单及预检任务 |
| 服务 | 报修与评价、校园问题、资源／时段查询、预约与签到接口 |
| 互助 | 失物招领、二手教材、技能与物品借用、匿名交流、站内会话 |
| 我的 | 身份与资料、成长记录、材料保险箱、信用异议、待办历史、隐私与数据申请 |
| 管理员 | 独立密码＋动态码登录入口、本校学生认证人工审核 |

“接口和页面接入”不代表已完成所有线上验收。真实微信授权、管理员 HTTPS 会话、上传扫描、AI 服务与学校官方接入等依赖，必须按部署配置和专用账号继续验证，不能用演示数据、假登录或假成功替代。

## 环境与启动

1. 后端按 [`backend/README.md`](backend/README.md) 配置 MySQL、Redis、S3 兼容存储、ClamAV 和必要环境变量；数据库按 [`DATABASE.md`](DATABASE.md) 与迁移说明准备。不要覆盖已有业务库或跳过迁移。
2. 使用 JDK 21、Maven 启动后端，例如 `mvn -f backend/pom.xml spring-boot:run`。密钥、数据库密码、微信 AppSecret 只能通过服务器私有环境配置提供。
3. 微信开发者工具导入 `miniprogram/`，在 `config/api.js` 配置接口地址。当前 `127.0.0.1` 仅供电脑本机演示；真机与发布需要可达的 HTTPS 域名及微信合法域名配置。
4. 管理员入口需要 HTTPS、授权开通的账号及 TOTP，见 [`miniprogram/ADMIN_GUIDE.md`](miniprogram/ADMIN_GUIDE.md)。不能凭输入手机号／工号获得管理权限。
5. AI 助手、通知解析和材料预检的真实模型能力需后端配置。可选本地 Ollama 方案见 [`docs/backend/ollama-local.md`](docs/backend/ollama-local.md)，不在小程序中保存模型密钥。

## 测试与联调说明

- 前端执行方式、功能对应及待验收项：[`miniprogram/BACKEND_INTEGRATION.md`](miniprogram/BACKEND_INTEGRATION.md)。
- 后端常规回归：`mvn -f backend/pom.xml test`。真实 MySQL／文件服务／模型测试需要显式启用并使用专用隔离环境，不能指向运行中的业务数据库。
- 本次结果与每项跳过说明，以交付清单为准，不把历史测试数当成新验证结果。

## 目录说明

`miniprogram/` 为当前主前端；`miniprogram-demo/` 为早期 API 演示；`stitch_web_repository_frontend_designer/` 为原始 HTML／图片原型。后两者保留用于历史参考，不是本次推荐导入目录。

`.env*`、私有启动脚本、`project.private.config.json`、日志和构建产物不随本次提交。仓库中的 AppID 是公开项目标识，不是 AppSecret；曾经暴露的真实密钥应重置后在服务器私下配置。
