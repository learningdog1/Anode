package com.nodeseek.app.ui.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.content.FileProvider
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
import com.nodeseek.app.ui.components.AnsiSgrState
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.MdImageItem
import com.nodeseek.app.ui.components.MdInlineToken
import com.nodeseek.app.ui.components.MdListItem
import com.nodeseek.app.ui.components.NodeSeekSticker
import com.nodeseek.app.ui.components.NodeSeekStickerCatalog
import com.nodeseek.app.ui.components.ParagraphRenderSegment
import com.nodeseek.app.ui.components.normalizeMarkdownImageUrl
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownDocument
import com.nodeseek.app.ui.components.splitParagraphSegments
import com.nodeseek.app.ui.components.tokenizeMarkdownInline
import com.nodeseek.app.ui.theme.CategoryPalette
import com.nodeseek.app.ui.theme.RankPalette
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin presentation nodes & page-slicing contracts for [TopicLongImageRenderer].
 */
internal data class TopicLongImagePresentationPlan(
    val topicId: Long,
    val title: String,
    val categorySlug: String,
    val categoryTitle: String,
    val topicUrl: String,
    val themeStyle: LongImageThemeStyle,
    val nodes: List<LongImageRenderNode>,
    val qrMatrix: QrBitMatrix?,
)

internal sealed interface LongImageRenderNode {
    data class TopicHeroHeader(
        val categoryTitle: String,
        val categorySlug: String,
        val title: String,
        val author: FloorAuthor,
        val floorNumber: Int,
        val createdAtIso: String,
        val viewCount: Int,
        val bookmarkCount: Int,
        val isLocked: Boolean = false,
        val showHeaderMeta: Boolean = true,
        val showAuthorCard: Boolean = true,
    ) : LongImageRenderNode

    data class HighlightReplyHeader(
        val floorNumber: Int,
        val author: FloorAuthor,
        val createdAtIso: String,
        val badgeText: String,
    ) : LongImageRenderNode

    data class Heading(
        val level: Int,
        val text: String,
        val referenceLinks: Map<String, Pair<String, String?>>,
    ) : LongImageRenderNode

    data class InlineText(
        val tokens: List<MdInlineToken>,
        val isQuoteChild: Boolean = false,
        val quoteDepth: Int = 0,
    ) : LongImageRenderNode

    data class ImageBlock(
        val imageUrl: String,
        val alt: String,
    ) : LongImageRenderNode

    data class ImageRowBlock(
        val items: List<MdImageItem>,
    ) : LongImageRenderNode

    data class CodeBlock(
        val language: String,
        val lines: List<String>,
        val isAnsi: Boolean,
        val truncatedOmittedLines: Int = 0,
    ) : LongImageRenderNode

    data class ListBlock(
        val items: List<MdListItem>,
        val referenceLinks: Map<String, Pair<String, String?>>,
        val isQuoteChild: Boolean = false,
    ) : LongImageRenderNode

    data class TableBlock(
        val headers: List<String>,
        val alignments: List<TextAlign>,
        val rows: List<List<String>>,
    ) : LongImageRenderNode

    data class SectionPill(
        val label: String,
        val isTab: Boolean,
    ) : LongImageRenderNode

    data class SpecialWidgetBlock(
        val title: String,
        val badgeLabel: String,
    ) : LongImageRenderNode

    data object Divider : LongImageRenderNode

    data class SignatureSection(
        val rawMarkdown: String,
    ) : LongImageRenderNode
}

internal data class LongImageMeasuredBlock(
    val node: LongImageRenderNode,
    val heightDp: Float,
)

internal data class LongImagePageSlice(
    val pageIndex: Int,
    val totalPages: Int,
    val blocks: List<LongImageMeasuredBlock>,
    val contentHeightDp: Float,
    val includeQrFooter: Boolean,
    val totalLogicalHeightDp: Float,
)

interface LongImageTextMeasurer {
    fun measureTextHeightDp(
        text: String,
        widthDp: Float,
        textSp: Float,
        bold: Boolean = false,
        monospace: Boolean = false,
        lineSpacingMult: Float = 1.35f,
    ): Float
}

object AndroidLongImageTextMeasurer : LongImageTextMeasurer {
    override fun measureTextHeightDp(
        text: String,
        widthDp: Float,
        textSp: Float,
        bold: Boolean,
        monospace: Boolean,
        lineSpacingMult: Float,
    ): Float {
        if (text.isEmpty()) return textSp * lineSpacingMult
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSp
            typeface = when {
                monospace && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                monospace -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }
        val safeW = widthDp.roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, safeW)
            ?.setLineSpacing(0f, lineSpacingMult)
            ?.setIncludePad(false)
            ?.build()
        val measured = layout?.height?.toFloat() ?: 0f
        if (measured > 0f) return measured
        // Fallback when running under JVM unit tests where StaticLayout stubs return null/0.
        return estimateJvmTextHeightDp(text, widthDp, textSp, lineSpacingMult)
    }
}

internal fun estimateJvmTextHeightDp(
    text: String,
    widthDp: Float,
    textSp: Float,
    lineSpacingMult: Float = 1.35f,
): Float {
    val safeWidth = widthDp.coerceAtLeast(40f)
    val avgCharWidthDp = textSp * 0.62f
    val charsPerLine = (safeWidth / avgCharWidthDp).toInt().coerceAtLeast(8)
    var lines = 0
    for (paragraph in text.split('\n')) {
        val weight = paragraph.sumOf { ch -> if (ch.code > 0x2E7F) 2L else 1L }.toInt().coerceAtLeast(1)
        lines += ((weight + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
    }
    return lines.coerceAtLeast(1) * textSp * lineSpacingMult
}

/**
 * Magazine-grade offscreen Canvas renderer for NodeSeek topics and replies (R16 Part 1).
 *
 * Reuses:
 * - [NetBenchReportCardImageRenderer]'s `500dp` logical width @ `2.0x` density (`1000px` wide PNG)
 * - [parseNodeSeekMarkdownDocument] / [tokenizeMarkdownInline] / [splitParagraphSegments] AST
 * - [AnsiAndCodeFormatter] for ANSI SGR terminal blocks & code highlighting
 * - [NodeSeekStickerCatalog] static preview stickers
 * - [CategoryPalette] & [RankPalette] semantic tokens
 * - [TopicLongImageQrGenerator] for vector-sharp ZXing QR codes
 */
object TopicLongImageRenderer {

    const val LOGICAL_WIDTH_DP = 500f
    const val DENSITY_SCALE = 2.0f
    const val MAX_PAGE_HEIGHT_DP = 5600f
    const val HARD_MAX_CODE_LINES_PER_BLOCK = 120

    private const val OUTER_INSET_DP = 10f
    private const val CARD_CORNER_DP = 20f
    private const val CARD_PAD_H_DP = 20f
    private const val CARD_PAD_V_DP = 20f
    private const val CONTENT_WIDTH_DP = LOGICAL_WIDTH_DP - (OUTER_INSET_DP + CARD_PAD_H_DP) * 2f
    private const val BLOCK_GAP_DP = 10f
    private const val CONTINUATION_HEADER_DP = 44f
    private const val QR_FOOTER_HEIGHT_DP = 124f
    private const val SIMPLE_FOOTER_HEIGHT_DP = 44f

    internal fun buildPresentationPlan(request: TopicLongImageRequest): TopicLongImagePresentationPlan {
        val isDark = request.themeStyle != LongImageThemeStyle.COOL_MIST_LIGHT
        val categorySpec = CategoryPalette.resolve(request.categorySlug, isDark = isDark)
        val resolvedCatTitle = request.categoryName.ifBlank { categorySpec.defaultTitle }

        val nodes = mutableListOf<LongImageRenderNode>()
        nodes += LongImageRenderNode.TopicHeroHeader(
            categoryTitle = resolvedCatTitle,
            categorySlug = request.categorySlug,
            title = request.title.ifBlank { "NodeSeek 主题 #${request.topicId}" },
            author = request.targetFloor.author,
            floorNumber = request.targetFloor.floorNumber,
            createdAtIso = request.targetFloor.createdAtIso,
            viewCount = request.viewCount,
            bookmarkCount = request.bookmarkCount,
        )

        nodes.addAll(convertMarkdownToNodes(request.targetFloor.markdownBody, isQuoteChild = false))

        if (request.includeSignature && !request.targetFloor.signatureMarkdown.isNullOrBlank()) {
            nodes += LongImageRenderNode.SignatureSection(request.targetFloor.signatureMarkdown)
        }

        request.highlightReply?.let { reply ->
            nodes += LongImageRenderNode.Divider
            nodes += LongImageRenderNode.HighlightReplyHeader(
                floorNumber = reply.floorNumber,
                author = reply.author,
                createdAtIso = reply.createdAtIso,
                badgeText = if (reply.isPinned) "置顶回复" else if (reply.isHot) "热门回复" else "精选回复",
            )
            nodes.addAll(convertMarkdownToNodes(reply.markdownBody, isQuoteChild = false))
        }

        val qrMatrix = if (request.includeQrCode && request.topicUrl.isNotBlank()) {
            runCatching { TopicLongImageQrGenerator.generateQrMatrix(request.topicUrl) }.getOrNull()
        } else {
            null
        }

        return TopicLongImagePresentationPlan(
            topicId = request.topicId,
            title = request.title,
            categorySlug = request.categorySlug,
            categoryTitle = resolvedCatTitle,
            topicUrl = request.topicUrl,
            themeStyle = request.themeStyle,
            nodes = nodes,
            qrMatrix = qrMatrix,
        )
    }

    internal fun planPages(
        request: TopicLongImageRequest,
        assets: TopicLongImageAssets = TopicLongImageAssets(),
        measurer: LongImageTextMeasurer = AndroidLongImageTextMeasurer,
        maxPageHeightDp: Float = MAX_PAGE_HEIGHT_DP,
    ): List<LongImagePageSlice> {
        val plan = buildPresentationPlan(request)
        val effectiveMaxPage = maxPageHeightDp.coerceAtLeast(900f)
        val footerReserve = if (plan.qrMatrix != null) QR_FOOTER_HEIGHT_DP else SIMPLE_FOOTER_HEIGHT_DP
        val chromeOverheadFirst = OUTER_INSET_DP * 2f + CARD_PAD_V_DP * 2f
        val chromeOverheadCont = chromeOverheadFirst + CONTINUATION_HEADER_DP
        val freshContBudget = (effectiveMaxPage - chromeOverheadCont - footerReserve).coerceAtLeast(200f)

        val rawBuckets = mutableListOf<List<LongImageMeasuredBlock>>()
        var current = mutableListOf<LongImageMeasuredBlock>()
        var currentHeight = 0f

        fun currentPageBudget(): Float {
            val overhead = if (rawBuckets.isEmpty()) chromeOverheadFirst else chromeOverheadCont
            return (effectiveMaxPage - overhead - footerReserve).coerceAtLeast(200f)
        }

        fun remainingBudget(): Float {
            val gap = if (current.isEmpty()) 0f else BLOCK_GAP_DP
            return currentPageBudget() - currentHeight - gap
        }

        fun flushPage() {
            if (current.isNotEmpty()) {
                rawBuckets += current
                current = mutableListOf()
                currentHeight = 0f
            }
        }

        fun appendMeasuredBlock(block: LongImageMeasuredBlock) {
            if (current.isNotEmpty() && currentHeight + BLOCK_GAP_DP + block.heightDp > currentPageBudget()) {
                flushPage()
            }
            val gap = if (current.isEmpty()) 0f else BLOCK_GAP_DP
            current += block
            currentHeight += gap + block.heightDp
        }

        for (node in plan.nodes) {
            when (node) {
                is LongImageRenderNode.TableBlock -> {
                    if (node.rows.isEmpty()) {
                        appendMeasuredBlock(
                            LongImageMeasuredBlock(node, measureNodeHeightDp(node, assets, measurer)),
                        )
                    } else {
                        var rowOffset = 0
                        while (rowOffset < node.rows.size) {
                            var avail = remainingBudget()
                            var maxRows = ((avail - 38f) / 30f).toInt()
                            if (maxRows < 1 && current.isNotEmpty()) {
                                flushPage()
                                avail = remainingBudget()
                                maxRows = ((avail - 38f) / 30f).toInt()
                            }
                            val takeRows = maxRows.coerceAtLeast(1)
                            val end = (rowOffset + takeRows).coerceAtMost(node.rows.size)
                            val sliceNode = node.copy(rows = node.rows.subList(rowOffset, end).toList())
                            appendMeasuredBlock(
                                LongImageMeasuredBlock(sliceNode, measureNodeHeightDp(sliceNode, assets, measurer)),
                            )
                            rowOffset = end
                            if (rowOffset < node.rows.size) {
                                flushPage()
                            }
                        }
                    }
                }

                is LongImageRenderNode.ListBlock -> {
                    val normalizedItems = normalizeOversizedListItems(
                        items = node.items,
                        referenceLinks = node.referenceLinks,
                        isQuoteChild = node.isQuoteChild,
                        maxItemHeightDp = freshContBudget,
                        measurer = measurer,
                    )
                    if (normalizedItems.isEmpty()) {
                        appendMeasuredBlock(
                            LongImageMeasuredBlock(node, measureNodeHeightDp(node, assets, measurer)),
                        )
                    } else {
                        var idx = 0
                        while (idx < normalizedItems.size) {
                            var avail = remainingBudget()
                            var count = countFittingListItems(
                                items = normalizedItems,
                                startIdx = idx,
                                referenceLinks = node.referenceLinks,
                                isQuoteChild = node.isQuoteChild,
                                availHeightDp = avail,
                                measurer = measurer,
                            )
                            if (count == 0 && current.isNotEmpty()) {
                                flushPage()
                                avail = remainingBudget()
                                count = countFittingListItems(
                                    items = normalizedItems,
                                    startIdx = idx,
                                    referenceLinks = node.referenceLinks,
                                    isQuoteChild = node.isQuoteChild,
                                    availHeightDp = avail,
                                    measurer = measurer,
                                )
                            }
                            val takeCount = count.coerceAtLeast(1)
                            val end = (idx + takeCount).coerceAtMost(normalizedItems.size)
                            val sliceNode = node.copy(items = normalizedItems.subList(idx, end).toList())
                            appendMeasuredBlock(
                                LongImageMeasuredBlock(sliceNode, measureNodeHeightDp(sliceNode, assets, measurer)),
                            )
                            idx = end
                            if (idx < normalizedItems.size) {
                                flushPage()
                            }
                        }
                    }
                }

                is LongImageRenderNode.InlineText -> {
                    val h = measureNodeHeightDp(node, assets, measurer)
                    if (h <= remainingBudget()) {
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else if (h <= freshContBudget) {
                        flushPage()
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else {
                        val chunks = splitInlineTextNode(
                            node = node,
                            maxHeightDp = freshContBudget,
                            assets = assets,
                            measurer = measurer,
                        )
                        for (chunk in chunks) {
                            appendMeasuredBlock(
                                LongImageMeasuredBlock(chunk, measureNodeHeightDp(chunk, assets, measurer)),
                            )
                        }
                    }
                }

                is LongImageRenderNode.CodeBlock -> {
                    val h = measureNodeHeightDp(node, assets, measurer)
                    if (h <= remainingBudget()) {
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else if (h <= freshContBudget) {
                        flushPage()
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else {
                        val chunks = splitCodeBlockNode(
                            node = node,
                            maxHeightDp = freshContBudget,
                            measurer = measurer,
                        )
                        for (chunk in chunks) {
                            appendMeasuredBlock(
                                LongImageMeasuredBlock(chunk, measureNodeHeightDp(chunk, assets, measurer)),
                            )
                        }
                    }
                }

                is LongImageRenderNode.SignatureSection -> {
                    val h = measureNodeHeightDp(node, assets, measurer)
                    if (h <= remainingBudget()) {
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else if (h <= freshContBudget) {
                        flushPage()
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else {
                        val parts = splitTextToFitHeight(
                            text = node.rawMarkdown,
                            widthDp = CONTENT_WIDTH_DP - 20f,
                            textSp = 11.5f,
                            bold = false,
                            monospace = false,
                            lineSpacingMult = 1.3f,
                            maxHeightDp = (freshContBudget - 26f).coerceAtLeast(60f),
                            measurer = measurer,
                        )
                        for (part in parts) {
                            val sigNode = LongImageRenderNode.SignatureSection(part)
                            appendMeasuredBlock(
                                LongImageMeasuredBlock(sigNode, measureNodeHeightDp(sigNode, assets, measurer)),
                            )
                        }
                    }
                }

                is LongImageRenderNode.TopicHeroHeader -> {
                    val h = measureNodeHeightDp(node, assets, measurer)
                    if (h <= remainingBudget()) {
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else if (h <= freshContBudget) {
                        flushPage()
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else {
                        val titleParts = splitTextToFitHeight(
                            text = node.title,
                            widthDp = CONTENT_WIDTH_DP,
                            textSp = 20f,
                            bold = true,
                            monospace = false,
                            lineSpacingMult = 1.28f,
                            maxHeightDp = (freshContBudget - 120f).coerceAtLeast(80f),
                            measurer = measurer,
                        )
                        titleParts.forEachIndexed { idx, part ->
                            val sliceNode = node.copy(
                                title = part,
                                showHeaderMeta = idx == 0,
                                showAuthorCard = idx == titleParts.lastIndex,
                            )
                            appendMeasuredBlock(
                                LongImageMeasuredBlock(sliceNode, measureNodeHeightDp(sliceNode, assets, measurer)),
                            )
                        }
                    }
                }

                is LongImageRenderNode.Heading -> {
                    val h = measureNodeHeightDp(node, assets, measurer)
                    if (h <= remainingBudget()) {
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else if (h <= freshContBudget) {
                        flushPage()
                        appendMeasuredBlock(LongImageMeasuredBlock(node, h))
                    } else {
                        val sp = headingTextSp(node.level)
                        val plain = flattenTokensPlainText(tokenizeMarkdownInline(node.text, node.referenceLinks))
                        val headingParts = splitTextToFitHeight(
                            text = plain,
                            widthDp = CONTENT_WIDTH_DP - 12f,
                            textSp = sp,
                            bold = true,
                            monospace = false,
                            lineSpacingMult = 1.26f,
                            maxHeightDp = (freshContBudget - 16f).coerceAtLeast(60f),
                            measurer = measurer,
                        )
                        for (part in headingParts) {
                            val sliceNode = node.copy(text = part, referenceLinks = emptyMap())
                            appendMeasuredBlock(
                                LongImageMeasuredBlock(sliceNode, measureNodeHeightDp(sliceNode, assets, measurer)),
                            )
                        }
                    }
                }

                else -> {
                    appendMeasuredBlock(
                        LongImageMeasuredBlock(node, measureNodeHeightDp(node, assets, measurer)),
                    )
                }
            }
        }
        flushPage()
        if (rawBuckets.isEmpty()) {
            rawBuckets.add(emptyList())
        }

        val totalPages = rawBuckets.size
        return rawBuckets.mapIndexed { idx, bucket ->
            val pageNumber = idx + 1
            val isLast = pageNumber == totalPages
            val contentH = if (bucket.isEmpty()) {
                0f
            } else {
                bucket.sumOf { it.heightDp.toDouble() }.toFloat() + (bucket.size - 1) * BLOCK_GAP_DP
            }
            val headerExtra = if (pageNumber > 1) CONTINUATION_HEADER_DP else 0f
            val includeQr = isLast && plan.qrMatrix != null
            val footerH = if (includeQr) QR_FOOTER_HEIGHT_DP else SIMPLE_FOOTER_HEIGHT_DP
            val totalH = (OUTER_INSET_DP * 2f + CARD_PAD_V_DP * 2f + headerExtra + contentH + footerH)
                .coerceAtMost(effectiveMaxPage)
            LongImagePageSlice(
                pageIndex = pageNumber,
                totalPages = totalPages,
                blocks = bucket,
                contentHeightDp = contentH,
                includeQrFooter = includeQr,
                totalLogicalHeightDp = totalH,
            )
        }
    }

    private fun measureSingleListItemHeightDp(
        item: MdListItem,
        referenceLinks: Map<String, Pair<String, String?>>,
        isQuoteChild: Boolean,
        measurer: LongImageTextMeasurer,
    ): Float {
        val w = if (isQuoteChild) CONTENT_WIDTH_DP - 44f else CONTENT_WIDTH_DP - 22f
        val plain = flattenTokensPlainText(tokenizeMarkdownInline(item.text, referenceLinks))
        return measurer.measureTextHeightDp(
            text = plain,
            widthDp = (w - item.indentLevel * 12f).coerceAtLeast(100f),
            textSp = 13.8f,
            lineSpacingMult = 1.36f,
        ) + 4f
    }

    private fun normalizeOversizedListItems(
        items: List<MdListItem>,
        referenceLinks: Map<String, Pair<String, String?>>,
        isQuoteChild: Boolean,
        maxItemHeightDp: Float,
        measurer: LongImageTextMeasurer,
    ): List<MdListItem> {
        val result = ArrayList<MdListItem>(items.size)
        val w = if (isQuoteChild) CONTENT_WIDTH_DP - 44f else CONTENT_WIDTH_DP - 22f
        val itemMaxTextHeight = (maxItemHeightDp - 4f).coerceAtLeast(80f)
        for (item in items) {
            val h = measureSingleListItemHeightDp(item, referenceLinks, isQuoteChild, measurer)
            if (h <= maxItemHeightDp) {
                result += item
            } else {
                val itemW = (w - item.indentLevel * 12f).coerceAtLeast(100f)
                val parts = splitTextToFitHeight(
                    text = item.text,
                    widthDp = itemW,
                    textSp = 13.8f,
                    bold = false,
                    monospace = false,
                    lineSpacingMult = 1.36f,
                    maxHeightDp = itemMaxTextHeight,
                    measurer = measurer,
                )
                parts.forEachIndexed { pIdx, partText ->
                    result += MdListItem(
                        text = partText,
                        indentLevel = item.indentLevel,
                        orderNumber = if (pIdx == 0) item.orderNumber else null,
                        checked = if (pIdx == 0) item.checked else null,
                    )
                }
            }
        }
        return result
    }

    private fun countFittingListItems(
        items: List<MdListItem>,
        startIdx: Int,
        referenceLinks: Map<String, Pair<String, String?>>,
        isQuoteChild: Boolean,
        availHeightDp: Float,
        measurer: LongImageTextMeasurer,
    ): Int {
        if (availHeightDp < 22f) return 0
        var sum = 0.0
        var count = 0
        for (i in startIdx until items.size) {
            val h = measureSingleListItemHeightDp(items[i], referenceLinks, isQuoteChild, measurer)
            val candidate = (sum + h).toFloat().coerceAtLeast(22f)
            if (candidate > availHeightDp) break
            sum += h
            count++
        }
        return count
    }

    private fun splitInlineTextNode(
        node: LongImageRenderNode.InlineText,
        maxHeightDp: Float,
        assets: TopicLongImageAssets,
        measurer: LongImageTextMeasurer,
    ): List<LongImageRenderNode.InlineText> {
        val w = if (node.isQuoteChild) CONTENT_WIDTH_DP - 24f else CONTENT_WIDTH_DP
        val overhead = if (node.isQuoteChild) 16f else 2f
        val textBudget = (maxHeightDp - overhead).coerceAtLeast(60f)

        val out = mutableListOf<LongImageRenderNode.InlineText>()
        var currentTokens = mutableListOf<MdInlineToken>()

        fun flushTokens() {
            if (currentTokens.isNotEmpty()) {
                out += node.copy(tokens = currentTokens.toList())
                currentTokens = mutableListOf()
            }
        }

        fun splitOversizedToken(token: MdInlineToken): List<MdInlineToken> = when (token) {
            is MdInlineToken.PlainText -> {
                splitTextToFitHeight(
                    text = token.text,
                    widthDp = w,
                    textSp = 14f,
                    bold = false,
                    monospace = false,
                    lineSpacingMult = 1.42f,
                    maxHeightDp = textBudget,
                    measurer = measurer,
                ).map { MdInlineToken.PlainText(it) }
            }
            is MdInlineToken.CodeSpan -> {
                splitTextToFitHeight(
                    text = token.code,
                    widthDp = w,
                    textSp = 14f,
                    bold = false,
                    monospace = false,
                    lineSpacingMult = 1.42f,
                    maxHeightDp = textBudget,
                    measurer = measurer,
                ).map { MdInlineToken.CodeSpan(it) }
            }
            is MdInlineToken.Styled -> {
                val childChunks = mutableListOf<List<MdInlineToken>>()
                var buf = mutableListOf<MdInlineToken>()
                for (child in token.children) {
                    val cand = buf + child
                    val candH = measurer.measureTextHeightDp(
                        text = flattenTokensPlainText(cand),
                        widthDp = w,
                        textSp = 14f,
                        lineSpacingMult = 1.42f,
                    )
                    if (candH <= textBudget) {
                        buf += child
                    } else {
                        if (buf.isNotEmpty()) {
                            childChunks += buf.toList()
                            buf = mutableListOf()
                        }
                        val singleH = measurer.measureTextHeightDp(
                            text = flattenTokensPlainText(listOf(child)),
                            widthDp = w,
                            textSp = 14f,
                            lineSpacingMult = 1.42f,
                        )
                        if (singleH <= textBudget) {
                            buf += child
                        } else {
                            val subPieces = splitOversizedToken(child)
                            if (subPieces.size > 1) {
                                subPieces.dropLast(1).forEach { childChunks += listOf(it) }
                            }
                            subPieces.lastOrNull()?.let { buf += it }
                        }
                    }
                }
                if (buf.isNotEmpty()) childChunks += buf.toList()
                childChunks.map { pieces -> token.copy(children = pieces) }
            }
            is MdInlineToken.Link -> {
                val childChunks = mutableListOf<List<MdInlineToken>>()
                var buf = mutableListOf<MdInlineToken>()
                for (child in token.labelTokens) {
                    val cand = buf + child
                    val candH = measurer.measureTextHeightDp(
                        text = flattenTokensPlainText(cand),
                        widthDp = w,
                        textSp = 14f,
                        lineSpacingMult = 1.42f,
                    )
                    if (candH <= textBudget) {
                        buf += child
                    } else {
                        if (buf.isNotEmpty()) {
                            childChunks += buf.toList()
                            buf = mutableListOf()
                        }
                        val singleH = measurer.measureTextHeightDp(
                            text = flattenTokensPlainText(listOf(child)),
                            widthDp = w,
                            textSp = 14f,
                            lineSpacingMult = 1.42f,
                        )
                        if (singleH <= textBudget) {
                            buf += child
                        } else {
                            val subPieces = splitOversizedToken(child)
                            if (subPieces.size > 1) {
                                subPieces.dropLast(1).forEach { childChunks += listOf(it) }
                            }
                            subPieces.lastOrNull()?.let { buf += it }
                        }
                    }
                }
                if (buf.isNotEmpty()) childChunks += buf.toList()
                childChunks.map { pieces -> token.copy(labelTokens = pieces) }
            }
            else -> listOf(token)
        }

        for (token in node.tokens) {
            val candidate = currentTokens + token
            val candidateNode = node.copy(tokens = candidate)
            if (measureNodeHeightDp(candidateNode, assets, measurer) <= maxHeightDp) {
                currentTokens += token
                continue
            }
            flushTokens()
            val singleNode = node.copy(tokens = listOf(token))
            if (measureNodeHeightDp(singleNode, assets, measurer) <= maxHeightDp) {
                currentTokens += token
            } else {
                val splitPieces = splitOversizedToken(token)
                if (splitPieces.size > 1) {
                    splitPieces.dropLast(1).forEach { piece ->
                        out += node.copy(tokens = listOf(piece))
                    }
                }
                splitPieces.lastOrNull()?.let { currentTokens += it }
            }
        }
        flushTokens()
        return if (out.isEmpty()) listOf(node) else out
    }

    private fun splitCodeBlockNode(
        node: LongImageRenderNode.CodeBlock,
        maxHeightDp: Float,
        measurer: LongImageTextMeasurer,
    ): List<LongImageRenderNode.CodeBlock> {
        val codeW = CONTENT_WIDTH_DP - 24f
        val fixedOverhead = 28f + 16f + 4f + if (node.truncatedOmittedLines > 0) 22f else 0f
        val bodyBudget = (maxHeightDp - fixedOverhead).coerceAtLeast(60f)

        val expandedLines = mutableListOf<String>()
        for (line in node.lines) {
            val clean = if (node.isAnsi) AnsiAndCodeFormatter.stripAnsiEscapes(line) else line
            val lh = measurer.measureTextHeightDp(
                text = clean.ifEmpty { " " },
                widthDp = codeW,
                textSp = 11.2f,
                monospace = true,
                lineSpacingMult = 1.32f,
            )
            if (lh <= bodyBudget) {
                expandedLines += line
            } else {
                expandedLines += splitTextToFitHeight(
                    text = clean.ifEmpty { " " },
                    widthDp = codeW,
                    textSp = 11.2f,
                    bold = false,
                    monospace = true,
                    lineSpacingMult = 1.32f,
                    maxHeightDp = bodyBudget,
                    measurer = measurer,
                )
            }
        }

        val chunks = mutableListOf<List<String>>()
        var buf = mutableListOf<String>()
        var bufH = 0.0
        for (line in expandedLines) {
            val clean = if (node.isAnsi) AnsiAndCodeFormatter.stripAnsiEscapes(line) else line
            val lh = measurer.measureTextHeightDp(
                text = clean.ifEmpty { " " },
                widthDp = codeW,
                textSp = 11.2f,
                monospace = true,
                lineSpacingMult = 1.32f,
            ).toDouble()
            if (buf.isNotEmpty() && (bufH + lh).toFloat().coerceAtLeast(20f) > bodyBudget) {
                chunks += buf.toList()
                buf = mutableListOf(line)
                bufH = lh
            } else {
                buf += line
                bufH += lh
            }
        }
        if (buf.isNotEmpty()) {
            chunks += buf.toList()
        }
        if (chunks.isEmpty()) return listOf(node)
        return chunks.mapIndexed { idx, linesChunk ->
            LongImageRenderNode.CodeBlock(
                language = node.language,
                lines = linesChunk,
                isAnsi = node.isAnsi,
                truncatedOmittedLines = if (idx == chunks.lastIndex) node.truncatedOmittedLines else 0,
            )
        }
    }

    private fun splitTextToFitHeight(
        text: String,
        widthDp: Float,
        textSp: Float,
        bold: Boolean,
        monospace: Boolean,
        lineSpacingMult: Float,
        maxHeightDp: Float,
        measurer: LongImageTextMeasurer,
    ): List<String> {
        if (text.isEmpty()) return listOf(text)
        val safeBudget = maxHeightDp.coerceAtLeast(textSp * lineSpacingMult)
        val out = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val fullH = measurer.measureTextHeightDp(
                text = remaining,
                widthDp = widthDp,
                textSp = textSp,
                bold = bold,
                monospace = monospace,
                lineSpacingMult = lineSpacingMult,
            )
            if (fullH <= safeBudget) {
                out += remaining
                break
            }
            var lo = 1
            var hi = remaining.length
            var best = 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val h = measurer.measureTextHeightDp(
                    text = remaining.substring(0, mid),
                    widthDp = widthDp,
                    textSp = textSp,
                    bold = bold,
                    monospace = monospace,
                    lineSpacingMult = lineSpacingMult,
                )
                if (h <= safeBudget) {
                    best = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            var cut = best.coerceAtLeast(1)
            if (cut < remaining.length && cut > 16) {
                val floorIdx = (cut * 3) / 4
                val nlIdx = remaining.lastIndexOf('\n', cut - 1)
                val spIdx = remaining.lastIndexOf(' ', cut - 1)
                val boundary = max(nlIdx, spIdx)
                if (boundary >= floorIdx) {
                    cut = boundary + 1
                }
            }
            out += remaining.substring(0, cut)
            remaining = remaining.substring(cut)
        }
        return out
    }

    fun render(
        request: TopicLongImageRequest,
        assets: TopicLongImageAssets = TopicLongImageAssets(),
        maxPageHeightDp: Float = MAX_PAGE_HEIGHT_DP,
    ): List<RenderedTopicLongImage> {
        val plan = buildPresentationPlan(request)
        val slices = planPages(
            request = request,
            assets = assets,
            measurer = AndroidLongImageTextMeasurer,
            maxPageHeightDp = maxPageHeightDp,
        )
        val palette = LongImagePalette.forStyle(request.themeStyle)

        return slices.mapNotNull { slice ->
            val widthPx = (LOGICAL_WIDTH_DP * DENSITY_SCALE).roundToInt().coerceAtLeast(1)
            val heightPx = (slice.totalLogicalHeightDp * DENSITY_SCALE).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888) ?: return@mapNotNull null
            try {
                bitmap.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(bitmap)
                canvas.scale(DENSITY_SCALE, DENSITY_SCALE)
                drawPageSlice(
                    canvas = canvas,
                    plan = plan,
                    slice = slice,
                    palette = palette,
                    assets = assets,
                    recordedTexts = null,
                )
                val out = ByteArrayOutputStream(32_768)
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                val bytes = out.toByteArray()
                if (!ok || bytes.isEmpty()) return@mapNotNull null
                val suffix = if (slice.totalPages > 1) "_p${slice.pageIndex}of${slice.totalPages}" else ""
                RenderedTopicLongImage(
                    pageIndex = slice.pageIndex,
                    totalPages = slice.totalPages,
                    pngBytes = bytes,
                    fileName = "anode_post_${request.topicId}_f${request.targetFloor.floorNumber}${suffix}.png",
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    fun extractRenderedTexts(
        request: TopicLongImageRequest,
        assets: TopicLongImageAssets = TopicLongImageAssets(),
        maxPageHeightDp: Float = MAX_PAGE_HEIGHT_DP,
    ): List<String> {
        val plan = buildPresentationPlan(request)
        val slices = planPages(
            request = request,
            assets = assets,
            measurer = AndroidLongImageTextMeasurer,
            maxPageHeightDp = maxPageHeightDp,
        )
        val palette = LongImagePalette.forStyle(request.themeStyle)
        val recorded = mutableListOf<String>()
        val dummyCanvas = Canvas()
        for (slice in slices) {
            drawPageSlice(
                canvas = dummyCanvas,
                plan = plan,
                slice = slice,
                palette = palette,
                assets = assets,
                recordedTexts = recorded,
            )
        }
        return recorded
    }

    fun saveToGallery(
        context: Context,
        images: List<RenderedTopicLongImage>,
    ): Result<Int> = runCatching {
        if (images.isEmpty()) error("没有可保存的长图")
        var savedCount = 0
        for (image in images) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, image.fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Anode")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建相册图片文件")
                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        out.write(image.pngBytes)
                        out.flush()
                    } ?: error("无法写入相册图片")
                    val publishValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, publishValues, null, null)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    throw t
                }
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = File(picturesDir, "Anode").apply {
                    if (!exists() && !mkdirs()) error("无法创建 Pictures/Anode 目录")
                }
                val outFile = File(targetDir, image.fileName)
                outFile.writeBytes(image.pngBytes)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.absolutePath),
                    arrayOf("image/png"),
                    null,
                )
            }
            savedCount++
        }
        savedCount
    }

    fun shareImages(
        context: Context,
        images: List<RenderedTopicLongImage>,
        title: String,
    ): Boolean {
        if (images.isEmpty()) return false
        return runCatching {
            val dir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
            val uris = ArrayList<android.net.Uri>(images.size)
            for (image in images) {
                val outFile = File(dir, image.fileName)
                outFile.writeBytes(image.pngBytes)
                uris += FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile,
                )
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/png"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val chooser = Intent.createChooser(intent, "分享杂志长图").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    // ── AST Conversion ────────────────────────────────────────────────────────

    private fun convertMarkdownToNodes(
        markdown: String,
        isQuoteChild: Boolean,
        quoteDepth: Int = 0,
    ): List<LongImageRenderNode> {
        val doc = parseNodeSeekMarkdownDocument(markdown)
        val out = mutableListOf<LongImageRenderNode>()

        fun emitBlocks(blocks: List<MdBlock>, inQuote: Boolean, depth: Int) {
            for (block in blocks) {
                when (block) {
                    is MdBlock.Heading -> {
                        out += LongImageRenderNode.Heading(
                            level = block.level,
                            text = block.text,
                            referenceLinks = doc.referenceLinks,
                        )
                    }

                    is MdBlock.Paragraph -> {
                        val tokens = tokenizeMarkdownInline(block.text, doc.referenceLinks)
                        val segments = splitParagraphSegments(tokens)
                        for (seg in segments) {
                            when (seg) {
                                is ParagraphRenderSegment.InlineRun -> {
                                    out += LongImageRenderNode.InlineText(
                                        tokens = seg.tokens,
                                        isQuoteChild = inQuote,
                                        quoteDepth = depth,
                                    )
                                }
                                is ParagraphRenderSegment.PromotedImage -> {
                                    val url = normalizeMarkdownImageUrl(seg.token.url)
                                    if (url.isNotBlank()) {
                                        out += LongImageRenderNode.ImageBlock(
                                            imageUrl = url,
                                            alt = seg.token.alt,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is MdBlock.Blockquote -> {
                        emitBlocks(block.children, inQuote = true, depth = depth + 1)
                    }

                    is MdBlock.CodeFence -> {
                        val isAnsi = block.language.equals("ansi", ignoreCase = true) ||
                            AnsiAndCodeFormatter.containsAnsiEscapes(block.code)
                        val allLines = block.code.replace("\r\n", "\n").split('\n')
                        val omitted = (allLines.size - HARD_MAX_CODE_LINES_PER_BLOCK).coerceAtLeast(0)
                        val capped = if (omitted > 0) allLines.take(HARD_MAX_CODE_LINES_PER_BLOCK) else allLines
                        out += LongImageRenderNode.CodeBlock(
                            language = block.language,
                            lines = capped,
                            isAnsi = isAnsi,
                            truncatedOmittedLines = omitted,
                        )
                    }

                    is MdBlock.ImageCard -> {
                        val url = normalizeMarkdownImageUrl(block.url)
                        if (url.isNotBlank()) {
                            out += LongImageRenderNode.ImageBlock(
                                imageUrl = url,
                                alt = block.alt,
                            )
                        }
                    }

                    is MdBlock.ImageRow -> {
                        val validItems = block.items.mapNotNull { item ->
                            val url = normalizeMarkdownImageUrl(item.imageUrl)
                            if (url.isNotBlank()) item.copy(imageUrl = url) else null
                        }
                        if (validItems.isNotEmpty()) {
                            out += LongImageRenderNode.ImageRowBlock(validItems)
                        }
                    }

                    is MdBlock.ListContainer -> {
                        out += LongImageRenderNode.ListBlock(
                            items = block.items,
                            referenceLinks = doc.referenceLinks,
                            isQuoteChild = inQuote,
                        )
                    }

                    is MdBlock.Table -> {
                        out += LongImageRenderNode.TableBlock(
                            headers = block.headers,
                            alignments = block.alignments,
                            rows = block.rows,
                        )
                    }

                    is MdBlock.TabsContainer -> {
                        for (tab in block.tabs) {
                            out += LongImageRenderNode.SectionPill(
                                label = tab.title.ifBlank { "分页" },
                                isTab = true,
                            )
                            emitBlocks(tab.children, inQuote = inQuote, depth = depth)
                        }
                    }

                    is MdBlock.DetailsSpoiler -> {
                        out += LongImageRenderNode.SectionPill(
                            label = block.summary.ifBlank { "折叠内容" },
                            isTab = false,
                        )
                        emitBlocks(block.children, inQuote = inQuote, depth = depth)
                    }

                    is MdBlock.SpecialWidget -> {
                        val isVote = block.url.contains("vote", ignoreCase = true)
                        out += LongImageRenderNode.SpecialWidgetBlock(
                            title = block.title.ifBlank { if (isVote) "帖内互动投票" else "星尘投喂卡片" },
                            badgeLabel = if (isVote) "🗳 互动投票" else "✨ 星尘投喂",
                        )
                    }

                    MdBlock.HorizontalRule -> {
                        out += LongImageRenderNode.Divider
                    }
                }
            }
        }

        emitBlocks(doc.blocks, inQuote = isQuoteChild, depth = quoteDepth)
        return out
    }

    // ── Height Measurement ────────────────────────────────────────────────────

    private fun measureNodeHeightDp(
        node: LongImageRenderNode,
        assets: TopicLongImageAssets,
        measurer: LongImageTextMeasurer,
    ): Float = when (node) {
        is LongImageRenderNode.TopicHeroHeader -> {
            val titleH = measurer.measureTextHeightDp(
                text = node.title,
                widthDp = CONTENT_WIDTH_DP,
                textSp = 20f,
                bold = true,
                lineSpacingMult = 1.28f,
            )
            // Category pill + spacing + title + author card (52dp) + bottom separator
            val topMetaH = if (node.showHeaderMeta) 28f else 0f
            val bottomAuthorH = if (node.showAuthorCard) 14f + 56f + 12f else 10f
            topMetaH + titleH + bottomAuthorH
        }

        is LongImageRenderNode.HighlightReplyHeader -> 56f

        is LongImageRenderNode.Heading -> {
            val sp = headingTextSp(node.level)
            val plain = flattenTokensPlainText(tokenizeMarkdownInline(node.text, node.referenceLinks))
            measurer.measureTextHeightDp(
                text = plain,
                widthDp = CONTENT_WIDTH_DP - 12f,
                textSp = sp,
                bold = true,
                lineSpacingMult = 1.26f,
            ) + 8f
        }

        is LongImageRenderNode.InlineText -> {
            val w = if (node.isQuoteChild) CONTENT_WIDTH_DP - 24f else CONTENT_WIDTH_DP
            val plain = flattenTokensPlainText(node.tokens)
            val h = measurer.measureTextHeightDp(
                text = plain,
                widthDp = w,
                textSp = 14f,
                lineSpacingMult = 1.42f,
            )
            if (node.isQuoteChild) h + 16f else h + 2f
        }

        is LongImageRenderNode.ImageBlock -> {
            val bmp = assets.get(resolveAssetLookupUrl(node.imageUrl))
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                val aspect = bmp.height.toFloat() / bmp.width.toFloat()
                (CONTENT_WIDTH_DP * aspect).coerceIn(100f, 380f) + 6f
            } else {
                84f
            }
        }

        is LongImageRenderNode.ImageRowBlock -> {
            val count = node.items.size.coerceIn(1, 3)
            val cellW = (CONTENT_WIDTH_DP - (count - 1) * 8f) / count
            (cellW * 0.78f).coerceIn(90f, 180f) + 4f
        }

        is LongImageRenderNode.CodeBlock -> {
            val headerH = 28f
            val codeW = CONTENT_WIDTH_DP - 24f
            val bodyH = node.lines.sumOf { line ->
                val clean = if (node.isAnsi) AnsiAndCodeFormatter.stripAnsiEscapes(line) else line
                measurer.measureTextHeightDp(
                    text = clean.ifEmpty { " " },
                    widthDp = codeW,
                    textSp = 11.2f,
                    monospace = true,
                    lineSpacingMult = 1.32f,
                ).toDouble()
            }.toFloat().coerceAtLeast(20f)
            val extra = if (node.truncatedOmittedLines > 0) 22f else 0f
            headerH + 16f + bodyH + extra + 4f
        }

        is LongImageRenderNode.ListBlock -> {
            val w = if (node.isQuoteChild) CONTENT_WIDTH_DP - 44f else CONTENT_WIDTH_DP - 22f
            node.items.sumOf { item ->
                val plain = flattenTokensPlainText(tokenizeMarkdownInline(item.text, node.referenceLinks))
                (measurer.measureTextHeightDp(
                    text = plain,
                    widthDp = (w - item.indentLevel * 12f).coerceAtLeast(100f),
                    textSp = 13.8f,
                    lineSpacingMult = 1.36f,
                ) + 4f).toDouble()
            }.toFloat().coerceAtLeast(22f)
        }

        is LongImageRenderNode.TableBlock -> {
            val rowCount = (node.rows.size + 1).coerceAtLeast(1)
            rowCount * 30f + 8f
        }

        is LongImageRenderNode.SectionPill -> 32f

        is LongImageRenderNode.SpecialWidgetBlock -> 48f

        LongImageRenderNode.Divider -> 14f

        is LongImageRenderNode.SignatureSection -> {
            val plain = AnsiAndCodeFormatter.stripAnsiEscapes(node.rawMarkdown)
                .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "[图]")
                .trim()
            val h = measurer.measureTextHeightDp(
                text = plain.ifEmpty { "签名" },
                widthDp = CONTENT_WIDTH_DP - 20f,
                textSp = 11.5f,
                lineSpacingMult = 1.3f,
            )
            26f + h
        }
    }

    // ── Canvas Drawing ────────────────────────────────────────────────────────

    private fun drawPageSlice(
        canvas: Canvas,
        plan: TopicLongImagePresentationPlan,
        slice: LongImagePageSlice,
        palette: LongImagePalette,
        assets: TopicLongImageAssets,
        recordedTexts: MutableList<String>?,
    ) {
        val isDark = plan.themeStyle != LongImageThemeStyle.COOL_MIST_LIGHT
        val categorySpec = CategoryPalette.resolve(plan.categorySlug, isDark = isDark)
        val accentColor = categorySpec.spineColor.toArgb()

        // Outer canvas backdrop
        canvas.drawColor(palette.outerBg)

        val cardRect = RectF(
            OUTER_INSET_DP,
            OUTER_INSET_DP,
            LOGICAL_WIDTH_DP - OUTER_INSET_DP,
            slice.totalLogicalHeightDp - OUTER_INSET_DP,
        )

        // Main magazine card surface + border
        val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardSurface
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(cardRect, CARD_CORNER_DP, CARD_CORNER_DP, cardFillPaint)

        // Subtle category top wash
        if (slice.pageIndex == 1) {
            val washRect = RectF(cardRect.left, cardRect.top, cardRect.right, (cardRect.top + 132f).coerceAtMost(cardRect.bottom))
            val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    washRect.left,
                    washRect.top,
                    washRect.left,
                    washRect.bottom,
                    withAlpha(accentColor, if (isDark) 0.18f else 0.10f),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.save()
            val clipPath = Path().apply { addRoundRect(cardRect, CARD_CORNER_DP, CARD_CORNER_DP, Path.Direction.CW) }
            canvas.clipPath(clipPath)
            canvas.drawRect(washRect, washPaint)
            canvas.restore()
        }

        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardBorder
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
        }
        canvas.drawRoundRect(cardRect, CARD_CORNER_DP, CARD_CORNER_DP, cardBorderPaint)

        val leftX = cardRect.left + CARD_PAD_H_DP
        val rightX = cardRect.right - CARD_PAD_H_DP
        var cursorY = cardRect.top + CARD_PAD_V_DP

        // Continuation header for page 2+
        if (slice.pageIndex > 1) {
            drawContinuationHeader(
                canvas = canvas,
                plan = plan,
                slice = slice,
                leftX = leftX,
                rightX = rightX,
                topY = cursorY,
                palette = palette,
                accentColor = accentColor,
                recordedTexts = recordedTexts,
            )
            cursorY += CONTINUATION_HEADER_DP
        }

        for ((idx, measured) in slice.blocks.withIndex()) {
            drawNode(
                canvas = canvas,
                node = measured.node,
                measuredHeightDp = measured.heightDp,
                leftX = leftX,
                rightX = rightX,
                topY = cursorY,
                palette = palette,
                accentColor = accentColor,
                isDark = isDark,
                assets = assets,
                recordedTexts = recordedTexts,
            )
            cursorY += measured.heightDp + if (idx < slice.blocks.lastIndex) BLOCK_GAP_DP else 0f
        }

        val footerTop = cardRect.bottom - CARD_PAD_V_DP - if (slice.includeQrFooter) QR_FOOTER_HEIGHT_DP else SIMPLE_FOOTER_HEIGHT_DP
        drawFooter(
            canvas = canvas,
            plan = plan,
            slice = slice,
            leftX = leftX,
            rightX = rightX,
            topY = footerTop.coerceAtLeast(cursorY + 8f),
            palette = palette,
            accentColor = accentColor,
            recordedTexts = recordedTexts,
        )
    }

    private fun drawContinuationHeader(
        canvas: Canvas,
        plan: TopicLongImagePresentationPlan,
        slice: LongImagePageSlice,
        leftX: Float,
        rightX: Float,
        topY: Float,
        palette: LongImagePalette,
        accentColor: Int,
        recordedTexts: MutableList<String>?,
    ) {
        val label = "${plan.categoryTitle} · ${plan.title}"
        val pageBadge = "第 ${slice.pageIndex}/${slice.totalPages} 页"
        drawSingleLineText(
            canvas = canvas,
            text = label,
            x = leftX,
            centerY = topY + 14f,
            maxWidth = (rightX - leftX) - 74f,
            textSp = 12f,
            color = palette.onSurfaceVariant,
            bold = true,
            recordedTexts = recordedTexts,
        )
        drawPill(
            canvas = canvas,
            text = pageBadge,
            rightX = rightX,
            topY = topY + 3f,
            bgColor = withAlpha(accentColor, 0.16f),
            fgColor = accentColor,
            textSp = 10.5f,
            recordedTexts = recordedTexts,
        )
        drawHairline(canvas, leftX, rightX, topY + 32f, palette.outlineVariant)
    }

    private fun drawNode(
        canvas: Canvas,
        node: LongImageRenderNode,
        measuredHeightDp: Float,
        leftX: Float,
        rightX: Float,
        topY: Float,
        palette: LongImagePalette,
        accentColor: Int,
        isDark: Boolean,
        assets: TopicLongImageAssets,
        recordedTexts: MutableList<String>?,
    ) {
        val width = rightX - leftX
        when (node) {
            is LongImageRenderNode.TopicHeroHeader -> {
                var y = topY
                if (node.showHeaderMeta) {
                    // Top category pill + floor pill
                    var pillX = leftX
                    val catBg = CategoryPalette.resolve(node.categorySlug, isDark).containerColor.toArgb()
                    val catFg = CategoryPalette.resolve(node.categorySlug, isDark).contentColor.toArgb()
                    pillX += drawPillLeft(canvas, node.categoryTitle, pillX, y, catBg, catFg, 11f, recordedTexts) + 6f
                    if (node.floorNumber > 0) {
                        drawPillLeft(
                            canvas,
                            "#${node.floorNumber}楼",
                            pillX,
                            y,
                            palette.primaryContainer,
                            palette.onPrimaryContainer,
                            11f,
                            recordedTexts,
                        )
                    } else {
                        drawPillLeft(
                            canvas,
                            "主楼 · 原帖",
                            pillX,
                            y,
                            palette.surfaceHigh,
                            palette.onSurfaceVariant,
                            11f,
                            recordedTexts,
                        )
                    }
                    y += 28f
                }

                // Title
                val titleH = drawStaticText(
                    canvas = canvas,
                    text = node.title,
                    x = leftX,
                    topY = y,
                    width = width,
                    textSp = 20f,
                    color = palette.onSurface,
                    bold = true,
                    lineSpacingMult = 1.28f,
                    recordedTexts = recordedTexts,
                )
                y += titleH + 12f

                if (node.showAuthorCard) {
                    // Author card
                    drawAuthorCard(
                        canvas = canvas,
                        author = node.author,
                        metaRight = buildList {
                            if (node.createdAtIso.isNotBlank()) add(node.createdAtIso.take(10))
                            if (node.viewCount > 0) add("${node.viewCount} 浏览")
                            if (node.bookmarkCount > 0) add("${node.bookmarkCount} 收藏")
                        }.joinToString(" · "),
                        badgeText = if (node.floorNumber <= 0) "OP 楼主" else "#${node.floorNumber}",
                        leftX = leftX,
                        rightX = rightX,
                        topY = y,
                        palette = palette,
                        isDark = isDark,
                        assets = assets,
                        recordedTexts = recordedTexts,
                    )
                    drawHairline(canvas, leftX, rightX, topY + measuredHeightDp - 2f, palette.outlineVariant)
                }
            }

            is LongImageRenderNode.HighlightReplyHeader -> {
                drawAuthorCard(
                    canvas = canvas,
                    author = node.author,
                    metaRight = node.createdAtIso.take(10),
                    badgeText = "#${node.floorNumber} ${node.badgeText}",
                    leftX = leftX,
                    rightX = rightX,
                    topY = topY + 2f,
                    palette = palette,
                    isDark = isDark,
                    assets = assets,
                    recordedTexts = recordedTexts,
                )
            }

            is LongImageRenderNode.Heading -> {
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
                val sp = headingTextSp(node.level)
                val barW = if (node.level <= 2) 4f else 2.5f
                val plain = flattenTokensPlainText(tokenizeMarkdownInline(node.text, node.referenceLinks))
                canvas.drawRoundRect(
                    RectF(leftX, topY + 3f, leftX + barW, topY + measuredHeightDp - 5f),
                    2f,
                    2f,
                    barPaint,
                )
                drawStaticText(
                    canvas = canvas,
                    text = plain,
                    x = leftX + 10f,
                    topY = topY + 2f,
                    width = width - 10f,
                    textSp = sp,
                    color = palette.onSurface,
                    bold = true,
                    lineSpacingMult = 1.26f,
                    recordedTexts = recordedTexts,
                )
            }

            is LongImageRenderNode.InlineText -> {
                var drawX = leftX
                var drawW = width
                var drawY = topY
                if (node.isQuoteChild) {
                    val bgRect = RectF(leftX, topY, rightX, topY + measuredHeightDp)
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceLow }
                    canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)
                    val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
                    canvas.drawRoundRect(RectF(leftX, topY, leftX + 3.5f, topY + measuredHeightDp), 2f, 2f, stripePaint)
                    drawX = leftX + 12f
                    drawW = width - 20f
                    drawY = topY + 8f
                }
                val spanned = buildSpannableFromTokens(
                    tokens = node.tokens,
                    palette = palette,
                    accentColor = accentColor,
                    assets = assets,
                    recordedTexts = recordedTexts,
                )
                drawSpannedStaticText(
                    canvas = canvas,
                    spanned = spanned,
                    x = drawX,
                    topY = drawY,
                    width = drawW,
                    textSp = 14f,
                    color = if (node.isQuoteChild) palette.onSurfaceVariant else palette.onSurface,
                    lineSpacingMult = 1.42f,
                )
            }

            is LongImageRenderNode.ImageBlock -> {
                val bmp = assets.get(resolveAssetLookupUrl(node.imageUrl))
                val boxRect = RectF(leftX, topY, rightX, topY + measuredHeightDp - 4f)
                if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                    canvas.save()
                    val clip = Path().apply { addRoundRect(boxRect, 12f, 12f, Path.Direction.CW) }
                    canvas.clipPath(clip)
                    canvas.drawBitmap(bmp, null, boxRect, Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.restore()
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.outlineVariant
                        style = Paint.Style.STROKE
                        strokeWidth = 0.8f
                    }
                    canvas.drawRoundRect(boxRect, 12f, 12f, borderPaint)
                } else {
                    drawImagePlaceholder(canvas, boxRect, node.alt, node.imageUrl, palette, recordedTexts)
                }
            }

            is LongImageRenderNode.ImageRowBlock -> {
                val count = node.items.size.coerceIn(1, 3)
                val gap = 8f
                val cellW = (width - (count - 1) * gap) / count
                val cellH = measuredHeightDp - 4f
                node.items.take(count).forEachIndexed { idx, item ->
                    val cx = leftX + idx * (cellW + gap)
                    val cellRect = RectF(cx, topY, cx + cellW, topY + cellH)
                    val bmp = assets.get(resolveAssetLookupUrl(item.imageUrl))
                    if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                        canvas.save()
                        val clip = Path().apply { addRoundRect(cellRect, 10f, 10f, Path.Direction.CW) }
                        canvas.clipPath(clip)
                        canvas.drawBitmap(bmp, null, cellRect, Paint(Paint.FILTER_BITMAP_FLAG))
                        canvas.restore()
                    } else {
                        drawImagePlaceholder(canvas, cellRect, item.alt, item.imageUrl, palette, recordedTexts)
                    }
                }
            }

            is LongImageRenderNode.CodeBlock -> {
                val boxRect = RectF(leftX, topY, rightX, topY + measuredHeightDp)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.codeSurface }
                canvas.drawRoundRect(boxRect, 12f, 12f, bgPaint)

                val headerRect = RectF(leftX, topY, rightX, topY + 28f)
                val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.codeHeaderSurface }
                canvas.save()
                val clip = Path().apply { addRoundRect(boxRect, 12f, 12f, Path.Direction.CW) }
                canvas.clipPath(clip)
                canvas.drawRect(headerRect, headerPaint)
                canvas.restore()

                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.codeBorder
                    style = Paint.Style.STROKE
                    strokeWidth = 0.8f
                }
                canvas.drawRoundRect(boxRect, 12f, 12f, borderPaint)

                val langLabel = node.language.ifBlank { if (node.isAnsi) "TERMINAL / ANSI" else "CODE" }
                    .uppercase(Locale.US)
                drawSingleLineText(
                    canvas = canvas,
                    text = langLabel,
                    x = leftX + 12f,
                    centerY = topY + 14f,
                    maxWidth = width - 24f,
                    textSp = 10.5f,
                    color = palette.onCodeMuted,
                    bold = true,
                    recordedTexts = recordedTexts,
                )

                var codeY = topY + 35f
                val codeW = width - 24f
                var ansiState = AnsiSgrState.Default
                val defaultFgCompose = androidx.compose.ui.graphics.Color(palette.onCodeSurface)
                for (line in node.lines) {
                    val spannable = if (node.isAnsi) {
                        val (annotated, nextState) = AnsiAndCodeFormatter.parseAnsiLine(
                            rawText = line,
                            initialState = ansiState,
                            defaultForeground = defaultFgCompose,
                        )
                        ansiState = nextState
                        annotatedStringToSpannable(annotated, recordedTexts)
                    } else {
                        val highlighted = AnsiAndCodeFormatter.highlightSourceCode(
                            code = line,
                            defaultForeground = defaultFgCompose,
                        )
                        annotatedStringToSpannable(highlighted, recordedTexts)
                    }
                    val h = drawSpannedStaticText(
                        canvas = canvas,
                        spanned = if (spannable.isEmpty()) SpannableStringBuilder(" ") else spannable,
                        x = leftX + 12f,
                        topY = codeY,
                        width = codeW,
                        textSp = 11.2f,
                        color = palette.onCodeSurface,
                        monospace = true,
                        lineSpacingMult = 1.32f,
                    )
                    codeY += h
                }
                if (node.truncatedOmittedLines > 0) {
                    val notice = "... (共省略 ${node.truncatedOmittedLines} 行，完整内容请扫码查看原帖)"
                    drawSingleLineText(
                        canvas = canvas,
                        text = notice,
                        x = leftX + 12f,
                        centerY = codeY + 10f,
                        maxWidth = codeW,
                        textSp = 10.5f,
                        color = palette.onCodeMuted,
                        bold = false,
                        recordedTexts = recordedTexts,
                    )
                }
            }

            is LongImageRenderNode.ListBlock -> {
                var y = topY
                for (item in node.items) {
                    val indent = item.indentLevel * 12f
                    val bullet = when {
                        item.checked == true -> "☑"
                        item.checked == false -> "☐"
                        item.orderNumber != null -> "${item.orderNumber}."
                        else -> "•"
                    }
                    drawSingleLineText(
                        canvas = canvas,
                        text = bullet,
                        x = leftX + indent,
                        centerY = y + 9f,
                        maxWidth = 20f,
                        textSp = 13.5f,
                        color = accentColor,
                        bold = true,
                        recordedTexts = recordedTexts,
                    )
                    val tokens = tokenizeMarkdownInline(item.text, node.referenceLinks)
                    val spanned = buildSpannableFromTokens(
                        tokens = tokens,
                        palette = palette,
                        accentColor = accentColor,
                        assets = assets,
                        recordedTexts = recordedTexts,
                    )
                    val itemW = (width - indent - 22f).coerceAtLeast(80f)
                    val h = drawSpannedStaticText(
                        canvas = canvas,
                        spanned = spanned,
                        x = leftX + indent + 22f,
                        topY = y,
                        width = itemW,
                        textSp = 13.8f,
                        color = palette.onSurface,
                        lineSpacingMult = 1.36f,
                    )
                    y += h + 4f
                }
            }

            is LongImageRenderNode.TableBlock -> {
                val cols = max(node.headers.size, node.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
                val colW = width / cols
                val rowH = 30f
                val tableRect = RectF(leftX, topY, rightX, topY + (node.rows.size + 1) * rowH)
                val headerRect = RectF(leftX, topY, rightX, topY + rowH)
                canvas.drawRect(headerRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceHigh })
                node.headers.take(cols).forEachIndexed { c, title ->
                    drawSingleLineText(
                        canvas = canvas,
                        text = title,
                        x = leftX + c * colW + 8f,
                        centerY = topY + rowH / 2f,
                        maxWidth = colW - 14f,
                        textSp = 11.5f,
                        color = palette.onSurface,
                        bold = true,
                        recordedTexts = recordedTexts,
                    )
                }
                node.rows.forEachIndexed { rIdx, row ->
                    val ry = topY + (rIdx + 1) * rowH
                    if (rIdx % 2 == 1) {
                        canvas.drawRect(
                            RectF(leftX, ry, rightX, ry + rowH),
                            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceLow },
                        )
                    }
                    row.take(cols).forEachIndexed { c, cell ->
                        drawSingleLineText(
                            canvas = canvas,
                            text = cell,
                            x = leftX + c * colW + 8f,
                            centerY = ry + rowH / 2f,
                            maxWidth = colW - 14f,
                            textSp = 11.2f,
                            color = palette.onSurfaceVariant,
                            bold = false,
                            recordedTexts = recordedTexts,
                        )
                    }
                }
                canvas.drawRoundRect(
                    tableRect,
                    8f,
                    8f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.outlineVariant
                        style = Paint.Style.STROKE
                        strokeWidth = 0.8f
                    },
                )
            }

            is LongImageRenderNode.SectionPill -> {
                val text = if (node.isTab) "[${node.label}]" else "▸ ${node.label}"
                drawPillLeft(
                    canvas = canvas,
                    text = text,
                    leftX = leftX,
                    topY = topY + 3f,
                    bgColor = withAlpha(accentColor, 0.14f),
                    fgColor = accentColor,
                    textSp = 11.5f,
                    recordedTexts = recordedTexts,
                )
            }

            is LongImageRenderNode.SpecialWidgetBlock -> {
                val rect = RectF(leftX, topY, rightX, topY + measuredHeightDp)
                canvas.drawRoundRect(rect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceLow })
                canvas.drawRoundRect(
                    rect,
                    12f,
                    12f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = withAlpha(accentColor, 0.35f)
                        style = Paint.Style.STROKE
                        strokeWidth = 0.9f
                    },
                )
                val pillW = drawPillLeft(
                    canvas = canvas,
                    text = node.badgeLabel,
                    leftX = leftX + 12f,
                    topY = topY + 12f,
                    bgColor = palette.primaryContainer,
                    fgColor = palette.onPrimaryContainer,
                    textSp = 11f,
                    recordedTexts = recordedTexts,
                )
                drawSingleLineText(
                    canvas = canvas,
                    text = node.title,
                    x = leftX + 20f + pillW,
                    centerY = topY + measuredHeightDp / 2f,
                    maxWidth = width - pillW - 32f,
                    textSp = 13f,
                    color = palette.onSurface,
                    bold = true,
                    recordedTexts = recordedTexts,
                )
            }

            LongImageRenderNode.Divider -> {
                drawHairline(canvas, leftX, rightX, topY + measuredHeightDp / 2f, palette.outlineVariant)
            }

            is LongImageRenderNode.SignatureSection -> {
                val rect = RectF(leftX, topY, rightX, topY + measuredHeightDp)
                canvas.drawRoundRect(rect, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceLow })
                val sigText = AnsiAndCodeFormatter.stripAnsiEscapes(node.rawMarkdown)
                    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "[图片]")
                    .trim()
                    .ifEmpty { "签名档" }
                drawSingleLineText(
                    canvas = canvas,
                    text = "签名 · SIGNATURE",
                    x = leftX + 10f,
                    centerY = topY + 11f,
                    maxWidth = width - 20f,
                    textSp = 9.5f,
                    color = palette.onSurfaceMuted,
                    bold = true,
                    recordedTexts = recordedTexts,
                )
                drawStaticText(
                    canvas = canvas,
                    text = sigText,
                    x = leftX + 10f,
                    topY = topY + 20f,
                    width = width - 20f,
                    textSp = 11.5f,
                    color = palette.onSurfaceVariant,
                    bold = false,
                    lineSpacingMult = 1.3f,
                    recordedTexts = recordedTexts,
                )
            }
        }
    }

    private fun drawAuthorCard(
        canvas: Canvas,
        author: FloorAuthor,
        metaRight: String,
        badgeText: String,
        leftX: Float,
        rightX: Float,
        topY: Float,
        palette: LongImagePalette,
        isDark: Boolean,
        assets: TopicLongImageAssets,
        recordedTexts: MutableList<String>?,
    ) {
        val avatarSize = 38f
        val avatarCenterX = leftX + avatarSize / 2f
        val avatarCenterY = topY + avatarSize / 2f + 2f
        val level = (author.level ?: 0).coerceIn(0, 6)
        val rankSpec = RankPalette.forLevel(level, isDark = isDark)

        // Rank gradient ring
        val ringColors = rankSpec.gradientColors.map { it.toArgb() }.toIntArray()
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            if (ringColors.size >= 2) {
                shader = LinearGradient(
                    leftX,
                    topY,
                    leftX + avatarSize,
                    topY + avatarSize,
                    ringColors,
                    null,
                    Shader.TileMode.CLAMP,
                )
            } else {
                color = rankSpec.contentColor.toArgb()
            }
        }
        canvas.drawCircle(avatarCenterX, avatarCenterY, avatarSize / 2f + 1.5f, ringPaint)

        // Avatar bitmap or initial monogram
        val avatarUrl = author.avatarUrl?.let(::normalizeMarkdownImageUrl)?.takeIf { it.isNotBlank() }
        val avatarBmp = avatarUrl?.let { assets.get(it) }
        val avatarRect = RectF(leftX + 1.5f, topY + 3.5f, leftX + avatarSize - 1.5f, topY + avatarSize + 0.5f)
        if (avatarBmp != null && !avatarBmp.isRecycled && avatarBmp.width > 0 && avatarBmp.height > 0) {
            canvas.save()
            val clip = Path().apply { addOval(avatarRect, Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawBitmap(avatarBmp, null, avatarRect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            canvas.drawOval(avatarRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rankSpec.containerColor.toArgb() })
            val initial = author.username.trim().take(1).uppercase(Locale.US).ifEmpty { "N" }
            drawCenteredText(
                canvas = canvas,
                text = initial,
                centerX = avatarCenterX,
                centerY = avatarCenterY,
                textSp = 15f,
                color = rankSpec.contentColor.toArgb(),
                bold = true,
                recordedTexts = recordedTexts,
            )
        }

        val infoX = leftX + avatarSize + 10f
        var rowX = infoX
        val nameW = drawSingleLineText(
            canvas = canvas,
            text = author.username.ifBlank { "匿名用户" },
            x = rowX,
            centerY = topY + 13f,
            maxWidth = (rightX - infoX) * 0.52f,
            textSp = 14f,
            color = palette.onSurface,
            bold = true,
            recordedTexts = recordedTexts,
        )
        rowX += nameW + 6f
        rowX += drawPillLeft(
            canvas = canvas,
            text = "Lv.$level",
            leftX = rowX,
            topY = topY + 4f,
            bgColor = rankSpec.containerColor.toArgb(),
            fgColor = rankSpec.contentColor.toArgb(),
            textSp = 10f,
            recordedTexts = recordedTexts,
        ) + 5f
        drawPillLeft(
            canvas = canvas,
            text = badgeText,
            leftX = rowX,
            topY = topY + 4f,
            bgColor = palette.primaryContainer,
            fgColor = palette.onPrimaryContainer,
            textSp = 10f,
            recordedTexts = recordedTexts,
        )

        if (metaRight.isNotBlank()) {
            drawSingleLineText(
                canvas = canvas,
                text = metaRight,
                x = infoX,
                centerY = topY + 32f,
                maxWidth = rightX - infoX,
                textSp = 11f,
                color = palette.onSurfaceMuted,
                bold = false,
                recordedTexts = recordedTexts,
            )
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        plan: TopicLongImagePresentationPlan,
        slice: LongImagePageSlice,
        leftX: Float,
        rightX: Float,
        topY: Float,
        palette: LongImagePalette,
        accentColor: Int,
        recordedTexts: MutableList<String>?,
    ) {
        drawHairline(canvas, leftX, rightX, topY, palette.outlineVariant)
        val pageLabel = if (slice.totalPages > 1) "第 ${slice.pageIndex}/${slice.totalPages} 页 · " else ""
        val qr = plan.qrMatrix
        if (slice.includeQrFooter && qr != null) {
            val qrBoxSize = 96f
            val qrLeft = rightX - qrBoxSize
            val qrTop = topY + 14f
            val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrTop + qrBoxSize)
            canvas.drawRoundRect(qrBgRect, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            canvas.drawRoundRect(
                qrBgRect,
                10f,
                10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.outlineVariant
                    style = Paint.Style.STROKE
                    strokeWidth = 0.8f
                },
            )
            drawQrMatrix(canvas, qr, qrLeft + 6f, qrTop + 6f, qrBoxSize - 12f)

            val textWidth = (qrLeft - leftX - 14f).coerceAtLeast(100f)
            drawSingleLineText(
                canvas = canvas,
                text = "Anode · NodeSeek 长图分享",
                x = leftX,
                centerY = qrTop + 22f,
                maxWidth = textWidth,
                textSp = 13.5f,
                color = accentColor,
                bold = true,
                recordedTexts = recordedTexts,
            )
            drawSingleLineText(
                canvas = canvas,
                text = "${pageLabel}长按或扫码直达原帖参与讨论",
                x = leftX,
                centerY = qrTop + 44f,
                maxWidth = textWidth,
                textSp = 11.5f,
                color = palette.onSurfaceVariant,
                bold = false,
                recordedTexts = recordedTexts,
            )
            drawStaticText(
                canvas = canvas,
                text = plan.topicUrl,
                x = leftX,
                topY = qrTop + 58f,
                width = textWidth,
                textSp = 10.2f,
                color = palette.onSurfaceMuted,
                bold = false,
                lineSpacingMult = 1.25f,
                recordedTexts = recordedTexts,
            )
        } else {
            drawSingleLineText(
                canvas = canvas,
                text = "Anode · ${pageLabel}NodeSeek 杂志长图",
                x = leftX,
                centerY = topY + 22f,
                maxWidth = (rightX - leftX) * 0.65f,
                textSp = 11.5f,
                color = palette.onSurfaceVariant,
                bold = true,
                recordedTexts = recordedTexts,
            )
            drawSingleLineText(
                canvas = canvas,
                text = plan.topicUrl,
                x = leftX + (rightX - leftX) * 0.66f,
                centerY = topY + 22f,
                maxWidth = (rightX - leftX) * 0.34f,
                textSp = 10f,
                color = palette.onSurfaceMuted,
                bold = false,
                recordedTexts = recordedTexts,
            )
        }
    }

    private fun drawQrMatrix(
        canvas: Canvas,
        matrix: QrBitMatrix,
        left: Float,
        top: Float,
        size: Float,
    ) {
        if (matrix.width <= 0 || matrix.height <= 0) return
        val cellW = size / matrix.width.toFloat()
        val cellH = size / matrix.height.toFloat()
        val darkPaint = Paint().apply {
            color = 0xFF111418.toInt()
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        for (y in 0 until matrix.height) {
            val cy = top + y * cellH
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    val cx = left + x * cellW
                    canvas.drawRect(cx, cy, cx + cellW + 0.05f, cy + cellH + 0.05f, darkPaint)
                }
            }
        }
    }

    private fun drawImagePlaceholder(
        canvas: Canvas,
        rect: RectF,
        alt: String,
        url: String,
        palette: LongImagePalette,
        recordedTexts: MutableList<String>?,
    ) {
        canvas.drawRoundRect(rect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceLow })
        canvas.drawRoundRect(
            rect,
            12f,
            12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.outlineVariant
                style = Paint.Style.STROKE
                strokeWidth = 0.8f
            },
        )
        val label = alt.ifBlank { "帖子图片" }
        drawSingleLineText(
            canvas = canvas,
            text = "🖼 $label",
            x = rect.left + 12f,
            centerY = rect.centerY() - 8f,
            maxWidth = rect.width() - 24f,
            textSp = 12f,
            color = palette.onSurfaceVariant,
            bold = true,
            recordedTexts = recordedTexts,
        )
        drawSingleLineText(
            canvas = canvas,
            text = url,
            x = rect.left + 12f,
            centerY = rect.centerY() + 10f,
            maxWidth = rect.width() - 24f,
            textSp = 10f,
            color = palette.onSurfaceMuted,
            bold = false,
            recordedTexts = recordedTexts,
        )
    }

    // ── Inline Token -> Spannable & Plain Text Helpers ────────────────────────

    private fun buildSpannableFromTokens(
        tokens: List<MdInlineToken>,
        palette: LongImagePalette,
        accentColor: Int,
        assets: TopicLongImageAssets,
        recordedTexts: MutableList<String>?,
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()

        fun appendTokens(list: List<MdInlineToken>) {
            for (token in list) {
                when (token) {
                    is MdInlineToken.PlainText -> sb.append(token.text)
                    is MdInlineToken.CodeSpan -> {
                        val start = sb.length
                        sb.append(" ${token.code} ")
                        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(BackgroundColorSpan(palette.surfaceHigh), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(accentColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    is MdInlineToken.Link -> {
                        val start = sb.length
                        appendTokens(token.labelTokens)
                        if (sb.length > start) {
                            sb.setSpan(ForegroundColorSpan(accentColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is MdInlineToken.Mention -> {
                        val start = sb.length
                        sb.append("@${token.username}")
                        sb.setSpan(ForegroundColorSpan(accentColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    is MdInlineToken.FloorRef -> {
                        val start = sb.length
                        sb.append("#${token.floor}")
                        sb.setSpan(ForegroundColorSpan(accentColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    is MdInlineToken.Sticker -> {
                        appendStickerSpan(sb, token.sticker, assets, accentColor, palette)
                    }
                    is MdInlineToken.Image -> {
                        val sticker = NodeSeekStickerCatalog.resolveFromAssetUrl(token.url)
                        if (sticker != null) {
                            appendStickerSpan(sb, sticker, assets, accentColor, palette)
                        } else {
                            sb.append(token.alt.ifBlank { "[图片]" })
                        }
                    }
                    is MdInlineToken.Styled -> {
                        val start = sb.length
                        appendTokens(token.children)
                        val end = sb.length
                        if (end > start) {
                            when {
                                token.bold && token.italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                token.bold -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                token.italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (token.strikethrough) {
                                sb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (token.underline) {
                                sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (token.isKbd) {
                                sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                sb.setSpan(BackgroundColorSpan(palette.surfaceHigh), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                    }
                    MdInlineToken.LineBreak -> sb.append('\n')
                }
            }
        }

        appendTokens(tokens)
        if (recordedTexts != null) {
            val plain = flattenTokensPlainText(tokens)
            if (plain.isNotBlank()) {
                recordedTexts += plain
            }
        }
        return sb
    }

    private fun appendStickerSpan(
        sb: SpannableStringBuilder,
        sticker: NodeSeekSticker,
        assets: TopicLongImageAssets,
        accentColor: Int,
        palette: LongImagePalette,
    ) {
        val bmp = assets.get(sticker.staticPreviewUrl) ?: assets.get(sticker.imageUrl)
        val start = sb.length
        val marker = ":${sticker.code}:"
        sb.append(marker)
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            val drawable = android.graphics.drawable.BitmapDrawable(null, bmp).apply {
                setBounds(0, 0, 22, 22)
            }
            sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.setSpan(BackgroundColorSpan(palette.surfaceHigh), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(accentColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun annotatedStringToSpannable(
        annotated: androidx.compose.ui.text.AnnotatedString,
        recordedTexts: MutableList<String>?,
    ): SpannableStringBuilder {
        val text = annotated.text
        if (recordedTexts != null && text.isNotBlank()) {
            recordedTexts += text
        }
        val sb = SpannableStringBuilder(text)
        for (span in annotated.spanStyles) {
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (start >= end) continue
            val st = span.item
            if (st.color != androidx.compose.ui.graphics.Color.Unspecified) {
                sb.setSpan(ForegroundColorSpan(st.color.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (st.background != androidx.compose.ui.graphics.Color.Unspecified) {
                sb.setSpan(BackgroundColorSpan(st.background.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val weight = st.fontWeight
            val isBold = weight != null && weight >= FontWeight.SemiBold
            val isItalic = st.fontStyle == FontStyle.Italic
            if (isBold && isItalic) {
                sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (isBold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (isItalic) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (st.textDecoration == TextDecoration.Underline) {
                sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun flattenTokensPlainText(tokens: List<MdInlineToken>): String {
        val sb = StringBuilder()
        fun walk(list: List<MdInlineToken>) {
            for (token in list) {
                when (token) {
                    is MdInlineToken.PlainText -> sb.append(token.text)
                    is MdInlineToken.CodeSpan -> sb.append(token.code)
                    is MdInlineToken.Link -> walk(token.labelTokens)
                    is MdInlineToken.Mention -> sb.append("@${token.username}")
                    is MdInlineToken.FloorRef -> sb.append("#${token.floor}")
                    is MdInlineToken.Sticker -> sb.append(":${token.sticker.code}:")
                    is MdInlineToken.Image -> {
                        val st = NodeSeekStickerCatalog.resolveFromAssetUrl(token.url)
                        sb.append(st?.let { ":${it.code}:" } ?: token.alt.ifBlank { "[图片]" })
                    }
                    is MdInlineToken.Styled -> walk(token.children)
                    MdInlineToken.LineBreak -> sb.append('\n')
                }
            }
        }
        walk(tokens)
        return sb.toString()
    }

    private fun resolveAssetLookupUrl(rawNormalizedUrl: String): String {
        val sticker = NodeSeekStickerCatalog.resolveFromAssetUrl(rawNormalizedUrl)
        return sticker?.staticPreviewUrl ?: rawNormalizedUrl
    }

    private fun headingTextSp(level: Int): Float = when (level) {
        1 -> 18.5f
        2 -> 17f
        3 -> 15.8f
        4 -> 14.8f
        else -> 14f
    }

    // ── Text Drawing Primitives ───────────────────────────────────────────────

    private fun drawStaticText(
        canvas: Canvas,
        text: String,
        x: Float,
        topY: Float,
        width: Float,
        textSp: Float,
        color: Int,
        bold: Boolean = false,
        lineSpacingMult: Float = 1.35f,
        recordedTexts: MutableList<String>?,
    ): Float {
        if (recordedTexts != null && text.isNotBlank()) {
            recordedTexts += text
        }
        if (text.isEmpty()) return 0f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = textSp
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val safeW = width.roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, safeW)
            ?.setAlignment(Layout.Alignment.ALIGN_NORMAL)
            ?.setLineSpacing(0f, lineSpacingMult)
            ?.setIncludePad(false)
            ?.build()
        if (layout != null) {
            canvas.save()
            canvas.translate(x, topY)
            layout.draw(canvas)
            canvas.restore()
            val measured = layout.height.toFloat()
            if (measured > 0f) return measured
        }
        return estimateJvmTextHeightDp(text, width, textSp, lineSpacingMult)
    }

    private fun drawSpannedStaticText(
        canvas: Canvas,
        spanned: CharSequence,
        x: Float,
        topY: Float,
        width: Float,
        textSp: Float,
        color: Int,
        monospace: Boolean = false,
        lineSpacingMult: Float = 1.35f,
    ): Float {
        if (spanned.isEmpty()) return 0f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = textSp
            this.color = color
            typeface = if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        }
        val safeW = width.roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(spanned, 0, spanned.length, paint, safeW)
            ?.setAlignment(Layout.Alignment.ALIGN_NORMAL)
            ?.setLineSpacing(0f, lineSpacingMult)
            ?.setIncludePad(false)
            ?.build()
        if (layout != null) {
            canvas.save()
            canvas.translate(x, topY)
            layout.draw(canvas)
            canvas.restore()
            val measured = layout.height.toFloat()
            if (measured > 0f) return measured
        }
        return estimateJvmTextHeightDp(spanned.toString().orEmpty(), width, textSp, lineSpacingMult)
    }

    private fun drawSingleLineText(
        canvas: Canvas,
        text: String,
        x: Float,
        centerY: Float,
        maxWidth: Float,
        textSp: Float,
        color: Int,
        bold: Boolean,
        recordedTexts: MutableList<String>?,
    ): Float {
        if (recordedTexts != null && text.isNotBlank()) {
            recordedTexts += text
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = textSp
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val fitted = ellipsizeToWidth(text, paint, maxWidth)
        val fm = paint.fontMetrics
        val baseline = if (fm != null) centerY - (fm.ascent + fm.descent) / 2f else centerY
        canvas.drawText(fitted, x, baseline, paint)
        val measuredW = paint.measureText(fitted)
        return if (measuredW > 0f) measuredW else (fitted.length * textSp * 0.55f).coerceAtMost(maxWidth)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textSp: Float,
        color: Int,
        bold: Boolean,
        recordedTexts: MutableList<String>?,
    ) {
        if (recordedTexts != null && text.isNotBlank()) {
            recordedTexts += text
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = textSp
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        val baseline = if (fm != null) centerY - (fm.ascent + fm.descent) / 2f else centerY
        canvas.drawText(text, centerX, baseline, paint)
    }

    private fun drawPillLeft(
        canvas: Canvas,
        text: String,
        leftX: Float,
        topY: Float,
        bgColor: Int,
        fgColor: Int,
        textSp: Float,
        recordedTexts: MutableList<String>?,
    ): Float {
        if (recordedTexts != null && text.isNotBlank()) {
            recordedTexts += text
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSp
            typeface = Typeface.DEFAULT_BOLD
            color = fgColor
        }
        val textW = paint.measureText(text).takeIf { it > 0f } ?: (text.length * textSp * 0.65f)
        val pillW = textW + 16f
        val pillH = textSp + 9f
        val rect = RectF(leftX, topY, leftX + pillW, topY + pillH)
        canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor })
        val fm = paint.fontMetrics
        val baseline = if (fm != null) rect.centerY() - (fm.ascent + fm.descent) / 2f else rect.centerY()
        canvas.drawText(text, leftX + 8f, baseline, paint)
        return pillW
    }

    private fun drawPill(
        canvas: Canvas,
        text: String,
        rightX: Float,
        topY: Float,
        bgColor: Int,
        fgColor: Int,
        textSp: Float,
        recordedTexts: MutableList<String>?,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSp
            typeface = Typeface.DEFAULT_BOLD
        }
        val textW = paint.measureText(text).takeIf { it > 0f } ?: (text.length * textSp * 0.65f)
        val pillW = textW + 16f
        drawPillLeft(canvas, text, rightX - pillW, topY, bgColor, fgColor, textSp, recordedTexts)
    }

    private fun drawHairline(canvas: Canvas, leftX: Float, rightX: Float, y: Float, color: Int) {
        canvas.drawLine(
            leftX,
            y,
            rightX,
            y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = 0.8f
            },
        )
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || text.isEmpty()) return ""
        val measured = paint.measureText(text)
        if (measured <= 0f || measured <= maxWidth) return text
        val ellipsis = "…"
        val budget = (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(0f)
        val count = paint.breakText(text, true, budget, null)
        return if (count <= 0) ellipsis else text.substring(0, count).trimEnd() + ellipsis
    }

    private fun withAlpha(argb: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    private data class LongImagePalette(
        val outerBg: Int,
        val cardSurface: Int,
        val cardBorder: Int,
        val surfaceLow: Int,
        val surfaceHigh: Int,
        val primary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val onSurfaceMuted: Int,
        val outlineVariant: Int,
        val codeSurface: Int,
        val codeHeaderSurface: Int,
        val codeBorder: Int,
        val onCodeSurface: Int,
        val onCodeMuted: Int,
    ) {
        companion object {
            fun forStyle(style: LongImageThemeStyle): LongImagePalette = when (style) {
                LongImageThemeStyle.COOL_MIST_LIGHT -> CoolMistLight
                LongImageThemeStyle.SLATE_STEEL_DARK -> SlateSteelDark
                LongImageThemeStyle.PURE_PITCH_AMOLED -> PurePitchAmoled
            }

            val CoolMistLight = LongImagePalette(
                outerBg = 0xFFF7FAFF.toInt(),
                cardSurface = 0xFFFFFFFF.toInt(),
                cardBorder = 0xB8C0C7CD.toInt(),
                surfaceLow = 0xFFF1F4F9.toInt(),
                surfaceHigh = 0xFFE5E8EE.toInt(),
                primary = 0xFF2D637E.toInt(),
                primaryContainer = 0xFFCDE7FB.toInt(),
                onPrimaryContainer = 0xFF001E2E.toInt(),
                onSurface = 0xFF171C1F.toInt(),
                onSurfaceVariant = 0xFF40484D.toInt(),
                onSurfaceMuted = 0xFF70787E.toInt(),
                outlineVariant = 0x99C0C7CD.toInt(),
                codeSurface = 0xFF12151E.toInt(),
                codeHeaderSurface = 0xFF1A1E2B.toInt(),
                codeBorder = 0xFF2A3042.toInt(),
                onCodeSurface = 0xFFE6EDF3.toInt(),
                onCodeMuted = 0xFF8B949E.toInt(),
            )

            val SlateSteelDark = LongImagePalette(
                outerBg = 0xFF101417.toInt(),
                cardSurface = 0xFF1C2024.toInt(),
                cardBorder = 0xCC40484D.toInt(),
                surfaceLow = 0xFF14181B.toInt(),
                surfaceHigh = 0xFF262A2E.toInt(),
                primary = 0xFF9ACAEC.toInt(),
                primaryContainer = 0xFF114B66.toInt(),
                onPrimaryContainer = 0xFFCDE7FB.toInt(),
                onSurface = 0xFFDFE3E7.toInt(),
                onSurfaceVariant = 0xFFC0C7CD.toInt(),
                onSurfaceMuted = 0xFF8A9297.toInt(),
                outlineVariant = 0x9940484D.toInt(),
                codeSurface = 0xFF0E1118.toInt(),
                codeHeaderSurface = 0xFF161B26.toInt(),
                codeBorder = 0xFF262D40.toInt(),
                onCodeSurface = 0xFFE6EDF3.toInt(),
                onCodeMuted = 0xFF8B949E.toInt(),
            )

            val PurePitchAmoled = LongImagePalette(
                outerBg = 0xFF000000.toInt(),
                cardSurface = 0xFF0C1013.toInt(),
                cardBorder = 0xFF272D32.toInt(),
                surfaceLow = 0xFF0A0D10.toInt(),
                surfaceHigh = 0xFF191D21.toInt(),
                primary = 0xFF9ACAEC.toInt(),
                primaryContainer = 0xFF114B66.toInt(),
                onPrimaryContainer = 0xFFCDE7FB.toInt(),
                onSurface = 0xFFDFE3E7.toInt(),
                onSurfaceVariant = 0xFFC0C7CD.toInt(),
                onSurfaceMuted = 0xFF8A9297.toInt(),
                outlineVariant = 0xFF272D32.toInt(),
                codeSurface = 0xFF07090D.toInt(),
                codeHeaderSurface = 0xFF0E121A.toInt(),
                codeBorder = 0xFF1E2433.toInt(),
                onCodeSurface = 0xFFE6EDF3.toInt(),
                onCodeMuted = 0xFF8B949E.toInt(),
            )
        }
    }
}
