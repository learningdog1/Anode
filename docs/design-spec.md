# Anode — Android Material 3 Expressive Design Specification

- **Product Name**: **Anode**
- **Application ID**: `com.nodeseek.app`
- **Target Platform**: Android 8.0+ (`minSdk = 26`, `targetSdk = 35`), Jetpack Compose + Material 3 (`1.3+`), Edge-to-Edge
- **Design Language**: **Material 3 Expressive — "Cool Mist Light & Slate Steel Telemetry"**

---

## 1. Executive Design Vision & Spatial Principles

### 1.1 Aesthetic North Star: "Cool Mist Light & Slate Steel Telemetry"
NodeSeek is home to infrastructure builders, VPS benchmarkers, network engineers, and self-hosters. **Anode** treats technical forum reading like a **boutique hardware & network journal** tailored for Android edge-to-edge ergonomics:

1. **Cool Mist Ice-Blue (Daylight)**: Clean cool-mist daylight surfaces (`#F7FAFF` / `#EBEEF3` with crisp `#FFFFFF` sheets) paired with high-contrast slate ink (`#171C1F`) and **Steel Teal-Blue (`#2D637E`)** accents—eliminating harsh glare during long technical threads.
2. **Slate Steel Telemetry (Night / AMOLED)**: Low-luminance slate obsidian (`#101417`, with an optional `#000000` Pure Pitch toggle) layered with cool slate containers (`#1C2024` / `#262A2E`), luminous **Sky Blue (`#9ACAEC`)**, **Slate Cyan-Secondary (`#B5C9D7`)**, and **Glowing Drumstick Gold (`#FFB951`)**.
3. **Architectural Precision**: 1dp crisp hairlines (`outlineVariant`), tabular numerals (`tnum`) across every metric, semantic category color spines, conic metallic rank rings (`Lv0`–`Lv6`), and bouncy Material 3 Expressive spring transitions.

### 1.2 Core Spatial & Component Principles

| Dimension | Design Principle & Compose Implementation |
| :--- | :--- |
| **Brand & App Icon** | **Anode**: Concentric radar pulse arcs and Anode architectural pillars in Steel Blue (`#2D637E` → `#06B6D4`) with a glowing Cyan/Gold signal core on an adaptive squircle. |
| **Color Philosophy** | **Cool Mist Light (`#F7FAFF`)** + **Slate Steel Dark (`#101417`)** (`#000000` Pitch option); **Steel Teal-Blue (`#2D637E` / `#9ACAEC`)** primary, **Muted Steel (`#4E616D` / `#B5C9D7`)** secondary, **Drumstick Gold (`#C86D00` / `#FFB951`)** reward accent, plus optional **Material You** wallpaper harmonization (`useDynamicColor = false` by default). |
| **Navigation Shell & Adaptive Layout** | Edge-to-edge viewport with a **Floating Tonal Pill Dock** (`首页 / EXPLORE`, `功能栏 / FEATURE_HUB`, `我的 / PULSE_HUB`) + **Contextual Collapsing Squircle FAB** (`发帖` on Explore). Re-tapping `首页` scrolls smoothly to top and refreshes. On `width >= 600.dp` (`Medium` / `Expanded`), `AdaptiveLayout` switches the dock to a start-edge **`NavigationRail`** and activates **dual-pane master-detail** (`0.42f/0.58f` or `0.38f/0.62f`). |
| **Feed Top Bar & Sort** | **Minimalist Glass Top Bar** with left `Anode` emblem + right compact **Search Icon** (`SearchRoute`) + borderless **Sort Selector** (`新评论 ▾` / `新帖子` / `热门` / `推荐阅读`), paired with a full-board **Text Category Strip** synchronized 1:1 with `HorizontalPager`. |
| **Pinned Topics** | Compact, transparent **Collapsible Pinned Ticker Strip** (`📌 置顶`) that expands inline on tap—preserving vertical viewport space. |
| **Feed Cards** | **High-Density Outlined `PulseTopicCard`** (`14dp` radius + `0.85dp` transparent hairline border, zero card fill/left spine): `24dp` author avatar, **1-tap Bookmark icon**, crisp **Read vs Unread typographic weight/alpha contrast**, compact telemetry footer, and **bottom-right backgroundless Category indicator**. |
| **Unified Search** | Dedicated **Search Screen (`SearchRoute`)** supporting topic/user scope switching, board & sort filters, recent search chips, and 1-tap Google `site:www.nodeseek.com` fallback. |
| **Feature Hub (`功能栏`) & Toolbox** | Centralized tool dock organizing **社区服务** (`邮箱 / SeekMailScreen`, `活动 / ActivityScreen`), **情报与监控** (`海外情报 / OverseasIntelScreen`, `补货监控 / RestockMonitorScreen`), **主机与网络** (`我的服务器 / MyServersScreen`, `手机端测机 / MobileNetBenchScreen` with ICMP Ping + TCPing + `-P` iperf3 + Dual Exit IP), and Hero **`轻量 SSH`** (`LiteSshScreen` with `SshTerminalAutoFit`, virtualized console & `NodeQuality` `:::: tabs` export). |
| **Topic Reader & Comments** | **Magazine-Grade Article Surface** + clean **Threaded Stream** separated by architectural hairlines: `楼主` (**OP**) solid Cobalt highlight chip on OP floors, compact tabular `#12` floor pill inside header, syntax-tinted code blocks with 1-tap copy, zoomable Coil image cards, and inline tonal reaction chips (`👍` `🍗` `👎` `💬`), plus **Seller Trust Sheet (`SellerTrustCardSheet`)** on avatar/badge tap. |
| **Reader Bottom Controls & Floor Jump** | **Single Anchored Tonal Quick-Reply Bar** (inline quick-reply pill + bookmark + floor counter `12/148` + expand icon + contextual `发测评` SSH quick action) + **Full Markdown Reply Bottom Sheet**, paired with a draggable right-edge **Floor Jump Scrubber & Sheet** (`只看楼主`, slider, page jump). |
| **Profile Hub, Check-in & Settings** | **Personal Hub (`我的`)**: Preserved ambient-glow Hero Rank & `Lv` Coin Progress Card with 4-column stats (`鸡腿 / 星尘 / 主题 / 评论`), **1-Line Compact Check-in Card (`CompactCheckInCard`)**, settings-style grouped lists (`消息`: 通知/私信/关键词提醒; `内容`: 我的收藏/浏览记录/我的帖子) linking to **`HubCollectionScreen`**, grouped-card **`SettingsScreen`** (hosting **`ContentFilterSection`** & **`BackupRestoreSection`**), in-place **`SeekMailApiKeyDialog`**, and **`WebViewSessionSheet`**. |
| **Single-Line Interactive Control Discipline** | Buttons, filter chips, segmented pills, and status tags across 30+ screens/dialogs enforce `maxLines = 1`, `softWrap = false`, `TextOverflow.Ellipsis` to prevent cramped multi-line button wrapping under `90%` compact density or dynamic font scaling. |

---

## 2. Color, Typography, Shape & Motion System

### 2.1 Core Material 3 Color Schemes (`Color.kt`)

Anode ships with two bespoke default palettes (**Cool Mist Ice-Blue Light** and **Slate Steel Dark**), plus an optional **Pure Pitch (`#000000`)** AMOLED sub-toggle and **Material You Dynamic Color** (Android 12+, `useDynamicColor = false` by default).

| M3 Color Role | Cool Mist Light | Slate Steel Dark | Usage in Anode |
| :--- | :--- | :--- | :--- |
| `primary` | `#2D637E` (Steel Teal-Blue) | `#9ACAEC` (Sky Blue) | Active nav indicator, OP chip, links, primary CTAs, unread dot |
| `onPrimary` | `#FFFFFF` | `#00344B` | Text/icons on solid `primary` fills |
| `primaryContainer` | `#CDE7FB` | `#114B66` | Selected filter pills, active sort segment, FAB container |
| `onPrimaryContainer` | `#001E2E` | `#CDE7FB` | Text/icons inside `primaryContainer` |
| `secondary` | `#4E616D` | `#B5C9D7` | RSS live badge, verified/pinned tags, benchmark highlights |
| `onSecondary` | `#FFFFFF` | `#20333E` | Text/icons on `secondary` |
| `secondaryContainer` | `#D1E5F3` | `#364955` | RSS mode pill, secondary accents |
| `onSecondaryContainer` | `#0A1E28` | `#D1E5F3` | Text/icons inside `secondaryContainer` |
| `tertiary` | `#605A7D` | `#CAC1E9` | Tertiary tonal badges & composer accents |
| `onTertiary` | `#FFFFFF` | `#322C4C` | Text/icons on `tertiary` |
| `tertiaryContainer` | `#E6DEFF` | `#494264` | Tertiary tonal containers |
| `onTertiaryContainer` | `#1C1736` | `#E6DEFF` | Text/icons inside `tertiaryContainer` |
| `background` | `#F7FAFF` (Cool Mist) | `#101417` (`#000000` Pitch mode) | Root edge-to-edge screen background |
| `onBackground` | `#171C1F` | `#DFE3E7` | Primary reading body & unread headline text |
| `surface` | `#F7FAFF` | `#101417` (`#000000` Pitch mode) | Collapsible top bar resting background, reader canvas |
| `onSurface` | `#171C1F` | `#DFE3E7` | Primary text, unread feed titles (`100%` alpha) |
| `onSurfaceVariant` | `#40484D` | `#C0C7CD` | Metadata, timestamps, read feed titles (`78%` Light / `66%` Dark alpha via `readTitleAlpha`) |
| `surfaceContainerLowest` | `#FFFFFF` (Crisp Sheet) | `#0B0F12` (`#000000` Pitch) | Outlined card lifts & modal highlights |
| `surfaceContainerLow` | `#F1F4F9` | `#181C1F` (`#0A0D10` Pitch) | Grouped reader blockquotes, settings group containers, alternating rows |
| `surfaceContainer` | `#EBEEF3` | `#1C2024` (`#111518` Pitch) | Default cards, reply bar base *(note: `NodePulseExtendedColors.cardSurface` uses `#0C1013` in Pure Pitch AMOLED, `#14181C` elevated)* |
| `surfaceContainerHigh` | `#E5E8EE` | `#262A2E` (`#191D21` Pitch) | Floating bottom navigation pill, search pill, modal sheets |
| `surfaceContainerHighest`| `#DFE3E8` | `#313539` (`#23272B` Pitch) | Pressed states, code block headers, segmented track |
| `outline` | `#70787E` | `#8A9297` | Input borders, focused outlines |
| `outlineVariant` | `#C0C7CD` | `#40484D` | `0.85.dp–1.dp` card borders, floor dividers, table gridlines |
| `error` / `onError` | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` | Expose warnings, auth/network errors, downvote confirmation |
| `errorContainer` | `#FFDAD6` | `#93000A` | Error banners & destructive action chips |
| `scrim` | `#000000` (`52%` alpha) | `#000000` (`68%` alpha) | Modal bottom sheet & image lightbox backdrop |

### 2.2 Optional Material You Dynamic Color & Semantic Harmonization
- **Default Behavior**: `useDynamicColor = false` by default so Anode's Cool Mist Light & Slate Steel Dark aesthetic is unmistakable on first launch.
- **User Opt-In (`Build.VERSION.SDK_INT >= 31`)**: Toggling *"Material You 壁纸动态取色"* in Pulse Hub Settings switches `MaterialTheme.colorScheme` to `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` (with `background` & `surface` overridden to `#000000` if *Pure Pitch AMOLED* is also enabled).
- **Semantic Harmonization**: When Dynamic Color is active, blend `colorScheme.primary` via pure-Kotlin sRGB interpolation `blendColor(...)` (`Color.kt`): **`12%`** across all 15 board specs (`CategoryPalette`), **`10%`** across `Lv.0–Lv.6` rank specs (`RankPalette`), and **`14%`** across `NodePulseExtendedColors` (Drumstick Gold, Stardust, Heat in `Theme.kt`) so semantic colors feel native to the user's wallpaper without losing categorical meaning.

### 2.3 Semantic Category Badge Palettes (`CategoryPalette.kt`)

**Anode** assigns an unmistakable, WCAG AA+ compliant Light & Dark tonal pair and rounded Material icon to all 15 NodeSeek boards (including synthetic `""` **全部** using `Icons.Rounded.GridView` and primary container tokens):

| Slug | Board Name | Compose Icon (`Icons.Rounded.*`) | Light Container / Text | Dark Container / Text | Editorial Spine / Accent |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `""` | **全部** (All) | `GridView` | `#CDE7FB` / `#001E2E` | `#114B66` / `#CDE7FB` | `#2D637E` (Steel Teal-Blue) |
| `daily` | **日常** (Daily) | `LocalCafe` | `#F7E8DC` / `#7C3A12` | `#342015` / `#FDBA8C` | `#D97736` (Warm Terracotta) |
| `tech` | **技术** (Tech) | `Memory` | `#DCE6FF` / `#163B9C` | `#162754` / `#93B4FF` | `#3B66F5` (Sapphire) |
| `info` | **情报** (Deals/Info) | `Radar` | `#EAE0FF` / `#4E229E` | `#28194D` / `#CBB2FF` | `#7C3AED` (Radar Violet) |
| `review` | **测评** (Review) | `Speed` | `#D6F3F8` / `#095266` | `#0F313C` / `#7CE2F6` | `#0891B2` (Benchmark Cyan) |
| `trade` | **交易** (Trade) | `SwapHoriz` | `#D7F5E6` / `#0B5C3B` | `#103526` / `#6EE7B7` | `#10B981` (Emerald Market) |
| `carpool` | **拼车** (Carpool) | `Groups` | `#D5F2EE` / `#0D5652` | `#103332` / `#5EEAD4` | `#14B8A6` (Transit Teal) |
| `promotion`| **推广** (Promo) | `Campaign` | `#FFE0EC` / `#8F1D4E` | `#3D1528` / `#F9A8D4` | `#EC4899` (Signal Rose) |
| `life` | **生活** (Life) | `Spa` | `#E3F2D5` / `#335916` | `#1E3114` / `#BEF264` | `#65A30D` (Botanical Sage) |
| `dev` | **Dev** (Code) | `Terminal` | `#E0E5FF` / `#27308A` | `#1B204E` / `#A5B4FC` | `#6366F1` (Terminal Indigo) |
| `photo-share`| **贴图** (Photo) | `PhotoLibrary` | `#FFE4DC` / `#8A2C14` | `#3B1C15` / `#FDA4AF` | `#F43F5E` (Aperture Coral) |
| `expose` | **曝光** (Expose) | `ReportProblem` | `#FFDAD8` / `#930014` | `#441217` / `#FCA5A5` | `#EF4444` (Crimson Alert) |
| `inside` | **内版** (Inside) | `Lock` | `#F5E9C8` / `#6B4C08` | `#332710` / `#FCD34D` | `#D97706` (Gilded Amber) |
| `meaningless` | **无意义** (Casual) | `ChatBubbleOutline` | `#ECE6F8` / `#4E3D73` | `#272038` / `#D3C4F3` | `#8B5CF6` (Casual Violet) |
| `sandbox` / `*` | **沙盒 / 其他** | `ViewInAr` | `#E6E4EC` / `#454352` | `#24242E` / `#C4C2D0` | `#71717A` (Graphite) |

### 2.4 NodeSeek Rank Badge System (`Lv0`–`Lv6` Metallic & Gem Gradients, `RankPalette.kt`)

NodeSeek computes user rank from total coins (`coins >= 100 * Lv²` for `Lv` in `0..6`). Anode visualizes rank via a **`1.75.dp` Sweep/Linear Gradient Ring** around avatars and a compact tabular **`Lv.X` Micro-Badge**:

| Rank | Coin Threshold | Tier Persona | Gradient Start → End (Ring & Pill Border) | Pill Container (Light / Dark) | Pill Text (Light / Dark) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Lv.0** | `0 – 99` | 新脉 (Seed) | `#94A3B8` → `#64748B` | `#EEF1F5` / `#1E222B` | `#525B6B` / `#94A3B8` |
| **Lv.1** | `100 – 399` | 赤铜 (Bronze) | `#E09558` → `#A85C2A` | `#F9EBE0` / `#2E1E16` | `#844318` / `#F3B382` |
| **Lv.2** | `400 – 899` | 海蓝 (Cerulean) | `#38BDF8` → `#0284C7` | `#E1F0FA` / `#142738` | `#0B4F78` / `#7DD3FC` |
| **Lv.3** | `900 – 1,599` | 翡翠 (Jade) | `#34D399` → `#0D9488` | `#DCF7EC` / `#102F26` | `#065F46` / `#6EE7B7` |
| **Lv.4** | `1,600 – 2,499` | 靛曜 (Indigo) | `#60A5FA` → `#6366F1` | `#DFE7FF` / `#19234F` | `#1E3A8A` / `#A5B4FC` |
| **Lv.5** | `2,500 – 3,599` | 紫晶 (Amethyst) | `#A855F7` → `#EC4899` | `#F0E1FF` / `#2D1942` | `#5B21B6` / `#E9D5FF` |
| **Lv.6** | `3,600+` | 曜金 (Sovereign)| `#F59E0B` → `#EF4444` → `#FBBF24` (3-stop) | `#FFF0C7` / `#38260D` | `#854D0E` / `#FDE047` |

### 2.5 Reward & Telemetry Accent Tokens (`鸡腿` Drumstick, `星尘` Stardust & Heat)
- **Drumstick (`鸡腿`) Gold**:
  - Hero Gradient: `#F59E0B` → `#D97706` (Light) / `#FCD34D` → `#F59E0B` (Dark)
  - Subtle Surface Tint: `#FFF4DC` (Light) / `#2B200E` (Dark); Text/Icon (`onDrumstickContainer`): `#9A5200` (Light) / `#FBBF24` (Dark)
- **Stardust (`星尘`) Astral Violet**:
  - Accent (`stardustAccent`): `#6D28D9` (Light) / `#C4B5FD` (Dark)
  - Surface Tint (`stardustContainer`): `#EDE7FF` (Light) / `#241B42` (Dark); Text/Icon (`onStardustContainer`): `#5B21B6` (Light) / `#DDD6FE` (Dark)
- **Thread Heat (`热门/高赞`) Flame Accent** (triggered when `replies >= 30` or `views >= 1500`):
  - Pill Background (`heatContainer`): `#FFE8DF` (Light) / `#361B15` (Dark); Flame Icon & Count (`onHeatContainer`): `#9A3412` (Light) / `#FB923C` (Dark)

### 2.6 Typography System (`Type.kt`)

Anode pairs clean CJK editorial legibility (`Noto Sans CJK SC` / Android system variable sans) with **`fontFeatureSettings = "tnum"`** on all numerical metadata and `FontFamily.Monospace` for code, terminal logs, and UIDs.

| Token Role | Size / LineHeight | Weight | Letter Spacing | Usage & Read/Unread Rules |
| :--- | :--- | :--- | :--- | :--- |
| `displaySmall` | `32.sp` / `40.sp` | Bold (`W700`) | `-0.5.sp` | Pulse Hub coin/level hero numerals (`tnum`) |
| `headlineMedium` | `25.sp` / `33.sp` | Bold (`W700`) | `-0.3.sp` | Hero screen headers |
| `headlineSmall` | `22.sp` / `30.sp` | Bold (`W700`) | `-0.2.sp` | **Topic Reader Article Title** (selectable, editorial rhythm) |
| `titleLarge` | `19.sp` / `26.sp` | SemiBold (`W600`)| `0.sp` | Top App Bar expanded brand/board title, Modal sheet titles |
| `titleMedium` / `titleMediumUnread` | `16.5.sp` / `23.5.sp`| **SemiBold (`W600`)** | `0.sp` | **UNREAD Feed Card Title** (`onSurface` `100%` alpha) |
| `titleMediumRead`| `16.5.sp` / `23.5.sp`| **Normal (`W400`)** | `0.sp` | **READ Feed Card Title** (`onSurfaceVariant` `78%` Light / `66%` Dark alpha via `readTitleAlpha`) |
| `titleSmall` | `14.5.sp` / `20.sp`| SemiBold (`W600`)| `0.1.sp` | Bento board title, author username in Reader & Hub |
| `bodyLarge` | `16.sp` / `26.5.sp`| Normal (`W400`) | `0.2.sp` | **Topic Reader Markdown/HTML Body** (`1.65x` line-height; default `90%` / `0.90f`, scalable `85%–130%`) |
| `bodyMedium` | `14.sp` / `21.sp` | Normal (`W400`) | `0.15.sp` | Floor comment body (`15.sp` option), Bento board descriptions, notifications |
| `bodySmall` | `12.5.sp` / `18.sp` | Normal (`W400`) | `0.2.sp` | Secondary metadata captions and helper notes |
| `labelLarge` | `13.5.sp` / `18.sp`| SemiBold (`W600`)| `0.1.sp` | Sort switcher labels, CTA buttons, Quick-Reply bar prompt (`tnum`) |
| `labelMedium` | `12.sp` / `16.sp` | Medium (`W500`) | `0.2.sp` | Category micro-tags, Heat/Comment pills, relative time (`tnum`) |
| `labelSmall` | `10.5.sp` / `14.sp`| Bold (`W700`) | `0.3.sp` | `Lv.X` rank pills, `楼主` OP badge, `#12` floor badge, `⚡ RSS` indicator (`tnum`) |
| `codeBody` | `13.sp` / `19.5.sp`| Normal (`W400`) | `0.sp` | `FontFamily.Monospace` syntax-highlighted code & YABS ANSI blocks (`tnum`) |

### 2.7 Shape, Border & Expressive Motion Tokens (`Shape.kt` & `Motion.kt`)
- **Corner Radius Scale (`NodePulseShapes` + `PulseArchitecturalShapes`)**:
  - `extraSmall = 6.dp` (`Lv.X` badge, `楼主` OP chip, inline `<kbd>`/`<code>` spans)
  - `small = 10.dp` (Category micro-tags, floor `#` pills, blockquote corner; `SmallChip = 8.dp`, `MediumBadge = 10.dp`)
  - `medium = 16.dp` (**`PulseTopicCard`** / `EditorialCard`, standard `1x1` Bento tiles; `CodeBlock` & `InlineImage = 14.dp`, `SquircleFab` & `ElevatedTile = 18.dp`)
  - `large = 22.dp` (Hero `2x1` Bento cards, Pinned ticker card, Modal dialogs; `GlassBar = 24.dp`)
  - `extraLarge = 28.dp` (Modal Bottom Sheets top corners / `SheetContainer`)
  - `full = CircleShape` (**Floating Navigation Pill Dock**, Search Pill, Sort Switcher capsule, `鸡腿` status chip)
- **Zero-Muddy-Shadow Rule**: Cards use `0.dp` shadow elevation in scroll lists; depth is expressed via **tonal surface contrast** (`surfaceContainerLowest` `#FFFFFF` over `#F7FAFF` in Light; `surfaceContainer` `#1C2024` over `#101417` in Dark; `ColorSchemePitchCard` `#0C1013` over `#000000` in Pure Pitch AMOLED) framed by a **`0.85.dp–1.dp` `outlineVariant` border** (`68%` alpha Light, `78%` alpha Dark, `#272D32` in Pure Pitch AMOLED). Only the Floating Navigation Dock and FAB carry subtle `4.dp–6.dp` shadow elevation.
- **Material 3 Expressive Springs (`NodePulseMotion`)**:
  - Spatial / Layout Morph (`spatialDpSpring` / `spatialFloatSpring` / `spatialOffsetSpring`): `spring(dampingRatio = 0.72f, stiffness = 380f)`
  - Micro-Interaction Pop (`microBounceSpring`): `spring(dampingRatio = 0.55f, stiffness = 520f)`
  - Quick Response (`quickSpring`): `spring(dampingRatio = 0.78f, stiffness = 560f)`

---

## 3. Screen & Component Specifications for Jetpack Compose

### 3.1 Root Navigation Shell (`NodePulseRootShell` & `AdaptiveLayout`)

Replaces classic bottom tab bars with an **Edge-to-Edge Viewport + Floating Tonal Pill Dock + Contextual Expressive FAB** (switching automatically to a start-edge **`NavigationRail`** + dual-pane master-detail split when `width >= 600.dp`).

```text
┌──────────────────────────────────────────────────────────┐
│ [Status Bar — Transparent Edge-to-Edge]                  │
│                                                          │
│                  ACTIVE SCREEN CONTENT                   │
│       (Content scrolls cleanly behind bottom dock        │
│        with bottom ContentPadding = 96.dp + navInsets)   │
│                                                          │
│       ┌────────────────────────────────────┐  ┌────────┐ │
│       │  (🏠 首页)   ❖ 功能栏    👤 我的•   │  │ ✎ 发帖 │ │
│       └────────────────────────────────────┘  └────────┘ │
│                    [Gesture Pill Area]                   │
└──────────────────────────────────────────────────────────┘
```

- **Edge-to-Edge Setup**: `enableEdgeToEdge()` in `MainActivity` with transparent status and navigation bars. Bottom area applies a subtle vertical scrim fade so list content scrolling underneath the floating dock remains clean and legible.
- **Floating Tonal Pill Dock (`FloatingPillDock` in `NavigationShellComponents.kt`)**:
  - **3 Focused Top-Level Destinations (`PulseNavTab`)**:
    1. **`首页` (`EXPLORE`)** — `Icons.Rounded.Home` / `Icons.Outlined.Home`: Main topic stream, sort switcher, full board strip. **Re-tapping** while active smoothly scrolls the active feed list to index `0` and triggers pull-to-refresh.
    2. **`功能栏` (`FEATURE_HUB`)** — `Icons.Rounded.GridView` / `Icons.Outlined.GridView`: Categorized toolbox hub (`FeatureHubScreen`) linking Seek Mail, Community Activity Hub (`ForumWatch`), Overseas Intel, Restock Monitor, My Servers, Mobile NetBench, and Lite SSH Hero card with live telemetry badges.
    3. **`我的` (`PULSE_HUB`)** — `Icons.Rounded.Person` / `Icons.Outlined.Person` (showing unread count badge when `unreadNotifications > 0`, or indicator dot when daily check-in is pending): Personal profile Hero card, 1-line check-in bar, grouped Messages & Content links, and Settings.
  - **Selection Indicator**: Sliding `primaryContainer` / `secondaryContainer` gradient stadium pill (`pulseNavActiveIndicatorBrush`) animating smoothly between tabs.
- **Contextual Expressive FAB (`CollapsingSquircleFab`)**:
  - **On `首页` (`EXPLORE`)**: Morphing `56.dp` squircle in `primary` next to the Floating Pill Dock showing `Icons.Rounded.Edit` + `"发帖"`, collapsing smoothly when scrolling down. Tapping opens `ComposerRoute` (or `WebViewSessionSheet` if unauthenticated).
  - **On `功能栏` & `我的` & Child Screens**: Smoothly scales out (`scaleOut + fadeOut`) or hides the bottom dock on secondary detail destinations so terminal/reader viewports remain unobstructed.
- **Large-Screen / Foldable Adaptation (`PulseAdaptiveLayoutInfo`, `width >= 600.dp`)**: Dock transforms into a start-edge **`PulseNavigationRail`** with the Compose squircle FAB at the top of the rail, and `AdaptiveLayout` enables **dual-pane master-detail** (`listPaneWeight = 0.42f` / `detailPaneWeight = 0.58f` for `Medium`, `0.38f` / `0.62f` for `Expanded >= 840.dp`).

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

### 3.3 Screen 2: Unified Search & Boards Directory (`BoardsScreen` · `SearchRoute` / `BoardsRoute`)

> **Navigation Note**: `BoardsRoute` was consolidated off the primary 3-tab bottom dock in `v2.3+`; `ExploreScreen` exposes the full category strip inline, while tapping the top-right Search icon opens **`SearchRoute` (`BoardsScreen`)** for unified topic/user search, category filtering, and external Google site-search fallback.

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
   - Author Hero Strip (`surfaceContainerLow`, `14.dp` radius, `12.dp` padding): `42.dp` Lv-ringed avatar, username, **`楼主 OP` Chip** (solid `primary` `#2D637E` / `#9ACAEC` fill with `onPrimary` bold `labelSmall`), `Lv.X` pill, and UID caption.
3. **Rich Native Compose HTML / Markdown Renderer (`PulseRichContent`)**:
   - Parses both full Web post Markdown/HTML and RSS `<description>`/`<content:encoded>` payloads cleanly into typed Compose blocks (`Paragraph`, `Heading`, `CodeBlock`, `Blockquote`, `Table`, `ImageGallery`, `ListBlock`, `Spoiler`, `PollCard`, `RuleDivider`):
   - **Syntax-Tinted Code & ANSI Terminal Blocks (`PulseCodeBlock`)**:
     - Dedicated dark code surface (`#12151E` / header `#1A1E2B` / border `#2A3042` in Light; `#0E1118` / header `#161B26` / border `#262D40` in Dark for unmistakable terminal contrast, `14.dp` radius, `1.dp` border).
     - Header strip (`32.dp` height, `codeHeaderSurface`): language tag (`bash`, `json`, `yaml`, `python`, `text`) on left + **1-Tap Copy Chip** (`📋 复制` → morphs into emerald `✓ 已复制` for `1.8s` with clipboard copy) on right.
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

### 3.5 Screen 4: Personal Hub (`PulseHubScreen`) & Grouped Settings (`SettingsScreen`)

Reorganized in `v2.x` from the initial Bento concept into an elevated **Hero Rank Card + 1-Line Compact Check-In Bar (`CompactCheckInCard`) + Clean Settings-Style Grouped Sections (`消息` & `内容`)**, while migrating toolbox utilities cleanly into **`FeatureHubScreen` (`功能栏`)**:

1. **Hero User Rank & Stats Card (`UserRankHeroCard`)**: `64.dp` Avatar wrapped in `Lv0–Lv6` conic gradient ring + ambient hero wash + `Lv.X` pill + UID + coin rank bar + 4-column telemetry (`鸡腿` / `星尘` / `主题` / `评论`); guest state offers WebView or Native Sign-in triggers.
2. **Compact Check-In Bar (`CompactCheckInCard`)**: Single-line row displaying check-in rank/gain and post/reply/like quota pills, allowing in-place `固定 +5` / `试试手气` toggle and check-in execution.
3. **Grouped Lists (`消息` & `内容`)**:
   - **消息**: `通知`, `私信`, `关键词提醒` (Pulse Radar alerts) with live unread pills.
   - **内容**: `我的收藏`, `浏览记录`, `我的帖子` linking to `HubCollectionScreen` (which reuses outlined `PulseTopicCard` with offline snapshots and `读至 #N 楼` badges) and `UserProfileScreen`.
4. **Grouped-Card Settings (`SettingsScreen`)**: Theme mode (`Cool Mist Light` / `Slate Steel Dark` / `Pure Pitch AMOLED`), Material You opt-in, global font scale (`90%` default), check-in strategy, **`ContentFilterSection`** (muted categories & keyword/regex block rules), **`BackupRestoreSection`** (versioned JSON export/preview/merge/replace), Pulse Radar polling/quiet hours, NodeImage API Key modal, cache cleanup, and GitHub Releases update controls.
5. **In-App WebView Session Login Sheet (`WebViewSessionSheet`)**: Official `https://www.nodeseek.com/signIn.html` WebView matching theme palette, strict anti-tamper compliance, and automatic `CookieManager` -> OkHttp cookie bridging upon sign-in verification.

---

### 3.6 Feature Hub (`功能栏`) & v2.4.0 Toolbox UX Architecture

1. **Feature Hub (`FeatureHubScreen`)**: Categorized architectural tile grid with live `FeatureHubTelemetry` badges:
   - **社区服务**: `邮箱` (`SeekMailScreen`, `已配置 / 待配置`) & `活动` (`ActivityScreen`, ForumWatch signed/encrypted community lottery & giveaway feed with live ongoing count badge and opt-in push notifications).
   - **情报与监控**: `海外情报` (`OverseasIntelScreen`) & `补货监控` (`RestockMonitorScreen`, active watch & unread badges).
   - **主机与网络**: `我的服务器` (`MyServersScreen`, server count) & `手机端测机` (`MobileNetBenchScreen`).
   - **Hero Card**: `轻量 SSH` (`LiteSshScreen`, saved vault count).
2. **Mobile NetBench (`MobileNetBenchScreen` & `NetBenchVisualComponents`)**:
   - **Test Toggles**: Places **Ping 延迟与抖动** (ICMP Ping) in the first toggle row directly above **TCPing 延迟与抖动**, displaying an inline warning callout when VPN is active without proxy mode (`Network.bindSocket` cannot bind external `/system/bin/ping` processes).
   - **Dual Egress Telemetry & Controls**: Displays both real cellular/Wi-Fi physical exit IP and proxy/VPN exit IP (`testViaProxy` toggle + lockdown fallback banner), `-P` parallel stream selector (`1 / 2 / 4 / 8`), zero-allocation `Canvas` latency waveforms & throughput charts, and `NetBenchIperf3ServerPromptDialog` coordinating 1-click VPS `iperf3` setup via SSH.
3. **Lite SSH (`LiteSshScreen` · `SshTerminalAutoFit` · `SshResultPublishSheet`)**:
   - **Virtualized Adaptive Terminal**: Uses `LazyColumn` (`SshTerminalConsoleBuffer`) for 60fps scrolling, **`AUTO_FIT`** dynamic font solver (`6.0sp–11.5sp`) to prevent wide NodeQuality/YABS ASCII tables from wrapping, **`FIXED`** mode with two-finger pinch zoom (`0.50x–1.80x`) & reset controls, and non-looping live PTY `window-change` dimension updates.
   - **Publishing Workflow (`SshResultPublishSheet`)**: Supports off-main-thread Markdown formatting, multi-run merge (`SshMergeLayout`), official 4-tab `NodeQuality` `:::: tabs` or plain-text copy, and contextual **`发测评`** button label when invoked from a topic reply bar.
4. **Overseas Intel (`OverseasIntelScreen` & `OverseasIntelDetailScreen`)**: Clear typographic deal card hierarchy with configurable quality filtering (`促销 / 讨论` category filtering and score thresholds); detail screen directly presents server-parsed Chinese titles, spec sections, promo codes, pros/cons, AI commentary, and 1-click NodeSeek repost.
5. **Seller Trust (`SellerTrustCardSheet` & `SellerTrustState`)**: Surfaces cached `/categories/expose` snapshot status immediately with zero default `/search` calls, explicit **on-demand deep `/search` verification** with live cooldown countdown pill, Google `site:www.nodeseek.com` site-search fallback, and local app-sandbox JSON notes (`filesDir/seller_trust_notes.json`).

---

## 4. Compose Design Token Architecture (`com.nodeseek.app.ui.theme`)

The `com.nodeseek.app.ui.theme` package separates tokens into eight decoupled files so every screen consumes semantic tokens rather than hardcoded hexes:

1. `Color.kt`:
   - `PaperLightColorScheme` (`lightColorScheme(...)` using exact §2.1 hexes)
   - `ObsidianDarkColorScheme` & `ColorScheme.withPurePitchAmoled()` (`darkColorScheme(...)` with `#000000` canvas and `#0A0D10` / `#111518` / `#191D21` / `#23272B` pitch containers)
   - `@Immutable data class NodePulseExtendedColors(...)` (`LightExtendedColors` / `DarkExtendedColors`) + `blendColor(...)`: `cardSurface`, `cardSurfaceElevated`, `cardBorder`, `drumstickGoldStart/End`, `drumstickContainer`, `onDrumstickContainer`, `stardustAccent`, `stardustContainer`, `onStardustContainer`, `heatContainer`, `onHeatContainer`, `codeSurface`, `codeHeaderSurface`, `codeBorder`, `readTitleAlpha`, `glassSurface`, `shimmerBase/Highlight` (§2.5)
2. `CategoryPalette.kt`:
   - `CategoryVisualSpec` & `CategoryPalette` covering all 15 board specs (`""` + 14 boards) with `12%` dynamic-color harmonization (§2.3)
3. `RankPalette.kt`:
   - `RankVisualSpec`, `RankProgressInfo` & `RankPalette` (`levelFromCoins`, `progressFromCoins`, `forLevel`) with `Lv.0–Lv.6` metallic/gemstone gradients and `10%` dynamic-color harmonization (§2.4)
4. `Type.kt`:
   - `buildNodePulseTypography(fontScale)` & `buildNodePulseExtraTypography(fontScale)` (`NodePulseExtraTypography`: `titleMediumUnread`, `titleMediumRead`, `codeBody`, `codeLabel`, `tabularBadge`, `sectionHeader`, `heroHeadline`, `metricValue`, `microLabel`) + `TextStyle.withTabularNumbers()`
5. `Shape.kt`:
   - `NodePulseShapes` (`extraSmall = 6.dp`, `small = 10.dp`, `medium = 16.dp`, `large = 22.dp`, `extraLarge = 28.dp`) + `PulseArchitecturalShapes` (`Pill`, `SquircleFab`, `EditorialCard`, `HeroBento`, `CodeBlock`, `InlineImage`, `BottomSheetTop`, `SmallChip`, `MediumBadge`, `ElevatedTile`, `GlassBar`, `SheetContainer`)
6. `Motion.kt`:
   - `NodePulseMotion` (`spatialDpSpring`, `spatialFloatSpring`, `spatialOffsetSpring`, `microBounceSpring`, `quickSpring`, `subtleTween`, `entranceFadeTween`, `cardPressScale`, `chipPressScale`)
7. `Theme.kt`:
   - `PulseThemeMode` (`SYSTEM`, `PAPER_LIGHT`, `OBSIDIAN_DARK`), `NodePulseTheme(themeMode, purePitchAmoled, dynamicColor, fontScale, content)` & `NodePulseThemeTokens` (`LocalNodePulseExtendedColors`, `LocalNodePulseExtraTypography`, `LocalFontScale`, `LocalNodePulseDarkTheme`, `LocalNodePulseDynamicColor`), clamping `fontScale` to `0.85f..1.30f` and updating edge-to-edge system bar icon appearance (`isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`)
8. `PulseDesign.kt`:
   - Shared architectural primitives (`PulseElevation`, `pulseCard`, `pulsePressScale`, `pulseShimmer`, `PulseStaggeredEntrance`, etc.)

---

## 5. Accessibility, Contrast & Quality Checklist

- **Contrast Compliance**: Every text/surface pair in both Cool Mist Light (`#171C1F` on `#F7FAFF`) and Slate Steel Dark (`#DFE3E7` on `#101417`), as well as all semantic Category Badges (`>= 5.2:1`), meets or exceeds **WCAG 2.1 AA** (and AAA for body text).
- **Touch Targets & Single-Line Labels**: All interactive chips, bookmark toggles, floor pills, and reaction buttons enforce `minimumInteractiveComponentSize()` (`48.dp × 48.dp` hit bounds even when visual pill height is `28.dp–34.dp`), while button/chip labels consistently enforce single-line ellipsized layout (`maxLines = 1`, `softWrap = false`, `TextOverflow.Ellipsis`).
- **TalkBack & Semantics**: `PulseTopicCard` merges header/title/telemetry via `Modifier.semantics(mergeDescendants = true)` with explicit `stateDescription = "未读"` / `"已读"`, while keeping the Bookmark quick-toggle as an independent accessible action.
- **Reduced Motion**: Respect system animator duration scale; spring bounces gracefully degrade to instant crossfades when animations are disabled.
