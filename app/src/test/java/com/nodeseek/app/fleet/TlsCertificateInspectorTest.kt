package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.JvmTlsCertificateInspector
import com.nodeseek.app.domain.fleet.TlsCertificateInspector
import com.nodeseek.app.domain.fleet.TlsPeerChain
import com.nodeseek.app.domain.fleet.TlsSocketOps
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.util.Date
import javax.security.auth.x500.X500Principal

@OptIn(ExperimentalCoroutinesApi::class)
class TlsCertificateInspectorTest {

    private fun stubX509Cert(
        notBeforeMillis: Long,
        notAfterMillis: Long,
        issuerDn: String = "CN=R10, O=Let's Encrypt, C=US",
        subjectDn: String = "CN=example.com",
        sanDns: List<String> = listOf("example.com", "www.example.com"),
    ): X509Certificate = object : X509Certificate() {
        override fun getNotBefore(): Date = Date(notBeforeMillis)
        override fun getNotAfter(): Date = Date(notAfterMillis)
        override fun getIssuerX500Principal(): X500Principal = X500Principal(issuerDn)
        override fun getSubjectX500Principal(): X500Principal = X500Principal(subjectDn)
        override fun getSubjectAlternativeNames(): MutableCollection<MutableList<*>> =
            sanDns.mapTo(mutableListOf()) { dns -> mutableListOf<Any>(2, dns) }

        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        @Suppress("DEPRECATION")
        override fun getIssuerDN(): Principal = issuerX500Principal
        @Suppress("DEPRECATION")
        override fun getSubjectDN(): Principal = subjectX500Principal
        override fun getTBSCertificate(): ByteArray = ByteArray(0)
        override fun getSignature(): ByteArray = ByteArray(0)
        override fun getSigAlgName(): String = "SHA256withRSA"
        override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun getEncoded(): ByteArray = ByteArray(0)
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "StubX509($subjectDn)"
        override fun getPublicKey(): PublicKey = throw UnsupportedOperationException()
        override fun hasUnsupportedCriticalExtension(): Boolean = false
        override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
        override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }

    @Test
    fun `extractFromCertificate handles valid, expiring-today, and expired negative days accurately`() {
        val today = LocalDate.of(2026, 6, 1)
        val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val notBefore = today.minusDays(30).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()

        // 1. Valid 45 days remaining
        val validNotAfter = today.plusDays(45).atTime(23, 59).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val validRes = TlsCertificateInspector.extractFromCertificate(
            host = "example.com",
            port = 443,
            cert = stubX509Cert(notBefore, validNotAfter),
            isTrusted = true,
            nowEpochMillis = nowMillis,
            zoneId = VALUATION_ZONE_ID,
        )
        assertEquals(45L, validRes.daysRemaining)
        assertFalse(validRes.isExpired)
        assertTrue(validRes.isTrusted)
        assertEquals("Let's Encrypt", validRes.issuer)
        assertEquals("example.com", validRes.subject)
        assertEquals(listOf("example.com", "www.example.com"), validRes.sanDnsNames)

        // 2. Expiring today (0 days remaining, later today)
        val todayEnd = today.atTime(23, 59).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val todayRes = TlsCertificateInspector.extractFromCertificate(
            host = "example.com",
            port = 443,
            cert = stubX509Cert(notBefore, todayEnd),
            isTrusted = true,
            nowEpochMillis = nowMillis,
            zoneId = VALUATION_ZONE_ID,
        )
        assertEquals(0L, todayRes.daysRemaining)
        assertFalse(todayRes.isExpired)

        // 3. Expired 10 days ago
        val expiredNotAfter = today.minusDays(10).atTime(10, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val expiredRes = TlsCertificateInspector.extractFromCertificate(
            host = "example.com",
            port = 443,
            cert = stubX509Cert(notBefore, expiredNotAfter),
            isTrusted = false,
            warningMessage = "Certificate expired",
            nowEpochMillis = nowMillis,
            zoneId = VALUATION_ZONE_ID,
        )
        assertEquals(-10L, expiredRes.daysRemaining)
        assertTrue(expiredRes.isExpired)
        assertFalse(expiredRes.isTrusted)
    }

    @Test
    fun `JvmTlsCertificateInspector surfaces socket failure cleanly and succeeds with injected TlsSocketOps`() = runTest {
        val today = LocalDate.of(2026, 6, 1)
        val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val notAfter = today.plusDays(60).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()

        val failingInspector = JvmTlsCertificateInspector(
            socketOps = object : TlsSocketOps {
                override suspend fun fetchPeerCertificates(host: String, port: Int, timeoutMillis: Int): TlsPeerChain {
                    throw IOException("Connection refused")
                }
            },
            nowMillisProvider = { nowMillis },
        )
        val failRes = failingInspector.inspect("example.com", 443)
        assertTrue(failRes.isFailure)
        assertTrue(failRes.exceptionOrNull()!!.message!!.contains("Connection refused"))

        val successInspector = JvmTlsCertificateInspector(
            socketOps = object : TlsSocketOps {
                override suspend fun fetchPeerCertificates(host: String, port: Int, timeoutMillis: Int): TlsPeerChain =
                    TlsPeerChain(
                        certificates = listOf(stubX509Cert(nowMillis - 10_000L, notAfter)),
                        isTrusted = true,
                    )
            },
            nowMillisProvider = { nowMillis },
        )
        val okRes = successInspector.inspect("https://example.com:8443/path", 8443)
        assertTrue(okRes.isSuccess)
        assertEquals(60L, okRes.getOrThrow().daysRemaining)
        assertEquals(8443, okRes.getOrThrow().port)
    }

    @Test
    fun `extractFromCertificate unquotes RFC 4514 escaped commas in issuer and subject DNs cleanly`() {
        val today = LocalDate.of(2026, 6, 1)
        val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val notBefore = today.minusDays(5).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val notAfter = today.plusDays(90).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()

        val cert = stubX509Cert(
            notBeforeMillis = notBefore,
            notAfterMillis = notAfter,
            issuerDn = "OU=Trust\\, Division, O=\"Acme\\, Inc.\", C=US",
            subjectDn = "CN=\"Wildcard\\, Example\", O=\"Example\\, Ltd.\"",
        )
        val res = TlsCertificateInspector.extractFromCertificate(
            host = "example.com",
            port = 443,
            cert = cert,
            isTrusted = true,
            nowEpochMillis = nowMillis,
            zoneId = VALUATION_ZONE_ID,
        )
        assertEquals("Acme, Inc.", res.issuer)
        assertEquals("Wildcard, Example", res.subject)

        val directParsed = TlsCertificateInspector.parseRdnAttributes("CN=R10, O=\"Acme\\, Inc.\", C=US")
        assertEquals("Acme, Inc.", directParsed["O"])
        assertEquals("R10", directParsed["CN"])
    }

    @Test
    fun `extractFromCertificate_futureNotBefore_flagsWarning`() {
        val today = LocalDate.of(2026, 6, 1)
        val nowMillis = today.atTime(12, 0).atZone(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val futureNotBefore = today.plusDays(10).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()
        val notAfter = today.plusDays(100).atStartOfDay(VALUATION_ZONE_ID).toInstant().toEpochMilli()

        val cert = stubX509Cert(
            notBeforeMillis = futureNotBefore,
            notAfterMillis = notAfter,
        )
        val res = TlsCertificateInspector.extractFromCertificate(
            host = "future.example.com",
            port = 443,
            cert = cert,
            isTrusted = true,
            nowEpochMillis = nowMillis,
            zoneId = VALUATION_ZONE_ID,
        )
        assertFalse(res.isTrusted)
        assertTrue(res.warningMessage?.contains("尚未生效") == true)
    }
}
