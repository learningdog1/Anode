package com.nodeseek.app.ui.baseline

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.nodeseek.app.domain.baseline.BaselineComparisonFormatter
import com.nodeseek.app.domain.baseline.BaselineDiffReport
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.baseline.MetricTrend
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BaselineCardMetricRow(
    val category: String,
    val beforeText: String,
    val afterText: String,
    val deltaText: String,
    val trend: MetricTrend,
)

data class BaselineComparisonCardModel(
    val title: String,
    val subtitle: String,
    val verdictBadge: String,
    val verdict: BaselineOverallVerdict,
    val highlightLines: List<String>,
    val rows: List<BaselineCardMetricRow>,
    val footerText: String,
)

data class RenderedBaselineCardImage(
    val pngBytes: ByteArray,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * Offscreen `1000px`-wide PNG card renderer for Before-vs-After purchase baseline comparisons,
 * matching the visual tokens and pure-JVM text-extraction testability of
 * [com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator].
 */
object AndroidBaselineComparisonCardImageGenerator {

    private const val LOGICAL_WIDTH_DP = 500f
    private const val DENSITY_SCALE = 2.0f
    private const val OUTER_INSET_DP = 2f
    private const val CARD_CORNER_DP = 18f
    private const val CARD_PAD_H_DP = 16f
    private const val CARD_PAD_V_DP = 14f
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun buildPresentation(
        diff: BaselineDiffReport,
        maskTargetIp: Boolean,
    ): BaselineComparisonCardModel {
        val rawHost = diff.current.hostDisplay.ifBlank { diff.baseline.hostDisplay }
        val safeHost = if (maskTargetIp && NetBenchTargetParser.detectIpFamily(rawHost) != IpFamily.UNKNOWN) {
            NetBenchTargetParser.maskTargetIpLastSegment(rawHost)
        } else {
            rawHost
        }
        val beforeDate = DATE_FORMATTER.format(
            Instant.ofEpochMilli(diff.baseline.capturedAtEpochMillis).atZone(VALUATION_ZONE_ID).toLocalDate(),
        )
        val afterDate = DATE_FORMATTER.format(
            Instant.ofEpochMilli(diff.current.capturedAtEpochMillis).atZone(VALUATION_ZONE_ID).toLocalDate(),
        )

        val rows = mutableListOf<BaselineCardMetricRow>()
        for (bw in diff.bandwidthDeltas.take(3)) {
            val bStr = bw.beforeMbps?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "-"
            val aStr = bw.afterMbps?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "-"
            val dStr = bw.deltaPercent?.let { String.format(Locale.US, "%+.0f%%", it) }
                ?: bw.deltaMbps?.let { String.format(Locale.US, "%+.1f", it) }
                ?: "-"
            rows += BaselineCardMetricRow(bw.metricLabel, bStr, aStr, dStr, bw.trend)
        }
        for (lat in diff.latencyDeltas.take(2)) {
            val bStr = lat.beforeValue?.let { String.format(Locale.US, "%.1f%s", it, lat.unit) } ?: "-"
            val aStr = lat.afterValue?.let { String.format(Locale.US, "%.1f%s", it, lat.unit) } ?: "-"
            val dStr = lat.deltaValue?.let { String.format(Locale.US, "%+.1f%s", it, lat.unit) } ?: "-"
            rows += BaselineCardMetricRow(lat.metricLabel, bStr, aStr, dStr, lat.trend)
        }
        for (shift in diff.routingShifts.take(2)) {
            rows += BaselineCardMetricRow(
                category = shift.directionLabel,
                beforeText = shift.beforeRoute?.take(22) ?: "-",
                afterText = shift.afterRoute?.take(22) ?: "-",
                deltaText = when {
                    shift.detourIntroduced -> "绕美"
                    shift.backboneDowngraded -> "降级"
                    shift.trend == MetricTrend.IMPROVED -> "升级"
                    else -> "变化"
                },
                trend = shift.trend,
            )
        }
        for (unlock in diff.streamingDiffs.filter { it.changeType.name != "UNCHANGED" }.take(3)) {
            val trend = when (unlock.changeType.name) {
                "GAINED_UNLOCK" -> MetricTrend.IMPROVED
                "REGION_CHANGED" -> MetricTrend.DEGRADED_WARN
                else -> MetricTrend.DEGRADED_SEVERE
            }
            rows += BaselineCardMetricRow(
                category = unlock.displayName,
                beforeText = unlock.beforeStatus.label,
                afterText = unlock.afterStatus.label,
                deltaText = unlock.changeType.label,
                trend = trend,
            )
        }

        val knownIps = listOfNotNull(
            diff.baseline.resolvedIp,
            diff.current.resolvedIp,
            diff.baseline.hostDisplay,
            diff.current.hostDisplay,
        )
        fun scrub(s: String): String =
            if (maskTargetIp) BaselineComparisonFormatter.scrubSensitiveIps(s, knownIps) else s

        return BaselineComparisonCardModel(
            title = scrub("Before vs After · $safeHost"),
            subtitle = scrub("${diff.baseline.label} ($beforeDate) → 当前实测 ($afterDate) · 间隔 ${diff.elapsedDays} 天"),
            verdictBadge = diff.overallVerdict.label,
            verdict = diff.overallVerdict,
            highlightLines = diff.highlightBullets.take(3).map(::scrub),
            rows = rows.map { row ->
                row.copy(
                    category = scrub(row.category),
                    beforeText = scrub(row.beforeText),
                    afterText = scrub(row.afterText),
                    deltaText = scrub(row.deltaText),
                )
            },
            footerText = "Anode · 购入基线对比卡",
        )
    }

    fun extractRenderedCardTexts(
        diff: BaselineDiffReport,
        maskTargetIp: Boolean,
        darkTheme: Boolean = false,
    ): List<String> {
        val model = buildPresentation(diff, maskTargetIp)
        val palette = if (darkTheme) CardColors.Dark else CardColors.Light
        val recorded = mutableListOf<String>()
        drawCard(
            canvas = Canvas(),
            model = model,
            palette = palette,
            recordedTexts = recorded,
        )
        return recorded
    }

    fun renderPng(
        diff: BaselineDiffReport,
        maskTargetIp: Boolean,
        darkTheme: Boolean = false,
    ): RenderedBaselineCardImage? {
        val model = buildPresentation(diff, maskTargetIp)
        val palette = if (darkTheme) CardColors.Dark else CardColors.Light
        val logicalHeight = measureCardLogicalHeight(model)
        val widthPx = (LOGICAL_WIDTH_DP * DENSITY_SCALE).toInt().coerceAtLeast(1)
        val heightPx = (logicalHeight * DENSITY_SCALE).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        canvas.scale(DENSITY_SCALE, DENSITY_SCALE)

        drawCard(
            canvas = canvas,
            model = model,
            palette = palette,
            recordedTexts = null,
        )

        return try {
            val out = ByteArrayOutputStream(24_576)
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            if (!ok || bytes.isEmpty()) null else RenderedBaselineCardImage(
                pngBytes = bytes,
                fileName = "anode_baseline_diff_${diff.current.capturedAtEpochMillis}.png",
                widthPx = widthPx,
                heightPx = heightPx,
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun shareCardPng(
        context: Context,
        diff: BaselineDiffReport,
        maskTargetIp: Boolean,
        darkTheme: Boolean,
    ): Boolean {
        val rendered = renderPng(diff, maskTargetIp, darkTheme) ?: return false
        return runCatching {
            val dir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
            val outFile = File(dir, rendered.fileName)
            outFile.writeBytes(rendered.pngBytes)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "分享 Before vs After 对比卡片").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun measureCardLogicalHeight(model: BaselineComparisonCardModel): Float {
        var h = OUTER_INSET_DP * 2f + CARD_PAD_V_DP * 2f
        h += 44f // header + subtitle
        if (model.highlightLines.isNotEmpty()) {
            h += 12f + model.highlightLines.size * 18f + 10f
        }
        h += 24f // table header
        h += model.rows.size.coerceAtLeast(1) * 26f
        h += 22f // footer
        return h.coerceAtLeast(180f)
    }

    private fun drawCard(
        canvas: Canvas,
        model: BaselineComparisonCardModel,
        palette: CardColors,
        recordedTexts: MutableList<String>?,
    ) {
        val logicalHeight = measureCardLogicalHeight(model)
        val cardRect = RectF(
            OUTER_INSET_DP,
            OUTER_INSET_DP,
            LOGICAL_WIDTH_DP - OUTER_INSET_DP,
            logicalHeight - OUTER_INSET_DP,
        )
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardSurface
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardBorder
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(cardRect, CARD_CORNER_DP, CARD_CORNER_DP, fillPaint)
        canvas.drawRoundRect(cardRect, CARD_CORNER_DP, CARD_CORNER_DP, strokePaint)

        val contentLeft = OUTER_INSET_DP + CARD_PAD_H_DP
        val contentWidth = LOGICAL_WIDTH_DP - (OUTER_INSET_DP + CARD_PAD_H_DP) * 2f
        var y = OUTER_INSET_DP + CARD_PAD_V_DP

        fun emitText(
            text: String,
            x: Float,
            baselineY: Float,
            sizeSp: Float,
            color: Int,
            bold: Boolean = false,
            align: Paint.Align = Paint.Align.LEFT,
        ) {
            recordedTexts?.add(text)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = sizeSp
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textAlign = align
            }
            canvas.drawText(text, x, baselineY, paint)
        }

        // Title + Verdict Badge
        emitText(model.title, contentLeft, y + 14f, 14f, palette.onSurface, bold = true)
        val badgeColor = when (model.verdict) {
            BaselineOverallVerdict.SIGNIFICANT_DEGRADATION -> palette.error
            BaselineOverallVerdict.MINOR_REGRESSION -> palette.amber
            BaselineOverallVerdict.IMPROVED,
            BaselineOverallVerdict.STABLE,
            -> palette.primary
        }
        emitText(
            model.verdictBadge,
            contentLeft + contentWidth,
            y + 14f,
            10.5f,
            badgeColor,
            bold = true,
            align = Paint.Align.RIGHT,
        )
        y += 20f
        emitText(model.subtitle, contentLeft, y + 10f, 10f, palette.onSurfaceMuted)
        y += 22f

        // Highlights
        if (model.highlightLines.isNotEmpty()) {
            val boxTop = y
            val boxHeight = model.highlightLines.size * 18f + 10f
            val boxRect = RectF(contentLeft, boxTop, contentLeft + contentWidth, boxTop + boxHeight)
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.surfaceLow
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(boxRect, 10f, 10f, boxPaint)
            var hy = boxTop + 17f
            for (hl in model.highlightLines) {
                emitText("• $hl", contentLeft + 8f, hy, 10f, palette.onSurfaceVariant)
                hy += 18f
            }
            y += boxHeight + 10f
        }

        // Table Header
        val colBeforeX = contentLeft + 115f
        val colAfterX = contentLeft + 245f
        val colDeltaX = contentLeft + contentWidth
        emitText("对比维度", contentLeft, y + 12f, 10f, palette.onSurfaceMuted, bold = true)
        emitText("购入基线", colBeforeX, y + 12f, 10f, palette.onSurfaceMuted, bold = true)
        emitText("当前实测", colAfterX, y + 12f, 10f, palette.onSurfaceMuted, bold = true)
        emitText("变化", colDeltaX, y + 12f, 10f, palette.onSurfaceMuted, bold = true, align = Paint.Align.RIGHT)
        y += 22f

        for (row in model.rows) {
            val trendColor = when (row.trend) {
                MetricTrend.DEGRADED_SEVERE -> palette.error
                MetricTrend.DEGRADED_WARN -> palette.amber
                MetricTrend.IMPROVED -> palette.primary
                MetricTrend.UNCHANGED,
                MetricTrend.INFO,
                -> palette.onSurfaceVariant
            }
            emitText(row.category, contentLeft, y + 13f, 10.5f, palette.onSurface, bold = true)
            emitText(row.beforeText, colBeforeX, y + 13f, 10.5f, palette.onSurfaceVariant)
            emitText(row.afterText, colAfterX, y + 13f, 10.5f, palette.onSurface)
            emitText(row.deltaText, colDeltaX, y + 13f, 10.5f, trendColor, bold = true, align = Paint.Align.RIGHT)
            y += 26f
        }

        if (model.rows.isEmpty()) {
            y += 26f
        }

        // Footer
        emitText(model.footerText, contentLeft, y + 10f, 9.5f, palette.onSurfaceMuted)
    }

    private data class CardColors(
        val cardSurface: Int,
        val cardBorder: Int,
        val primary: Int,
        val amber: Int,
        val error: Int,
        val surfaceLow: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val onSurfaceMuted: Int,
    ) {
        companion object {
            val Light = CardColors(
                cardSurface = 0xFFFFFFFF.toInt(),
                cardBorder = 0xC8C0C7CD.toInt(),
                primary = 0xFF2D637E.toInt(),
                amber = 0xFF9A5200.toInt(),
                error = 0xFFBA1A1A.toInt(),
                surfaceLow = 0xFFF1F4F9.toInt(),
                onSurface = 0xFF171C1F.toInt(),
                onSurfaceVariant = 0xFF40484D.toInt(),
                onSurfaceMuted = 0xFF6A7379.toInt(),
            )
            val Dark = CardColors(
                cardSurface = 0xFF1C2024.toInt(),
                cardBorder = 0xCC40484D.toInt(),
                primary = 0xFF9ACAEC.toInt(),
                amber = 0xFFFBBF24.toInt(),
                error = 0xFFFFB4AB.toInt(),
                surfaceLow = 0xFF14181B.toInt(),
                onSurface = 0xFFDFE3E7.toInt(),
                onSurfaceVariant = 0xFFC0C7CD.toInt(),
                onSurfaceMuted = 0xFF8A9297.toInt(),
            )
        }
    }
}
