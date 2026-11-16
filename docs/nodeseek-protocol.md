# NodeSeek Web & API Protocol Reference

> **Target Project**: Anode (`com.nodeseek.app`) — Native Android Client (Kotlin, Jetpack Compose, OkHttp, Jsoup, Kotlinx Serialization, Room, DataStore, Coil, AndroidX WebKit).

---

## 1. Executive Architecture Overview

NodeSeek (`https://www.nodeseek.com`) does **not** expose a standalone public OAuth/REST mobile API. Instead, the client interacts with four web data mechanisms:

| Data Mechanism | Where NodeSeek Uses It | Android (`com.nodeseek.app`) Implementation |
| :--- | :--- | :--- |
| **1. Server-Rendered HTML** | Feed rows (`.post-list-item`), search results (`/search`), curated posts (`/award`), pagination (`[aria-label="pagination"]`), directory pages (`/providers`, `/friends`), static articles, and post access-denied notices (`#nsk-body-left`). | Fetch HTML via `OkHttpClient` (sharing WebView cookies + `User-Agent`) and parse with **Jsoup** using the CSS selectors documented below. |
| **2. Embedded `temp-script` Base64 JSON (`window.__config__`)** | Standard forum HTML responses embed `<script id="temp-script" type="application/json">BASE64_UTF8_JSON</script>`. Contains signed-in user state (`user`), full board metadata (`allCategory`), page-1 carousel (`rotateTopics`), and **complete thread detail + raw Markdown comments** (`postData`). | Extract `script#temp-script[type="application/json"]` via Jsoup, `Base64.decode`, and deserialize with **Kotlinx Serialization** (handling `0`/`1` SQL integers alongside booleans). |
| **3. Same-Origin `/api/*` JSON Endpoints** | Account profile, sign-in/out, notifications, DMs, check-in (鸡腿), reactions, collections, publishing/editing, pins, user topics/comments, follows, blocks, ledgers, stardust, polls, lucky draw floors, invitations, moderation rulings, settings, avatar upload. | Execute via **OkHttp** (or interactive same-origin WebView transport when needed) with shared `WebViewCookieBridge`, matching `User-Agent`, and request-signing interceptor for CSRF & `x-dynamic-sign` headers. |
| **4. Public RSS Feed (`https://rss.nodeseek.com`)** | Public RSS 2.0 XML stream of latest posts (no cookies required). | Lightweight guest/offline fallback parser (RSS 2.0 `<item>`), while primary browsing uses HTML + `temp-script` for sorting, pagination, view/reply counts, avatars, and pinned posts. |

### URL & Origin Normalization Rules
- **Canonical Origin**: `https://www.nodeseek.com` (normalize bare `nodeseek.com` and `http://` links to `https://www.nodeseek.com`).
- **Query `+` Encoding Gotcha**: NodeSeek's server treats literal `+` in URL query strings as a space (`' '`). Always percent-encode `+` as `%2B` in query parameters (especially `/search?q=...`).
- **Internal Redirect Unwrapping**: External links in posts are often wrapped as `https://www.nodeseek.com/jump?to=<percent-encoded-url>`. Unwrap the `to` query parameter if its scheme is `http` or `https`.

---

## 2. Client Capabilities & Data Flows

| Area | Behavior & Protocol Details | Android Implementation Notes |
| :--- | :--- | :--- |
| **Feed & Tabs** | Sort modes: **新评论** (`sortBy=replyTime`, default), **新帖子** (`sortBy=postTime`), plus curated **推荐阅读** (`/award`) and **热门** (`hot`). Merges `__config__.rotateTopics` (page 1) + `[title="置顶"]` items into a deduplicated top pinned section and excludes them from normal feed rows. Optional toggle to show/hide blocked posts (`.blocked-post` / `topic.blocked`). | Support `replyTime`, `postTime`, `/award` (Curated/Recommended), and client-assisted hot sorting. |
| **Categories / Boards** | 14 built-in categories + `"all"` (`""`), dynamically refreshed every 12h (`43,200s`) via `GET /api/content/list-categories`. Authenticated users can customize homepage board visibility via `GET/POST /api/homepage`. `adminOnly` categories are hidden from the new-topic picker. | Cache categories in Room with 12h TTL; seed with the 15 static defaults for instant cold start. |
| **Topic Detail & Floor Comments** | Reads `__config__.postData` from `GET /post-{id}-{page}`. Floor `0` is the OP post body; floors `1..10` appear on page 1, then 10 floors per subsequent page (`page = floor <= 10 ? 1 : (floor - 1) / 10 + 1`). Replies are deduplicated by `commentId` and ordered: **pinned (`pined == true`) -> hot (`hot == true`) -> chronological floor order**. Detects access errors (`private`, `levelRequired(Lv)`, `signInRequired`). Supports jumping to any page/floor and loading earlier pages. | Render native Compose Markdown blocks + floor list; preserve `pined` -> `hot` -> `floorIndex` ordering on page 1 and floor order on deeper pages. |
| **Rich Markdown, Code & Stickers** | Parses raw `markdown` from `postData.comments`: headings, blockquotes (with floor-quote jump), lists, GFM tables, fenced code blocks with **ANSI SGR terminal color escape rendering** (for VPS benchmark scripts like YABS/NodeQuality), `:::: tabs` / `::: tab-item Title` tab containers, images (`![alt](<url>)` and `![alt](url)`), inline `@username` & `#floor` links, embedded polls (`nsapp://vote?id=...`), stardust payment cards (`nsapp://stardust-receive?...`), and **native sticker packs** (`ac` 149 PNGs, `yct` 22 GIFs, `xhj` 32 PNG/GIFs, `emoji` 49 `.webm` animated stickers). | Support GFM + custom `:::: tabs` container + ANSI code block spans + inline `:stickerCode:` rendering via Coil (`coil-gif`). |
| **Search** | Native topic search (`GET /search?q=...&page=...&sortBy=...&category=...`), category-scoped search, user search (`GET /api/account/find/{keyword}`), and 1-tap external Google site search (`https://www.google.com/search?q=site:www.nodeseek.com+{query}`). | Combine topic search, category filter, user lookup, and Google site search shortcut (helpful when native search is restricted or rate-limited). |
| **Authentication & 2FA** | Username/password form + Turnstile token -> `POST /api/account/signIn` with `x-captcha-token` & `x-captcha-source: turnstile`. Supports 2-step TOTP (`need2FA` + `otpSession` -> second `POST /api/account/signIn` with `{otp_code, otp_session}`). Full in-app WebView (`/signIn.html`) for registration, password reset, Telegram login, or challenge verification. Verified sign-out via `POST /api/account/signOut` + home re-read. | Primary interactive **in-app WebView login** (`/signIn.html`, handling Turnstile/2FA/Telegram natively) plus native credential + 2FA dialog support. |
| **Cloudflare & Session Bridging** | Zero cookie-name hardcoding or challenge bypassing. Shares `CookieManager` cookies across OkHttp, Coil, and interactive `WebViewSessionSheet`. Detects `cf-mitigated: challenge`, HTTP `403` without `#nsk-body`, or missing `script#temp-script` -> prompts user to open interactive WebView to verify manually. | Bridge `android.webkit.CookieManager.getInstance()` <-> OkHttp `CookieJar` and synchronize `WebSettings.getDefaultUserAgent(context)`. Never attempt automated bypass; present interactive `WebView` sheet when challenged. |
| **Daily Check-In (鸡腿)** | Reads status & leaderboard (`GET /api/attendance/board?page={p}`, 50/page; `record == null` means not checked in today; `record.gain` can be `0`). Check-in via `POST /api/attendance?random=false` (fixed **5 drumsticks**) or `?random=true` (random reward). Also displays daily bonus progress (`GET /api/progress/today`). | Support both **Fixed (+5 鸡腿)** and **Random (手气签到)** options, leaderboard pagination, and daily task progress card. |
| **Comment Reactions & Free Quota** | Per-comment **点赞** (`upvote`, free, awards stardust), **加鸡腿** (`like`, uses daily free quota first via `GET /api/progress/today?scope=freelike`, then costs 1 coin), and **反对** (`dislike`, costs 2 coins). Pre-checks free quota before confirming paid reactions; irreversible once submitted. | Query `freelike` quota before `like`, confirm coin spend when paid, update counts from `{ current, coin }` response. |
| **Bookmarks / Collections** | Server-synced collections: add/remove via `POST /api/statistics/collection` (`action: "add" | "remove"`), paginated list via `GET /api/statistics/list-collection?page={p}` (15/page). | Sync server collections + mirror bookmarked topic snapshots locally in Room for instant filtering and offline reading. |
| **Publishing, Editing & Pinning** | Native Markdown composer with live preview, sticker picker, VPS valuation markdown export, NodeImage photo upload, and local Room drafts. Supports `new-discussion`, `edit-discussion`, `new-comment`, `edit-comment`, and OP `pin-comment` (`add` costs 5 coins, `remove` free). Warns before paid actions (`inside` board = 5 coins; changing existing post to private `rank=255` = 10 coins). | Implement full Markdown composer with Room draft auto-save, NodeImage upload (`api.nodeimage.com`), and coin-cost confirmation dialogs. |
| **Notifications & Direct Messages** | Badge count (`GET /api/notification/unread-count` -> `unreadCount.all`), **回复我** (`reply-to-me/list`), **@我** (`at-me/list`), mark single or `?all=true` viewed, DM thread list (`message/list` grouped client-side by peer), 1-on-1 DM chat (`message/with/{uid}`), send DM (`message/send`), 10s foreground polling while chat is open, per-account/per-peer DM drafts. | Implement 3-tab Inbox (Replies, Mentions, Direct Messages) + 1-on-1 chat screen with foreground refresh and local draft persistence. |
| **User Profiles, Social & Level** | Public & self profiles (`GET /api/account/getInfo/{uid}?readme=1[&signature=1]`), edit `bio`/`signature`/`readme` (`POST /api/account/introduction`), user's topics (`list-discussions`) & replies (`list-comments`, HTML converted to plain text), follow/unfollow (`fans/add|del`), following/followers lists (`fans/follow`, `fans/fans`), block list (`block-list/list|add|del`). Display level `Lv0–Lv6` computed from `coin >= 100 * Lv²`. | Compute `Lv0–Lv6` from `coins` (never confuse with permission `rank`), display Markdown `readme` & `signature`, user posts/comments, follow & block controls. |
| **Community Tools & Ledgers** | Coin ledger (`credit/page-{p}`), Stardust ledger & P2P transfer (`stardust/list`, `payment-prepare`, `send`), Polls (`vote/*`), Drand provably-fair Lucky Draw (`floor-data` + `api.drand.sh`), Invitations (`invite/config|list|buyOne`), Moderation Rulings (`admin/ruling/*`), Account Security, Partner directory (`/providers`, `/friends`). | Core ledgers/tools in Profile & Reader; secondary web flows open in interactive WebView. |
| **Read History (Local Room)** | Dedicated **Room `read_history` table** recording visited `topicId`, `title`, `author`, `lastReadFloor`, `lastReadPage`, and `visitedAt` to dim read titles in feeds, resume reading floor, and browse local history. | Local-only Room persistence synchronized smoothly across Explore and Pulse Hub. |

---

## 3. NodeSeek Categories / Boards & Slugs Reference

### 3.1 Static Default Categories

| Order | Key / Slug (`categoryKey`) | Chinese Title (`cn_text`) | Route Path | Posting & Access Notes |
| :---: | :--- | :--- | :--- | :--- |
| 0 | `""` *(empty string)* | **全部** | `/` (`/?sortBy=...`) | Aggregated feed across user's visible boards (`all` synthetic client tab; not returned by `/api/content/list-categories`). |
| 1 | `daily` | **日常** | `/categories/daily` | General daily chatter |
| 2 | `tech` | **技术** | `/categories/tech` | Servers, Linux, networking, self-hosting |
| 3 | `info` | **情报** | `/categories/info` | Deals, restocks, industry news |
| 4 | `review` | **测评** | `/categories/review` | VPS/dedi benchmarks (often uses `:::: tabs` + ANSI code blocks) |
| 5 | `trade` | **交易** | `/categories/trade` | Secondary market / server transfers |
| 6 | `carpool` | **拼车** | `/categories/carpool` | Group buys / shared subscriptions |
| 7 | `promotion` | **推广** | `/categories/promotion` | Merchant promotions & offers |
| 8 | `life` | **生活** | `/categories/life` | Off-topic lifestyle |
| 9 | `dev` | **Dev** | `/categories/dev` | Software engineering & open-source |
| 10 | `photo-share` | **贴图** | `/categories/photo-share` | Image & photography sharing |
| 11 | `expose` | **曝光** | `/categories/expose` | Dispute reports & provider warnings |
| 12 | `inside` | **内版** | `/categories/inside` | Members-only board; **creating a new topic costs 5 drumsticks (鸡腿)** |
| 13 | `meaningless` | **无意义** | `/categories/meaningless` | Casual chatter / low-signal posts |
| 14 | `sandbox` | **沙盒** | `/categories/sandbox` | Formatting & bot test sandbox |

### 3.2 Dynamic Category Synchronization & Special Feeds
- **Dynamic API**: `GET /api/content/list-categories` (cached for 12 hours / `43,200s` per `userID`).
  - Response:
    ```json
    {
      "success": true,
      "data": [
        { "key": "daily", "cn_text": "日常", "adminOnly": 0 }
      ]
    }
    ```
  - Validation regex for `key`: `^[a-zA-Z0-9_-]+$`.
  - Prepend synthetic `"全部"` (`key = ""`) to `data`. Exclude categories where `adminOnly == true` (or `1`) from the New Topic composer picker.
- **Embedded HTML Category Metadata (`window.__config__.allCategory`)**:
  - Array of `{ "key": String, "cn_text": String, "customizable": Boolean, "showInIndex": Boolean, "adminOnly": Boolean|Int }`.
- **Curated / Recommended Feed (推荐阅读)**:
  - Page 1: `GET /award`
  - Page $N \ge 2$: `GET /award/page-{N}`
  - Parsed with the exact same feed HTML extractor.

---

## 4. Transport, Session, Cookie Bridging & Request Signing

### 4.1 Cookies, `User-Agent`, and Cloudflare Challenge Handling
1. **Cookie Lifecycle**:
   - The client never hardcodes cookie names (such as `cf_clearance` or session cookie identifiers). Every cookie set by `https://www.nodeseek.com` lives in Android's `CookieManager` store and is shared with OkHttp via `WebViewCookieBridge`.
   - **Critical Android Rule**: Cloudflare binds clearance cookies (`cf_clearance`) to the **exact `User-Agent` string** and TLS/IP context. On Android:
     - Read `val webViewUserAgent = WebSettings.getDefaultUserAgent(context)` once at startup and attach it to **both** every `WebView` (`settings.userAgentString = webViewUserAgent`) and every `OkHttpClient` request targeting `nodeseek.com`.
     - Implement an OkHttp `CookieJar` backed directly by `android.webkit.CookieManager.getInstance()`:
       - `loadForRequest(url)`: parse `CookieManager.getInstance().getCookie(url.toString())` (`;` separated) into `Cookie` objects.
       - `saveFromResponse(url, cookies)`: write each `Set-Cookie` header string back via `CookieManager.getInstance().setCookie(url.toString(), cookie.toString())` and call `flush()`.
2. **Detecting Cloudflare Challenges (Strict Non-Bypass Policy)**:
   - A request requires interactive user verification (`CloudflareChallengeException` -> prompt user to open in-app `WebViewSessionSheet` to `https://www.nodeseek.com`) when **any** of the following occur:
     1. Response header `cf-mitigated` equals `"challenge"` (case-insensitive).
     2. HTTP status is `403` **and** (for HTML endpoints) `#nsk-body` is absent from the DOM, or (for `/api/*` JSON endpoints) JSON deserialization fails because an HTML challenge page was returned.
     3. On HTML forum pages (other than valid `#nsk-body` access-denied pages), `<script id="temp-script" type="application/json">` is missing or empty.
   - **Never attempt to solve, click, script, or bypass Turnstile/Cloudflare challenges.** Open a visible, first-party `WebView` to `https://www.nodeseek.com`, let the user complete verification or login normally, wait for `window.__config__` to exist on the loaded page, `CookieManager.flush()`, and resume OkHttp requests.

### 4.2 Special Request Headers & Cryptographic Signing

| Header Name | Value Format | Endpoints Requiring It |
| :--- | :--- | :--- |
| `User-Agent` | Exact system `WebSettings.getDefaultUserAgent(context)` | All requests to `https://www.nodeseek.com` |
| `Referer` | `https://www.nodeseek.com/` | All forum/post image loads (CDNs reject hotlinks without NodeSeek `Referer`). **Never send NodeSeek `Cookie` or `Authorization` headers to external image hosts.** |
| `csrf-token` | 16-character random lowercase hex string (e.g. `UUID.randomUUID().toString().replace("-", "").take(16)`) | `POST /api/content/new-discussion`, `POST /api/content/edit-discussion`, `POST /api/content/new-comment`, `POST /api/content/edit-comment` |
| `x-csrf-challenge` | Literal string `"simple-token"` | `POST /api/account/signOut`, `POST /api/avatar/upload`, `POST /api/invite/buyOne` |
| `x-csrf-token` | Token persisted from any prior `/api/*` response header `x-csrf-token` (if value matches `^\w+\.\w+$`, NodeSeek treats it as `security_token` rather than `csrf_token`; user must visit `/invite` on web first if missing) | `POST /api/invite/buyOne` (sent alongside `x-csrf-challenge: simple-token`) |
| `x-dynamic-sign` | **Lowercase 40-char hex SHA-1** of `listOf(method.uppercase(), fullUrl, userAgent, exactRawBodyOrEmpty).joinToString("\n\n")` | **All** `/api/vote/*` (`GET` and `POST`) and **all** `/api/stardust/*` (`GET` and `POST`) endpoints |
| `x-captcha-token` | Turnstile verification token string | `POST /api/account/signIn` (Step 1: username/password login only; **omitted** on Step 2 2FA OTP submission) |
| `x-captcha-source` | Literal string `"turnstile"` | `POST /api/account/signIn` (Step 1 only, paired with `x-captcha-token`) |
| `X-API-Key` | 32–128 hex char user API key (`^[a-fA-F0-9]{32,128}$`) | `https://api.nodeimage.com/*` (`Accept: application/json`, **cookies disabled**) |

#### `x-dynamic-sign` Kotlin Reference
```kotlin
fun computeDynamicSign(
    method: String,      // e.g. "GET" or "POST"
    fullUrl: String,     // e.g. "https://www.nodeseek.com/api/stardust/list?member_id=101&count=10"
    userAgent: String,   // exact User-Agent header sent on this request
    rawBody: String = "" // exact UTF-8 JSON body string for POST, or "" for GET
): String {
    val payload = listOf(method.uppercase(), fullUrl, userAgent, rawBody).joinToString("\n\n")
    val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
```

### 4.3 `WebsiteFlag` / Flexible JSON Type Quirks
NodeSeek mixes SQL `0`/`1` integers, booleans, and stringified numbers across endpoints:
- **Boolean Flags (`WebsiteFlag`)**: Fields like `locked`, `collected`, `award`, `pined`, `hot`, `adminOnly`, `isPublic`, `multiple`, `voted`, `need2FA` may arrive as `true`/`false`, `1`/`0`, or `null`/omitted. Treat `1` as `true`, `0` as `false`, `null`/missing as `null` (unknown — do **not** coerce missing `collected` or `voted` to `false` when unknown matters), and reject other numbers.
- **Counts (`views`)**: `postData.views` in thread JSON can be an `Int` (`240`) or a `String` (`"240"` or `"1,240"`). Strip commas and parse to `Int`.

---

## 5. Embedded `temp-script` (`window.__config__`) Specification

Every valid NodeSeek HTML document contains:
```html
<script id="temp-script" type="application/json">eyJ1c2VyIjpudWxsLCJhbGxDYXRlZ29yeSI6Wy4uLl19</script>
```
Decode steps:
1. Select `document.selectFirst("script#temp-script[type=application/json]")?.data()?.trim()`.
2. Base64-decode (`Base64.DEFAULT`) into UTF-8 bytes and parse as JSON (`ConfigPayloadDto`).
3. Verify `allCategory` is a JSON array when parsing standard forum pages.

### 5.1 `__config__.user` (Signed-In Session State)
Present on **every** HTML page (`null` when signed out):
```json
{
  "enableCustomedStyle": true,
  "user": {
    "member_id": 101,
    "member_name": "SampleAuthor",
    "coin": 1650,
    "stardust": 42,
    "nPost": 18,
    "nComment": 320,
    "rank": 2,
    "roles": ["dev"],
    "collectionCount": 14,
    "telegram_id": "123456789",
    "unViewedCount": {
      "all": 3
    }
  }
}
```
- **Custom CSS Permission**: `enableCustomedStyle == true && (user.rank >= 2 || "dev" in user.roles)`.
- **Telegram Bound Check** (verified on `GET /setting`): `user.telegram_id != null && user.telegram_id.toString().isNotEmpty()`.
- **Display Level (`Lv0`–`Lv6`) Formula**:
  - **IMPORTANT**: `user.rank` is an internal forum permission/moderation rank, **NOT** the user's `Lv` badge!
  - Display level is computed strictly from `coin` (鸡腿):
    $$\text{level} = \max \{ L \in \{0, 1, 2, 3, 4, 5, 6\} \mid \text{coins} \ge 100 \times L^2 \}$$
  - Threshold table:
    - `Lv 0`: `0 – 99` coins (next at `100`)
    - `Lv 1`: `100 – 399` coins (next at `400`)
    - `Lv 2`: `400 – 899` coins (next at `900`)
    - `Lv 3`: `900 – 1,599` coins (next at `1,600`)
    - `Lv 4`: `1,600 – 2,499` coins (next at `2,500`)
    - `Lv 5`: `2,500 – 3,599` coins (next at `3,600`)
    - `Lv 6`: `>= 3,600` coins (max level)

### 5.2 `__config__.rotateTopics` (Page-1 Pinned Carousel)
Present on page 1 of `/` and `/categories/{key}`:
```json
[
  {
    "postId": 999,
    "titleText": "官方公告标题",
    "category": "daily",
    "categoryWord": "日常",
    "op": { "uid": 1, "name": "admin", "avatar": "/avatar/1.png" },
    "views": 12500,
    "comments": 88,
    "lastCommentDate": "2026-10-15T08:30:00.000Z",
    "rank": 0,
    "blocked": false
  }
]
```

### 5.3 `__config__.postData` (Full Topic Detail & Floor Comments)
Present on `GET /post-{postId}-{page}`:
```json
{
  "category": "dev",
  "categoryWord": "Dev",
  "categoryLink": "/categories/dev",
  "postId": 123,
  "postPage": 1,
  "postPageCount": 3,
  "rank": 0,
  "title": "示例帖子：周末读书计划",
  "views": "240",
  "collectionCount": 4,
  "locked": 0,
  "collected": 0,
  "award": 0,
  "op": {
    "uid": 101,
    "name": "SampleAuthor",
    "avatar": "/avatar/101.png"
  },
  "comments": [
    {
      "commentId": 501,
      "floorIndex": 0,
      "hot": false,
      "pined": false,
      "poster": {
        "uid": 101,
        "name": "SampleAuthor",
        "info": "楼主",
        "avatar": "/avatar/101.png",
        "profile": "/space/101",
        "isOp": true,
        "roles": [
          { "name": "admin", "display_text": "管理" }
        ]
      },
      "time": {
        "createdDate": "2025-01-04T12:00:00.000Z",
        "createdDateFormated": "2025-01-04 20:00:00",
        "editedDate": "2025-01-04T12:10:00.000Z",
        "editedDateFormated": "2025-01-04 20:10:00",
        "createdDateRel": "2 days ago",
        "editedDateRel": "2 days ago"
      },
      "markdown": "## 读书计划\n\n周六读两章，谢谢 @SampleReader42 :ac2054:",
      "signature": "Optional author signature markdown",
      "likeCount": 5,
      "dislikeCount": 0,
      "upvoteCount": 2,
      "liked": false,
      "disliked": false,
      "upvoted": false,
      "blocked": false
    }
  ]
}
```
> **Watch Out for Typo**: NodeSeek spells pinned comment as **`pined`** (single `n`) in `postData.comments`! Also note `poster.roles` is an array of `{ "name": String, "display_text": String }` objects (unlike `__config__.user.roles` which is `[String]`).

---

## 6. HTML Scraping Routes, CSS Selectors & Regexes

### 6.1 Feed, Category, Curated & Search Routes

| Screen | Page 1 URL | Page $N \ge 2$ URL | Query Parameters |
| :--- | :--- | :--- | :--- |
| **All Categories Feed** | `GET /?sortBy={sort}` | `GET /page-{N}?sortBy={sort}` | `sortBy=replyTime` or `postTime` |
| **Category Feed** | `GET /categories/{key}?sortBy={sort}` | `GET /categories/{key}/page-{N}?sortBy={sort}` | `sortBy=replyTime` or `postTime` |
| **Recommended (推荐阅读)** | `GET /award` | `GET /award/page-{N}` | None |
| **Topic Search** | `GET /search?q={q}&page=1&sortBy={sort}` | `GET /search?q={q}&page={N}&sortBy={sort}` | Optional `&category={key}`; replace `+` with `%2B` in query! |
| **Topic Detail** | `GET /post-{postId}-1` | `GET /post-{postId}-{N}` | None |
| **Partner Directory** | `GET /providers` | — | None |
| **Friend Links** | `GET /friends` | — | None |
| **Static Articles** | `GET /about`, `GET /termsofservice` | — | None |

### 6.2 Feed HTML CSS Selectors & Regexes

| Datum | Exact CSS Selector | Attribute / Text Extraction | Regex / Post-Processing |
| :--- | :--- | :--- | :--- |
| **Feed Item Row** | `.post-list-item:not(.topic-carousel-item):not(.blocked-post)` *(omit `:not(.blocked-post)` when `includeBlocked = true`)* | Element container | Skip row if `postId` was already added from `__config__.rotateTopics`. |
| **Title & Post Link** | `.post-title a[href^="/post-"]` | Text = `title.trim()`; `href` attribute | Regex `^/post-(\d+)-\d+` -> Group 1 is `postId: Long`. |
| **Category Badge** | `.post-category, a[href^="/categories/"]` | Text = `categoryTitle` (fallback to `__config__.allCategory` lookup by `categoryKey`); `href` attribute | `categoryKey = href?.substringAfterLast('/') ?: item.attr("data-category")`. |
| **Read-Level Lock** | `.post-title use[href="#lock"]` | `lock.closest("span")?.text()` | Regex `\d+` -> `readRankRequirement: Int?`. |
| **Author Name & UID** | `.info-author a` | Text = `authorName`; `href` attribute | Regex `/space/(\d+)` -> Group 1 is `authorId: Long?`. |
| **Author Avatar** | `.avatar-normal` | `src` attribute | Resolve relative `/avatar/{uid}.png` against `https://www.nodeseek.com`. |
| **View Count** | `.info-views span` | `text()` | Strip commas `,`, parse `Int` (default `0`). |
| **Reply Count** | `.info-comments-count span` | `text()` | Strip commas `,`, parse `Int` (default `0`). |
| **Last Activity Time** | `time` | `datetime` attribute | ISO-8601 timestamp string (e.g. `2026-10-15T08:30:00.000Z`). |
| **Pinned Indicator** | `[title="置顶"]` | Presence check (`!= null`) | Items with `pinned == true` on feed pages are merged after `rotateTopics` into `pinnedTopics` (deduped by `postId`) and excluded from regular `topics` list. |
| **Has Next Page** | `[aria-label="pagination"] a` or `a.pager-next` | `href` on pagination anchors, or existence of `a.pager-next` | Extract page via regex `page-(\d+)` on `pathname` or `page` query param; `hasNextPage = anyPage > currentPage \|\| selectFirst("a.pager-next") != null`. |

### 6.3 Topic Access-Restriction HTML Detection
When `GET /post-{id}-{page}` has container `#nsk-body-left` (or fallback `#nsk-body`) **without** any `.nsk-post, .post-content` child:
1. Normalize `message = forumBody.text().replace(Regex("\\s+"), " ")`.
2. Match regex `查看本帖需要\s*Lv\s*(\d+)` (case-insensitive):
   - If Group 1 == `"255"` **or** `message.contains("本帖已经被用户设为私有")` -> **`NodeSeekPostAccessException.PrivatePost`**.
   - If regex matched any other number **or** (`message.contains("权限不足")` && `message.contains("升级您的用户等级")`) -> **`NodeSeekPostAccessException.LevelRequired(minimumLevel = group1?.toIntOrNull())`**.
3. If `message.contains("需要注册用户才能查看")` -> **`NodeSeekPostAccessException.SignInRequired`**.

### 6.4 Directory & Static Article HTML Extraction
- **Partners (`/providers`) & Friends (`/friends`)**:
  - Selector on `/providers`: `.provider a[href]`; on `/friends`: `a[title][href]`.
  - Filter: must contain an `img` (`a.selectFirst("img") != null`), must NOT be inside `footer, header`, protocol must be `http`/`https`, deduplicate `url`, and on `/friends` skip internal `www.nodeseek.com` URLs.
  - Fields: `title = a.attr("title").ifBlank { a.text().trim() }`, `url = absUrl("href")`, `imageUrl = img.absUrl("src")`.
- **Static Articles (`/about`, `/termsofservice`)**:
  - If `__config__.postData.comments` has floor `0` with non-blank `markdown`, use that directly.
  - Otherwise select `article.post-content` -> fallback `article` -> fallback `#nsk-body-left-block, #nsk-frame-block`, remove `script, style, .user-card`, convert `a[href]` to `[text](url)`, and join `h1, h2, h3, p, li` into Markdown paragraphs.

---

## 7. Complete `/api/*` JSON Endpoints Reference

Unless noted otherwise, all endpoints below use base URL `https://www.nodeseek.com`, `credentials: same-origin` (shared WebView cookies), `Content-Type: application/json` when a JSON body is present, and return `"success": true | false` with optional `"message": "error description"`.

### 7.1 Authentication & Account Security

#### `POST /api/account/signIn` — Native Sign-In & 2FA Verification
- **Step 1 (Username/Email + Password)**:
  - **Headers**:
    - `Content-Type: application/json`
    - `x-captcha-token: <cloudflare_turnstile_token>` (Turnstile `sitekey`: `0x4AAAAAAAaNy7leGjewpVyR`, loaded with `baseURL = https://www.nodeseek.com`)
    - `x-captcha-source: turnstile`
  - **Request Body**:
    ```json
    { "username": "user_or_email", "password": "secret" }
    ```
  - **Response**:
    ```json
    {
      "success": true,
      "need2FA": 1,
      "otpSession": "opaque_session_token",
      "message": "..."
    }
    ```
    *(If `need2FA` is `false`/`0`/`null` and `success == true`, session cookies are already set.)*
- **Step 2 (TOTP 2FA Code, when `need2FA == true`)**:
  - **Headers**: `Content-Type: application/json` *(omit `x-captcha-token` and `x-captcha-source`!)*
  - **Request Body**:
    ```json
    { "otp_code": "123456", "otp_session": "opaque_session_token" }
    ```
  - **Expired Session Check**: `message?.contains("OTP_EXPIRED_OR_NOT_EXIST") == true` -> restart from Step 1.

#### `POST /api/account/signOut` — Sign Out
- **Headers**: `x-csrf-challenge: simple-token`
- **Body**: Empty
- **Verification**: Re-fetch `GET /` afterward and confirm `__config__.user == null` before clearing local user state.

#### Security, Avatar, Password, TOTP & Telegram Endpoints
| Purpose | Method & Path | Headers / Request Payload | Response Shape |
| :--- | :--- | :--- | :--- |
| **2FA Status** | `GET /api/account/otp-status` | — | `{ "success": true, "enabled": true }` |
| **Toggle 2FA** | `POST /api/account/otp` | `{ "action": "create" \| "remove", "password": "..." }` | `{ "success": true, "secret": "...", "otpauth": "otpauth://..." }` *(keep secret in memory only)* |
| **Sign-In IP History** | `GET /api/account/signIn-history` | — | `{ "success": true, "historyIp": [{ "ip": "1.2.3.4", "time": "..." }] }` |
| **Change Password** | `POST /api/account/changePassword` | `{ "oldPassword": "...", "password": "..." }` | `{ "success": true, "message": "..." }` |
| **Upload Avatar** | `POST /api/avatar/upload` | Header `x-csrf-challenge: simple-token`; `multipart/form-data` fields: `token="123456798"`, `name="avatar"`, file `img` (`img.png`, `image/png`, center-cropped `100x100` PNG) | `{ "success": true, "message": "..." }` |
| **Telegram Info** | `GET /api/account/telegram` | *(Call after checking `__config__.user.telegram_id` on `GET /setting`)* | `{ "success": true, "telegramDetail": { "telegram_id": ..., "username": "...", "first_name": "..." } }` |
| **Unbind Telegram** | `POST /api/account/unbind-telegram` | Empty body | `{ "success": true }` |

---

### 7.2 Daily Check-In (签到 / 鸡腿) & Daily Progress

#### `GET /api/attendance/board?page={page}` — Check-In Status & Leaderboard
- **Pagination**: `50` members per page (`hasNext = page * 50 < total`).
- **Response**:
  ```json
  {
    "success": true,
    "record": { "gain": 5 },
    "order": 128,
    "total": 4520,
    "list": [
      { "member_id": 101, "member_name": "SampleAuthor", "gain": 12 }
    ]
  }
  ```
- **Critical Semantics**:
  - `"record": null` -> **User has NOT checked in today.**
  - `"record": { "gain": 0 }` -> **User HAS checked in today** and rolled `0` drumsticks! Never conflate `null` with `0`.
  - Leaderboard rank for item at index `i` (0-based): `(page - 1) * 50 + i + 1`.

#### `POST /api/attendance?random={true|false}` — Perform Daily Check-In
- **Query Param**:
  - `random=false` -> **Fixed reward (+5 drumsticks / 保底 5 鸡腿)**
  - `random=true` -> **Random reward (试试手气)**
- **Body**: Empty
- **Response**:
  ```json
  {
    "success": true,
    "gain": 5,
    "current": 1655,
    "message": "签到成功，获得 5 个鸡腿"
  }
  ```
  *(After success, update `current` coin balance and re-fetch `/api/attendance/board?page=1` to confirm `record` & `order`.)*

#### `GET /api/progress/today` & `GET /api/progress/today?scope=freelike` — Daily Quotas
- **Full Progress (`GET /api/progress/today`)**:
  ```json
  {
    "success": true,
    "postBonusCount": 1,
    "maxPostBonusCount": 4,
    "commentBonusCount": 5,
    "maxCommentBonusCount": 20,
    "freeLikeUsed": 0,
    "maxFreeLike": 1
  }
  ```
- **Free Like Pre-Check (`GET /api/progress/today?scope=freelike`)**:
  - Returns `{ "maxFreeLike": Int, "freeLikeUsed": Int }`.
  - Cost of `like` (加鸡腿): `if (freeLikeUsed < maxFreeLike) 0 else 1` coin.

---

### 7.3 Comment Reactions & Topic Collections (Bookmarks)

#### `POST /api/statistics/{reaction}` — React to a Floor Comment (or OP Floor 0)
- **Paths**:
  - `POST /api/statistics/upvote` — 点赞 (Free; awards Stardust to author)
  - `POST /api/statistics/like` — 加鸡腿 (Uses free quota if available, otherwise costs **1 coin**)
  - `POST /api/statistics/dislike` — 反对 (Costs **2 coins**)
- **Request Body**:
  ```json
  { "commentId": 501, "action": "add" }
  ```
  *(Reactions cannot be undone on NodeSeek; disable button once `upvoted` / `liked` / `disliked` is true.)*
- **Response**:
  ```json
  {
    "success": true,
    "current": 6,
    "coin": 1654,
    "message": "..."
  }
  ```
  *(`current` is the authoritative updated reaction count for that comment; `coin` is the user's updated coin balance.)*

#### `POST /api/statistics/collection` — Add or Remove Bookmark
- **Request Body**:
  ```json
  { "postId": 123, "action": "add" }
  ```
  *(`"action"` is `"add"` or `"remove"`.)*
- **Response**: `{ "success": true, "message": "..." }`

#### `GET /api/statistics/list-collection?page={page}` — List Bookmarked Topics
- **Pagination**: `15` items per page (`hasNext = collections.size == 15`).
- **Response**:
  ```json
  {
    "success": true,
    "collections": [
      { "post_id": 123, "title": "示例帖子", "rank": 0 }
    ]
  }
  ```

---

### 7.4 Publishing, Editing & Pinning Content

All four write/edit endpoints require header **`csrf-token: <16-char-random-hex>`**:

| Action | Method & Path | JSON Request Body | Coin Fee Rules |
| :--- | :--- | :--- | :--- |
| **Create Topic** | `POST /api/content/new-discussion` | `{ "mode": "new-discussion", "title": "...", "content": "markdown...", "category": "tech", "rank": 0 }` | Posting in `category == "inside"` costs **5 coins**. `rank` is `0`–`6` (minimum `Lv` required to view) or `255` (Private). |
| **Edit Topic** | `POST /api/content/edit-discussion` | `{ "mode": "edit-discussion", "postId": 123, "title": "...", "content": "markdown...", "rank": 0 }` *(Note: **do NOT send `category`** when editing!)* | Changing an existing non-private topic to `rank: 255` (Private) costs **10 coins** — confirm with user first! |
| **Post Reply** | `POST /api/content/new-comment` | `{ "mode": "new-comment", "postId": 123, "content": "markdown..." }` | Free (earns coin bonus up to `maxCommentBonusCount`). |
| **Edit Reply** | `POST /api/content/edit-comment` | `{ "mode": "edit-comment", "commentId": 501, "content": "markdown..." }` | Free. |
| **Pin / Unpin Reply** | `POST /api/content/pin-comment` | `{ "commentId": 502, "action": "add" \| "remove" }` *(No `csrf-token` required)* | Only available to the topic OP on replies (`floorIndex > 0`). `"add"` costs **5 coins**; `"remove"` is free. |

---

### 7.5 Notifications & Direct Messages (Inbox)

| Action | Method & Path | Request Payload | Response Shape & Notes |
| :--- | :--- | :--- | :--- |
| **Unread Badge** | `GET /api/notification/unread-count` | — | `{ "success": true, "unreadCount": { "all": 3 } }` |
| **Replies to Me** | `GET /api/notification/reply-to-me/list` | — | `{ "success": true, "data": [{ "id": 91, "viewed": false, "post_id": 123, "title": "...", "floor_id": 4, "commenter_id": 202, "commenter_name": "Bob", "created_at": "..." }] }` |
| **@Mentions** | `GET /api/notification/at-me/list` | — | Same `data` array shape as `reply-to-me/list`. |
| **Mark Replies Read** | `POST /api/notification/reply-to-me/markViewed` *(or append `?all=true`)* | `{ "replys": [91, 92] }` *(note NodeSeek key **`replys`**, or `{}` when `?all=true`)* | `{ "success": true }` |
| **Mark @Me Read** | `POST /api/notification/at-me/markViewed` *(or append `?all=true`)* | `{ "atMe": [55] }` *(or `{}` when `?all=true`)* | `{ "success": true }` |
| **DM Thread List** | `GET /api/notification/message/list` | — | `{ "success": true, "msgArray": [{ "id": 701, "sender_id": 101, "sender_name": "Me", "receiver_id": 202, "receiver_name": "Bob", "content": "...", "is_markdown": false, "created_at": "...", "viewed": true }] }`. **Group client-side by `peerId`** (`sender_id == myUid ? receiver_id : sender_id`), keeping latest message per peer (`viewed = sender_id == myUid \|\| r.viewed`), sorted by `createdAt` desc. |
| **1-on-1 DM Conversation** | `GET /api/notification/message/with/{peerUid}` | — | `{ "success": true, "talkTo": { "member_id": 202, "member_name": "Bob" }, "msgArray": [{ "id": 701, "sender_id": 101, "receiver_id": 202, "content": "...", "created_at": "...", "is_markdown": false, "viewed": true }] }`. Verify `talkTo.member_id == peerUid` and sort `msgArray` ascending by `id`. Poll every 10s only while chat screen is visible in foreground. |
| **Mark DMs Read** | `POST /api/notification/message/markViewed` *(or append `?all=true`)* | `{ "messages": [701, 702] }` *(only mark messages where `receiver_id == myUid && !viewed`; or `{}` when `?all=true`)* | `{ "success": true }` |
| **Send DM** | `POST /api/notification/message/send` | `{ "receiverUid": 202, "content": "Hello", "markdown": false }` *(supports `true` for Markdown DMs)* | `{ "success": true, "message": "..." }` |

---

### 7.6 User Profiles, User Content, Social Graph & Preferences

| Action | Method & Path | Request Payload | Response Shape & Notes |
| :--- | :--- | :--- | :--- |
| **User Profile** | `GET /api/account/getInfo/{uid}?readme=1` *(add `&signature=1` when editing own profile)* | — | `{ "success": true, "detail": { "member_id": 101, "member_name": "...", "bio": "...", "readme": "...", "signature_markdown": "...", "created_at": "...", "coin": 1650, "stardust": 42, "nPost": 18, "nComment": 320, "follows": 5, "fans": 12, "followed": false } }` |
| **Update Own Profile** | `POST /api/account/introduction` | `{ "bio": "...", "signature": "...", "readme": "..." }` | `{ "success": true }` |
| **User's Topics** | `GET /api/content/list-discussions?uid={uid}&page={page}` | — | `{ "success": true, "discussions": [{ "post_id": 123, "title": "...", "rank": 0 }] }` (15/page; `hasNext = size == 15`). |
| **User's Replies** | `GET /api/content/list-comments?uid={uid}&page={page}` | — | `{ "success": true, "comments": [{ "post_id": 123, "title": "...", "floor_id": 4, "text": "<p>HTML...</p>", "rank": 0 }] }` (15/page). **`text` is HTML, NOT Markdown**: strip `<script>`/`<style>`, replace `<img>` with `alt` or `"[图片]"`, append space to `<br>/<p>/<div>/<li>`, collapse whitespace. |
| **Search Users** | `GET /api/account/find/{urlEncodedKeyword}` | — | `{ "success": true, "memberList": [{ "member_id": 202, "member_name": "Bob" }] }` |
| **Follow / Unfollow** | `POST /api/fans/add` or `POST /api/fans/del` | `{ "followed_member_id": 202 }` | `{ "success": true }` |
| **Following / Followers** | `GET /api/fans/follow` *(singular!)* / `GET /api/fans/fans` | — | `{ "success": true, "memberList": [...] }` |
| **Blocked Users** | `GET /api/block-list/list` | — | `{ "success": true, "data": [{ "block_member_id": 303, "block_member_name": "Spammer" }] }` |
| **Block / Unblock** | `POST /api/block-list/add` / `POST /api/block-list/del` | Add: `{ "block_member_name": "Spammer" }`<br>Del: `{ "block_member_id": 303 }` | `{ "success": true }` *(Note: `add` takes **username**, `del` takes **numeric ID**!)* |
| **Homepage Boards** | `GET /api/homepage` / `POST /api/homepage` | POST: `{ "data": [{ "category": "tech", "showInIndex": true }] }` | GET: `{ "success": true, "data": [{ "category": "tech", "showInIndex": true }] }`, merged with `__config__.allCategory.filter { it.customizable }`. |
| **Website Preferences** | `POST /api/preference/list` / `POST /api/preference/set` | List: `{ "keys": ["enable_festivous_style"] }`<br>Set: `{ "enable_festivous_style": true }` | List: `{ "success": true, "data": { "enable_festivous_style": false } }` |
| **Custom Website CSS** | `GET /api/style` / `POST /api/style` | POST: `{ "style": "body { ... }" }` | GET: `{ "success": true, "data": "css string" }` |

---

### 7.7 Economy, Polls, Lucky Draw, Invitations, Rulings & NodeImage

1. **Coin Ledger (鸡腿账簿)**:
   - `GET /api/account/credit/page-{page}` (20 items/page, max 100 pages: `hasNext = page < min(100, ceil(total / 20.0))`).
   - Response `data` is an **array of 4-element tuples**: `[change: Int, balance: Int, reason: String, createdAt: String]`, plus `total: Int`.
2. **Stardust Ledger & P2P Transfers (星尘)** *(Requires `x-dynamic-sign` header on ALL requests!)*:
   - Ledger: `GET /api/stardust/list?member_id={uid}&count=10[&before_id={cursor}][&type=...][&ref_id=...][&peer_id=...]`
     - Response: `{ "success": true, "records": [{ "id": 1, "diff": 10, "result": 52, "type": "transfer", "created_at": "...", "peer_id": 202 }], "exist_more": true, "cursor": 980 }`.
     - Type labels: `system`="系统", `admin`="管理", `transfer`="转账", `upvote`="点赞", `buyCode`="购买邀请码".
   - Prepare Transfer: `POST /api/stardust/payment-prepare` with `{ "receiver_id": 202, "origin": "https://www.nodeseek.com" }` -> `{ "success": true, "receiver_name": "Bob" }`.
   - Execute Transfer: `POST /api/stardust/send` with `{ "member_id": 202, "diff": 10, "ref_id": 0 }` -> `{ "success": true }`.
3. **Polls / Votes (投票)** *(Requires `x-dynamic-sign` header on ALL requests!)*:
   - Read Poll: `GET /api/vote/info/{voteId}` -> `{ "success": true, "vote": { "id": 1, "title": "...", "uid": 101, "isPublic": 1, "locked": 0, "multiple": 0, "items": [{ "vote_item_id": 11, "text": "Option A", "count": 5, "voted": 1, "voters": [101] }] } }` *(Note: before voting, `count` and `voters` may be `null`/omitted)*.
   - Create Poll: `POST /api/vote/info` with `{ "title": "...", "multiple": false, "isPublic": true, "items": ["A", "B"] }` -> `{ "success": true, "vote": { "id": 42 } }` -> insert Markdown `[投票](nsapp://vote?id=42)` into post.
   - Cast Vote: `POST /api/vote/voteforitem` with `{ "ids": [11] }`.
   - Lock/Close Poll: `POST /api/vote/lock/{voteId}` with `{ "locked": true }`.
   - Public Voters List: `GET /api/vote/voter-of-item?id={voteItemId}&page={page}` -> `{ "success": true, "voters": [101, 202] }`.
4. **Provably Fair Lucky Draw (抽奖)**:
   - Share link format: `https://www.nodeseek.com/lucky?post={postId}&time={epochMs}&count={winners}&start={firstFloor}&duplicate={true|false}&mode=view` *(note: `duplicate=false` means `removeDuplicates = true`!)*.
   - Fetch eligible floors: `GET /api/content/floor-data?postId={postId}&time={epochMs}` -> `{ "success": true, "data": [{ "floor_id": 1, "member_id": 202, "member_name": "Bob" }] }`.
   - Fetch public randomness beacon: `GET https://api.drand.sh/8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce/public/{round}` where `round = floor((revealEpochSeconds - 1595431050) / 30.0)`.
   - Deterministic winner selection: Seed **`cyrb128` + `sfc32`** PRNG with the 64-char hex `randomness` string, generate one `UInt32` per floor index `0..<floors.size`, sort indices by random value ascending, filter `floor_id >= firstFloor` (and deduplicate `member_id` from `index >= firstFloor` if `removeDuplicates`), take `count`, and sort winners by `floor_id` ascending.
5. **Invitations (邀请码)**:
   - Config: `GET /api/invite/config` -> `{ "payment": { "coin": { "enabled": true, "cost": 100 }, "stardust": { "enabled": true, "cost": 500 } } }`.
   - List: `GET /api/invite/list?page={page}` (50/page) -> `{ "data": [{ "code": "...", "applied": false, "created_at": "..." }], "total": 2 }`.
   - Purchase: `POST /api/invite/buyOne` with `{ "payment": "coin" | "stardust" }` + headers `x-csrf-challenge: simple-token` and `x-csrf-token: <token>`.
6. **Moderation Rulings (管理记录)**:
   - List / Search: `GET /api/admin/ruling/page-{page}[?search={q}]` (20/page, max 100 pages).
   - Single Record: `GET /api/admin/ruling/id-{id}`.
7. **NodeImage Official Image Hosting (`https://api.nodeimage.com`)**:
   - Optional Key Grant via Forum Session: `GET /api/cAuth?target=NodeImage` on `www.nodeseek.com`.
   - All `api.nodeimage.com` requests send `X-API-Key: <key>` and `Accept: application/json` with **cookies disabled**:
     - Upload: `POST https://api.nodeimage.com/api/upload` (`multipart/form-data`: file part `image` (`image.png` or `image.jpg`), optional form field `deleteDays` `1..365`) -> read `links.direct` (fallback `url`) and format as `![alt](url)`.
     - List Images: `GET https://api.nodeimage.com/api/v1/images`.
     - Delete Image: `DELETE https://api.nodeimage.com/api/v1/delete/{imageId}` (`imageId` matches `^[a-zA-Z0-9_-]+$`).

---

## 8. Markdown Extensions, Mentions & Sticker Packs

1. **Inline `@username` and `#floor` Regex**:
   ```regex
   (?<![A-Za-z0-9_@#])(?:@([\p{L}\p{N}_-]+)|#([0-9]+)(?![A-Za-z0-9_]|\.[0-9]))
   ```
   - `@username` navigates to user profile; `#floor` scrolls/jumps to that floor in the current topic (`page = floor <= 10 ? 1 : (floor - 1) / 10 + 1`).
2. **Custom Tab Blocks (NodeQuality / VPS Benchmark Reports)**:
   ```markdown
   :::: tabs
   ::: tab-item 基础信息
   ...markdown content...
   :::
   ::: tab-item 网络测速
   ...markdown content...
   :::
   ::::
   ```
3. **ANSI Terminal Escapes in Fenced Code Blocks**:
   - Benchmark posts embed raw `\u001B[...m` SGR sequences inside ```` ``` ```` blocks. Parse standard 16-color, 256-color (`38;5;n` / `48;5;n`), TrueColor (`38;2;r;g;b`), bold (`1`), dim (`2`), italic (`3`), underline (`4`), and reset (`0`) into Compose `AnnotatedString` spans.
4. **Embedded `nsapp://` Widgets**:
   - Poll link: `[投票标题](nsapp://vote?id=42)` -> interactive poll card.
   - Stardust receive button: `[投喂星尘](nsapp://stardust-receive?uid=101&amount=10&ref=123&once=1&note=...)` -> stardust tip card.
5. **Official Sticker Packs (`/static/image/sticker/{group}/{file}`)**:
   - Inline syntax `:code:` (outside inline code & links):
     - **AC娘 (`ac`)**: 149 static `.png` stickers (e.g. `:ac01:` .. `:ac2054:` -> `/static/image/sticker/ac/{num}.png`).
     - **洋葱头 (`yct`)**: 22 animated `.gif` stickers (`:yct001:` .. `:yct022:` -> `/static/image/sticker/yct/{num}.gif`).
     - **小黄鸡 (`xhj`)**: 32 mixed `.png` & `.gif` stickers (`:xhj001:` .. `:xhj032:` -> exact per-code extension table).
     - **Fluent Emoji (`emoji`)**: 49 animated stickers (`/static/image/sticker/emoji/{num}.webm` on Android/Web).

---

## 9. Android Domain Layer & Architecture Contracts

Core Kotlin domain models (`com.nodeseek.app.domain.model`) and repository interfaces (`com.nodeseek.app.domain.repository`) are decoupled from transport/HTML parsing details:

1. **Piggyback Session Updates on Every HTML Fetch**: Because every forum HTML page embeds `__config__.user` and `unViewedCount.all`, `NodeSeekApiClient` automatically notifies `SessionRepository` and `InboxRepository` whenever any HTML document is parsed — eliminating extra polling round-trips during active browsing.
2. **Coil Image Loader Setup**: A dedicated `OkHttpClient` (`CoilHttpClientFactory`) injects `Referer: https://www.nodeseek.com/` and appropriate image `Accept` headers while **stripping NodeSeek cookies** whenever `request.url.host` is outside `nodeseek.com`.
3. **Account-Scoped Caching & Drafts**: All cached private responses, bookmarks, editor drafts, and DM drafts are scoped by `AccountSession.uid` so switching accounts or signing out never exposes previous user data.
4. **External Service Isolation**: Seek Mail (`seek.li/openapi/v1`) and NodeImage (`api.nodeimage.com`) use dedicated clients with `CookieJar.NO_COOKIES` and redirect/origin checks so forum cookies and external API keys never cross boundaries.
