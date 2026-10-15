---
name: Academic Nexus
colors:
  surface: '#f9f9ff'
  surface-dim: '#d0daf0'
  surface-bright: '#f9f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f0f3ff'
  surface-container: '#e7eeff'
  surface-container-high: '#dee8ff'
  surface-container-highest: '#d9e3f9'
  on-surface: '#121c2c'
  on-surface-variant: '#41474d'
  inverse-surface: '#273141'
  inverse-on-surface: '#ebf1ff'
  outline: '#72787e'
  outline-variant: '#c1c7ce'
  surface-tint: '#386380'
  primary: '#00324b'
  on-primary: '#ffffff'
  primary-container: '#1b4965'
  on-primary-container: '#8eb8d8'
  inverse-primary: '#a1cced'
  secondary: '#1a6b4a'
  on-secondary: '#ffffff'
  secondary-container: '#a5f3c9'
  on-secondary-container: '#237150'
  tertiary: '#5c1600'
  on-tertiary: '#ffffff'
  tertiary-container: '#7f2708'
  on-tertiary-container: '#ff9878'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#c9e6ff'
  primary-fixed-dim: '#a1cced'
  on-primary-fixed: '#001e2f'
  on-primary-fixed-variant: '#1d4b67'
  secondary-fixed: '#a5f3c9'
  secondary-fixed-dim: '#8ad6ae'
  on-secondary-fixed: '#002113'
  on-secondary-fixed-variant: '#005235'
  tertiary-fixed: '#ffdbd0'
  tertiary-fixed-dim: '#ffb59f'
  on-tertiary-fixed: '#3a0a00'
  on-tertiary-fixed-variant: '#812909'
  background: '#f9f9ff'
  on-background: '#121c2c'
  surface-variant: '#d9e3f9'
typography:
  headline-xl:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 17px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 13px
    fontWeight: '600'
    lineHeight: 18px
  label-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
  caption:
    fontFamily: Plus Jakarta Sans
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 12px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 0.75rem
  margin: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1rem
  space-xl: 1.5rem
---

## Brand & Style

### Brand Personality & Mission
The design system establishes a credible, rigorous, and empowering digital campus companion for students, researchers, and campus administrators. Its visual narrative centers on reliability, efficiency, and structured institutional clarity. It replaces informal, chaotic forum clutter with a dignified academic interface that inspires confidence in university governance, resource reservations, career/academic opportunities, and peer mutual aid.

### Visual Language & Aesthetic Movement
The design language adopts a Modern Institutional Minimalist philosophy:
- **Tone:** Grounded, orderly, lucid, and empathetic. High textual legibility and strict visual hierarchy eliminate cognitive strain during administrative and academic tasks.
- **Affordance & Texture:** Soft card-based elevation, subtle cool-tinted borders, crisp typography, and restrained chromatic signaling.
- **Compliance & Transparency:** Explicit data-source watermarking and provenance badges (such as "模拟预约", "演示数据", and "平台处理中") integrated natively into UI surfaces to guarantee institutional compliance and zero user ambiguity.

## Colors

### Palette Strategy
The chromatic hierarchy is built on functional academic credibility and distinct status clarity:
- **Primary (`#1B4965` Deep Academy Slate Blue):** Commands the primary navigational surfaces, top application bars, master CTA buttons, active state indicators, and authoritative heading blocks.
- **Secondary (`#2D7A58` Scholar Spruce Green):** Communicates institutional approval, audit clearance ("已受理", "资格符合"), successful verification tags, and positive growth milestones.
- **Tertiary (`#C55A38` Terracotta Amber):** Designated strictly for urgent deadlines ("待办截止"), critical maintenance dispatches ("紧急报修"), and non-compliant or warning indicators ("不符合").
- **Neutral (`#2D3748` Iron Charcoal):** Governs foundational typographic contrast and layout structures.

### Surface & Functional Distribution
- **Background Root:** `#F0F4F8` (Crisp cool-tinted slate neutral).
- **Surface Layer 1 (Cards & Modules):** `#FFFFFF` pure white with `#E2E8F0` soft perimeter borders.
- **Surface Layer 2 (Inset Wells & Sub-sections):** `#F8FAFD` (Airy cool white).
- **Text Tiers:**
  - High Emphasis: `#1A202C`
  - Medium Emphasis (Body & Metadata): `#4A5568`
  - Low Emphasis (Captions & Footnotes): `#718096`
  - Disabled / Placeholder: `#A0AEC0`

### Multi-State Qualification Tokens
- **Eligible ("符合"):** Fill `#EAF5F0`, Text `#2D7A58`, Border `#B2D8C7`.
- **Conditionally Eligible ("可能符合"):** Fill `#FEF6EE`, Text `#B45309`, Border `#FCD34D`.
- **Ineligible ("不符合"):** Fill `#FDF2F2`, Text `#C55A38`, Border `#F8B4B4`.

### Provenance Badges
- **Simulation & Demo ("演示数据" / "模拟预约"):** Fill `#EDF2F7`, Text `#4A5568`, Border `#CBD5E0`.
- **Platform In-Progress ("平台处理"):** Fill `#EBF8FF`, Text `#1B4965`, Border `#BEE3F8`.

## Typography

### Typographic System
The type hierarchy leverages modern, balanced letterforms (`Plus Jakarta Sans` paired with native Chinese system fonts: `PingFang SC`, `Hiragino Sans GB`, `Microsoft YaHei`) to deliver crystalline legibility in high-density WeChat Mini-Program layouts.

### Implementation Rules
- **Display & Section Headers (`headline-xl`, `headline-lg`):** Reserved for service directory category headers, opportunity titles, and campus profile dashboards. Always set in semi-bold or bold weights.
- **Card Titles & Component Headers (`headline-sm`):** Standard sizing for all actionable campus event cards, service items, and peer request cards.
- **Primary Body Text (`body-md`):** Main text standard for descriptions, guidelines, notices, and message bubbles. Set with a 1.45–1.5 line height ratio to guarantee scanning comfort under small mobile viewports.
- **Meta & Provenance Indicators (`label-sm`, `caption`):** Compact sizing designed for capsule tags ("已受理", "演示数据", "资格符合"), timestamps, and anonymous handle labels.

## Layout & Spacing

### WeChat Mini-Program Frame & Safe Areas
The interface strictly complies with standard WeChat Mini-Program viewport requirements (390px base width):
- **Custom Navigation Top Bar:** Top safe area calculated dynamically via `wx.getSystemInfoSync().statusBarHeight` plus a 44px navigation bar height. Total right-side capsule safe zone (88px horizontal padding) is preserved to prevent collisions with the native Mini-Program menu capsule.
- **Bottom TabBar Safe Area:** Fixed height of 52px plus dynamic `env(safe-area-inset-bottom)` reserve. Provides persistent clearance for the 5 global tabs: **首页 (Home)**, **机会 (Opportunities)**, **服务 (Services)**, **互助 (Mutual Aid)**, and **我的 (Profile)**.

### Canvas Grid & Density
- **Global Page Inset (`margin`):** `16px` (`1rem`) continuous left and right padding to keep card structures comfortably decoupled from physical screen bezels.
- **Column Gutter (`gutter`):** `12px` (`0.75rem`) for 2-column service item grids and metric cards.
- **Vertical Card Stacking Rhythm:** `12px` (`0.75rem`) vertical spacing between discrete campus items; `8px` (`0.5rem`) within related grouping modules.

## Elevation & Depth

### Atmospheric Depth Philosophy
The system avoids heavy, muddy drop-shadows. It relies on subtle surface layering with razor-thin hairline borders tinted with institutional cool grey, paired with light ambient diffusion:

- **Flat / Background Level (Level 0):** `#F0F4F8`.
- **Card Level (Level 1):** `#FFFFFF` surface accompanied by a 1px solid border in `#E2E8F0` and an ambient shadow: `box-shadow: 0 1px 3px rgba(27, 73, 101, 0.04), 0 4px 12px rgba(27, 73, 101, 0.02)`.
- **Interactive / Pressed Card (Level 1 Active):** Border shifts to `#CBD5E1`; subtle scale down to `transform: scale(0.995)`.
- **Floating / Modal Layer (Level 2):** Sheet modals, bottom drawer filters, and popovers utilize `#FFFFFF` surface with `box-shadow: 0 8px 24px rgba(27, 73, 101, 0.12)` accompanied by a backdrop dim of `rgba(15, 23, 42, 0.45)`.

## Shapes

### Form Geometry
The system uses level `2` (Rounded) geometry to convey warm approachability while maintaining administrative seriousness:
- **Base Components (`rounded-md`, 8px):** Buttons, inputs, search bars, and segmented pills.
- **Container Surfaces (`rounded-lg`, 12px - 14px):** Standard event cards, service list items, qualification match blocks, and mutual-aid threads.
- **Floating Sheets & Popovers (`rounded-xl`, 16px - 20px):** Top edges of bottom sheets and confirmation dialogs.
- **Capsule / Circular Elements (`rounded-full`, 9999px):** Status badges, qualification tags, source verification badges, and anonymous avatars.

## Components

### 1. Buttons & Interaction Triggers
- **Primary Action Button:** Background `#1B4965`, text `#FFFFFF`, border-radius `8px`, height `44px`, font weight `600`. Tapping state triggers background darkening to `#13354A`.
- **Secondary / Ghost Button:** Background `#F0F4F8`, text `#1B4965`, border `1px solid #CBD5E1`.
- **Critical Action Button:** Background `#C55A38`, text `#FFFFFF`, height `44px`, reserved for urgent repairs and critical dispatches.

### 2. Status & Provenance Badges (Tags)
- **Compact Geometry:** Padding `2px 8px`, border-radius `9999px`, font size `11px` (`label-sm`).
- **Data Provenance Badges:**
  - `演示数据` (Demo): Surface `#EDF2F7`, border `#CBD5E0`, text `#4A5568`.
  - `模拟预约` (Simulation): Surface `#F1F5F9`, border `#94A3B8`, text `#334155`.
  - `平台处理中` (Processing): Surface `#EBF8FF`, border `#BEE3F8`, text `#1B4965`.

### 3. Qualification Tri-State Cards ("资格匹配卡片")
Structured with a clear three-tier visual language:
- **Eligible ("符合"):** Left border accent `4px solid #2D7A58`. Card top-right pill tag: green background `#EAF5F0`, border `#B2D8C7`, text `#2D7A58` with a checkmark glyph.
- **Conditionally Eligible ("可能符合"):** Left border accent `4px solid #B45309`. Card top-right pill tag: amber background `#FEF6EE`, border `#FCD34D`, text `#B45309`.
- **Ineligible ("不符合"):** Left border accent `4px solid #C55A38`. Card top-right pill tag: muted red background `#FDF2F2`, border `#F8B4B4`, text `#C55A38`.

### 4. Anonymous Community Cards ("互助匿名卡片")
- **Header:** Features a procedural de-sensitized avatar (geometric circle with high-contrast dual pastel colors) and an institutional alias tag (e.g., "学工楼旁的小齿轮 #A814").
- **Identity Label:** Accompanied by a `已脱敏` (De-identified) micro-chip in `#EDF2F7` text `#718096`.
- **Card Body:** `14px` regular text with an inner reply block rendered on `#F8FAFD`.

### 5. Input Fields & Form Controls
- **Standard Input Box:** Height `44px`, background `#FFFFFF`, border `1px solid #E2E8F0`, rounded `8px`, placeholder color `#A0AEC0`. Focus state triggers border highlight in `#1B4965` with a subtle glow.
- **Checkboxes & Radios:** Size `20px`, active fill `#1B4965` with white iconography.

### 6. WeChat Bottom Navigation Bar (5-Tab Standard)
- **Container:** Fixed bottom dock, height `52px` + `safe-area-inset-bottom`, background `#FFFFFF`, top border `1px solid #E2E8F0`.
- **Tab Layout (5 items):**
  1. 首页 (Home) - School gate / dashboard icon
  2. 机会 (Opportunities) - Graduation cap / compass icon
  3. 服务 (Services) - Grid / toolbox icon
  4. 互助 (Mutual Aid) - Hands / heart icon
  5. 我的 (Profile) - User badge icon
- **Active State:** Tint color `#1B4965`, font weight `600`.
- **Inactive State:** Tint color `#A0AEC0`, font weight `400`.