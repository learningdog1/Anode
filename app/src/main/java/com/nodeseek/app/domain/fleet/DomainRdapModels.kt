package com.nodeseek.app.domain.fleet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Structured domain registration & expiry metadata parsed from RFC 9083 RDAP or WHOIS responses.
 */
data class DomainRdapResult(
    val domainName: String,
    val unicodeName: String? = null,
    val registrarName: String? = null,
    val registrarIanaId: String? = null,
    val expiryDateIso: String? = null,
    val expiryInstantIso: String? = null,
    val registrationDateIso: String? = null,
    val lastChangedDateIso: String? = null,
    val statuses: List<String> = emptyList(),
    val nameservers: List<String> = emptyList(),
    val sourceUrl: String,
    val fetchedAtEpochMillis: Long,
) {
    /**
     * Concise human-readable summary of synced RDAP / WHOIS fields, including sparse registries
     * (such as DENIC `.de`) that intentionally omit expiration date and registrar details.
     */
    fun toSyncSummary(): String = buildString {
        append(if (sourceUrl.startsWith("whois://", ignoreCase = true)) "WHOIS 已同步" else "RDAP 已同步")
        val reg = registrarName?.trim().orEmpty()
        if (reg.isNotEmpty()) append(" · $reg")
        if (!registrationDateIso.isNullOrBlank()) append(" · 注册于 $registrationDateIso")
        if (!expiryDateIso.isNullOrBlank()) {
            append(" · 到期 $expiryDateIso")
        } else {
            if (!lastChangedDateIso.isNullOrBlank()) append(" · 变更于 $lastChangedDateIso")
            val primaryStatus = statuses.firstOrNull()?.takeIf { it.isNotBlank() }
            if (primaryStatus != null) append(" · 状态 $primaryStatus")
            if (nameservers.isNotEmpty()) {
                val nsPreview = if (nameservers.size == 1) {
                    nameservers.first()
                } else {
                    "${nameservers.first()} 等 ${nameservers.size} 条 NS"
                }
                append(" · $nsPreview")
            }
            append("（注册局未公开到期日，请手动核对）")
        }
    }
}

/**
 * Pure-Kotlin RFC 9083 RDAP JSON parser extracting domain events, registrar vCard/IANA ID,
 * statuses, and nameservers with full tolerance for sparse ccTLD responses (e.g. DENIC `.de`).
 */
object DomainRdapParser {

    private val json = Json { ignoreUnknownKeys = true }

    private val FLEXIBLE_DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US),
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.US),
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d-MMM-yyyy")
            .toFormatter(Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),
    )

    private val EMBEDDED_ISO_DATE_REGEX = Regex("""(\d{4})[-/.](\d{2})[-/.](\d{2})""")
    private val EMBEDDED_DMY_MONTH_REGEX = Regex("""\b(\d{1,2}-[A-Za-z]{3}-\d{4})\b""")

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun parse(
        rawJson: String,
        queriedDomain: String,
        sourceUrl: String,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Result<DomainRdapResult> = runCatching {
        val root = json.parseToJsonElement(rawJson).jsonObject

        val errorCode = root.stringField("errorCode")?.toIntOrNull()
        val hasDomainIdentity = root.stringField("ldhName") != null ||
            root.stringField("unicodeName") != null ||
            root.stringField("handle") != null ||
            root.stringField("objectClassName").equals("domain", ignoreCase = true) ||
            (root["events"] as? JsonArray)?.isNotEmpty() == true ||
            (root["nameservers"] as? JsonArray)?.isNotEmpty() == true
        if (errorCode != null && errorCode >= 400 && !hasDomainIdentity) {
            val title = root.stringField("title") ?: "HTTP $errorCode"
            throw IllegalStateException("RDAP 返回错误 ($errorCode): $title")
        }

        val ldhName = root.stringField("ldhName")
            ?.let { VpsAssetRecord.normalizeDomainName(it) }
            ?: VpsAssetRecord.normalizeDomainName(queriedDomain)
            ?: queriedDomain.trim().trimEnd('.').lowercase(Locale.ROOT)
        val unicodeName = root.stringField("unicodeName")

        var expiryDateIso: String? = null
        var expiryInstantIso: String? = null
        var registrationDateIso: String? = null
        var lastChangedDateIso: String? = null
        var lastRdapDbUpdateDateIso: String? = null

        val events = root["events"] as? JsonArray
        events?.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val action = obj.stringField("eventAction")?.lowercase(Locale.ROOT).orEmpty()
            val eventDate = obj.stringField("eventDate").orEmpty()
            if (eventDate.isEmpty()) return@forEach
            val dateIso = parseRdapDateToIso(eventDate)
            when (action) {
                "expiration", "soft expiration", "registrar expiration", "registry expiration" -> {
                    if (expiryDateIso == null) {
                        expiryDateIso = dateIso
                        expiryInstantIso = eventDate
                    }
                }
                "registration", "registered", "creation" -> {
                    if (registrationDateIso == null) {
                        registrationDateIso = dateIso
                    }
                }
                "last changed", "last update" -> {
                    if (lastChangedDateIso == null) {
                        lastChangedDateIso = dateIso
                    }
                }
                "last update of rdap database" -> {
                    if (lastRdapDbUpdateDateIso == null) {
                        lastRdapDbUpdateDateIso = dateIso
                    }
                }
            }
        }
        val resolvedLastChanged = lastChangedDateIso ?: lastRdapDbUpdateDateIso

        val (registrarName, registrarIanaId) = extractRegistrarFromEntities(root["entities"] as? JsonArray)

        val statuses = (root["status"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            .orEmpty()

        val nameservers = (root["nameservers"] as? JsonArray)
            ?.mapNotNull { ns ->
                (ns as? JsonObject)?.stringField("ldhName")
                    ?.trimEnd('.')
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf { it.isNotEmpty() }
            }
            ?.distinct()
            .orEmpty()

        DomainRdapResult(
            domainName = ldhName,
            unicodeName = unicodeName,
            registrarName = registrarName,
            registrarIanaId = registrarIanaId,
            expiryDateIso = expiryDateIso,
            expiryInstantIso = expiryInstantIso,
            registrationDateIso = registrationDateIso,
            lastChangedDateIso = resolvedLastChanged,
            statuses = statuses,
            nameservers = nameservers,
            sourceUrl = sourceUrl,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        )
    }

    internal fun parseRdapDateToIso(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        runCatching {
            val instant = Instant.parse(trimmed)
            return DateTimeFormatter.ISO_LOCAL_DATE.format(instant.atZone(ZoneOffset.UTC).toLocalDate())
        }
        runCatching {
            val odt = OffsetDateTime.parse(trimmed)
            return DateTimeFormatter.ISO_LOCAL_DATE.format(odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate())
        }
        if (trimmed.length >= 10) {
            runCatching {
                val ld = LocalDate.parse(trimmed.take(10))
                return DateTimeFormatter.ISO_LOCAL_DATE.format(ld)
            }
        }
        for (formatter in FLEXIBLE_DATE_FORMATTERS) {
            runCatching {
                val ld = LocalDate.parse(trimmed, formatter)
                return DateTimeFormatter.ISO_LOCAL_DATE.format(ld)
            }
        }
        EMBEDDED_ISO_DATE_REGEX.find(trimmed)?.let { match ->
            val candidate = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
            runCatching {
                val ld = LocalDate.parse(candidate)
                return DateTimeFormatter.ISO_LOCAL_DATE.format(ld)
            }
        }
        EMBEDDED_DMY_MONTH_REGEX.find(trimmed)?.let { match ->
            val token = match.groupValues[1]
            for (formatter in FLEXIBLE_DATE_FORMATTERS.subList(2, 4)) {
                runCatching {
                    val ld = LocalDate.parse(token, formatter)
                    return DateTimeFormatter.ISO_LOCAL_DATE.format(ld)
                }
            }
        }
        return null
    }

    private fun extractRegistrarFromEntities(entities: JsonArray?): Pair<String?, String?> {
        if (entities == null) return null to null
        for (elem in entities) {
            val entity = elem as? JsonObject ?: continue
            val roles = (entity["roles"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT) }
                .orEmpty()

            if ("registrar" in roles) {
                val vcard = entity["vcardArray"] as? JsonArray
                val nameFromVcard = extractNameFromVcard(vcard)
                val nameFromHandle = entity.stringField("handle")
                val nameFromRemarks = extractNameFromRemarks(entity["remarks"] as? JsonArray)
                val ianaId = extractIanaId(entity["publicIds"] as? JsonArray) ?: nameFromHandle?.takeIf { it.all(Char::isDigit) }
                val resolvedName = nameFromVcard ?: nameFromRemarks ?: nameFromHandle
                if (resolvedName != null || ianaId != null) {
                    return resolvedName to ianaId
                }
            }

            // Recurse into nested entities
            val nested = extractRegistrarFromEntities(entity["entities"] as? JsonArray)
            if (nested.first != null || nested.second != null) {
                return nested
            }
        }
        return null to null
    }

    private fun extractNameFromVcard(vcardArray: JsonArray?): String? {
        if (vcardArray == null || vcardArray.size < 2) return null
        val properties = vcardArray[1] as? JsonArray ?: return null
        var fnValue: String? = null
        var orgValue: String? = null
        for (propElem in properties) {
            val prop = propElem as? JsonArray ?: continue
            if (prop.size < 4) continue
            val key = (prop[0] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT) ?: continue
            val valueText = extractVcardValueText(prop[3]) ?: continue
            when (key) {
                "fn" -> if (fnValue == null) fnValue = valueText
                "org" -> if (orgValue == null) orgValue = valueText
            }
        }
        return fnValue ?: orgValue
    }

    private fun extractVcardValueText(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        is JsonArray -> element.firstNotNullOfOrNull { extractVcardValueText(it) }
        else -> null
    }

    private fun extractNameFromRemarks(remarks: JsonArray?): String? {
        if (remarks == null) return null
        for (elem in remarks) {
            val obj = elem as? JsonObject ?: continue
            val desc = (obj["description"] as? JsonArray)
                ?.firstOrNull()
                ?.let { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.takeIf { it.isNotEmpty() }
            if (desc != null) return desc
        }
        return null
    }

    private fun extractIanaId(publicIds: JsonArray?): String? {
        if (publicIds == null) return null
        for (elem in publicIds) {
            val obj = elem as? JsonObject ?: continue
            val type = obj.stringField("type").orEmpty()
            val identifier = obj.stringField("identifier")
            if (type.contains("IANA", ignoreCase = true) && identifier != null) {
                return identifier
            }
        }
        return null
    }

    /**
     * Parses IANA RDAP DNS bootstrap JSON (`https://data.iana.org/rdap/dns.json`) and returns
     * the authoritative RDAP base URL for [tld] (lowercase, without leading dot).
     */
    fun extractBootstrapUrlForTld(
        bootstrapJson: String,
        tld: String,
    ): String? {
        val cleanTld = tld.trim().trimStart('.').lowercase(Locale.ROOT)
        if (cleanTld.isEmpty()) return null
        return runCatching {
            val root = json.parseToJsonElement(bootstrapJson).jsonObject
            val services = root["services"] as? JsonArray ?: return@runCatching null
            for (serviceElem in services) {
                val pair = serviceElem as? JsonArray ?: continue
                if (pair.size < 2) continue
                val tlds = pair[0] as? JsonArray ?: continue
                val urls = pair[1] as? JsonArray ?: continue
                val matched = tlds.any { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT) == cleanTld }
                if (matched) {
                    val url = urls.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                        .firstOrNull { it.startsWith("https://") }
                        ?: urls.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.firstOrNull()
                    if (!url.isNullOrBlank()) {
                        return@runCatching if (url.endsWith('/')) url else "$url/"
                    }
                }
            }
            null
        }.getOrNull()
    }
}

/**
 * Pure-Kotlin RFC 3912 WHOIS text parser used as a fallback when RDAP is unavailable for a TLD
 * or temporarily unreachable.
 */
object DomainWhoisParser {

    private val NOT_FOUND_PATTERNS = listOf(
        "no match for",
        "not found",
        "no entries found",
        "no data found",
        "domain not found",
        "no object found",
        "nothing found",
        "status: free",
        "status: available",
        "is available for registration",
        "the queried object does not exist",
        "no such domain",
    )

    private val EXPIRY_KEYS = setOf(
        "registry expiry date",
        "registrar registration expiration date",
        "expiration date",
        "expiry date",
        "expiration time",
        "paid-till",
        "expires on",
        "expire date",
        "renewal date",
        "valid until",
        "expires",
        "free-date",
    )

    private val CREATION_KEYS = setOf(
        "creation date",
        "created on",
        "created",
        "registration time",
        "registered on",
        "registered",
        "domain registration date",
    )

    private val UPDATED_KEYS = setOf(
        "updated date",
        "last updated on",
        "last updated",
        "last modified",
        "changed",
        "modified",
    )

    private val REGISTRAR_KEYS = setOf(
        "registrar",
        "sponsoring registrar",
        "registrar name",
        "registrar organization",
    )

    private val IANA_ID_KEYS = setOf(
        "registrar iana id",
        "iana id",
    )

    private val STATUS_KEYS = setOf(
        "domain status",
        "status",
        "state",
    )

    private val NAMESERVER_KEYS = setOf(
        "name server",
        "nserver",
        "nameserver",
        "dns",
    )

    private val REFERRAL_KEYS = setOf(
        "refer",
        "registrar whois server",
        "whois server",
        "whois",
    )

    /**
     * Extracts a referred WHOIS hostname (e.g. `refer: whois.denic.de` from `whois.iana.org`,
     * or `Registrar WHOIS Server: whois.spaceship.com`) if present.
     */
    fun extractReferralServer(rawWhois: String): String? {
        for (rawLine in rawWhois.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("%") || line.startsWith("#")) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
            if (key !in REFERRAL_KEYS) continue
            val rawVal = line.substring(colonIdx + 1).trim()
                .removePrefix("whois://")
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
                .trim()
            val normalized = VpsAssetRecord.normalizeDomainName(rawVal)
            if (normalized != null) return normalized
        }
        return null
    }

    fun parse(
        rawWhois: String,
        queriedDomain: String,
        sourceUrl: String,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Result<DomainRdapResult> = runCatching {
        val text = rawWhois.trim()
        require(text.isNotEmpty()) { "WHOIS 返回内容为空" }

        var domainName: String? = null
        var registrarName: String? = null
        var registrarIanaId: String? = null
        var expiryDateIso: String? = null
        var expiryInstantIso: String? = null
        var registrationDateIso: String? = null
        var lastChangedDateIso: String? = null
        val statuses = LinkedHashSet<String>()
        val nameservers = LinkedHashSet<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("%") || line.startsWith("#") || line.startsWith(">>>")) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
            val value = line.substring(colonIdx + 1).trim()
            if (value.isEmpty()) continue

            when {
                key == "domain name" || key == "domain" -> {
                    if (domainName == null) {
                        domainName = VpsAssetRecord.normalizeDomainName(value.substringBefore(' '))
                    }
                }
                key in REGISTRAR_KEYS -> {
                    if (registrarName == null && !value.equals("not applicable", ignoreCase = true)) {
                        registrarName = value
                    }
                }
                key in IANA_ID_KEYS -> {
                    if (registrarIanaId == null) {
                        registrarIanaId = value.takeWhile(Char::isDigit).takeIf { it.isNotEmpty() }
                    }
                }
                key in EXPIRY_KEYS -> {
                    if (expiryDateIso == null) {
                        val parsed = DomainRdapParser.parseRdapDateToIso(value)
                        if (parsed != null) {
                            expiryDateIso = parsed
                            expiryInstantIso = value
                        }
                    }
                }
                key in CREATION_KEYS -> {
                    if (registrationDateIso == null) {
                        registrationDateIso = DomainRdapParser.parseRdapDateToIso(value)
                    }
                }
                key in UPDATED_KEYS -> {
                    if (lastChangedDateIso == null) {
                        lastChangedDateIso = DomainRdapParser.parseRdapDateToIso(value)
                    }
                }
                key in STATUS_KEYS -> {
                    val token = value.substringBefore(" http").trim()
                    if (token.isNotEmpty() && !token.equals("free", ignoreCase = true) && !token.equals("available", ignoreCase = true)) {
                        statuses += token
                    }
                }
                key in NAMESERVER_KEYS -> {
                    val ns = value.substringBefore(' ').trim().trimEnd('.').lowercase(Locale.ROOT)
                    if (ns.contains('.')) {
                        nameservers += ns
                    }
                }
            }
        }

        val lowerText = text.lowercase(Locale.ROOT)
        val hasPositiveSignal = expiryDateIso != null ||
            registrationDateIso != null ||
            lastChangedDateIso != null ||
            registrarName != null ||
            nameservers.isNotEmpty() ||
            statuses.isNotEmpty()

        val looksNotFound = NOT_FOUND_PATTERNS.any { pattern -> lowerText.contains(pattern) }
        if (looksNotFound && !hasPositiveSignal) {
            throw NoSuchElementException("未查询到「$queriedDomain」的域名注册记录")
        }
        if (!hasPositiveSignal) {
            throw IllegalStateException("WHOIS 响应未包含可识别的域名记录")
        }

        val resolvedDomain = domainName
            ?: VpsAssetRecord.normalizeDomainName(queriedDomain)
            ?: queriedDomain.trim().lowercase(Locale.ROOT)

        DomainRdapResult(
            domainName = resolvedDomain,
            registrarName = registrarName,
            registrarIanaId = registrarIanaId,
            expiryDateIso = expiryDateIso,
            expiryInstantIso = expiryInstantIso,
            registrationDateIso = registrationDateIso,
            lastChangedDateIso = lastChangedDateIso,
            statuses = statuses.toList(),
            nameservers = nameservers.toList(),
            sourceUrl = sourceUrl,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        )
    }
}
