package com.nodeseek.app.domain.alerts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

data class AlertHttpRequestSpec(
    val url: String,
    val jsonBody: String,
    val headers: Map<String, String> = emptyMap(),
)

object AlertUrlValidator {
    private val METADATA_HOSTNAMES = setOf(
        "metadata.google.internal",
        "metadata.goog",
        "metadata.azure.internal",
        "169.254.169.254",
        "100.100.100.200",
    )

    fun isValidHttpUrl(
        rawUrl: String,
        allowLoopback: Boolean = true,
        allowPrivateNetwork: Boolean = true,
    ): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        for (ch in trimmed) {
            if (ch <= ' ' || ch == '\u007F') return false
        }
        val lower = trimmed.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val rawHost = uri.host?.trim().orEmpty()
        if (rawHost.isEmpty()) return false

        val cleanHost = rawHost
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
            .trimEnd('.')
            .lowercase(Locale.US)
        if (cleanHost.isEmpty()) return false

        if (cleanHost in METADATA_HOSTNAMES) return false
        if (!allowLoopback && (cleanHost == "localhost" || cleanHost.endsWith(".localhost"))) {
            return false
        }

        val parsedIp = parseLiteralIpAddressOrNull(cleanHost)
        if (parsedIp != null) {
            if (isIpAddressBlocked(parsedIp, allowLoopback = allowLoopback, allowPrivateNetwork = allowPrivateNetwork)) {
                return false
            }
        }
        return true
    }

    internal fun isIpAddressBlocked(
        address: InetAddress,
        allowLoopback: Boolean,
        allowPrivateNetwork: Boolean,
    ): Boolean {
        val bytes = address.address
        if (bytes.size == 4) {
            return isIpv4BytesBlocked(
                v4Bytes = bytes,
                allowLoopback = allowLoopback,
                allowPrivateNetwork = allowPrivateNetwork,
            )
        }

        if (bytes.size == 16) {
            for (embeddedV4 in extractEmbeddedIpv4Candidates(bytes)) {
                if (isIpv4BytesBlocked(embeddedV4, allowLoopback = allowLoopback, allowPrivateNetwork = allowPrivateNetwork)) {
                    return true
                }
            }
            if (!allowPrivateNetwork && isNat64LocalUsePrefix(bytes)) {
                return true
            }
            if (address is Inet6Address) {
                if (address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
                    return true
                }
                if (!allowLoopback && address.isLoopbackAddress) {
                    return true
                }
                val firstByte = bytes[0].toInt() and 0xFF
                // ULA fc00::/7
                if (!allowPrivateNetwork && (firstByte and 0xFE) == 0xFC) {
                    return true
                }
            }
        }
        return false
    }

    private fun isIpv4BytesBlocked(
        v4Bytes: ByteArray,
        allowLoopback: Boolean,
        allowPrivateNetwork: Boolean,
    ): Boolean {
        val b0 = v4Bytes[0].toInt() and 0xFF
        val b1 = v4Bytes[1].toInt() and 0xFF
        val b2 = v4Bytes[2].toInt() and 0xFF
        val b3 = v4Bytes[3].toInt() and 0xFF

        // Unspecified 0.0.0.0/8, Link-local / Cloud IMDS 169.254.0.0/16, Alibaba metadata 100.100.100.200
        if (b0 == 0) return true
        if (b0 == 169 && b1 == 254) return true
        if (b0 == 100 && b1 == 100 && b2 == 100 && b3 == 200) return true
        if (!allowLoopback && b0 == 127) return true
        if (!allowPrivateNetwork) {
            if (b0 == 10) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 192 && b1 == 168) return true
            if (b0 == 100 && b1 in 64..127) return true
            if (b0 >= 224) return true
        }
        return false
    }

    private fun extractEmbeddedIpv4Candidates(bytes: ByteArray): List<ByteArray> {
        if (bytes.size != 16) return emptyList()
        val b = IntArray(16) { idx -> bytes[idx].toInt() and 0xFF }

        // 1. IPv4-mapped ::ffff:0:0/96 or IPv4-compatible ::/96 (excluding ::1 IPv6 loopback)
        if ((0..9).all { b[it] == 0 }) {
            if (b[10] == 0xFF && b[11] == 0xFF) {
                return listOf(bytes.copyOfRange(12, 16))
            }
            if (b[10] == 0 && b[11] == 0 && !isIpv6LoopbackBytes(b)) {
                return listOf(bytes.copyOfRange(12, 16))
            }
        }

        // 2. IPv4-translated ::ffff:0:0:0/96 (RFC 2765 / RFC 6052: ::ffff:0:a.b.c.d)
        if ((0..7).all { b[it] == 0 } && b[8] == 0xFF && b[9] == 0xFF && b[10] == 0 && b[11] == 0) {
            return listOf(bytes.copyOfRange(12, 16))
        }

        // 3. NAT64 Well-Known Prefix 64:ff9b::/96 (RFC 6052)
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && (4..11).all { b[it] == 0 }) {
            return listOf(bytes.copyOfRange(12, 16))
        }

        // 4. NAT64 Local-Use Prefix 64:ff9b:1::/48 (RFC 8215 / RFC 6052)
        if (isNat64LocalUsePrefix(bytes)) {
            if ((6..11).all { b[it] == 0 }) {
                return listOf(bytes.copyOfRange(12, 16))
            }
            val candidates = ArrayList<ByteArray>(4)
            if (b[8] != 0 || !(13..15).all { b[it] == 0 }) {
                candidates += bytes.copyOfRange(12, 16)
            }
            if (b[8] == 0) {
                candidates += byteArrayOf(bytes[6], bytes[7], bytes[9], bytes[10])
                candidates += byteArrayOf(bytes[7], bytes[9], bytes[10], bytes[11])
                candidates += byteArrayOf(bytes[9], bytes[10], bytes[11], bytes[12])
            }
            return candidates
        }

        // 5. 6to4 2002::/16 (RFC 3056)
        if (b[0] == 0x20 && b[1] == 0x02) {
            return listOf(bytes.copyOfRange(2, 6))
        }

        // 6. Teredo 2001:0000::/32 (RFC 4380): server IPv4 in bytes 4..7, inverted client IPv4 in bytes 12..15
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) {
            val serverV4 = bytes.copyOfRange(4, 8)
            val clientV4 = byteArrayOf(
                (b[12] xor 0xFF).toByte(),
                (b[13] xor 0xFF).toByte(),
                (b[14] xor 0xFF).toByte(),
                (b[15] xor 0xFF).toByte(),
            )
            return listOf(serverV4, clientV4)
        }

        return emptyList()
    }

    private fun isIpv6LoopbackBytes(b: IntArray): Boolean =
        (0..14).all { b[it] == 0 } && b[15] == 1

    private fun isNat64LocalUsePrefix(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            (bytes[0].toInt() and 0xFF) == 0x00 &&
            (bytes[1].toInt() and 0xFF) == 0x64 &&
            (bytes[2].toInt() and 0xFF) == 0xFF &&
            (bytes[3].toInt() and 0xFF) == 0x9B &&
            (bytes[4].toInt() and 0xFF) == 0x00 &&
            (bytes[5].toInt() and 0xFF) == 0x01

    private fun parseLiteralIpAddressOrNull(host: String): InetAddress? {
        if (host.isEmpty()) return null
        // Pure IPv6 literal
        if (':' in host) {
            return parseIpv6LiteralToInetAddress(host)
        }
        // Standard dotted decimal IPv4
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }) {
            val octets = ByteArray(4)
            for (i in 0..3) {
                val parsed = parseNumericPart(parts[i]) ?: return null
                if (parsed !in 0L..255L) return null
                octets[i] = parsed.toInt().toByte()
            }
            return InetAddress.getByAddress(octets)
        }
        // Obfuscated numeric/hex/octal IPv4 (e.g. 2130706433, 0x7f000001, 0177.0.0.1)
        if (parts.size == 1 && (host.startsWith("0x") || host.all { it in '0'..'9' })) {
            val v = parseNumericPart(host) ?: return null
            if (v in 0L..0xFFFFFFFFL) {
                return InetAddress.getByAddress(
                    byteArrayOf(
                        ((v ushr 24) and 0xFF).toByte(),
                        ((v ushr 16) and 0xFF).toByte(),
                        ((v ushr 8) and 0xFF).toByte(),
                        (v and 0xFF).toByte(),
                    ),
                )
            }
        }
        if (parts.size in 2..4 && parts.all { parseNumericPart(it) != null }) {
            val parsedParts = parts.map { parseNumericPart(it) ?: return null }
            val value32: Long = when (parsedParts.size) {
                2 -> if (parsedParts[0] <= 0xFFL && parsedParts[1] <= 0xFFFFFFL) {
                    (parsedParts[0] shl 24) or parsedParts[1]
                } else {
                    return null
                }
                3 -> if (parsedParts[0] <= 0xFFL && parsedParts[1] <= 0xFFL && parsedParts[2] <= 0xFFFFL) {
                    (parsedParts[0] shl 24) or (parsedParts[1] shl 16) or parsedParts[2]
                } else {
                    return null
                }
                4 -> if (parsedParts.all { it <= 0xFFL }) {
                    (parsedParts[0] shl 24) or (parsedParts[1] shl 16) or (parsedParts[2] shl 8) or parsedParts[3]
                } else {
                    return null
                }
                else -> return null
            }
            return InetAddress.getByAddress(
                byteArrayOf(
                    ((value32 ushr 24) and 0xFF).toByte(),
                    ((value32 ushr 16) and 0xFF).toByte(),
                    ((value32 ushr 8) and 0xFF).toByte(),
                    (value32 and 0xFF).toByte(),
                ),
            )
        }
        return null
    }

    private fun parseNumericPart(part: String): Long? {
        if (part.isEmpty()) return null
        return when {
            part.startsWith("0x", ignoreCase = true) -> part.substring(2).takeIf { it.isNotEmpty() }?.toLongOrNull(16)
            part.length > 1 && part[0] == '0' && part.all { it in '0'..'7' } -> part.toLongOrNull(8)
            part.all { it in '0'..'9' } -> part.toLongOrNull(10)
            else -> null
        }
    }

    private fun parseIpv6LiteralToInetAddress(host: String): InetAddress? {
        val bytes = ByteArray(16)
        var effectiveHost = host
        val lastColon = host.lastIndexOf(':')
        val hasEmbeddedV4 = lastColon >= 0 && '.' in host.substring(lastColon + 1)
        var embeddedV4: ByteArray? = null
        if (hasEmbeddedV4) {
            val v4Part = host.substring(lastColon + 1)
            val v4Addr = parseLiteralIpAddressOrNull(v4Part) as? Inet4Address ?: return null
            embeddedV4 = v4Addr.address
            effectiveHost = host.substring(0, lastColon + 1) + "0:0"
        }

        val ddIdx = effectiveHost.indexOf("::")
        if (ddIdx >= 0 && effectiveHost.indexOf("::", ddIdx + 2) >= 0) return null
        val hextets = IntArray(8)
        if (ddIdx >= 0) {
            val leftStr = effectiveHost.substring(0, ddIdx)
            val rightStr = effectiveHost.substring(ddIdx + 2)
            val leftParts = if (leftStr.isEmpty()) emptyList() else leftStr.split(':')
            val rightParts = if (rightStr.isEmpty()) emptyList() else rightStr.split(':')
            if (leftParts.size + rightParts.size > 7) return null
            for (i in leftParts.indices) {
                val v = leftParts[i].takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null
                hextets[i] = v
            }
            val rightStart = 8 - rightParts.size
            for (i in rightParts.indices) {
                val v = rightParts[i].takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null
                hextets[rightStart + i] = v
            }
        } else {
            val parts = effectiveHost.split(':')
            if (parts.size != 8) return null
            for (i in 0..7) {
                val v = parts[i].takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null
                hextets[i] = v
            }
        }

        for (i in 0..7) {
            bytes[i * 2] = ((hextets[i] ushr 8) and 0xFF).toByte()
            bytes[i * 2 + 1] = (hextets[i] and 0xFF).toByte()
        }
        if (embeddedV4 != null) {
            bytes[12] = embeddedV4[0]
            bytes[13] = embeddedV4[1]
            bytes[14] = embeddedV4[2]
            bytes[15] = embeddedV4[3]
        }
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    fun isValidTelegramBotToken(rawToken: String): Boolean {
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty()) return false
        var colonIndex = -1
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (ch == ':') {
                if (colonIndex >= 0) return false
                colonIndex = i
                continue
            }
            val allowed = ch in '0'..'9' || ch in 'A'..'Z' || ch in 'a'..'z' || ch == '_' || ch == '-'
            if (!allowed) return false
        }
        return colonIndex > 0 && colonIndex < trimmed.length - 1
    }

    private const val RFC7230_TOKEN_SYMBOLS = "!#\$%&'*+-.^_`|~"

    fun isValidHeaderName(name: String): Boolean {
        if (name.isEmpty()) return false
        for (ch in name) {
            val isTchar = ch in 'A'..'Z' ||
                ch in 'a'..'z' ||
                ch in '0'..'9' ||
                ch in RFC7230_TOKEN_SYMBOLS
            if (!isTchar) return false
        }
        return true
    }

    fun sanitizeHeaderName(name: String): String? {
        val cleaned = sanitizeHeaderValue(name)
        return cleaned.takeIf { isValidHeaderName(it) }
    }

    fun sanitizeHeaderValue(value: String): String =
        value.replace("\r", "").replace("\n", "").trim()
}

data class FormattedAlertContent(
    val eventKey: String,
    val title: String,
    val message: String,
    val targetUrl: String?,
    val details: JsonObject,
)

object AlertEventTextFormatter {

    fun format(event: ForwardableAlertEvent): FormattedAlertContent {
        return when (event) {
            is ForwardableAlertEvent.ProbeOffline -> {
                val prefix = event.flagEmoji.trim().takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
                val title = "🔴 探针离线：${prefix}${event.serverName}"
                val message = buildString {
                    append("面板：${event.panelName}")
                    append("\n节点：${prefix}${event.serverName}")
                    if (event.probeKey.isNotBlank()) {
                        append("\n标识：${event.probeKey}")
                    }
                }
                FormattedAlertContent(
                    eventKey = "probe_offline",
                    title = title,
                    message = message,
                    targetUrl = null,
                    details = buildJsonObject {
                        put("panelName", event.panelName)
                        put("serverName", event.serverName)
                        put("probeKey", event.probeKey)
                        put("flagEmoji", event.flagEmoji)
                    },
                )
            }

            is ForwardableAlertEvent.ProbeRecovered -> {
                val prefix = event.flagEmoji.trim().takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
                val title = "🟢 探针恢复：${prefix}${event.serverName}"
                val message = buildString {
                    append("面板：${event.panelName}")
                    append("\n节点：${prefix}${event.serverName} 已重新上线")
                    if (event.probeKey.isNotBlank()) {
                        append("\n标识：${event.probeKey}")
                    }
                }
                FormattedAlertContent(
                    eventKey = "probe_recovered",
                    title = title,
                    message = message,
                    targetUrl = null,
                    details = buildJsonObject {
                        put("panelName", event.panelName)
                        put("serverName", event.serverName)
                        put("probeKey", event.probeKey)
                        put("flagEmoji", event.flagEmoji)
                    },
                )
            }

            is ForwardableAlertEvent.TrafficThreshold -> {
                val prefix = event.flagEmoji.trim().takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
                val pct = (event.usageRatio * 100.0).roundToInt().coerceIn(0, 999)
                val title = "⚠️ 流量预警 ${pct}%：${prefix}${event.serverName}"
                val message = buildString {
                    append("面板：${event.panelName}")
                    append("\n已用：${formatGiB(event.usedBytes)} / ${formatGiB(event.limitBytes)} ($pct%)")
                    if (event.nextResetDateIso.isNotBlank()) {
                        append("\n下次重置：${event.nextResetDateIso}")
                    }
                    event.exhaustionForecastText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        append("\n预计耗尽：$it")
                    }
                }
                FormattedAlertContent(
                    eventKey = "traffic_threshold",
                    title = title,
                    message = message,
                    targetUrl = null,
                    details = buildJsonObject {
                        put("panelName", event.panelName)
                        put("serverName", event.serverName)
                        put("probeKey", event.probeKey)
                        put("usedBytes", event.usedBytes)
                        put("limitBytes", event.limitBytes)
                        put("usagePercent", pct)
                        put("nextResetDateIso", event.nextResetDateIso)
                        event.exhaustionForecastText?.let { put("exhaustionForecast", it) }
                    },
                )
            }

            is ForwardableAlertEvent.RestockAvailable -> {
                val title = "🛒 补货有货：${event.watchTitle}"
                val message = buildString {
                    if (event.panelTypeLabel.isNotBlank()) {
                        append("面板类型：${event.panelTypeLabel}\n")
                    }
                    if (event.priceHint.isNotBlank()) {
                        append("参考价格：${event.priceHint}\n")
                    }
                    if (event.evidenceSummary.isNotBlank()) {
                        append("信号：${event.evidenceSummary}\n")
                    }
                    if (event.resolvedOrderUrl.isNotBlank()) {
                        append("下单链接：${event.resolvedOrderUrl}")
                    }
                }.trim()
                FormattedAlertContent(
                    eventKey = "restock_available",
                    title = title,
                    message = message,
                    targetUrl = event.resolvedOrderUrl.takeIf { AlertUrlValidator.isValidHttpUrl(it) },
                    details = buildJsonObject {
                        put("watchId", event.watchId)
                        put("watchTitle", event.watchTitle)
                        put("orderUrl", event.resolvedOrderUrl)
                        put("priceHint", event.priceHint)
                        put("panelType", event.panelTypeLabel)
                        put("evidenceSummary", event.evidenceSummary)
                    },
                )
            }

            is ForwardableAlertEvent.RadarKeywordHit -> {
                val title = "📡 雷达命中 [${event.matchedKeyword}]：${event.topicTitle}"
                val message = buildString {
                    if (event.categoryName.isNotBlank()) {
                        append("版块：${event.categoryName} · ")
                    }
                    append("作者：${event.authorName.ifBlank { "未知" }}")
                    if (event.summarySnippet.isNotBlank()) {
                        append("\n摘要：${event.summarySnippet}")
                    }
                    if (event.topicUrl.isNotBlank()) {
                        append("\n链接：${event.topicUrl}")
                    }
                }
                FormattedAlertContent(
                    eventKey = "radar_keyword_hit",
                    title = title,
                    message = message,
                    targetUrl = event.topicUrl.takeIf { AlertUrlValidator.isValidHttpUrl(it) },
                    details = buildJsonObject {
                        put("topicId", event.topicId)
                        put("topicTitle", event.topicTitle)
                        put("matchedKeyword", event.matchedKeyword)
                        put("authorName", event.authorName)
                        put("categoryName", event.categoryName)
                        put("topicUrl", event.topicUrl)
                    },
                )
            }

            is ForwardableAlertEvent.RadarTrackedReply -> {
                val title = "💬 追更贴新回复 (+${event.newReplyCount})：${event.topicTitle}"
                val message = buildString {
                    append("楼层：#${event.targetFloor} · 回复人：${event.actorName.ifBlank { "未知" }}")
                    if (event.summarySnippet.isNotBlank()) {
                        append("\n内容：${event.summarySnippet}")
                    }
                    if (event.topicUrl.isNotBlank()) {
                        append("\n链接：${event.topicUrl}")
                    }
                }
                FormattedAlertContent(
                    eventKey = "radar_tracked_reply",
                    title = title,
                    message = message,
                    targetUrl = event.topicUrl.takeIf { AlertUrlValidator.isValidHttpUrl(it) },
                    details = buildJsonObject {
                        put("topicId", event.topicId)
                        put("topicTitle", event.topicTitle)
                        put("targetFloor", event.targetFloor)
                        put("newReplyCount", event.newReplyCount)
                        put("actorName", event.actorName)
                        put("categoryName", event.categoryName)
                        put("topicUrl", event.topicUrl)
                    },
                )
            }
        }
    }

    private fun formatGiB(bytes: Long): String {
        val gib = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f GiB", gib)
    }
}

private val formatterJson = Json { encodeDefaults = true }

object TelegramBotFormatter {
    private const val MAX_TEXT_LENGTH = 4096

    fun buildRequest(
        config: TelegramForwardConfig,
        event: ForwardableAlertEvent,
    ): AlertHttpRequestSpec? {
        if (!config.enabled) return null
        val token = config.botToken.trim()
        val chatId = config.chatId.trim()
        if (!AlertUrlValidator.isValidTelegramBotToken(token) || chatId.isEmpty()) {
            return null
        }
        val formatted = AlertEventTextFormatter.format(event)
        val combinedText = buildString {
            append(formatted.title)
            if (formatted.message.isNotBlank()) {
                append("\n\n")
                append(formatted.message)
            }
        }.take(MAX_TEXT_LENGTH)

        val body = buildJsonObject {
            put("chat_id", chatId)
            put("text", combinedText)
            put("disable_web_page_preview", true)
            put("disable_notification", config.disableNotification)
        }
        return AlertHttpRequestSpec(
            url = "https://api.telegram.org/bot$token/sendMessage",
            jsonBody = formatterJson.encodeToString(JsonObject.serializer(), body),
        )
    }
}

object BarkFormatter {
    private const val MAX_BODY_LENGTH = 3500

    fun buildRequest(
        config: BarkForwardConfig,
        event: ForwardableAlertEvent,
        allowLoopback: Boolean = true,
        allowPrivateNetwork: Boolean = true,
    ): AlertHttpRequestSpec? {
        if (!config.enabled) return null
        val baseUrl = config.serverUrl.trim().trimEnd('/')
        val deviceKey = config.deviceKey.trim()
        if (
            !AlertUrlValidator.isValidHttpUrl(
                rawUrl = baseUrl,
                allowLoopback = allowLoopback,
                allowPrivateNetwork = allowPrivateNetwork,
            ) ||
            deviceKey.isEmpty()
        ) {
            return null
        }
        val formatted = AlertEventTextFormatter.format(event)
        val body = buildJsonObject {
            put("device_key", deviceKey)
            put("title", formatted.title.take(256))
            put("body", formatted.message.ifBlank { formatted.title }.take(MAX_BODY_LENGTH))
            put("group", config.group.trim().ifBlank { "Anode" })
            put("isArchive", if (config.isArchive) 1 else 0)
            config.sound.trim().takeIf { it.isNotEmpty() }?.let { put("sound", it) }
            formatted.targetUrl?.let { put("url", it) }
        }
        return AlertHttpRequestSpec(
            url = "$baseUrl/push",
            jsonBody = formatterJson.encodeToString(JsonObject.serializer(), body),
        )
    }
}

object WebhookFormatter {
    const val HEADER_SIGNATURE = "X-Anode-Signature"

    fun buildRequest(
        config: WebhookForwardConfig,
        event: ForwardableAlertEvent,
        allowLoopback: Boolean = true,
        allowPrivateNetwork: Boolean = true,
    ): AlertHttpRequestSpec? {
        if (!config.enabled) return null
        val targetUrl = config.url.trim()
        if (
            !AlertUrlValidator.isValidHttpUrl(
                rawUrl = targetUrl,
                allowLoopback = allowLoopback,
                allowPrivateNetwork = allowPrivateNetwork,
            )
        ) {
            return null
        }
        val formatted = AlertEventTextFormatter.format(event)
        val payload = buildJsonObject {
            put("event", formatted.eventKey)
            put("category", event.category.name)
            put("title", formatted.title)
            put("message", formatted.message)
            put("timestamp", event.occurredAtEpochMillis)
            formatted.targetUrl?.let { put("url", JsonPrimitive(it)) }
            put("details", formatted.details)
        }
        val jsonBody = formatterJson.encodeToString(JsonObject.serializer(), payload)

        val headers = LinkedHashMap<String, String>()
        config.customHeaders.forEach { (k, v) ->
            val cleanKey = AlertUrlValidator.sanitizeHeaderName(k)
            val cleanVal = AlertUrlValidator.sanitizeHeaderValue(v)
            if (cleanKey != null && cleanVal.isNotEmpty()) {
                headers[cleanKey] = cleanVal
            }
        }
        val secret = AlertUrlValidator.sanitizeHeaderValue(config.secretValue)
        if (secret.isNotEmpty()) {
            val headerName = AlertUrlValidator.sanitizeHeaderName(config.secretHeaderName)
                ?: WebhookForwardConfig.DEFAULT_SECRET_HEADER
            headers[headerName] = secret
            headers[HEADER_SIGNATURE] = "sha256=${hmacSha256Hex(secret, jsonBody)}"
        }

        return AlertHttpRequestSpec(
            url = targetUrl,
            jsonBody = jsonBody,
            headers = headers,
        )
    }

    internal fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
