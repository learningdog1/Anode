package com.nodeseek.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.CollapsingSquircleFab
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.DrumstickCheckInChip
import com.nodeseek.app.ui.components.FloatingPillDock
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseNavTab
import com.nodeseek.app.ui.components.PulseTopicCard
import com.nodeseek.app.ui.components.PulseTopicCardModel
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.PulseThemeMode

private val SAMPLE_UNREAD_HOT_TOPIC = PulseTopicCardModel(
    id = 884210L,
    title = "【深度测评】东京 Tier-1 BGP 双栈小鸡首发实测：三网 CMI/IIJ 回程延迟、YABS Geekbench 6 与晚高峰流媒体报告",
    categorySlug = "review",
    categoryName = "测评",
    authorId = 1024L,
    authorName = "latency_hunter",
    viewCount = 6840,
    replyCount = 142,
    lastActivityIso = "3m",
    isPinned = false,
    requiredViewLevel = null,
    isRead = false,
    lastReadFloor = null,
    isBookmarked = true,
    isHot = true,
)

private val SAMPLE_READ_RESUME_TOPIC = PulseTopicCardModel(
    id = 883905L,
    title = "自建 WireGuard + Bird2 任意播选路笔记：如何优雅规避跨太平洋海缆抖动并保持 MTU 1420 零分片",
    categorySlug = "tech",
    categoryName = "技术",
    authorId = 2048L,
    authorName = "bgp_artisan",
    viewCount = 1920,
    replyCount = 46,
    lastActivityIso = "42m",
    isPinned = false,
    requiredViewLevel = 1,
    isRead = true,
    lastReadFloor = 18,
    isBookmarked = false,
    isHot = false,
)

private val PREVIEW_SORT_OPTIONS = listOf(
    SortSegmentItem("reply", "最新回复"),
    SortSegmentItem("newest", "最新发布"),
    SortSegmentItem("hot", "热门精选"),
    SortSegmentItem("curated", "推荐阅读"),
)

private val SAMPLE_BENCHMARK_MARKDOWN = """
> **评测摘要**：东京 Equinix TY8 机房直连上游，三网去程各自优化，晚高峰 21:30 丢包率 `0.0%`。感谢 @bgp_artisan 在 #18 提供的内核 BBRv3 调优参数 :ac01:

:::: tabs
::: tab-item YABS 基准跑分
```ansi
${'\u001B'}[1;36m# ## ## ## ## ## ## ## ## ## ## ## ## ## ## #${'\u001B'}[0m
${'\u001B'}[1;36m#              Yet-Another-Bench-Script      #${'\u001B'}[0m
${'\u001B'}[1;36m# ## ## ## ## ## ## ## ## ## ## ## ## ## ## #${'\u001B'}[0m
CPU Model   : AMD EPYC 9654 96-Core Processor @ 3.70GHz
AES-NI      : ${'\u001B'}[1;32m✔ Enabled${'\u001B'}[0m    VM-x/AMD-V : ${'\u001B'}[1;32m✔ Enabled${'\u001B'}[0m
4k Mixed    : ${'\u001B'}[1;33m418.6 MB/s${'\u001B'}[0m  (104.6k IOPS)
Geekbench 6 : ${'\u001B'}[1;32m2,418 Single-Core${'\u001B'}[0m | ${'\u001B'}[1;35m9,860 Multi-Core${'\u001B'}[0m
```
:::
::: tab-item 三网回程矩阵
| 运营商 | 回程路由节点 | 晚高峰 RTT | 抖动 |
| :--- | :--- | ---: | ---: |
| 电信 CT | IIJ -> 202.97 骨干直连 | 48.2 ms | ±1.4 ms |
| 联通 CU | SoftBank -> AS4837 | 43.9 ms | ±0.9 ms |
| 移动 CM | CMI -> 香港 -> 东京 | 51.6 ms | ±1.8 ms |
:::
::::
""".trimIndent()

// ── 1. Editorial Topic Cards Showcase (Light, Obsidian Dark & Pure Pitch AMOLED) ──────────

@Preview(
    name = "1A · Topic Cards — Cool Mist Light",
    showBackground = true,
    widthDp = 412,
)
@Composable
fun NodePulseTopicCardsLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        TopicCardsPreviewContent(subtitle = "Cool Mist Light · #F7FAFF")
    }
}

@Preview(
    name = "1B · Topic Cards — Slate Steel Dark",
    showBackground = true,
    widthDp = 412,
)
@Composable
fun NodePulseTopicCardsDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK, purePitchAmoled = false) {
        TopicCardsPreviewContent(subtitle = "Slate Steel Dark · #101417")
    }
}

@Preview(
    name = "1C · Topic Cards — Pure Pitch AMOLED (#000000)",
    showBackground = true,
    widthDp = 412,
)
@Composable
fun NodePulseTopicCardsPitchAmoledPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK, purePitchAmoled = true) {
        TopicCardsPreviewContent(subtitle = "Pure Pitch AMOLED · #000000")
    }
}

@Composable
private fun TopicCardsPreviewContent(subtitle: String) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Anode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            PulseTopicCard(
                topic = SAMPLE_UNREAD_HOT_TOPIC,
                onClick = {},
                onBookmarkToggle = {},
                onAuthorClick = {},
                onCategoryClick = {},
            )

            PulseTopicCard(
                topic = SAMPLE_READ_RESUME_TOPIC,
                onClick = {},
                onBookmarkToggle = {},
                onAuthorClick = {},
                onCategoryClick = {},
            )
        }
    }
}

// ── 2. Navigation Dock, Squircle FAB, Sort Switcher & Badges Showcase ─────────────────────

@Preview(
    name = "2A · Navigation Dock & Controls — Paper Light",
    showBackground = true,
    widthDp = 412,
)
@Composable
fun NodePulseShellControlsLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        ShellControlsPreviewContent()
    }
}

@Preview(
    name = "2B · Navigation Dock & Controls — Obsidian Dark",
    showBackground = true,
    widthDp = 412,
)
@Composable
fun NodePulseShellControlsDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        ShellControlsPreviewContent()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShellControlsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Check-In Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrumstickCheckInChip(
                    checkedIn = false,
                    isSignedIn = true,
                    onClick = {},
                )
                DrumstickCheckInChip(
                    checkedIn = true,
                    todayGain = 8,
                    isSignedIn = true,
                    onClick = {},
                )
            }

            // Rank Ladder Lv.0 - Lv.6
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (lv in 0..6) {
                    RankMicroBadge(level = lv, showPersona = lv == 5 || lv == 6)
                }
            }

            // Semantic Category Micro-Tags
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("tech", "review", "info", "dev", "trade", "carpool", "daily", "inside").forEach { slug ->
                    CategoryMicroTag(slug = slug)
                }
            }

            // Animated 4-Way Sort Segmented Switcher
            SortSegmentedSwitcher(
                options = PREVIEW_SORT_OPTIONS,
                selectedValue = "reply",
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )

            // Floating Pill Dock + Expanded Squircle FAB
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloatingPillDock(
                    selectedTab = PulseNavTab.EXPLORE,
                    onSelectTab = {},
                    unreadNotificationCount = 5,
                    modifier = Modifier.weight(1f),
                )
                CollapsingSquircleFab(
                    onClick = {},
                    expanded = true,
                )
            }

            // Floating Pill Dock (Hub Selected, Check-in Dot) + Collapsed Squircle FAB
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloatingPillDock(
                    selectedTab = PulseNavTab.PULSE_HUB,
                    onSelectTab = {},
                    hasPendingCheckIn = true,
                    modifier = Modifier.weight(1f),
                )
                CollapsingSquircleFab(
                    onClick = {},
                    expanded = false,
                )
            }

            // Floating Pill Dock (Seek Mail Selected, 99+ Unread) + Compact Badge Scale Check (1 -> 88 -> 99+)
            FloatingPillDock(
                selectedTab = PulseNavTab.SEEK_MAIL,
                onSelectTab = {},
                unreadNotificationCount = 128,
                modifier = Modifier.fillMaxWidth(),
            )

            // Compact numeric badge scale check (1 -> 88 -> 99+)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1, 88, 128).forEach { count ->
                    CompactCountBadge(
                        count = count,
                        cutoutBorderColor = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
    }
}

// ── 3. Magazine-Grade Markdown, ANSI Benchmark & Tabs Showcase ────────────────────────────

@Preview(
    name = "3A · Markdown VPS Telemetry — Paper Light",
    showBackground = true,
    widthDp = 412,
)
@Composable
fun NodePulseMarkdownLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        MarkdownPreviewContent()
    }
}

@Preview(
    name = "3B · Markdown VPS Telemetry — Obsidian Dark",
    showBackground = true,
    widthDp = 412,
)
@Composable
fun NodePulseMarkdownDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        MarkdownPreviewContent()
    }
}

@Composable
private fun MarkdownPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            MarkdownRenderer(
                markdown = SAMPLE_BENCHMARK_MARKDOWN,
                onMentionClick = {},
                onFloorClick = {},
                onUrlClick = {},
            )
        }
    }
}
