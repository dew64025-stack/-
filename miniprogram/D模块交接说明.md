# 材智通小程序 · D 模块交接说明

> 交接方：负责人 D —— 会话消息 · 订阅 · 首页 · 我的 · 数据权利 · 管理运维
> 交接时间：2026-10-15
> 对应后端分工：`dew64025-stack/backend-接口分工-4人.md` 第 7 节「负责人 D」（Task 10 + 12 + 13，45 条接口）
> 阅读顺序：先读本项目根目录《交接文档.md》（21 页全局交接），再读本文档（D 模块专项）。

---

## 一、D 模块是什么

后端把 206 条接口按业务域拆给 4 人（A/B/C/D），**负责人 D** 负责「会话消息 · 订阅 · 首页 · 我的 · 数据权利 · 管理运维」，映射到小程序前端共 **3 个页面**：

| 小程序页面 | 标题 | 后端对应小节 |
|------------|------|--------------|
| `pages/home/home` | 首页 | §7.3 首页 / 搜索 / AI 助手 / 异步任务 |
| `pages/profile/profile` | 我的 | §7.4 我的 / 成长 / 信用 + §7.5 数据权利 |
| `pages/chat/chat` | 直接沟通 | §7.1 会话与消息 |

> §7.2「通知 / 订阅 / 实时」目前**无独立前端页面**，见「五、待办」第 2 条。

## 二、当前完成度

三个页面 **UI 全部完成**，可整体编译；但**交互均为本地演示**（`data + setData + setTimeout` 模拟），**尚未接入真实后端**。

| 页面 | UI | JS | 说明 |
|------|-----|-----|------|
| home | ✅ | ✅ 完整 | AI 搜索三阶段状态机、快捷胶囊回填、7 个业务按钮 |
| profile | ✅ | ⚠️ 仅演示 | 全部入口 `onAction` → Toast，纯静态，无真实跳转/表单 |
| chat | ✅ | ✅ 完整 | 本地消息发送、快捷回复、滚动到底、跳互助详情 |

## 三、文件清单（D 模块）

```
pages/home/home.{json,wxml,wxss,js}
pages/profile/profile.{json,wxml,wxss,js}
pages/chat/chat.{json,wxml,wxss,js}
```

依赖的根文件（已随项目交付，勿删）：
- `app.js` —— `globalData` 脱敏用户（`晨读的银杏叶 #4092` / `138****8826` / `2024****08`）+ `demoMode: true`
- `app.json` —— 21 页注册 + 5 个底部 Tab（home 为首页）
- `app.wxss` —— 设计令牌 + 共享样式类（所有页面复用）
- `CONVERSION_SPEC.md` —— HTML → 小程序转换规范（含图标 emoji 对照表）

## 四、各页面已实现功能

### 4.1 首页 `home`
- **AI 校园助手**：输入框（placeholder「搜索：材料学院奖学金申报材料 / 图书馆选座 / 空调报修」）；3 个快捷胶囊（`onQuickTap` 回填）；提交走 `submitAi` 三阶段状态机（🔄 → ✅ → 清空并提示「已接收查询…」）。
- 硬编码演示卡片：今日截止（奖学金申报·截止倒计时 6 小时）、模拟预约（扫码签到 / 释放座位）、机会匹配（互联网+ 大赛 94% 符合）、校园热点（食堂拥堵 142 关注 / 89 附议）、附近互助（教材循环 / 失物招领）。
- `onAction` 按钮（7 个）：立即去核对 / 查看原文 / 更多项目 / 参与附议 / 扫码签到 / 释放座位 / 去认领。

### 4.2 我的 `profile`
- 身份卡：头像占位 + 林学长 + 研一在读 + 脱敏手机号 `138****8826`（已脱敏徽章）+ 学号 `2024****08` + 学生认证「官方核验」。
- 统计四宫格：待办 3 / 预约 1 / 工单 2 / 发布 4。
- 材料保险箱：3 条文档（身份证复印件 v2.1 / 成绩单 clean / 加分证明 3 项）+「管理材料与授权」。
- 成长档案：2 张成长卡（竞赛二等奖已核验 / 志愿时长 18h）。
- 信用记录：信用分 100/100 +「申请异议复核」入口 + 合规说明（无 AI 黑箱评分）。
- 隐私、授权与数据权利：授权记录与撤回 / 个人数据导出 / 账号注销冷静期（15 天）。
- 全部 6 个入口均 `onAction` → Toast 演示（编辑资料 / 管理材料与授权 / 申请异议复核 / 授权记录与撤回 / 个人数据导出请求 / 账号注销冷静期申请）。

### 4.3 直接沟通 `chat`
- 三种气泡（`system` 系统提示 / `left` 对方 / `right` 本人）+ 底部借用申请卡片。
- 本地输入发送（`send` 追加右侧气泡 + 滚动到底）、快捷回复（`quickSend` ×3）、`goDetail` 跳互助详情。

## 五、待办 / 剩余工作（交接重点）

1. **接入真实后端 API**：把 `setTimeout` / 本地数组改为 `wx.request`，接口见第六节；保持现有错误码 / 合规文案（如 409、502、422、403 等异常页契约文案）。
2. **补「通知 / 订阅」页面或入口**：后端 §7.2 的 `me/notifications`、`subscriptions`、`realtime/tickets` 目前无页面承接，建议在「我的」加「消息通知 / 订阅管理」入口，或在首页加未读角标。
3. **图片占位替换**：home / chat 的 `ph-image` 占位块 → 本地 `assets/` 图片 + `<image src="/assets/...">`。
4. **profile JS 完善**：目前纯 `onAction` → Toast，需补真实交互：编辑资料、授权记录、数据导出（ZIP 下载）、账号注销冷静期（申请 / 撤销 / 倒计时）等。
5. **页面跳转串联**：home 卡片 → 机会 / 服务 / 互助详情；chat → 互助详情；profile 各入口 → 对应功能页。
6. **实时消息**：chat 接入 WebSocket（§7.2 `realtime/tickets` 短时凭证），或退化为增量轮询。

## 六、对应后端接口清单（负责人 D，共 45 条）

### 6.1 会话与消息（§7.1，5 条）
| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/v1/conversations` | S | 按上下文建会话（校验双方，复用既有） |
| GET | `/api/v1/conversations` | L | 分页会话 |
| GET | `/api/v1/conversations/{id}/messages` | 成员 | 分页消息 |
| POST | `/api/v1/conversations/{id}/messages` | 成员且 S | 发消息（clientMessageId 唯一） |
| POST | `/api/v1/conversations/{id}/read` | 成员 | 已读游标 |

### 6.2 通知 / 订阅 / 实时（§7.2，6 条）
| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/me/notifications` | L | 分页通知 |
| POST | `/api/v1/notifications/{id}/read` | 接收人 | 已读 |
| POST | `/api/v1/notifications/read-all` | L | 全部已读（beforeId 避免漏并发） |
| POST | `/api/v1/subscriptions` | L | 订阅（templateId/scene/consentResult） |
| DELETE | `/api/v1/subscriptions/{id}` | 所有者 | 撤销订阅 |
| POST | `/api/v1/realtime/tickets` | L | WebSocket 短时凭证 |

### 6.3 首页 / 搜索 / AI / 异步（§7.3，4 条）
| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/home/overview` | P/L | 首页聚合（deadlines/recommendations/hotIssues/nearbyPosts/unreadCount） |
| GET | `/api/v1/search` | P/L | 全局搜索 |
| POST | `/api/v1/ai/assistant/messages` | L | AI 助手，202 AsyncJob |
| GET | `/api/v1/jobs/{jobId}` | O/管理员 | 异步任务结果 |

### 6.4 我的 / 成长 / 信用（§7.4，10 条）
| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/me/dashboard` | L | 个人中心概览 |
| GET | `/api/v1/me/todos` | L | 待办聚合 |
| GET | `/api/v1/me/history` | L | 浏览历史 |
| DELETE | `/api/v1/me/history` | L | 清空历史 |
| GET | `/api/v1/me/growth-records` | L | 成长记录 |
| POST | `/api/v1/me/growth-records` | S | 新增（proofFileId） |
| POST | `/api/v1/me/growth-records/{id}/update` | O | 编辑 |
| DELETE | `/api/v1/me/growth-records/{id}` | O | 删除 |
| GET | `/api/v1/me/credit-events` | L | 信用事件（显示依据） |
| POST | `/api/v1/me/credit-events/{id}/disputes` | 本人 | 信用纠错（人工复核） |

### 6.5 数据权利（§7.5，10 条）
| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/v1/me/authorizations` | L | 授权列表 |
| POST | `/api/v1/me/authorizations` | L | 新增授权 |
| POST | `/api/v1/me/authorizations/{id}/revoke` | O | 撤销授权 |
| GET | `/api/v1/me/privacy-settings` | L | 隐私设置 |
| POST | `/api/v1/me/privacy-settings/update` | L | 更新隐私设置 |
| POST | `/api/v1/me/data-exports` | L | 数据导出，202 DataRequest |
| POST | `/api/v1/me/account-deletion` | L | 注销，202 DataRequest |
| GET | `/api/v1/me/data-requests` | L | 数据请求列表 |
| GET | `/api/v1/me/data-requests/{id}` | O | 详情（导出成功含短期下载凭证） |
| POST | `/api/v1/me/data-requests/{id}/cancel` | O | 撤销（仅冷静期） |

### 6.6 管理运维（§7.6，11 条，后台）
`/api/v1/admin/*`（campuses / campus configs / statistics / audit-logs / integrations），**不在本 3 个学生端页面内**，需另建后台管理页（权限 `A(code)`，不在小程序前端交付范围）。

## 七、关键实现要点（来自后端分工文档，接手必读）

- **消息可靠性**：先落库再通知，发送失败有限重试；`clientMessageId` 唯一去重、已读游标单调；WebSocket 认证不把永久令牌放 URL。
- **数据权利**：导出 / 注销为 202 异步（DataRequest），含**冷静期**，撤销仅冷静期；导出成功给短期下载凭证。
- **AI 助手**：无依据明确「未知」，不编造；scope 限 campus / opportunity / service。
- **首页聚合**：依赖 A/B/C 的公开窄读取接口（跨模块），联调时注意协作契约。

## 八、运行与规范

- **运行方式**：微信开发者工具「导入项目」→ 目录指向本项目 → AppID 选测试号。详见根目录《交接文档.md》第四节。
- **样式 / 标签 / 图标规范**：见 `CONVERSION_SPEC.md` 与根目录《交接文档.md》第六、七节（WXML 标签铁律、rpx 单位、共享类、emoji 对照表）。
- **合规红线**：脱敏数据（`138****8826`、`2024****08`、匿名昵称「晨读的银杏叶 #4092」）与「演示数据 / 模拟预约」徽章**逐字保留**，无在线支付，无 AI 黑箱人格评分。
