# Anode — Android Material 3 Expressive Design Specification

- **Product Name**: **Anode**
- **Application ID**: `com.nodeseek.app`
- **Target Platform**: Android 8.0+ (`minSdk = 26`, `targetSdk = 35`), Jetpack Compose + Material 3 (`1.3+`), Edge-to-Edge
- **Design Language**: **Material 3 Expressive — "Editorial Paper & Obsidian Telemetry"**

---

## 1. Executive Design Vision & Spatial Principles

### 1.1 Aesthetic North Star: "Editorial Paper & Obsidian Telemetry"
NodeSeek is home to infrastructure builders, VPS benchmarkers, network engineers, and self-hosters. **Anode** treats technical forum reading like a **boutique hardware & network journal** tailored for Android edge-to-edge ergonomics:

1. **Warm Editorial Paper (Daylight)**: Unbleached warm parchment surfaces (`#FAF7F2` / `#EFEAE1`) paired with crisp ink (`#1B1B1F`) and **Electric Editorial Cobalt (`#3655D6`)** accents—eliminating harsh clinical white glare during long technical threads.
2. **Deep Obsidian Telemetry (Night / AMOLED)**: True low-luminance obsidian (`#07080C`, with an optional `#000000` Pure Pitch toggle) layered with cool slate-obsidian containers (`#151821` / `#1D212D`), luminous **Periwinkle Cobalt (`#8DA2FF`)**, **Neon Telemetry Cyan (`#4FD8CE`)**, and **Glowing Drumstick Gold (`#FFB951`)**.
3. **Architectural Precision**: 1dp crisp hairlines (`outlineVariant`), tabular numerals (`tnum`) across every metric, semantic category color spines, conic metallic rank rings (`Lv0`–`Lv6`), and bouncy Material 3 Expressive spring transitions.

### 1.2 Core Spatial & Component Principles

| Dimension | Design Principle & Compose Implementation |
| :--- | :--- |
| **Brand & App Icon** | **Anode**: Concentric radar pulse arcs and Anode architectural pillars in Electric Cobalt (`#3655D6` → `#06B6D4`) with a glowing Cyan/Gold signal core on an adaptive squircle. |
| **Color Philosophy** | **Warm Editorial Paper (`#FAF7F2`)** + **Deep Obsidian AMOLED (`#07080C`)**; **Electric Cobalt (`#3655D6` / `#8DA2FF`)** primary, **Telemetry Teal (`#0B7A75`)** secondary, **Drumstick Gold (`#C86D00` / `#FFB951`)** reward tertiary, plus optional **Material You** wallpaper harmonization. |
| **Navigation Shell** | Edge-to-edge viewport with a **Floating Tonal Pill Dock** (`Explore / 首页`, `Seek Mail / 邮箱`, `Pulse Hub / 我的`) + **Contextual Collapsing Squircle FAB** (`发帖` on Explore). |
| **Feed Top Bar & Sort** | **Minimalist Glass Top Bar** with left `Anode` emblem + right compact **Search Icon** (`SearchRoute`) + borderless **Sort Selector** (`新评论 ▾` / `新帖子` / `热门` / `推荐阅读`), paired with a full-board **Text Category Strip** synchronized 1:1 with `HorizontalPager`. |
| **Pinned Topics** | Compact, transparent **Collapsible Pinned Ticker Strip** (`📌 置顶`) that expands inline on tap—preserving vertical viewport space. |
| **Feed Cards** | **High-Density Outlined `PulseTopicCard`** (`14dp` radius + `0.85dp` transparent hairline border, zero card fill/left spine): `24dp` avatar with **Lv1–Lv6 conic gradient ring**, **1-tap Bookmark icon**, crisp **Read vs Unread typographic weight/alpha contrast**, compact telemetry footer, and **bottom-right backgroundless Category indicator**. |
| **Unified Search** | Dedicated **Search Screen (`SearchRoute`)** supporting topic/user scope switching, board & sort filters, recent search chips, and 1-tap Google `site:www.nodeseek.com` fallback. |
| **Topic Reader & Comments** | **Magazine-Grade Article Surface** + clean **Threaded Stream** separated by architectural hairlines: `楼主` (**OP**) solid Cobalt highlight chip on OP floors, compact tabular `#12` floor pill inside header, syntax-tinted code blocks with 1-tap copy, zoomable Coil image cards, and inline tonal reaction chips (`👍` `🍗` `👎` `💬`). |
| **Reader Bottom Controls & Floor Jump** | **Single Anchored Tonal Quick-Reply Bar** (inline quick-reply pill + bookmark + floor counter `12/148` + expand icon) + **Full Markdown Reply Bottom Sheet**, paired with a draggable right-edge **Floor Jump Scrubber & Sheet** (`只看楼主`, slider, page jump). |
| **Profile Hub & Check-in** | **Personal Hub (`我的`)**: Preserved ambient-glow Hero Rank & `Lv` Coin Progress Card with 4-column stats (`鸡腿 / 星尘 / 主题 / 评论`), **1-Line Compact Check-in Card**, settings-style grouped lists (`消息`: 通知/私信/关键词提醒; `内容`: 我的收藏/浏览记录/我的帖子) linking to **`HubCollectionScreen`**, Settings, and a **Clean WebView Session Login Bottom Sheet**. |

---

## 2. Color, Typography, Shape & Motion System

### 2.1 Core Material 3 Color Schemes (`Color.kt`)

Anode ships with two bespoke default palettes (**Warm Editorial Paper Light** and **Deep Obsidian AMOLED Dark**), plus an optional **Pure Pitch (`#000000`)** AMOLED sub-toggle and **Material You Dynamic Color** (Android 12+).

| M3 Color Role | Warm Editorial Paper (Light) | Deep Obsidian AMOLED (Dark) | Usage in Anode |
| :--- | :--- | :--- | :--- |
| `primary` | `#3655D6` (Electric Cobalt) | `#8DA2FF` (Luminous Periwinkle) | Active nav indicator, OP chip, links, primary CTAs, unread dot |
| `onPrimary` | `#FFFFFF` | `#071E72` | Text/icons on solid `primary` fills |
| `primaryContainer` | `#DDE3FF` | `#213C9E` | Selected filter pills, active sort segment, FAB container |
| `onPrimaryContainer` | `#0B1D6E` | `#DDE3FF` | Text/icons inside `primaryContainer` |
| `secondary` | `#0B7A75` (Telemetry Teal) | `#4FD8CE` (Signal Cyan) | RSS live badge, verified/pinned tags, benchmark highlights |
| `onSecondary` | `#FFFFFF` | `#003734` | Text/icons on `secondary` |
| `secondaryContainer` | `#CCF3EE` | `#00504C` | RSS mode pill, secondary Bento accents |
| `onSecondaryContainer` | `#00201E` | `#92F4EC` | Text/icons inside `secondaryContainer` |
| `tertiary` | `#C86D00` (Drumstick Amber) | `#FFB951` (Glowing Gold) | `鸡腿` check-in CTA, drumstick rewards, hot thread accents |
| `onTertiary` | `#FFFFFF` | `#452B00` | Text/icons on `tertiary` |
| `tertiaryContainer` | `#FFDFB8` | `#633F00` | `鸡腿` check-in chip/card background, reward toast |
| `onTertiaryContainer` | `#3E1E00` | `#FFDDB3` | Text/icons inside `tertiaryContainer` |
| `background` | `#FAF7F2` (Warm Paper) | `#07080C` (`#000000` Pitch mode) | Root edge-to-edge screen background |
| `onBackground` | `#1B1B1F` (Deep Warm Ink) | `#EAE8EF` (Soft Alabaster) | Primary reading body & unread headline text |
| `surface` | `#FAF7F2` | `#07080C` (`#000000` Pitch mode) | Collapsible top bar resting background, reader canvas |
| `onSurface` | `#1B1B1F` | `#EAE8EF` | Primary text, unread feed titles (`100%` alpha) |
| `onSurfaceVariant` | `#5A5863` | `#9C9AA6` | Metadata, timestamps, read feed titles (`68%–85%` alpha) |
| `surfaceContainerLowest` | `#FFFFFF` (Crisp Sheet) | `#040507` | Elevated feed cards in Light mode for subtle paper-on-parchment lift |
| `surfaceContainerLow` | `#F5F1EA` | `#0F1117` | Grouped reader blockquotes, table alternating rows |
| `surfaceContainer` | `#EFEAE1` | `#151821` | Default Bento cards, dark-mode feed cards, reply bar base |
| `surfaceContainerHigh` | `#E8E2D7` | `#1D212D` | Floating bottom navigation pill, search pill, modal sheets |
| `surfaceContainerHighest`| `#DFD8CB` | `#262B3A` | Pressed states, code block headers, segmented track |
| `outline` | `#84818C` | `#656470` | Input borders, focused outlines |
| `outlineVariant` | `#DBD5C9` | `#262A38` | `1.dp` card borders, floor dividers, table gridlines |
| `error` / `onError` | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` | Expose warnings, auth/network errors, downvote confirmation |
| `errorContainer` | `#FFDAD6` | `#93000A` | Error banners & destructive action chips |
| `scrim` | `#000000` (`52%` alpha) | `#000000` (`68%` alpha) | Modal bottom sheet & image lightbox backdrop |

### 2.2 Optional Material You Dynamic Color & Semantic Harmonization
- **Default Behavior**: `useDynamicColor = false` by default so Anode's signature Warm Paper & Obsidian Cobalt aesthetic is unmistakable on first launch.
- **User Opt-In (`Build.VERSION.SDK_INT >= 31`)**: Toggling *"Material You 壁纸动态取色"* in Pulse Hub Settings switches `MaterialTheme.colorScheme` to `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` (with `background` & `surface` overridden to `#000000` if *Pure Pitch AMOLED* is also enabled).
- **Semantic Harmonization**: When Dynamic Color is active, blend `12%` of `colorScheme.primary` into all 11 Category Badge tokens and Drumstick Gold via `ColorUtils.blendARGB(semanticArgb, primaryArgb, 0.12f)` so semantic colors feel native to the user's wallpaper without losing categorical meaning.

### 2.3 Semantic Category Badge Palettes (`AnodeCategoryTokens`)

**Anode** assigns an unmistakable, WCAG AA+ compliant Light & Dark tonal pair and rounded Material icon to every NodeSeek board:

| Slug | Board Name | Compose Icon (`Icons.Rounded.*`) | Light Container / Text | Dark Container / Text | Editorial Spine / Accent |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `daily` | **日常** (Daily) | `LocalCafe` | `#F7E8DC` / `#7C3A12` | `#342015` / `#FDBA8C` | `#D97736` (Warm Terracotta) |
| `tech` | **技术** (Tech) | `Memory` | `#DCE6FF` / `#163B9C` | `#162754` / `#93B4FF` | `#3B66F5` (Sapphire) |
| `info` | **情报** (Deals/Info) | `Radar` | `#EAE0FF` / `#4E229E` | `#28194D` / `#CBB2FF` | `#7C3AED` (Radar Violet) |
| `review` | **测评** (Review) | `Speed` | `#D6F3F8` / `#095266` | `#0F313C` / `#7CE2F6` | `#0891B2` (Benchmark Cyan) |
| `trade` | **交易** (Trade) | `SwapHoriz` | `#D7F5E6` / `#0B5C3B` | `#103526` / `#6EE7B7` | `#10B981` (Emerald Market) |
| `carpool` | **拼车** (Carpool) | `GroupWork` | `#D5F2EE` / `#0D5652` | `#103332` / `#5EEAD4` | `#14B8A6` (Transit Teal) |
| `promotion`| **推广** (Promo) | `Campaign` | `#FFE0EC` / `#8F1D4E` | `#3D1528` / `#F9A8D4` | `#EC4899` (Signal Rose) |
| `life` | **生活** (Life) | `Spa` | `#E3F2D5` / `#335916` | `#1E3114` / `#BEF264` | `#65A30D` (Botanical Sage) |
| `dev` | **Dev** (Code) | `Terminal` | `#E0E5FF` / `#27308A` | `#1B204E` / `#A5B4FC` | `#6366F1` (Terminal Indigo) |
| `photo-share`| **贴图** (Photo) | `PhotoLibrary` | `#FFE4DC` / `#8A2C14` | `#3B1C15` / `#FDA4AF` | `#F43F5E` (Aperture Coral) |
| `expose` | **曝光** (Expose) | `ReportProblem` | `#FFDAD8` / `#930014` | `#441217` / `#FCA5A5` | `#EF4444` (Crimson Alert) |
| `inside` | **内版** (Inside) | `Lock` | `#F5E9C8` / `#6B4C08` | `#332710` / `#FCD34D` | `#D97706` (Gilded Amber) |
| `sandbox` / `*` | **沙盒 / 其他** | `DeployedCode` | `#E6E4EC` / `#454352` | `#24242E` / `#C4C2D0` | `#71717A` (Graphite) |

### 2.4 NodeSeek Rank Badge System (`Lv0`–`Lv6` Metallic & Gem Gradients)

NodeSeek computes user rank from total coins (`coins >= 100 * Lv²` for `Lv` in `0..6`). Anode visualizes rank via a **`1.75.dp` Sweep/Linear Gradient Ring** around avatars and a compact tabular **`Lv.X` Micro-Badge**:

| Rank | Coin Threshold | Tier Persona | Gradient Start → End (Ring & Pill Border) | Pill Container (Light / Dark) | Pill Text (Light / Dark) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Lv.0** | `0 – 99` | 新芽节点 (Seed) | `#94A3B8` → `#64748B` | `#EEF1F5` / `#1E222B` | `#525B6B` / `#94A3B8` |
| **Lv.1** | `100 – 399` | 青铜脉冲 (Bronze) | `#D98A4E` → `#9F582A` | `#F9EBE0` / `#2E1E16` | `#844318` / `#F3B382` |
| **Lv.2** | `400 – 899` | 秘银链路 (Silver) | `#64748B` → `#0284C7` | `#E3EFF8` / `#162636` | `#0F4C75` / `#7DD3FC` |
| **Lv.3** | `900 – 1,599` | 翡翠中继 (Jade) | `#10B981` → `#0D9488` | `#DCF7EC` / `#112F26` | `#065F46` / `#6EE7B7` |
| **Lv.4** | `1,600 – 2,499` | 钴蓝核心 (Cobalt) | `#3B82F6` → `#6366F1` | `#DFE7FF` / `#19234F` | `#1E3A8A` / `#A5B4FC` |
| **Lv.5** | `2,500 – 3,599` | 紫晶骨干 (Amethyst) | `#8B5CF6` → `#EC4899` | `#F0E1FF` / `#2D1942` | `#5B21B6` / `#E9D5FF` |
| **Lv.6** | `3,600+` | 曜金主干 (Sovereign)| `#F59E0B` → `#EF4444` → `#FBBF24` (3-stop) | `#FFF0C7` / `#38260D` | `#854D0E` / `#FDE047` |

### 2.5 Reward & Telemetry Accent Tokens (`鸡腿` Drumstick, `星尘` Stardust & Heat)
- **Drumstick (`鸡腿`) Gold**:
  - Hero Gradient: `#F59E0B` → `#D97706` (Light) / `#FCD34D` → `#F59E0B` (Dark)
  - Subtle Surface Tint: `#FFF4DC` (Light) / `#2B200E` (Dark); Text/Icon: `#9A5200` (Light) / `#FBBF24` (Dark)
- **Stardust (`星尘`) Astral Violet**:
  - Surface Tint: `#EDE7FF` (Light) / `#241B42` (Dark); Text/Icon: `#5B21B6` (Light) / `#C4B5FD` (Dark)
- **Thread Heat (`热门/高赞`) Flame Accent** (triggered when `replies >= 30` or `views >= 1500`):
  - Pill Background: `#FFE8DF` (Light) / `#361B15` (Dark); Flame Icon & Count: `#C2410C` (Light) / `#FB923C` (Dark)

### 2.6 Typography System (`Type.kt`)

Anode pairs clean CJK editorial legibility (`Noto Sans CJK SC` / Android system variable sans) with **`fontFeatureSettings = "tnum"`** on all numerical metadata and `FontFamily.Monospace` for code, terminal logs, and UIDs.

| Token Role | Size / LineHeight | Weight | Letter Spacing | Usage & Read/Unread Rules |
| :--- | :--- | :--- | :--- | :--- |
| `displaySmall` | `32.sp` / `40.sp` | Bold (`W700`) | `-0.5.sp` | Pulse Hub coin/level hero numerals (`tnum`) |
| `headlineSmall` | `22.sp` / `30.sp` | Bold (`W700`) | `-0.2.sp` | **Topic Reader Article Title** (selectable, editorial rhythm) |
| `titleLarge` | `19.sp` / `26.sp` | SemiBold (`W600`)| `0.sp` | Top App Bar expanded brand/board title, Modal sheet titles |
| `titleMedium` | `16.5.sp` / `23.sp`| **SemiBold (`W600`)** | `0.sp` | **UNREAD Feed Card Title** (`onSurface` `100%` alpha) |
| `titleMediumRead`| `16.5.sp` / `23.sp`| **Normal (`W400`)** | `0.sp` | **READ Feed Card Title** (`onSurfaceVariant` `68%` alpha) |
| `titleSmall` | `14.5.sp` / `20.sp`| SemiBold (`W600`)| `0.1.sp` | Bento board title, author username in Reader & Hub |
| `bodyLarge` | `16.sp` / `26.5.sp`| Normal (`W400`) | `0.2.sp` | **Topic Reader Markdown/HTML Body** (`1.65x` line-height; scalable `85%–130%`) |
| `bodyMedium` | `14.sp` / `21.sp` | Normal (`W400`) | `0.15.sp` | Floor comment body (`15.sp` option), Bento board descriptions, notifications |
| `labelLarge` | `13.5.sp` / `18.sp`| SemiBold (`W600`)| `0.1.sp` | Sort switcher labels, CTA buttons, Quick-Reply bar prompt |
| `labelMedium` | `12.sp` / `16.sp` | Medium (`W500`) | `0.2.sp` | Category micro-tags, Heat/Comment pills, relative time (`tnum`) |
| `labelSmall` | `10.5.sp` / `14.sp`| Bold (`W700`) | `0.3.sp` | `Lv.X` rank pills, `楼主` OP badge, `#12` floor badge, `⚡ RSS` indicator (`tnum`) |
| `codeBody` | `13.sp` / `19.5.sp`| Normal (`W400`) | `0.sp` | `FontFamily.Monospace` syntax-highlighted code & YABS ANSI blocks |

### 2.7 Shape, Border & Expressive Motion Tokens (`Shape.kt`)
- **Corner Radius Scale**:
  - `extraSmall = 6.dp` (`Lv.X` badge, `楼主` OP chip, inline `<kbd>`/`<code>` spans)
  - `small = 10.dp` (Category micro-tags, floor `#` pills, blockquote corner)
  - `medium = 16.dp` (**`PulseTopicCard`**, standard `1x1` Bento tiles, code blocks, image cards)
  - `large = 22.dp` (Hero `2x1` Bento cards, Pinned ticker card, Modal dialogs)
  - `extraLarge = 28.dp` (Modal Bottom Sheets top corners)
  - `full = CircleShape` (**Floating Navigation Pill Dock**, Search Pill, Sort Switcher capsule, `鸡腿` status chip)
- **Zero-Muddy-Shadow Rule**: Cards use `0.dp` shadow elevation in scroll lists; depth is expressed via **tonal surface contrast** (`surfaceContainerLowest` `#FFFFFF` over `#FAF7F2` in Light; `surfaceContainer` `#151821` over `#07080C` in Dark) framed by a **`1.dp` `outlineVariant` border** (`45%` alpha Light, `65%` alpha Dark). Only the Floating Navigation Dock and FAB carry subtle `4.dp–6.dp` shadow elevation.
- **Material 3 Expressive Springs**:
  - Spatial / Layout Morph (`navPillIndicator`, `sortSegmentSlide`, `fabExpand`): `spring(dampingRatio = 0.72f, stiffness = 380f)`
  - Micro-Interaction Pop (`bookmarkToggle`, `drumstickBurst`, `upvoteBounce`): `spring(dampingRatio = 0.55f, stiffness = 520f)`

---

## 3. Screen & Component Specifications for Jetpack Compose

### 3.1 Root Navigation Shell (`AnodeAppShell`)

Replaces classic bottom tab bars with an **Edge-to-Edge Viewport + Floating Tonal Pill Dock + Contextual Expressive FAB**.

```text
┌──────────────────────────────────────────────────────────┐
│ [Status Bar — Transparent Edge-to-Edge]                  │
│                                                          │
│                  ACTIVE SCREEN CONTENT                   │
│       (Content scrolls cleanly behind bottom dock        │
│        with bottom ContentPadding = 96.dp + navInsets)   │
│                                                          │
│       ┌────────────────────────────────────┐  ┌────────┐ │
│       │  (◉ 脉冲)    ❖ 版块    ◎ 枢机•     │  │ ✎ 发帖 │ │
│       └────────────────────────────────────┘  └────────┘ │
│                    [Gesture Pill Area]                   │
└──────────────────────────────────────────────────────────┘
```

- **Edge-to-Edge Setup**: `enableEdgeToEdge()` in `MainActivity` with transparent status and navigation bars. Bottom area applies a subtle `56.dp` vertical scrim fade (`background.copy(alpha = 0f)` → `background.copy(alpha = 0.88f)`) so list content scrolling underneath the floating dock remains clean and legible.
- **Floating Tonal Pill Dock (`PulseFloatingNavBar`)**:
  - **Dimensions**: Height `62.dp`, horizontal margin `20.dp` (or centered `maxWidth = 300.dp` paired alongside the FAB), floating `12.dp` above `WindowInsets.navigationBars`, `CircleShape`, `surfaceContainerHigh` (`94%` alpha) + `1.dp` `outlineVariant` stroke + `6.dp` tonal shadow.
  - **3 Focused Destinations**:
    1. **`脉冲` (Explore / Feed)** — `Icons.Rounded.Explore` (selected) / `Icons.Outlined.Explore`: Main topic stream, sort switcher, quick check-in. Re-tapping while active smoothly scrolls `LazyColumn` to index `0` and triggers pull-to-refresh if already at top.
    2. **`版块` (Boards / Discover)** — `Icons.Rounded.GridView` / `Icons.Outlined.GridView`: Asymmetric Bento board directory, RSS/Web mode switcher, and instant search.
    3. **`枢机` (Pulse Hub / Me)** — `Icons.Rounded.Hub` / `Icons.Outlined.Hub` (with live `8.dp` `primary` or `tertiary` unread badge dot when `unreadNotifications > 0` or daily check-in pending): Bento profile dashboard, check-in card, inbox, offline bookmarks, history, and settings.
  - **Selection Indicator**: Sliding `primaryContainer` stadium pill (`height = 46.dp`) animating horizontally between tabs via `animateDpAsState(spring(0.72f, 380f))`, displaying Icon + `labelMedium` label for the active destination and crisp icon + compact label (or icon-first compact pill) for inactive destinations.
- **Contextual Expressive FAB (`PulseContextualFab`)**:
  - **On `脉冲` (Explore)**: `56.dp`-tall squircle (`RoundedCornerShape(18.dp)`) in `primary` (`#3655D6` / `#8DA2FF`) next to the Floating Pill Dock. Shows `Icons.Rounded.Edit` + `"发帖"` when at top of feed or scrolling up; smoothly collapses to a `56.dp × 56.dp` icon-only squircle when scrolling down. Tapping opens `TopicComposerSheet` (or `SessionLoginSheet` if unauthenticated).
  - **On `版块` & `枢机`**: Smoothly scales out (`scaleOut + fadeOut`) so Bento grids breathe unobstructed.
  - **On `TopicReaderScreen`**: Navigation dock hides automatically (`slideOutVertically`) in favor of the Reader's anchored **Quick-Reply Bar** and right-edge **Floor Scrubber**.
- **Large-Screen / Foldable Adaptation (`width >= 600.dp`)**: Dock automatically transforms into a left-anchored `NavigationRail` with the Compose squircle FAB at the top of the rail.

---

### 3.2 Screen 1: Explore / Feed Screen (`ExploreFeedScreen`)

```text
┌──────────────────────────────────────────────────────────┐
│ Anode                                   [🍗 签到领腿]  │ <- Collapsible Row 1
│ ┌──────────────────────────────────────────────────────┐ │
│ │ 🔍 搜索节点、VPS 测评、线路或 @用户…         [⚡RSS] │ │ <- Integrated Search Pill
│ └──────────────────────────────────────────────────────┘ │
│ ┌──────────────────────────────┐  [全部] [技术] [情报] > │ <- Sticky Sub-Bar:
│ │ (最新回复)  最新发布   热门  │                         │    Animated Sort + Boards
│ └──────────────────────────────┘                         │
├──────────────────────────────────────────────────────────┤
│ 📌 置顶 · NodeSeek 社区版规与发帖指南 (共 2 条)      [⌄] │ <- Compact Pinned Ticker
├──────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────┐ │
│ │ ┃ (Avatar) 用户名 [Lv.4] · 4m前      [技术]    [🔖]  │ │ <- PulseTopicCard (Unread)
│ │ ┃          Ring                                      │ │    3dp Sapphire Spine
│ │ ┃ DMIT LAX Pro 三网回程晚高峰 YABS 与 MTR 深度实测   │ │    SemiBold 16.5sp Ink
│ │ ┃                                                    │ │
│ │ ┃ [🔥 48 评论]  👁 1.8k  · 最后回复 @alice           │ │    Heat Pill + Telemetry
│ └──────────────────────────────────────────────────────┘ │
│ ┌──────────────────────────────────────────────────────┐ │
│ │   (Avatar) 用户名 [Lv.2] · 19m前     [日常]    [🔖]  │ │ <- PulseTopicCard (Read)
│ │   今天收了一台传家宝小鸡，分享一下折腾心得           │ │    Normal 16.5sp Muted
│ │   [💬 7 评论]   👁 312   [🔒 Lv.1+]                  │ │
│ └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

#### Component Breakdown & Compose Specs:
1. **Collapsible Editorial Top Bar (`ExploreTopBar`, `TopAppBarDefaults.exitUntilCollapsedScrollBehavior`)**:
   - **Row 1 (Collapses on scroll)**:
     - Left: Geometric pulse glyph (`20.dp`, `primary`) + `"Anode"` (`titleLarge`, Bold) + active board subtitle chip if filtered (e.g. `· 技术`).
     - Right: **One-Tap Daily Check-in (`鸡腿`) Status Chip (`DrumstickStatusChip`)**:
       - *Unchecked (Signed In)*: `tertiaryContainer` capsule (`height = 34.dp`), animated subtle amber shimmer border, `🍗 签到` (`labelMedium`, Bold). Single tap executes check-in using user's preferred mode (default random or fixed) and morphs in-place with a `+X 🍗` particle/counter flip!
       - *Checked In*: Soft `surfaceContainerHigh` capsule with gold icon `🍗 今日 +5` (`onSurfaceVariant`), tapping opens Pulse Hub Check-in details.
       - *Signed Out / RSS Guest*: Tapping opens `SessionLoginSheet` with prompt *"登录即可每日领取鸡腿奖励"*.
   - **Row 2 (Pins or smoothly compacts)**:
     - **Integrated Search Pill (`PulseSearchPill`)**: `height = 44.dp`, `CircleShape`, `surfaceContainerHigh`, `Icons.Rounded.Search` + `"搜索帖子、关键词或版块…"` + trailing **Data Source Micro-Badge** (`⚡ RSS` in `secondaryContainer` when browsing fast unauthenticated RSS, or `🌐 Web` when using authenticated HTML/JSON session). Tapping transitions smoothly to Search mode on `BoardsBentoScreen` (or inline search overlay).
2. **Sticky Sort & Quick-Board Strip (`ExploreFilterStrip`)**:
   - **Animated Sort Segmented Switcher (`PulseSegmentedSort`)**:
     - Capsule track (`height = 36.dp`, `surfaceContainer`) containing 3 segments:
       1. `最新回复` (`ReplyTime` — Web live sort)
       2. `最新发布` (`PostTime` — Web & RSS `rss.nodeseek.com` native sort)
       3. `热门精选` (`Hot` — Web recommended / high-velocity sort; badge-assisted in RSS fallback)
     - Selected segment renders a `surfaceContainerLowest` (Light) / `primaryContainer` (Dark) pill sliding with `spring(0.72f, 380f)`.
   - **Quick-Pin Board Chips**: Horizontally scrollable `FilterChip` rail next to/below the sort bar (`全部`, plus user's pinned boards from Bento directory, defaulting to `日常`, `技术`, `情报`, `测评`, `交易`).
3. **Compact Pinned Ticker (`PinnedTopicsBanner`)**:
   - Single `42.dp` tonal bar (`surfaceContainerLow`, `14.dp` radius) displaying `📌 置顶` in `secondary` + cycling single-line title + count pill (`2`) + expand chevron. Expanding reveals compact pinned rows inline; collapsed state keeps `140.dp+` of vertical screen space clear for the main feed.
4. **Rich Editorial Feed Card (`PulseTopicCard`)**:
   - **Container**: `RoundedCornerShape(16.dp)`, `surfaceContainerLowest` (Light) / `surfaceContainer` (Dark), `1.dp` `outlineVariant` border, `horizontalPadding = 14.dp`, `verticalPadding = 13.dp`, outer margin `16.dp` horizontal / `5.dp` vertical.
   - **Left Category Accent Spine**: For **unread** posts, a `3.dp` wide, `28.dp` tall rounded vertical bar on the card's left inner edge tinted with the post's `CategoryAccent` color; fades out once read!
   - **Header Row**:
     - `LvRingAvatar(size = 34.dp, ringWidth = 1.75.dp, level = authorLevel)` (tappable → Member Profile sheet).
     - `8.dp` spacer → Author Name (`labelMedium`, `SemiBold`, `onSurface`, maxLines 1) + `LvBadge(level)` (`labelSmall`, `6.dp` radius) + `·` + Relative Timestamp (`4m`, `2h`, tabular `tnum` in `onSurfaceVariant`).
     - `Spacer(Modifier.weight(1f))`
     - **Category Micro-Tag (`CategoryChip`)**: `height = 22.dp`, `RoundedCornerShape(8.dp)`, board-specific container & foreground hex from §2.3, `12.dp` board icon + board title (`labelMedium`).
     - **1-Tap Bookmark Quick-Toggle (`IconButton`, `32.dp` visual / `48.dp` hit target)**: `Icons.Rounded.BookmarkBorder` (`onSurfaceVariant` `55%` alpha) → `Icons.Rounded.Bookmark` (`primary` Cobalt fill) with `scale(1.22f -> 1.0f)` spring bounce and haptic tick (`HapticFeedbackType.TextHandleMove`). Works offline immediately (Room bookmark) + syncs to NodeSeek account if logged in!
   - **Title Body**:
     - `10.dp` top spacing. Max `3` lines (`overflow = TextOverflow.Ellipsis`).
     - **Unread**: `titleMedium` (`16.5.sp`, `SemiBold W600`, `onSurface` `100%` alpha).
     - **Read**: `titleMediumRead` (`16.5.sp`, `Normal W400`, `onSurfaceVariant` `68%` alpha).
   - **Footer Telemetry Rail**:
     - `10.dp` top spacing.
     - **Heat / Comment Pill**: Rounded `999.dp` pill (`height = 22.dp`, horizontal padding `8.dp`). Standard threads use `surfaceContainerHigh` + `💬 12`; hot threads (`replies >= 30` or `views >= 1500`) switch to **Heat Coral/Amber Tint** (§2.5) + `🔥 48`.
     - **View Count**: `👁 1.8k` (`labelMedium`, `tnum`, `onSurfaceVariant`).
     - **Access Gate Chip (if `rank > 0`)**: `🔒 Lv.2+` pill in `inside` amber tint.
     - Trailing right: Last replier subtle caption (`最后 @bob`) when available.
5. **Loading, Pull-to-Refresh & Error States**:
   - Material 3 `PullToRefreshBox` with custom rotating Cobalt/Gold pulse indicator.
   - Initial load shows **5 Shimmer Editorial Skeleton Cards** matching exact `PulseTopicCard` geometry (never a bare centered spinner on blank screen).
   - Pagination footer auto-triggers `3` items before list end with a sleek inline shimmer strip and retry pill on network timeout.

---

### 3.3 Screen 2: Boards & Search Screen (`BoardsBentoScreen`)

```text
┌──────────────────────────────────────────────────────────┐
│ 版块与探索 · Node Directory              [⚡ RSS / 🌐 Web]│
│ ┌──────────────────────────────────────────────────────┐ │
│ │ 🔍 筛选版块，或输入关键词回车搜全站帖子…        [✕]  │ │ <- Instant Filter + Search
│ └──────────────────────────────────────────────────────┘ │
│ 最近搜索: [DMIT] [claw] [软路由] [出鸡]          [清空]  │
├──────────────────────────────────────────────────────────┤
│ 核心脉冲 · FEATURED BOARDS                               │
│ ┌──────────────────────────────────────────────────────┐ │
│ │ [💻] 技术 · Tech                      [★ 已常驻] ⚡🌐 │ │ <- 2x1 Hero Bento Tile
│ │ VPS 线路回程、Linux 内核调优、网络架构与自建服务实战 │ │
│ └──────────────────────────────────────────────────────┘ │
│ ┌─────────────────────────┐  ┌─────────────────────────┐ │
│ │ [📡] 情报 · Info   ⚡🌐 │  │ [⚡] 测评 · Review ⚡🌐 │ │ <- 1x1 Medium Bento Tiles
│ │ 补货监控、神鸡线报与优惠│  │ YABS 跑分、Geekbench 实测│ │
│ └─────────────────────────┘  └─────────────────────────┘ │
│ ┌─────────────────────────┐  ┌─────────────────────────┐ │
│ │ [☕] 日常 · Daily  ⚡🌐 │  │ [⇄] 交易 · Trade   ⚡🌐 │ │
│ │ 日常闲聊、社区百态交流  │  │ 闲置 VPS 溢价/折价流转  │ │
│ └─────────────────────────┘  └─────────────────────────┘ │
│ 全部频道 · ALL CHANNELS                                  │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐             │
│ │ 🚗 拼车    │ │ 📣 推广    │ │ 🌿 生活    │  ...        │ <- Compact Bento Grid
│ │ 💻 Dev     │ │ 🖼 贴图    │ │ 🚨 曝光    │             │
│ │ 🔒 内版(Web)│ │ 🧪 沙盒    │ │ 💬 无意义  │             │
│ └────────────┘ └────────────┘ └────────────┘             │
└──────────────────────────────────────────────────────────┘
```

#### Component Breakdown & Compose Specs:
1. **Dual-Purpose Instant Search & Filter Bar (`DirectorySearchBar`)**:
   - Typing immediately filters the Bento board tiles below in real time (matching Chinese title, English slug `tech`/`carpool`, or keywords like `"VPS"`/`"合租"`).
   - Pressing IME `Search` (or tapping `"搜索「{query}」相关的帖子"`) switches the body into **Search Results Stream** with filter chips: `按回复时间`, `按发布时间`, `指定版块`, and a fallback `"使用 Google 站内搜索 (site:nodeseek.com)"` helper card when unauthenticated or rate-limited.
2. **Global Data Mode Pill (`DataModeStatusPill`)**:
   - Top-right interactive status pill indicating current transport:
     - `⚡ RSS 极速模式`: Zero-login XML feed (`rss.nodeseek.com`), ultra-fast, read-only public boards.
     - `🌐 Web 完整会话`: Authenticated session supporting sort-by-reply, `内版`, search, check-in, and replies.
   - Tapping shows a concise popover explaining RSS vs Web capabilities with a 1-tap switch or login shortcut.
3. **Asymmetric Visual Bento Grid (`LazyVerticalGrid(columns = GridCells.Fixed(2))`)**:
   - **Hero `2x1` Span (`GridItemSpan(2)`)**: `技术 (tech)` (or dynamically the user's #1 most-visited board). Height `108.dp`, `RoundedCornerShape(22.dp)`, subtle diagonal gradient wash (`categoryContainer` `45%` → `surfaceContainer`), `40.dp` rounded icon badge, title + slug, 2-line description, pin star toggle (`★`), and `⚡ RSS` / `🌐 Web` capability micro-icons.
   - **Featured `1x1` Span (`GridItemSpan(1)`)**: `情报 (info)`, `测评 (review)`, `日常 (daily)`, `交易 (trade)`. Height `116.dp`, `RoundedCornerShape(18.dp)`, `surfaceContainerLowest` / `surfaceContainer` + `1.dp` hairline.
   - **Compact Channel Tiles (`GridItemSpan(1)` or 3-col subgrid)**: `拼车 (carpool)`, `推广 (promotion)`, `生活 (life)`, `Dev (dev)`, `贴图 (photo-share)`, `曝光 (expose)`, `内版 (inside — shows 🔒 Web Auth badge)`, `沙盒 (sandbox)`, `无意义 (meaningless)`.
   - **Editorial Descriptions Map** (built into client constants so boards always feel rich and informative even when fetched dynamically):
     - `daily`: *"社区日常闲聊、数码见闻与水贴交流"*
     - `tech`: *"VPS 线路优化、Linux 运维、Docker 与自建服务"*
     - `info`: *"主机补货监控、促销折扣与第一手业界线报"*
     - `review`: *"YABS 硬件跑分、三网回程 MTR 与流媒体解锁实测"*
     - `trade`: *"二手闲置服务器、域名与数字资产流转交易"*
     - `carpool`: *"流媒体家庭组、专线流量与合租车队招募"*
     - `promotion`: *"主机商官方发布、AFF 推介与新商家自荐"*
     - `life`: *"职场感悟、美食摄影、装机桌面与三次元生活"*
     - `dev`: *"开源项目Show HN、脚本工具、前后端与移动开发"*
     - `photo-share`: *"壁纸插画、摄影作品与趣味图集分享"*
     - `expose`: *"跑路预警、交易纠纷仲裁与不良商家避坑指南"*
     - `inside`: *"高阶会员专属讨论区（需登录并满足等级权限）"*

---

### 3.4 Screen 3: Topic Reader Screen (`TopicReaderScreen`)

```text
┌──────────────────────────────────────────────────────────┐
│ [←]  (Avatar) 用户名 [楼主]                   [只看楼主] [⋮]│ <- Collapsible Top Bar
├──────────────────────────────────────────────────────────┤
│ [测评 · Review]  ·  2026-11-15 13:42  ·  👁 2,410        │ <- Editorial Eyebrow
│ DMIT LAX Pro 三网回程晚高峰 YABS 与 MTR 深度实测报告     │ <- 22sp Bold Article Title
│ ┌──────────────────────────────────────────────────────┐ │
│ │ (Avatar 42dp) 用户名 [Lv.5] [楼主 OP]     [+ 关注]   │ │ <- Author Telemetry Strip
│ │ UID 10492 · 发布于 1 楼                              │ │
│ └──────────────────────────────────────────────────────┘ │
│ 正文段落采用 16sp 字号与 1.65 倍行高，中文与英文数字之   │
│ 间保持舒适呼吸感……                                       │
│ ┌─ bash · YABS Output ──────────────────── [📋 复制代码]┐ │
│ │ curl -sL yabs.sh | bash                              │ │ <- Syntax-Tinted Code Card
│ │ Geekbench 6: Single 2145 | Multi 6890                │ │    Dark Slate #12151E
│ └──────────────────────────────────────────────────────┘ │
│ ┃ 引用 @alice (#3): 请问电信晚高峰丢包率如何？           │ <- Cobalt Spine Blockquote
│ ┌──────────────────────────────────────────────────────┐ │
│ │              [ 🖼 Coil Zoomable Image ]              │ │ <- Rounded 14dp Image Card
│ │                                        [🔍 点击放大] │ │
│ └──────────────────────────────────────────────────────┘ │
│ [👍 28]   [🍗 投喂 6]   [👎]                      [分享] │ <- OP Reaction Rail
├══════════════════════════════════════════════════════════┤
│ 全部回复 (148)                         [正序 ⇅] [只看楼主]│
├──────────────────────────────────────────────────────────┤
│ (Avatar 32dp) 用户名 [Lv.3] [楼主] [📌 置顶]       [#2]  │ <- Clean Floor Header
│ 补充一下晚高峰 21:30 上海电信 CN2 GIA 的 MTR 截图：     │    NO protruding watermark!
│ [👍 9]  [🍗 2]  [👎]  [💬 引用]             14 分钟前   │ ╭──────╮
├──────────────────────────────────────────────────────────┤ │ 2/148│<- Draggable Floor
│ (Avatar 32dp) 路人甲 [Lv.2]                        [#3]  │ │  ⇕   │   Jump Scrubber
│ 前排支持，绑定鸡腿送上！                                 │ ╰──────╯
│ [👍 1]  [🍗 0]  [👎]  [💬 引用]             11 分钟前   │
├──────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────┐ │
│ │ [💬 说说你的看法，支持 Markdown…]  [🔖] [2/148] [⤢] │ │ <- Anchored Tonal/Glass
│ └──────────────────────────────────────────────────────┘ │    Quick-Reply Bar
└──────────────────────────────────────────────────────────┘
```

#### Component Breakdown & Compose Specs:
1. **Collapsible Reader Top Bar**:
   - Resting at scroll `0`: `[←]` Back button, `[只看楼主]` filter toggle chip, `[⋮]` Overflow menu (*在浏览器打开*, *复制链接*, *分享长图/链接*, *刷新*, *编辑主题 (if OP)*).
   - Scrolled past Article Title: Compact author avatar + single-line marquee/ellipsized topic title fades into the top bar smoothly.
2. **Reader-Grade Article Header (`TopicArticleHeader`)**:
   - Eyebrow row: `CategoryChip` + publish timestamp + view count (`👁 2.4k`) + access lock pill (`🔒 Lv.X+` if restricted).
   - Selectable `SelectionContainer` wrapping `headlineSmall` (`22.sp`, `Bold`, `1.36x` line height) title.
   - Author Hero Strip (`surfaceContainerLow`, `14.dp` radius, `12.dp` padding): `42.dp` Lv-ringed avatar, username, **`楼主 OP` Chip** (solid `primary` `#3655D6` / `#8DA2FF` fill with `onPrimary` bold `labelSmall`), `Lv.X` pill, and UID caption.
3. **Rich Native Compose HTML / Markdown Renderer (`PulseRichContent`)**:
   - Parses both full Web post Markdown/HTML and RSS `<description>`/`<content:encoded>` payloads cleanly into typed Compose blocks (`Paragraph`, `Heading`, `CodeBlock`, `Blockquote`, `Table`, `ImageGallery`, `ListBlock`, `Spoiler`, `PollCard`, `RuleDivider`):
   - **Syntax-Tinted Code & ANSI Terminal Blocks (`PulseCodeBlock`)**:
     - Dedicated dark code surface (`#12151E` in both Light & Dark themes for unmistakable terminal contrast, `14.dp` radius, `1.dp` `#2A3042` border).
     - Header strip (`32.dp` height, `#1A1E2B`): language tag (`bash`, `json`, `yaml`, `python`, `text`) on left + **1-Tap Copy Chip** (`📋 复制` → morphs into emerald `✓ 已复制` for `1.8s` with clipboard copy) on right.
     - Regex/lexer keyword, string, comment, number, and ANSI escape color highlighting inside horizontally scrollable `SelectionContainer` (`FontFamily.Monospace`, `13.sp`).
   - **Editorial Blockquotes (`PulseBlockquote`)**:
     - `3.dp` left `primary` Cobalt vertical bar + `surfaceContainerLow` rounded-right background (`10.dp`). When quoting `@username #floor`, renders an interactive Cobalt chip that **smooth-scrolls or jumps pages directly to `#floor`**!
   - **Data Tables (`PulseMarkdownTable`)**:
     - Horizontally scrollable `12.dp`-radius bordered table with `surfaceContainerHigh` header row, alternating `surfaceContainerLow` zebra rows, and `tnum` alignment for benchmark comparison tables.
   - **Zoomable Coil Image Cards (`PulseImageCard` & `PulseImageLightbox`)**:
     - Inline rounded `14.dp` container with shimmer placeholder, automatic aspect-ratio sizing (capped at `420.dp` inline preview height with `"长图"` badge if taller), animated GIF/WebP/Sticker support (AC娘, 洋葱头, 小黄鸡, Fluent emojis), and Referer-preserving OkHttp/Coil loader.
     - Tapping opens full-screen edge-to-edge **`PulseImageLightbox`** (`#000000` backdrop, 2-finger pinch-to-zoom `1x–5x`, double-tap `2.5x` zoom, vertical swipe-to-dismiss, image index `2 / 5`, and 1-tap Save to Gallery / Share).
4. **Threaded Floor Stream (`FloorCommentItem`)**:
   - Separated by crisp `1.dp` `outlineVariant` hairlines (or subtle `surfaceContainerLow` tint when `isPinned == true`).
   - **Header**: `32.dp` Lv-ringed avatar + username + **`楼主` Solid Cobalt Highlight Chip** (whenever `comment.authorId == topic.authorId`) + `📌 楼主置顶` (`secondaryContainer`) + `🔥 热评` (`Heat` tint) + trailing compact **`#12` Floor Pill** (`surfaceContainerHigh`, `labelSmall`, `tnum` — tapping copies floor link or quotes).
   - **Inline Reaction & Action Row**:
     - Compact tonal chips (`height = 28.dp`, `8.dp` radius):
       - `👍 12` (Upvote — free, turns `primaryContainer` when active)
       - `🍗 3` (Feed Drumstick — tinted warm gold; checks cost & prompts lightweight confirmation snackbar/dialog *"确认投喂 1 个鸡腿？（今日免费额度已用完）"* when non-free)
       - `👎` (Downvote — prompts cost confirmation)
       - `💬 引用` (Opens Reply Bar/Sheet pre-populated with `@author #floor` Markdown quote)
5. **Floor Jump Scrubber & Sheet (`FloorScrubberOverlay` & `FloorJumpSheet`)**:
   - **Floating Scrubber Pill**: Appears along the right screen edge during scroll (`height = 56.dp`, `surfaceContainerHigh` + `1.dp` border), showing `12 / 148` (`tnum`) and a vertical drag handle; auto-fades to `0%` after `2s` of scroll inactivity.
   - **Floor Jump Modal Bottom Sheet** (opened by tapping the scrubber pill or the `12/148` pill in the bottom bar):
     - Smooth `Slider` from `#1 (OP)` to `#TotalFloors` with live floor & page preview (`第 3 页 · #42 楼`).
     - Quick action grid: `⏫ 直达顶楼 (OP)`, `⏬ 跳至最新末楼`, `👤 只看楼主`, `⇅ 倒序看帖`, plus numbered Page Pills (`P1`, `P2`, `P3`…) for multi-page threads.
6. **Anchored Tonal/Glass Quick-Reply Bar + Full Reply Bottom Sheet**:
   - **Anchored Quick-Reply Bar (`QuickReplyBar`)**:
     - Docked cleanly above `WindowInsets.ime` / `WindowInsets.navigationBars`, `surfaceContainerHigh.copy(alpha = 0.94f)` + top `1.dp` `outlineVariant` border (`padding = 12.dp horizontal, 8.dp vertical`).
     - Left (`weight(1f)`): `40.dp`-tall `CircleShape` input trigger/field (`surfaceContainerLowest` / `surfaceContainer`, `"说说你的看法…"` — if user has typed a draft, displays `📝 草稿: …` in Cobalt!). Supports immediate single-line quick reply with inline `发送` icon button when focused, or 1-tap expansion to the Full Sheet!
     - Right actions (`40.dp` touch targets): Bookmark toggle (`🔖`), Floor jump pill (`12/148`), and Expand Composer button (`⤢`).
   - **Full Reply & Topic Composer Bottom Sheet (`ReplyComposerSheet`)**:
     - `ModalBottomSheet` (`extraLarge` `28.dp` top corners) with:
       - Top bar: Replying-to context pill (`回复 @alice #12` with `✕` clear) + **`撰写` / `预览` Segmented Toggle** + primary `发布` Cobalt pill button.
       - Multi-line `OutlinedTextField` (`minLines = 6`, monospace-friendly code editing) with local Room/DataStore draft auto-save keyed by `(accountId, topicId)`.
       - Sticky IME Markdown Toolbar above keyboard: `B` Bold, `I` Italic, `<>` Inline Code, ` ``` ` Code Block, `>` Quote, `🔗` Link, `🖼` Image Upload/Link, `😊` NodeSeek Sticker Picker (AC娘 / 洋葱头 / 小黄鸡 / Fluent).

---

### 3.5 Screen 4: Pulse Hub (Me) Screen (`PulseHubScreen`)

Presents a vibrant **Material 3 Bento-Grid Dashboard** + **Embedded Offline Bookmarks & History Tabs** + **Clean In-App WebView Session Login Sheet**.

```text
┌──────────────────────────────────────────────────────────┐
│ 枢机 · Pulse Hub                              [⚙ 设置]   │
├──────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────┐ │
│ │ (Avatar)  alex_dev  [Lv.4 钴蓝核心]      [UID 10492] │ │ <- Hero Identity & Rank
│ │  64dp     Lv.4 ━━━━━━━━━━━░░░░░ Lv.5 (1,920 / 2,500) │ │    Bento Card (2x1)
│ │  Ring     ┌──────────┬──────────┬──────────────────┐ │ │
│ │           │ 🍗 1,920 │ ✨ 340   │ 📝 24 帖 · 186 回│ │ │
│ │           │ 鸡腿余额 │ 星尘结晶 │ 社区足迹         │ │ │
│ │           └──────────┴──────────┴──────────────────┘ │ │
│ └──────────────────────────────────────────────────────┘ │
│ ┌─────────────────────────┐  ┌─────────────────────────┐ │
│ │ 🍗 每日鸡腿签到         │  │ 🔔 消息与提及     [3•]  │ │ <- 1x1 Interactive Bento
│ │ [🎲 随机手气 | 稳拿5腿] │  │ @alice 在 #12 提到了你  │ │    Row (Check-in + Inbox)
│ │ ┌─────────────────────┐ │  │ 私信: "小鸡还在吗？"    │ │
│ │ │   立即签到领鸡腿    │ │  │                         │ │
│ │ └─────────────────────┘ │  │ 查看全部通知与私信 →    │ │
│ └─────────────────────────┘  └─────────────────────────┘ │
├──────────────────────────────────────────────────────────┤
│   [ 🔖 离线收藏 (18) ]          [ 🕓 阅读历史 (64) ]     │ <- Embedded PrimaryTabRow
├──────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────┐ │
│ │ [测评] DMIT LAX Pro 三网回程深度实测       [⚡已离线]│ │ <- Offline Cached Item
│ │ 收藏于 2 小时前 · 已缓存正文与 42 条评论             │ │
│ └──────────────────────────────────────────────────────┘ │
│ ┌──────────────────────────────────────────────────────┐ │
│ │ [技术] Docker Compose 自建 Vaultwarden 备份方案      │ │ <- History Resume Item
│ │ 浏览于 昨天 22:15 · [上次读至 #24 楼 →]              │ │
│ └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

#### Component Breakdown & Compose Specs:
1. **Hero User Rank & Stats Bento Card (`UserRankHeroCard`, Full-Width `2x1`)**:
   - **Authenticated State**:
     - `64.dp` Avatar wrapped in an animated `2.5.dp` `Lv0–Lv6` metallic/gemstone ring + username (`titleLarge`, Bold) + `Lv.X` persona pill (`Lv.4 钴蓝核心`) + `UID` monospace pill.
     - **Rank Progress Bar**: Linear progress track (`8.dp` height, `CircleShape`, gradient fill matching current Lv tier) showing exact progress toward next `100 * (Lv + 1)²` coin threshold (e.g., `1,920 / 2,500 鸡腿 · 距 Lv.5 还差 580`).
     - **3-Column Telemetry Strip**: `🍗 鸡腿` (tappable → Coin Ledger sheet), `✨ 星尘` (tappable → Stardust Ledger), and `📝 主题 / 回复` (tappable → My Posts sheet).
   - **Unauthenticated / Guest RSS State**:
     - Warm Cobalt/Amber hero card with radar illustration: *"当前处于 RSS 极速访客模式"* + subtitle *"连接 NodeSeek 账号以解锁每日鸡腿签到、内版阅读、云端收藏同步与回帖互动"* + full-width **`连接 NodeSeek 账号`** `primary` CTA button opening `SessionLoginSheet`.
2. **Interactive Daily Check-in (`鸡腿`) Bento Card (`CheckInBentoCard`, `1x1`)**:
   - **Mode Switcher Pill**: Compact 2-way toggle right inside the card:
     - `🎲 手气随机` (`random = true`, variable drumstick reward)
     - `🛡️ 稳拿 5 腿` (`random = false`, guaranteed `+5` drumsticks)
     - Persists user's preferred mode in `DataStore` (also used by the Explore top bar's 1-tap check-in chip!).
   - **Action & Celebration State**:
     - *Before Check-in*: Warm `tertiary` (`#C86D00` / `#FFB951`) full-width capsule button `"立即签到"`.
     - *After Check-in*: Morphs smoothly into a gold-tinted badge displaying `"今日已获 +7 🍗"` + `"今日第 386 位签到 · 连签记录"`.
3. **Notifications & Mentions Inbox Bento Card (`InboxBentoCard`, `1x1`)**:
   - Header with `Icons.Rounded.Notifications` + live unread count pill (`primary` Cobalt fill).
   - Displays a 2-line preview of the most recent unread `@提及`, `帖子回复`, or `私信 (DM)` (or *"暂无未读动态，一切静好"* when caught up).
   - Tapping opens `InboxBottomSheet` / `InboxScreen` with 3 filter pills (`回复我的`, `@提到我`, `站内私信`) and a 1-tap `全部已读` action button.
4. **Offline Bookmarks & Read History Tabs (`HubCollectionsSection`)**:
   - `PrimaryTabRow` directly below the Bento grid:
     - **Tab 1 — `🔖 离线收藏 (Offline Bookmarks)`**:
       - Stores full parsed `TopicDetail` + first-page comments in local **Room** database on bookmark so users can read saved VPS tutorials and benchmarks completely **offline** (`⚡ 已离线缓存` green/teal micro-pill), while syncing bookmark IDs with their NodeSeek web account when signed in.
       - Includes instant local filter chip & swipe-to-remove with `Snackbar` undo.
     - **Tab 2 — `🕓 阅读历史 (Read History)`**:
       - Automatically records viewed topics in Room with timestamp and **last visible floor index**, displaying an actionable **`上次读至 #24 楼 →`** Cobalt chip that jumps straight back to `#24` on tap!
5. **Theme, Typography & Cache Settings (`SettingsBottomSheet` / `SettingsScreen`, opened via `[⚙ 设置]`)**:
   - **Appearance Visual Picker**: 3 illustrated preview cards (`暖纸日光 Paper Light`, `曜石深邃 Obsidian Dark`, `跟随系统 Auto`) + **`纯黑 AMOLED 省电模式 (#000000)`** toggle + **`Material You 动态取色 (Android 12+)`** toggle.
   - **Reader Comfort**: Font size slider (`85%` · `100%` · `115%` · `130%` with live paragraph preview) + Default Data Source preference (`自动智能切换` / `优先 RSS 极速` / `始终 Web 会话`).
   - **Storage & Privacy**: Live cache meter (`图片缓存 42.6 MB · 离线帖子 3.1 MB`) + 1-tap `清理图片缓存` + `清空阅读历史` + `退出当前账号` (clears WebView cookies & OkHttp `CookieJar` atomically).
6. **Clean In-App WebView Session Login Bottom Sheet (`SessionLoginSheet`)**:
   - `ModalBottomSheet` (`92%` screen height, `28.dp` top corners, `dragHandle` disabled over WebView scroll area to prevent accidental swipe-dismiss during verification):
     - **Header Bar**: Close `[✕]` button + **`🔒 https://www.nodeseek.com/signIn.html`** verified SSL domain pill + Reload `[↻]` button.
     - **Trust & Privacy Callout Strip** (`secondaryContainer`, `12.dp` radius): *"安全声明：您在下方官方网页直接完成登录、人机验证或两步验证（2FA）。Anode 绝不读取或存储您的密码，仅在登录成功后同步本地会话 Cookie。"*
     - **WebView Container**: `androidx.webkit` WebView matching app Dark/Light theme (`WebSettingsCompat`), zero custom script injection or anti-bot tampering—letting the user complete official sign-in/2FA naturally in the visible UI.
     - **Automatic Session Bridge**: Observes `CookieManager.getInstance().getCookie("https://www.nodeseek.com")`; as soon as valid NodeSeek session cookies appear and `/api/account` or profile verification succeeds, bridges cookies into the shared OkHttp `CookieJar`, triggers haptic success feedback (`HapticFeedbackType.LongPress`), dismisses the sheet, and refreshes Pulse Hub!

---

## 4. Compose Design Token Architecture (Implementation Blueprint)

UI engineers should structure `com.nodeseek.app.ui.theme` into four clean, decoupled files so every screen consumes semantic tokens rather than hardcoded hexes:

1. `Color.kt`:
   - `PaperLightColorScheme` (`lightColorScheme(...)` using exact §2.1 hexes)
   - `ObsidianDarkColorScheme` & `PitchAmoledDarkColorScheme` (`darkColorScheme(...)` using exact §2.1 hexes)
   - `@Immutable data class AnodeExtendedColors(...)` provided via `LocalAnodeColors`:
     - `categoryPalette(slug: String): CategoryColorPair` (§2.3)
     - `rankPalette(level: Int): RankGradientSpec` (§2.4)
     - `drumstickGold`, `drumstickContainer`, `stardustAccent`, `stardustContainer`, `heatContainer`, `heatForeground`, `codeSurface`, `codeBorder` (§2.5)
2. `Type.kt`:
   - `AnodeTypography` with `tnum` OpenType feature helper (`Modifier` / `TextStyle.withTabularNumbers()`) and `titleMediumRead` extension property.
3. `Shape.kt`:
   - `AnodeShapes` (`extraSmall = 6.dp`, `small = 10.dp`, `medium = 16.dp`, `large = 22.dp`, `extraLarge = 28.dp`) + `PulseSpringSpecs` (`spatialSpring`, `microBounceSpring`).
4. `Theme.kt`:
   - `AnodeTheme(themeMode: ThemeMode, pureAmoled: Boolean, dynamicColor: Boolean, fontScale: Float, content: @Composable () -> Unit)` wiring `MaterialTheme`, `LocalAnodeColors` (with optional `12%` primary harmonization when `dynamicColor = true`), and edge-to-edge system bar icon appearance (`isAppearanceLightStatusBars`).

---

## 5. Accessibility, Contrast & Quality Checklist

- **Contrast Compliance**: Every text/surface pair in both Paper Light (`#1B1B1F` on `#FAF7F2` = `15.8:1`) and Obsidian Dark (`#EAE8EF` on `#07080C` = `16.4:1`), as well as all 11 semantic Category Badges (`>= 5.2:1`), meets or exceeds **WCAG 2.1 AA** (and AAA for body text).
- **Touch Targets**: All interactive chips, bookmark toggles, floor pills, and reaction buttons enforce `minimumInteractiveComponentSize()` (`48.dp × 48.dp` hit bounds even when visual pill height is `28.dp–34.dp`).
- **TalkBack & Semantics**: `PulseTopicCard` merges header/title/telemetry via `Modifier.semantics(mergeDescendants = true)` with explicit `stateDescription = "未读"` / `"已读"`, while keeping the Bookmark quick-toggle as an independent accessible action.
- **Reduced Motion**: Respect system animator duration scale; spring bounces gracefully degrade to instant crossfades when animations are disabled.
