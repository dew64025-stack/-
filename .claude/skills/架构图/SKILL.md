---
name: 架构图
description: 将架构、工作流、时序、数据流、生命周期/状态等图表，生成为精美且经过校验的可探索独立 HTML（内联 SVG、深/浅色主题、可选轨迹动画，支持 PNG/JPEG/WebP/SVG/WebM 导出）。接受自然语言需求或粘贴的 Mermaid flowchart、sequenceDiagram、stateDiagram 输入；当图表需要反映真实代码时核查仓库证据。当用户要求可视化系统架构、基础设施、云/安全/网络拓扑、技术工作流、API 调用时序、请求生命周期、数据管道、ETL/ELT、数据血缘、状态机，或转换/美化 Mermaid 时使用。
license: MIT
metadata:
  version: "2.17"
  author: tt-a1i
  based_on: Cocoon-AI/architecture-diagram-generator (MIT, v1.0)
---

# 架构图

从一个小的类型化 JSON 规范生成自包含、可交互的 HTML 图表。静态输出是默认；只有当用户要求演示或展示时才启用动画。

## 快速创作路径

对普通生成使用这条有界路径。除非用户问及这些功能，否则不要读取可选的「查看器运行时」参考。

1. 从问题中选出 `architecture`、`workflow`、`sequence`、`dataflow` 或 `lifecycle` 之一。
2. 在 `schemas/` 里读一个匹配的 schema、读 `schemas/common.schema.json`，并在 `examples/` 里读一个匹配的 JSON 示例。只读这些文件。全新创作意味着新的稳定 ID、领域措辞和布局；示例只用于了解字段形状，不用于事实。新的 workflow 源使用 `schema_version: 2` 及其可读的布局契约；只有当保留既有 workflow 的固定几何时才用 `schema_version: 1`。当真实产品身份重要时，用 `node bin/archify.mjs brands "<name>" --json` 查询；仅当遇到未知品牌且用户提供了 URL 时才读 `references/brand-marks.md`。
3. 先产出产物：下一步工具动作必须是写出候选文件。在检查渲染器内部实现之前先写出候选。不要在散文里规划精确坐标。从一个清晰的主路径、短侧支、稀疏标签、最多 12 个主节点开始。除非用户明确要求密集的 `standard` 地图，否则把 `meta.quality_profile` 设为 `"showcase"`。先用自动路由和标签。在诊断确实要求之前，不要添加 `via`、`channelX`、`channelY` 或 `labelAt`；每次修复最多只施加一个经诊断确认的几何控制。
4. 每次编辑候选后以及交接前立即校验：

   ```bash
   node bin/archify.mjs validate <type> <candidate.json> --quality showcase --json
   ```

   只有 4 项产物检查的回执是基础校验，绝不是 showcase 验收。showcase 通过必须报告全部 9 项产物检查、0 个组合错误、0 个警告。如果候选遗漏或拼错了 `meta.quality_profile` 字段，先修好它再处理几何。对 workflow v2 的几何诊断，运行 `node bin/archify.mjs validate workflow <candidate.json> --layout-json` 并使用稳定的编译器回执；求解器内部不是创作控制。最终校验通过即冻结候选：之后绝不再编辑它。
5. 对于交付的 HTML，`deliver` 是最终验收命令：

   ```bash
   node bin/archify.mjs deliver <type> <candidate.json> <output.html> --quality showcase --json
   ```

   非零退出永远不能描述为成功。失败的交付会保留之前的输出，所以不要在该路径上运行 `visual-check`：那会检查过期的上一个好产物，而不是失败的候选。如果校验失败，只修改被诊断的 `subject`、核实 `evidence`、从 `supportedFixes` 中选择，然后重跑。持续聚焦修正，直到目标错误数达到新的最小值。如果连续两轮都没有改进这个最佳计数，就停下来如实报告未解决的诊断。

## 更新感知

在第一个候选存在之后，用 Node 运行一次打包的检查器 `scripts/check-update.mjs`，然后继续请求的工作流。如果命令无法运行，就继续而不提检查。

- 对于 `silent`，继续而不提更新检查。
- 对于 `update_available`，用用户会话语言显示一条紧凑通知，包含已安装版本、最新版本、检查器的固定本地摘要，以及官方发布说明链接。当 `severity` 为 `security` 时，明确标记为安全更新并使用克制的警示标记；这仅改变强调，绝不改变用户自主权。明确说明已安装的技能未改变，由用户决定是否以及何时更新。你可以翻译那句固定的本地句子，但绝不引用、总结或翻译远程清单的摘要。通知可见后，用 `--ack "<eventKey>"` 运行同一个检查器确认它的精确 `eventKey`，然后继续用户原本的任务。

通知是信息，不是许可。保持已安装版本不变；这个 v0.1 工作流从不下载、安装或执行更新，沉默也绝不等于同意。

在第一个候选之前，不要读 `renderers/shared/geometry.mjs`、渲染器源码、校验器源码、测试或基准。只有面对不支持的内部分诊断或两次聚焦修复失败后，才去检查实现。

Workflow 说明：新 workflow 用 schema v2；只有当既有源需要保留旧固定几何时才保留 schema v1。保留语义边标签，并根据编译器诊断行事。规范布局、pin、迁移和回执契约见 [`renderers/workflow/README.md`](renderers/workflow/README.md#layout-contracts)。

Lifecycle 说明：阶段列 `0..4` 占据主轨；事件/终态列 `N`（`0..2`）精确对齐到主列 `N + 2` 下方。可恢复状态用 `type: "failure"` 加一条回到活动状态的真正转移。

## 类型路由

| 类型 | 用于 |
|---|---|
| `architecture` | 组件、服务、云/安全边界、基础设施 |
| `workflow` | 流程、审批门、工具调用、runbook、CI/CD |
| `sequence` | API 调用链、请求生命周期、异步轨迹、返回 |
| `dataflow` | 管道、ETL/ELT、血缘、治理、消费者 |
| `lifecycle` | 状态/状态转移、重试、等待与终态 |

含糊时运行 `node bin/archify.mjs guide "<scenario>" --json`。场景示例是结构参考，不是要复制的事实。

## Mermaid 输入

读取 Mermaid 以获取拓扑与含义，然后全新创作 Archify JSON；不要机械地渲染 Mermaid 样式。

- `flowchart` / `graph` → `workflow`，或组件地图用 `architecture`。
- `sequenceDiagram` → `sequence`；参与者变成语义参与者，箭头变成消息。
- `stateDiagram` → `lifecycle`；状态和转移保留含义，而非 Mermaid 样式。

## 创作不变量

- 一条明显的主路径；侧支从最近的主路径节点离开。在添加路由控制之前先移除低价值边。
- 默认省略 `meta.visual_preset`，让每个图表以 `classic` 打开，无论其解析出的颜色模式是浅色还是深色。颜色模式和视觉预设相互独立：切换浅/深必须保留当前预设。只有当用户明确要求那种视觉风格时才设置 `signal-flow`、`blueprint` 或 `editorial`。
- 默认省略 `meta.subtitle`。绝不要发明一个重复标题、节点或卡片的副标题；只有用户明确要求时才加一条简短的支撑说明。
- 默认把独立桌面查看器当作首屏产物，而不是一个浅条带。为笔记本和外接显示器生成一个响应式产物——绝不生成设备专属 HTML 或替代拓扑。查看器只可根据实时视口高度调整外部的阅读宽度；它必须保留已创作的 SVG/viewBox、比例、语义几何和正常文档流。在宽或高的桌面上，使用足够的已创作纵向节奏，使图表面板及其必要的结论卡片作为一个平衡的整体占满屏幕；运行时缩放无法修复过度压缩的 Y 布局或过小的显式 `meta.viewBox`。交接前，在 1440×900、1600×1000 和 1920×1080 打开真实 HTML；若构图面向大桌面显示器，再检查 2048×1320。在每一个检查的尺寸下要求 `document.documentElement.scrollWidth <= window.innerWidth` 且 `scrollHeight <= window.innerHeight`，同时目视确认图表在最大检查视口下依然清晰可读且纵向平衡。修复溢出时，先移除真正冗余的内容或压缩间距，再缩小节点、标签或主面板。若最大视口在查看器宽度上限处仍有明显空旷的下带，就重新分配已创作的 Y 位置并成比例增加 viewBox 高度；不要添加填充文案或装饰卡片。绝不要用 `overflow: hidden`、裁剪内容、内部图表滚动条、拉伸 SVG 高度或更小的排版来伪造通过。窄/移动布局在需要包含时可以纵向滚动。
- 省略 `meta.legend` 以使用真实默认的 `auto`。需要时只用 `mode: auto|all|hidden` 以及渲染器支持的 `entries.<kind>.label|visible`；标签绝不改变语义。
- 从用户的明确选择中选择一种主要创作语言；否则遵循请求或会话的主导语言。`meta.locale` 只控制渲染器拥有的查看器 UI：对应的受支持主要语言用 `"en"` 或 `"zh-CN"`。对其他所有语言，省略 `meta.locale` 并明确披露固定的查看器 UI 和 `<html lang>` 回退为英文。渲染器绝不翻译已创作内容。详见 `references/authoring-contract.md`。
- 保留精确的产品名、代码标识符、命令、协议、API 路径和环境名。它们在本地化文案中可以保持英文，但绝不因此让周围说明性散文留在另一种语言。
- 品牌身份是可选的、显式的。当节点名对应真实产品时，在 `brand` 放一个规范内置 ID。如果没有预设匹配且用户提供了官方 HTTP(S) URL，先运行 `node bin/archify.mjs brands capture "<url>" --json`，再创作返回的摘要钉死的 `brand` 对象。渲染和校验绝不执行未钉死的捕获。否则省略 `brand`。绝不从「database」之类的模糊角色推断品牌，也绝不让徽章取代语义 `type`、标签或关系事实。
- 对时序图，省略 `meta.column_fit` 以使用稳定的 `fixed` 布局。当宽 viewBox 会留下未使用水平空间，或有意义的参与者标签放不进固定盒子时，设为 `"spread"`；在尝试 `spread` 之前不要缩短语义标签。
- 组件类型为 `frontend`、`backend`、`database`、`cloud`、`security`、`messagebus` 和 `external`；变体为 `default`、`emphasis`、`security` 和 `dashed`。
- 关系标签是语义数据。当一个标签冲突时，移动标签、调整路由或间距，然后在不失含义的前提下缩短措辞。只有当措辞已被两个端点完全蕴含、且不含任何协议、动作、方向、同步/异步行为或跨边界机制时，才省略。保留每一个有意义的标签；删除它不是几何修复。如果一条关系一开始就没标签是因为其端点完全蕴含它，解释为什么措辞冗余；这是语义创作选择，不是几何修复。
- 默认省略 `meta.engineering_profile`。仅凭 region、cluster 和安全边界措辞不足以启用它。只有当用户明确要求生产部署拓扑、所有权交接或 fail-closed 部署审查、且来源事实已知时才启用 `deployment-ownership`。一旦启用，绝不要仅仅为了通过校验而移除工程概况；修复事实或如实报告诊断。
- 间距意味着清晰的间隙，不是中心距。对关系标签，清晰间隙必须超过其测量掩膜宽度；遵循保留标签的修复顺序。
- 自动路由拥有其端点侧。侧是方向契约：首段和末段必须垂直于该侧进出。
- 自动端口扩散（Automatic Port Spread）是 architecture、workflow、data-flow 和 lifecycle 的默认渲染器行为。它跳过单条关系以及显式的 `via`、`channelX`、`channelY`、`labelAt` 或非 `auto` 路由。近平行端口用外侧桥接，使自动路由无法产生小于 8px 的线段或小于 16px 的内侧转弯。architecture 另外在共享同一轴、偏移小于 16px 且两端都保留角落间隙时，保持无障碍的相对自动端口（`left`/`right` 或 `top`/`bottom`）。若恰好一个端点被扩散，只有未共享的端点可以移到该轴上；若两端都被扩散，保持外侧桥接以使竞争端口保持区分。
- 绝不接受一条边穿过无关的不透明节点、模糊的共享走廊，或关系标签遮住另一条路由。

只有在需要字段枚举、间距数学、几何修复规则、仓库证据或模式专属放置时，才读 `references/authoring-contract.md`。

## 交付

修复期间用 `validate`，最终验收用一次 `deliver`。交付把精确的规范字节冻结为同目录的私有快照，渲染并检查该快照，原子地提交 HTML，并报告规范与产物两者的 SHA-256 和字节数。这是确定性产物证据；它不在浏览器中运行查看器。

交付后，在不修改或重新渲染受信 HTML 的前提下收集有界桌面证据：

```bash
node bin/archify.mjs visual-check <output.html> --json
```

`visual-check` 从精确交付的 HTML 收集自动化浏览器证据，而不修改或重新渲染它。它的机器可读测量和截图不批准感知层面的润色。规范的收据字段、覆盖范围、sidecar、退出行为以及补充人工记录要求，见 `references/delivery-contract.md`。

把三个声明分开：`deliver` 证明确定性产物检查，`visual-check` 证明真实浏览器中的有界行为，而感知层面的视觉审查需要真实的人或能看图的人。分别报告浏览器证据和感知审查。无约束的一瞥只能支持感知审查；在记录补充人工浏览器工作或处理环境失败时，使用规范交付契约。

只有用户想要立即本地预览时才加 `--open`。对活跃的桌面创作循环，可选命令是：

```bash
node bin/archify.mjs preview <type> <input>.json <output>.html --quality showcase
```

绝不要默认启动 preview。使用 preview、仓库证据、导出回执、视觉审查或提交后打开时，读 `references/delivery-contract.md`。

## 可选的查看器能力

生成的 HTML 已包含主题切换、平移/缩放、搜索、聚焦、关系追踪、语义视图、展示和真实导出。这些是读者能力，不是额外创作工作。`meta.animation: "trace"` 是可选加入的；`meta.views` 是可选的，最多五个策划章节。

只有当用户明确要求分享卡片、路由/可达卡片、动画、引导故事、深链、展示、搜索/聚焦或其他查看器运行时功能时，才读 `references/viewer-runtime.md`。

## 安装与回退

技能包内部无需安装。用以下命令验证：

```bash
node bin/archify.mjs doctor
node bin/archify.mjs demo <output-directory>
```

当 shell 不可用时，手工把架构 SVG 放进 `assets/template.html`，用 CSS 语义类而非内联颜色，并遵循 `references/delivery-contract.md` 中的视觉审查契约。

## 输出

返回检查过的 HTML 路径、图表类型、校验摘要、规范/产物回执、浏览器证据状态，以及如实的视觉审查状态。不要对非零命令宣称成功，也不要宣称你未执行的视觉检查。
