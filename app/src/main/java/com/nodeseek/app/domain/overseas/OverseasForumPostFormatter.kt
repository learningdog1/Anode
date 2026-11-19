package com.nodeseek.app.domain.overseas

import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.valuation.ValuationCurrency
import java.math.RoundingMode

/**
 * Generates a NodeSeek-ready Chinese intelligence post (`情报搬运帖`) with structured Markdown
 * spec/price table, converted CNY values, coupon codes, direct order links, and explicit source
 * attribution for [TopicComposerScreen].
 */
object OverseasForumPostFormatter {

    fun format(
        offer: OverseasDealOffer,
        fxRatesState: FxRatesState? = null,
    ): SuggestedForumPost {
        val chineseTitle = offer.translatedTitle
            ?.takeIf { it.isNotBlank() }
            ?: VpsGlossaryTranslator.translateTitleOffline(offer.originalTitle)

        val priceTail = offer.primaryPrice?.let { price ->
            " — ${price.rawText} (${price.formattedCny})"
        }.orEmpty()

        val promoPrefix = if (offer.isFlashOrBlackFriday) {
            "[黑五/海外情报/${offer.source.shortLabel}]"
        } else {
            "[海外情报/${offer.source.shortLabel}]"
        }

        val suggestedTitle = "$promoPrefix ${chineseTitle.take(80)}$priceTail".trim()

        val md = StringBuilder()
        md.appendLine("## 🌐 海外 VPS 情报搬运（${offer.source.label}）")
        md.appendLine()
        md.appendLine("> **中文译题**：$chineseTitle")
        if (!chineseTitle.equals(offer.originalTitle, ignoreCase = true)) {
            md.appendLine("> **英文原题**：${offer.originalTitle}")
        }
        md.appendLine()

        // Structured overview table
        md.appendLine("| 项目 | 详情 |")
        md.appendLine("| :--- | :--- |")
        md.appendLine("| **情报来源** | ${offer.source.label} (`${offer.source.shortLabel}`) |")
        if (offer.authorOrProvider.isNotBlank()) {
            md.appendLine("| **商家 / 作者** | ${escapeTableCell(offer.authorOrProvider)} |")
        }
        if (offer.specHighlights.isNotEmpty()) {
            md.appendLine("| **配置与机房** | ${escapeTableCell(offer.specHighlights.joinToString(" · "))} |")
        }
        if (offer.priceMatches.isNotEmpty()) {
            val priceSummary = offer.priceMatches.take(4).joinToString(" / ") { tag ->
                "**${tag.rawText}** (${tag.formattedCny})"
            }
            md.appendLine("| **价格折算** | $priceSummary |")
        }
        if (offer.couponCodes.isNotEmpty()) {
            val codes = offer.couponCodes.joinToString(" / ") { "`$it`" }
            md.appendLine("| **优惠码** | $codes |")
        }
        if (offer.isOutOfStockHint) {
            md.appendLine("| **库存提示** | ⚠️ 原帖提示可能缺货 / 已售罄，下单前请先确认 |")
        }
        md.appendLine()

        // Translated summary
        val summaryZh = offer.translatedSummary
            ?.takeIf { it.isNotBlank() }
            ?: VpsGlossaryTranslator.translateSummaryOffline(
                englishSummary = offer.originalSummary,
                specHighlights = offer.specHighlights,
                priceMatches = offer.priceMatches,
                couponCodes = offer.couponCodes,
            )
        if (summaryZh.isNotBlank()) {
            md.appendLine("### 📋 优惠摘要")
            md.appendLine()
            md.appendLine(summaryZh.trim())
            md.appendLine()
        }

        // Direct order / cart links
        if (offer.orderLinks.isNotEmpty()) {
            md.appendLine("### 🛒 直达购买链接")
            md.appendLine()
            offer.orderLinks.take(5).forEachIndexed { index, url ->
                md.appendLine("${index + 1}. [直达下单链接 ${index + 1}]($url)")
            }
            md.appendLine()
        }

        // Source attribution & FX reference disclaimer
        md.appendLine("---")
        md.appendLine("**来源致谢**：情报搬运自 [${offer.source.label} 原帖](${offer.sourceLink})，版权与最终条款归原帖作者及商家所有。")
        if (fxRatesState != null) {
            val usdRate = fxRatesState.rateFor(ValuationCurrency.USD).setScale(2, RoundingMode.HALF_UP)
            val eurRate = fxRatesState.rateFor(ValuationCurrency.EUR).setScale(2, RoundingMode.HALF_UP)
            val dateSuffix = fxRatesState.providerDate?.let { "（汇率日期 $it）" }.orEmpty()
            md.appendLine("*参考汇率：1 USD ≈ ¥${usdRate.toPlainString()} · 1 EUR ≈ ¥${eurRate.toPlainString()}$dateSuffix，实际以发卡行入账为准。*")
        } else {
            md.appendLine("*人民币价格按实时参考汇率折算，实际以发卡行入账为准。*")
        }

        return SuggestedForumPost(
            title = suggestedTitle,
            markdownBody = md.toString().trimEnd(),
            suggestedCategorySlug = "info",
        )
    }

    private fun escapeTableCell(value: String): String =
        value.replace("|", "\\|").replace("\n", " ").trim()
}
