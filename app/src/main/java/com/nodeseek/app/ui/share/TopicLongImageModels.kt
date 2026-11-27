package com.nodeseek.app.ui.share

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.model.FloorComment

enum class LongImageThemeStyle(val label: String) {
    COOL_MIST_LIGHT("Cool Mist 浅色"),
    SLATE_STEEL_DARK("Slate Steel 深色"),
    PURE_PITCH_AMOLED("Pure Pitch 纯黑"),
}

@Immutable
data class TopicLongImageRequest(
    val topicId: Long,
    val title: String,
    val categorySlug: String,
    val categoryName: String,
    val targetFloor: FloorComment,
    val highlightReply: FloorComment? = null,
    val topicUrl: String,
    val viewCount: Int = 0,
    val bookmarkCount: Int = 0,
    val themeStyle: LongImageThemeStyle = LongImageThemeStyle.COOL_MIST_LIGHT,
    val includeSignature: Boolean = true,
    val includeQrCode: Boolean = true,
)

data class RenderedTopicLongImage(
    val pageIndex: Int,
    val totalPages: Int,
    val pngBytes: ByteArray,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
)
