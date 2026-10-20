# 转换规范：Google Stitch HTML → 微信小程序

本规范用于把 `D:/qianduan/stitch_web_repository_frontend_designer/_N/code.html` 转换为
`C:/Users/Y7000P/caizhitong-miniprogram/` 下的原生微信小程序页面。

## 目标
把 21 个单文件 HTML 原型（Tailwind CSS + Material Symbols + Google 图片 + 内联 JS）
转换为可运行的微信小程序。要求：完全忠实还原页面内容与交互，但技术栈改为原生小程序。

## 页面映射（源目录 `_N` → 页面英文名 → 中文标题）

| 源 | 页面路径 | navigationBarTitleText |
|----|----------|------------------------|
| _1  | pages/service/service            | 服务 |
| _2  | pages/mutual/mutual              | 互助 |
| _3  | pages/opportunity/opportunity    | 机会 |
| _4  | pages/home/home                  | 首页 |
| _5  | pages/profile/profile            | 我的 |
| _6  | pages/precheck/precheck          | 材料预检 |
| _7  | pages/seatplan/seatplan          | 逸夫馆 3F 平面选座 |
| _8  | pages/seatreserve/seatreserve    | 图书馆座位预约 |
| _9  | pages/voucher/voucher            | 预约凭证与核销 |
| _10 | pages/reserveconfirm/reserveconfirm | 预约确认 |
| _11 | pages/postcreate/postcreate      | 发布互助 |
| _12 | pages/mutualdetail/mutualdetail  | 互助详情 |
| _13 | pages/chat/chat                  | 直接沟通 |
| _14 | pages/login/login                | 登录 |
| _15 | pages/phonebind/phonebind        | 手机绑定 |
| _16 | pages/verify/verify              | 学生认证 |
| _17 | pages/vault/vault                | 材料保险箱 |
| _18 | pages/neterror/neterror          | 网络异常 |
| _19 | pages/maintenance/maintenance    | 服务维护 |
| _20 | pages/denied/denied              | 权限受限 |
| _21 | pages/success/success            | 操作结果反馈 |

## 每个页面产出 4 个文件

```
pages/<name>/<name>.json   -> { "usingComponents": {}, "navigationBarTitleText": "<中文标题>" }
pages/<name>/<name>.wxml   -> 页面结构
pages/<name>/<name>.wxss   -> 页面私有样式（rpx）
pages/<name>/<name>.js     -> Page({ data: {...}, 方法... })
```

## 转换步骤

1. **读取** 源 `code.html`。
2. **只取** `<main>` 内部内容（忽略 `<head>`、末尾 `<script>`、固定的 `<header>` 顶栏——
   原生导航栏已替代它；但 `<main>` 里如果有 `<header>`/`<section>` 等标签照常转为 `<view>`）。
3. **标签映射**：
   - `div` / `section` / `footer` / `header` / `article` → `<view>`
   - `span` / `p` / `h1` / `h2` / `h3` / `h4` / `code` / `strong` / `label` / `b` / `em` → `<text>`
     （多行文本可在 `<text>` 内用 `\n`；`<text>` 不支持块级换行，长段落拆成多个 `<text>` 或单个 `<text>` 内 `\n`）
   - **重要**：`<text>` 只支持文字样式，不支持背景色/圆角/内边距/边框。所有「徽章 badge、胶囊 chip、圆点 dot、带背景的代码块」等带背景+圆角+内边距的元素，一律用 `<view>`（不要用 `<text>`）。`<text>` 只用于纯文字（纯文字内可嵌套 `<text>` 做加粗/变色）。
   - `button` → `<button>`（绑定 `bindtap`；小程序 `<button>` 默认样式已在 app.wxss 用 `.btn::after{border:none}` 复位）
   - `input` → `<input>`（placeholder 用 `placeholder` 属性，样式用 `.input` 类）
   - `img` → 见「图片处理」
   - `textarea` → `<textarea>`
4. **样式**：删除所有 Tailwind 类。改用 `app.wxss` 中已有的共享类（见下），页面特有布局写在页面私有
   `.wxss` 里，全部用 rpx（1px = 2rpx）。**不得残留任何 Tailwind 类名**。
5. **图标**：`<span class="material-symbols-outlined ...">名字</span>` → 用对应 emoji 的 `<text class="ico">`。
   见下方图标对照表。未列出的图标自行选最贴切的 emoji。
6. **图片**：Google 图片（`lh3.googleusercontent.com` 等外链）与内联 SVG 一律替换为
   `<view class="ph-image" style="height: XXXrpx;">📷 描述文字</view>`。
   高度根据原布局合理设定（头像约 64rpx、卡片图约 240rpx、封面约 320rpx）。绝不引用外链。
7. **交互**：内联 JS（`onclick`、`addEventListener`、`setTimeout` 等）改为 `bindtap` + `Page` 方法。
   - `onclick="history.back()"` → `bindtap="goBack"`，方法：
     ```js
     goBack() {
       const pages = getCurrentPages();
       if (pages.length > 1) wx.navigateBack();
       else wx.switchTab({ url: '/pages/home/home' });
     }
     ```
   - 状态切换（评分、投票、tab、开关、抽屉、倒计时、勾选）→ `data` 字段 + `setData`。
   - `setTimeout` 可用 `setTimeout`，但涉及定时刷新 UI 用 `this.setData`。
8. **保真**：所有中文文案、脱敏数据（`138****8826`、`2024****08`、匿名昵称「晨读的银杏叶 #4092」、
   虚构姓名「林学长」「张师傅」等）、合规徽章（「演示数据」「模拟预约」等）、错误码、时间戳，
   **逐字保留，不得改动**。

## app.wxss 已有共享类（优先复用）

- 布局：`.page` `.page-safe` `.col` `.row` `.flex-1` `.center` `.space-between` `.wrap` `.gap-xs/.gap-sm/.gap-md`
- 文本：`.headline-xl` `.headline-lg` `.headline-sm` `.title` `.body-lg` `.body` `.body-sm`
  `.label-md` `.label-sm` `.caption` `.mono` `.muted` `.faint` `.primary-text` `.secondary-text`
  `.tertiary-text` `.error-text` `.bold` `.semibold` `.truncate` `.text-center` `.text-left`
- 容器：`.card` `.card-pad-sm` `.card-pad-lg` `.well` `.well-high` `.divider` `.hairline`
- 徽章：`.badge` + `.badge-primary/.badge-secondary/.badge-tertiary/.badge-error/.badge-demo/.badge-outline/.badge-green/.badge-plain`
- 按钮：`.btn` + `.btn-primary/.btn-primary-container/.btn-secondary/.btn-outline/.btn-ghost/.btn-neutral`
  + 尺寸 `.btn-sm/.btn-xs`
- 图标：`.ico` + 尺寸 `.ico-24/.ico-28/.ico-32/.ico-40/.ico-48`
- 头像：`.avatar` `.avatar-lg`；圆底 `.icon-round` `.icon-round-sm` `.icon-round-md`
- 输入：`.input` `.placeholder`
- 分区：`.section-head` `.section-title`；列表 `.list-row`
- 图片占位：`.ph-image`

颜色令牌（可在页面 `.wxss` 用 `var(--primary)` 等）：
`--primary #00324b` `--on-primary #fff` `--primary-container #1b4965` `--on-primary-container #8eb8d8`
`--secondary #1a6b4a` `--on-secondary #fff` `--secondary-container #a5f3c9` `--on-secondary-container #237150`
`--secondary-fixed #a5f3c9` `--on-secondary-fixed-variant #005235`
`--tertiary #5c1600` `--tertiary-container #7f2708` `--on-tertiary-container #ff9878`
`--error #ba1a1a` `--error-container #ffdad6` `--on-error-container #93000a`
`--on-surface #121c2c` `--on-surface-variant #41474d` `--outline #72787e` `--outline-variant #c1c7ce`
`--surface #f9f9ff` `--surface-low #f0f3ff` `--surface-container #e7eeff`
`--surface-container-high #dee8ff` `--surface-container-highest #d9e3f9` `--surface-lowest #ffffff`
`--wechat-green #07c160`

## 图标 → emoji 对照表

| Material Symbol | emoji | | Material Symbol | emoji |
|---|---|---|---|---|
| arrow_back_ios_new | (原生返回) | | star | ⭐ |
| person | 👤 | | star_border / star_half | ☆ / ⭐ |
| more_horiz / radio_button_checked | (原生胶囊) | | thumb_up | 👍 |
| security / shield / verified_user | 🛡️ | | favorite | ❤️ |
| lock | 🔒 | | bookmark | 🔖 |
| wifi_off | 📶 | | location_on | 📍 |
| power_off / power | 🔌 | | calendar_today / schedule / event_busy | 📅 / 🕐 / ⏰ |
| task_alt / check_circle / done_all / verified / check | ✅ | | chevron_right | › |
| archive | 📦 | | expand_more | ⌄ |
| refresh / sync_problem | 🔄 | | close | ✕ |
| search_off | 🔍 | | add / add_circle | ＋ / ➕ |
| travel_explore | 🧭 | | remove | － |
| tips_and_updates / info | 💡 / ℹ️ | | send | ➤ |
| filter_alt_off / merge_type | 🔀 | | image / camera_alt | 🖼️ / 📷 |
| badge / school / workspace_premium | 🎓 | | visibility / visibility_off | 👁️ / 🙈 |
| volunteer_activism / handshake | 🤝 | | progress_activity | ⏳ |
| eco | 🌱 | | gpp_maybe | ⚠️ |
| radar | 📡 | | gpp_bad | 🛑 |
| qr_code_scanner / qr_code / qr_code_2 | 📷 / ▦ | | policy | 📜 |
| desk / chair | 🪑 / 💺 | | terminal / dashboard_customize | 💻 / 📊 |
| auto_awesome | ✨ | | folder_zip | 📁 |
| groups / diversity_3 | 👥 | | delete_forever | 🗑️ |
| enhanced_encryption | 🔐 | | upload_file | ⬆️ |
| phone_iphone | 📱 | | help_outline | ❓ |
| window | 🪟 | | account_balance | 🏛️ |
| wb_sunny | ☀️ | | link_off | 🔗 |
| sync_problem / devices | 🔄 / 💻 | | menu_book / local_library / library_books | 📚 |
| science | 🔬 | | build | 🛠️ |
| cleaning_services | 🧹 | | local_cafe | ☕ |
| directions_bus | 🚌 | | restaurant | 🍽️ |
| support_agent | 🎧 | | report_problem / campaign | ⚠️ / 📢 |
| emoji_events | 🏆 | | fingerprint | 🔎 |

未列出的图标：选最贴近语义的 emoji，保持视觉清楚即可。

## 图片占位示例

原：
```html
<img src="https://lh3.googleusercontent.com/..." class="w-full h-40 object-cover rounded-xl" alt="图书馆">
```
转：
```xml
<view class="ph-image" style="height: 240rpx;">📷 图书馆</view>
```

## JS 骨架模板

```js
Page({
  data: {
    // 页面状态字段
  },
  onLoad() {},
  goBack() {
    const pages = getCurrentPages();
    if (pages.length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/home/home' });
  },
  // 其它交互方法
});
```

## 质量检查（完成前自查）

- [ ] `.wxml` 无 `<div>/<span>/<p>/<h*>/<img>`，全部为小程序标签
- [ ] 无 Tailwind 类、无 Material Symbols 类、无外链图片、无 `<script>`/`<link>`
- [ ] 所有 emoji 图标含义与原 Material Symbol 一致
- [ ] 所有交互已绑定并可在小程序运行（无 `onclick`，用 `bindtap`）
- [ ] 脱敏数据与合规徽章逐字保留
- [ ] 颜色均来自令牌或明确的小程序色值，单位为 rpx
