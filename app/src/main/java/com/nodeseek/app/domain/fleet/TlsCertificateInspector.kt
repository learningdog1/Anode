package com.nodeseek.app.domain.fleet

import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Outcome of inspecting a host's HTTPS TLS leaf [X509Certificate].
 */
data class TlsCertificateInspectionResult(
    val host: String,
    val port: Int,
    val isTrusted: Boolean,
    val notBeforeEpochMillis: Long,
    val notAfterEpochMillis: Long,
    val expiresDateIso: String,
    val daysRemaining: Long,
    val isExpired: Boolean,
    val issuer: String,
    val subject: String,
    val sanDnsNames: List<String>,
    val checkedAtEpochMillis: Long,
    val warningMessage: String? = null,
)

/**
 * Captured TLS peer certificate chain and trust evaluation outcome.
 */
data class TlsPeerChain(
    val certificates: List<X509Certificate>,
    val isTrusted: Boolean,
    val trustWarning: String? = null,
)

/**
 * Injectable TLS socket operations boundary enabling deterministic JVM unit tests without network I/O.
 */
interface TlsSocketOps {
    suspend fun fetchPeerCertificates(
        host: String,
        port: Int,
        timeoutMillis: Int,
    ): TlsPeerChain
}

/**
 * Inspects an HTTPS endpoint's TLS certificate validity, issuer, SANs, and remaining days.
 */
interface TlsCertificateInspector {
    suspend fun inspect(
        rawHost: String,
        port: Int = VpsAssetRecord.DEFAULT_TLS_PORT,
    ): Result<TlsCertificateInspectionResult>

    companion object {
        /**
         * Pure-JVM extraction & remaining-days calculation from an [X509Certificate].
         *
         * Negative [daysRemaining] and `isExpired = true` are preserved accurately when
         * `cert.notAfter` is in the past.
         */
        fun extractFromCertificate(
            host: String,
            port: Int,
            cert: X509Certificate,
            isTrusted: Boolean,
            warningMessage: String? = null,
            nowEpochMillis: Long = System.currentTimeMillis(),
            zoneId: ZoneId = VALUATION_ZONE_ID,
        ): TlsCertificateInspectionResult {
            val notBeforeMillis = cert.notBefore.time
            val notAfterMillis = cert.notAfter.time
            val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
            val expiryDate = Instant.ofEpochMilli(notAfterMillis).atZone(zoneId).toLocalDate()
            val daysRemaining = ChronoUnit.DAYS.between(today, expiryDate)
            val isNotYetValid = notBeforeMillis > nowEpochMillis
            val isExpired = notAfterMillis < nowEpochMillis || daysRemaining < 0L
            val effectiveTrusted = isTrusted && !isNotYetValid && !isExpired
            val effectiveWarning = when {
                !warningMessage.isNullOrBlank() -> warningMessage
                isNotYetValid -> "证书尚未生效"
                else -> warningMessage
            }

            val issuerDn = cert.issuerX500Principal?.name.orEmpty()
            val subjectDn = cert.subjectX500Principal?.name.orEmpty()
            val issuerDisplay = extractOrgOrCn(issuerDn) ?: issuerDn.ifBlank { "未知签发机构" }
            val subjectDisplay = extractCnOnly(subjectDn) ?: extractOrgOrCn(subjectDn) ?: host

            val sanDnsNames = runCatching {
                cert.subjectAlternativeNames
                    ?.mapNotNull { entry ->
                        val type = entry.getOrNull(0) as? Int
                        val value = entry.getOrNull(1) as? String
                        if (type == 2 && !value.isNullOrBlank()) value.trim().lowercase(Locale.ROOT) else null
                    }
                    ?.distinct()
                    .orEmpty()
            }.getOrDefault(emptyList())

            return TlsCertificateInspectionResult(
                host = host,
                port = port,
                isTrusted = effectiveTrusted,
                notBeforeEpochMillis = notBeforeMillis,
                notAfterEpochMillis = notAfterMillis,
                expiresDateIso = DateTimeFormatter.ISO_LOCAL_DATE.format(expiryDate),
                daysRemaining = daysRemaining,
                isExpired = isExpired,
                issuer = issuerDisplay,
                subject = subjectDisplay,
                sanDnsNames = sanDnsNames,
                checkedAtEpochMillis = nowEpochMillis,
                warningMessage = effectiveWarning,
            )
        }

        private fun extractOrgOrCn(dn: String): String? {
            if (dn.isBlank()) return null
            val attrs = parseRdnAttributes(dn)
            return attrs["O"]?.takeIf { it.isNotBlank() }
                ?: attrs["CN"]?.takeIf { it.isNotBlank() }
        }

        private fun extractCnOnly(dn: String): String? {
            if (dn.isBlank()) return null
            return parseRdnAttributes(dn)["CN"]?.takeIf { it.isNotBlank() }
        }

        internal fun parseRdnAttributes(dn: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val n = dn.length
            var i = 0
            while (i < n) {
                while (i < n && (dn[i] == ',' || dn[i] == '+' || dn[i].isWhitespace())) i++
                if (i >= n) break
                val keyStart = i
                while (i < n && dn[i] != '=' && dn[i] != ',') i++
                if (i >= n || dn[i] != '=') {
                    while (i < n && dn[i] != ',') i++
                    continue
                }
                val key = dn.substring(keyStart, i).trim().uppercase(Locale.ROOT)
                i++ // skip '='
                while (i < n && dn[i].isWhitespace()) i++
                val valueSb = StringBuilder()
                if (i < n && dn[i] == '"') {
                    i++ // skip opening quote
                    while (i < n) {
                        val ch = dn[i]
                        if (ch == '\\' && i + 1 < n) {
                            valueSb.append(dn[i + 1])
                            i += 2
                        } else if (ch == '"') {
                            i++ // skip closing quote
                            break
                        } else {
                            valueSb.append(ch)
                            i++
                        }
                    }
                    while (i < n && dn[i] != ',' && dn[i] != '+') i++
                } else {
                    while (i < n) {
                        val ch = dn[i]
                        if (ch == '\\' && i + 1 < n) {
                            valueSb.append(dn[i + 1])
                            i += 2
                        } else if (ch == ',' || ch == '+') {
                            break
                        } else {
                            valueSb.append(ch)
                            i++
                        }
                    }
                }
                val value = valueSb.toString().trim()
                if (key.isNotEmpty() && value.isNotEmpty() && key !in result) {
                    result[key] = value
                }
            }
            return result
        }
    }
}
