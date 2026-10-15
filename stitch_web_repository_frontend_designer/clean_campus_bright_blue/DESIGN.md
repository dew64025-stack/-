---
name: Clean Campus Bright Blue
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#434655'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#737686'
  outline-variant: '#c3c6d7'
  surface-tint: '#0053db'
  primary: '#004ac6'
  on-primary: '#ffffff'
  primary-container: '#2563eb'
  on-primary-container: '#eeefff'
  inverse-primary: '#b4c5ff'
  secondary: '#006c49'
  on-secondary: '#ffffff'
  secondary-container: '#6cf8bb'
  on-secondary-container: '#00714d'
  tertiary: '#005a82'
  on-tertiary: '#ffffff'
  tertiary-container: '#0074a6'
  on-tertiary-container: '#e4f2ff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b4c5ff'
  on-primary-fixed: '#00174b'
  on-primary-fixed-variant: '#003ea8'
  secondary-fixed: '#6ffbbe'
  secondary-fixed-dim: '#4edea3'
  on-secondary-fixed: '#002113'
  on-secondary-fixed-variant: '#005236'
  tertiary-fixed: '#c9e6ff'
  tertiary-fixed-dim: '#89ceff'
  on-tertiary-fixed: '#001e2f'
  on-tertiary-fixed-variant: '#004c6e'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
typography:
  display:
    fontFamily: Plus Jakarta Sans
    fontSize: 40px
    fontWeight: '800'
    lineHeight: 48px
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
  headline-lg-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 26px
    fontWeight: '700'
    lineHeight: 34px
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  title-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  title-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
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
    fontWeight: '600'
    lineHeight: 14px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-md: 1.5rem
  margin: 1rem
  margin-md: 2rem
  margin-lg: 3rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.5rem
---

## Brand & Style

This design system expresses the optimism, clarity, and boundless energy of modern campus life. Designed for students, educators, and campus communities, it cuts through academic clutter with high-transparency surfaces, luminous accents, and crisp contrast.

The aesthetic fuses **Modern Minimalism** with **Tactile Airiness**:
- **Tone:** Energetic, candid, focused, and invigorating.
- **Visual Rhythm:** Crisp grid alignment paired with luminous color accents that instantly orient students between classes, extracurriculars, schedules, and social feeds.
- **Materiality:** High-transparency layers, crisp hairline divisions, and subtle ambient lifts instead of heavy, muddy skeuomorphism.

## Colors

The palette avoids dull graying or muddy shadows, relying strictly on crisp, high-luminance primaries and pristine cool backgrounds to preserve focus and vitality.

### Color Tokens & Roles
- **Primary (`#2563eb` - Campus Vivid Blue):** Used for focal CTAs, active tab indicators, high-priority notifications, and key interactive outlines.
- **Secondary (`#10b981` - Growth Emerald):** Used for academic progress badges, completed statuses, positive balance indicators, and active club sign-ups.
- **Tertiary (`#0ea5e9` - Sky Cyan):** Soft informational tags, live activity pulses, timetable slot highlights, and active state highlights.
- **Neutral Core (`#64748b` - Slate Gray):** Subtitle text, muted borders, inactive tab icons, and grid dividers.
- **Canvas (`#ffffff` - Pristine White):** Primary card surfaces, modals, popovers, and input fields.
- **Background (`#f8fafc` - Crisp Ice Mist):** Page foundations and structural container backgrounds to ensure pristine white components visually pop without harsh boundary lines.
- **Text Primary (`#0f172a` - Midnight Ink):** High-legibility typography for long-form reading, course schedules, and headings.

## Typography

**Plus Jakarta Sans** provides a modern, geometric balance with subtly sculpted terminal curves that bring warmth, approachability, and exceptional legibility to dense student timetables, dashboards, and notices.

### Implementation Guidelines
- **Weight Pairing:** Titles and labels rely exclusively on weights `600` (SemiBold) and `700`/`800` (Bold) to enforce unambiguous visual hierarchy against the light `#f8fafc` canvas.
- **Body Copy:** Standard body sizes stick to regular weight (`400`) with tight, comfortable tracking (`letter-spacing: -0.01em`) to minimize ocular fatigue during extended reading of campus syllabus documents.
- **Mobile Readout:** Display sizes over 28px must collapse to their `-mobile` equivalents on screens narrower than 640px to prevent awkward word wraps.

## Layout & Spacing

A flexible 12-column grid system anchors complex portal views, converting into a single-column flow on mobile and a 6-to-8-column structure on tablet devices.

### Grid Rhythm & Breakpoints
- **Mobile (< 640px):** 4 columns, `margin: 1rem` (16px), `gutter: 1rem` (16px). Single-column stacking for campus feeds, schedule cards, and course lists.
- **Tablet (640px – 1024px):** 8 columns, `margin-md: 2rem` (32px), `gutter: 1.25rem` (20px). Two-column card layouts and side-drawer navigation.
- **Desktop (> 1024px):** 12 columns, max-width `1280px`, `margin-lg: 3rem` (48px), `gutter-md: 1.5rem` (24px). Persistent left navigation rail, central action feed, and persistent contextual right rail.

### Spacing Scale Rules
- `space-xs` (4px) and `space-sm` (8px) define tight interior component pairings (e.g., icon-to-label gaps, badge padding).
- `space-md` (16px) establishes standard card padding and form control separation.
- `space-lg` (24px) separates card groups and nested sections.
- `space-xl` (40px) controls primary dashboard section dividers.

## Elevation & Depth

To avoid dingy, muddied designs, depth is achieved through **Tonal Clean Layers** and **Tinted Ambient Glows** rather than generic black drop shadows.

### Depth Levels
- **Level 0 (Flat Canvas):** `#f8fafc`. Underlying page surface.
- **Level 1 (Card & Module Layer):** `#ffffff` surface with a crisp border `1px solid rgba(226, 232, 240, 0.8)` and a faint buoyant ambient shadow: `0 1px 3px 0 rgba(37, 99, 235, 0.04), 0 1px 2px -1px rgba(15, 23, 42, 0.04)`.
- **Level 2 (Interactive Floating / Hover):** Used for elevated cards, active calendar nodes, and dropdown menus. Elevates to `0 8px 24px -4px rgba(37, 99, 235, 0.08), 0 4px 8px -2px rgba(15, 23, 42, 0.03)`.
- **Level 3 (Overlays & Dialogs):** Modals, side drawers, and urgent campus alerts. Framed with `0 20px 32px -8px rgba(15, 23, 42, 0.12), 0 8px 16px -4px rgba(37, 99, 235, 0.08)`, paired with a semi-opaque backdrop blur (`rgba(248, 250, 252, 0.7)`, `backdrop-filter: blur(8px)`).

## Shapes

The system relies on an exact `8px` (`0.5rem`) base radius across primary interface surfaces to project structured, friendly efficiency.

### Rounded Tokens Applied
- **Base Components (`0.5rem` / 8px):** Buttons, text inputs, card containers, dropdown menus, and alert banners.
- **Inner Micro Elements (`0.25rem` / 4px):** Selection indicators, checkboxes, tooltips, and progress bar tracks.
- **Container Surfaces (`1rem` / 16px):** Modal dialog boxes, floating side sheets, and full-bleed hero banners.
- **Pill Exception (`9999px`):** Status chips, counter tags, avatar status badges, and interactive pill filters.

## Components

### Buttons
- **Primary:** Solid `#2563eb` fill, `#ffffff` text, 8px border-radius, font weight 600. Hover state darkens cleanly to `#1d4ed8` with a subtle lift shadow `0 4px 12px rgba(37, 99, 235, 0.25)`.
- **Secondary (Green Vitality):** `#10b981` solid or a soft 10% emerald background (`#ecfdf5`) with `#059669` text for success, enrollment confirmations, and submit buttons.
- **Outline / Ghost:** 1px hairline border in `#e2e8f0` on `#ffffff`, hover transition to `#eff6ff` with primary `#2563eb` text.

### Chips & Badges
- **Status Badges:** Compact 20px height, fully rounded pill (`9999px`), 11px semi-bold text. 
  - *Active Course / Ongoing:* Background `#eff6ff`, text `#1d4ed8`.
  - *Completed / Verified:* Background `#ecfdf5`, text `#047857`.
  - *Upcoming / Alert:* Background `#f0f9ff`, text `#0284c7`.

### Cards
- **Base Course / Schedule Card:** `#ffffff` surface, 8px radius, border `1px solid #e2e8f0`. Padding fixed at `1rem` (16px).
- **Interactive State:** Hover invokes smooth translation of `-2px` on the Y-axis and lightens border to `rgba(37, 99, 235, 0.35)`.

### Form Inputs
- **Text Fields:** 42px height, 8px radius, `#ffffff` background, `1px solid #cbd5e1` outline.
- **Focus State:** 0-opacity ring expands to a crisp `2px solid #2563eb` with `box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.15)`, eliminating muddy native outlines.

### Selection Controls (Checkboxes & Radios)
- **Checkboxes:** 18px x 18px, 4px corner radius. Unchecked has a clean `#cbd5e1` stroke; checked triggers a solid `#2563eb` fill with a sharp white checkmark.
- **Radio Buttons:** 18px outer circle, checked state displays a centered 8px solid `#2563eb` dot surrounded by a crisp white gap.

### Campus Timetable Block (Custom Element)
- Segmented scheduling cells with a transparent 12% tint of `#2563eb` or `#10b981`, featuring an authoritative 3px solid left accent bar indicating the active room and course tier.