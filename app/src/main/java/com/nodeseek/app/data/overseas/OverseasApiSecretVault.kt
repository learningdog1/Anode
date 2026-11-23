package com.nodeseek.app.data.overseas

import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Compile-time obfuscated credential & TLS pinning vault for the Overseas Promotions API.
 *
 * All sensitive material (API endpoint, 256-bit master shared secret, pinned X.509 certificate,
 * and SPKI pin) is split across interleaved byte shards with per-index bit rotation and a
 * 32-bit SplitMix/Murmur rolling XOR stream so plaintext strings never appear in source or DEX.
 */
object OverseasApiSecretVault {

    private const val MASK_SALT: UInt = 0x5A3C96E1u

    private const val URL_SEED: UInt = 0x1F73A92Bu
    private val URL_SHARD_A = byteArrayOf(
        29, -113, -70, 108, -65, 41, -57, -118, 33, 65, 12, 102, 5, -69,
    )
    private val URL_SHARD_B = byteArrayOf(
        -91, -6, 93, -48, -81, 71, -13, -24, -53, -77, 62, 76, 87,
    )

    private const val SECRET_SEED: UInt = 0x6D4E8C15u
    private val SECRET_SHARD_A = byteArrayOf(
        75, 107, -110, -94, 119, -18, 79, 103, -110, -84, 96, -52, -3, 65, 3, -15,
    )
    private val SECRET_SHARD_B = byteArrayOf(
        31, -47, -92, 42, 120, -3, 111, -12, -35, 99, 54, -85, -122, 51, -87, -54,
    )

    private const val PIN_SEED: UInt = 0x3B91E47Au
    private val PIN_SHARD_A = byteArrayOf(
        -20, 28, -81, 28, -91, 124, -18, -92, 74, 119, 28, -56, -13, 9, -3, 118,
        -118, -15, 94, 2, 41, -114, 79, 6, 47, -80,
    )
    private val PIN_SHARD_B = byteArrayOf(
        114, 83, -64, 106, 77, -115, 54, -24, 120, 112, 66, 101, 118, 42, -70, -95,
        -98, 65, 121, -63, -108, -21, -40, 33, -32,
    )

    private const val CERT_HASH_SEED: UInt = 0x7C25D08Eu
    private val CERT_HASH_SHARD_A = byteArrayOf(
        -36, -68, -32, 125, -66, 126, -79, 99, 124, 95, 11, 117, 62, -75, 81, 111,
    )
    private val CERT_HASH_SHARD_B = byteArrayOf(
        92, 121, 36, 114, -36, 93, 18, -1, 4, 86, 75, 64, 98, 75, -32, 67,
    )

    private const val CERT_DER_SEED: UInt = 0x4E82B63Du
    private val CERT_DER_SHARD_A = byteArrayOf(
        91, -20, -108, -79, -77, 88, 39, -46, -127, 51, -26, 41, -54, 91, 58, 65,
        -122, -52, -4, 15, -54, 43, -111, -91, -108, -30, -90, -54, 9, -15, -12, 7,
        -45, 72, -42, 72, 31, 111, 11, 47, 101, -80, 61, -25, -64, -118, -45, 94,
        -97, 2, 106, -79, -30, 84, -110, -67, 71, -23, 44, -3, 81, 29, -45, 7,
        54, 124, 72, -12, 3, 26, 64, -29, -20, 116, 51, -34, -74, 18, 28, -116,
        -128, -86, -88, -104, 51, -43, -75, -11, -40, 10, 77, 125, -40, 27, 38, 14,
        119, -123, -124, 99, 76, 88, 51, 67, -118, -73, 31, -26, -37, -54, -9, 45,
        115, -123, -83, -34, 18, 108, 122, 84, 92, -88, -91, -10, -124, -116, -108, -62,
        -83, -17, 17, 50, 107, -101, 30, -28, 7, -23, 65, -42, 99, -53, -113, 74,
        -109, -56, 7, -41, -116, 66, -108, 106, -77, 79, 52, 52, 69, -81, 88, -108,
        44, -26, -94, -104, -118, 70, -86, 23, -34, -86, 32, -19, 12, 101, -67, 117,
        125, 71, -48, -78, 30, 27, -78, 51, -98, -80, 36, 100, 52, -120, 32, 101,
        -40, 95, -54, 19, -14, -83, -77, 110, -106, 26, -100, -80, -1, 27, 44, 112,
        -47, 106, -98, -105, 50, 92, -74, 98, -19, -113, -121, -89, 111, -49, 5, 126,
        -31, -15, -5, 35, 94, 101, -77, 85, -53, 78, 44, 120, -5, 28, -86, 26,
        -43, -34, 17, -84, -38, 55, -76, 16, 37, 100, -31, -14, -86, -109, 1, -11,
        -122, -12, 84, 52, 61, -44, -75, -76, 62, -33, -88, 10, -112, 28, 2, 89,
        93, -98, 115, 30, -104, 3, 78, -6, -46, 94, -121, 10, 15, -111, 4, 45,
        49, 116, 113, 91, -116, -89, 125, 108, -22, -71, -113, -36, 64, 60, -50, -97,
        12, -17, -78, -113, 114, 101, 95, -120, -6, -65, -68, -10, -10, 127, -19, -36,
        -57, -17, -17, 42, 10, -62, 73, 120, 124, 120, -109, 105, -111, -70, 92, -72,
        -7, -24, 85, -20, -89, 42, -74, -64, 95, -92, -52, 119, -74, 106, -83, 19,
        100, 120, 22, 38, 86, -89, 25, -35, -2, 96, 85, -72, 65, -36, -89, 124,
        -50, 107, -13, 77, -124, 61, -49, 72, -112, 26, -103, 79, 93, 55, 88, 79,
        114, 61, 122, -54, -108, -92, 109, 25, 12, -16, -80, 65, -54, -18, 110, 94,
        -75, 114, 4, 45, -4, -127, 60, -20, -119,
    )
    private val CERT_DER_SHARD_B = byteArrayOf(
        -27, 13, 85, -81, -116, 117, 111, 29, -113, 55, 39, 113, -70, -73, -97, -128,
        15, -87, 53, 40, -72, 10, -20, 122, 31, 121, -12, -74, 93, -9, -19, -101,
        -46, -59, 67, -2, 21, -41, -25, -27, 115, 20, -22, 20, -98, -88, 67, 22,
        -126, 55, -76, 25, -110, -9, -62, -93, -16, 123, -122, 103, 35, -99, -93, 41,
        109, -24, -42, 44, 95, -55, -126, 110, -32, -42, 81, -68, 71, 114, 102, -50,
        33, 102, -124, -98, 51, -105, 57, 68, -26, -126, 70, 61, 84, 71, -124, -24,
        -33, -105, -72, -7, 24, 36, -46, -74, -7, 26, 34, -54, -126, 105, 95, -58,
        -10, -67, -106, -89, -5, 102, 28, 85, -88, -54, 125, -34, 107, -93, 91, 52,
        113, -40, -40, 84, -58, 10, -11, 53, -103, -21, -82, -76, 10, 90, -32, 21,
        16, -113, 32, -12, -113, -45, -85, 88, -29, 102, -98, 21, -127, -24, -17, -23,
        100, 43, 35, -3, -41, 30, -25, -33, 116, 60, 57, -24, -19, -104, -25, -80,
        112, 48, 109, 32, -38, 1, -68, 24, 3, -29, 38, 109, 68, -70, 55, -40,
        78, -10, 59, -22, -65, -97, 90, 40, -52, 82, 21, -125, -10, 69, 92, -11,
        -36, 59, 92, 17, 58, 4, -29, -128, -111, -36, 108, 2, 20, -56, 80, 14,
        63, 53, -37, -14, -82, 54, 123, 95, -70, 114, 120, 112, -28, -57, -19, -102,
        55, 27, -53, -117, -74, 72, -30, 79, -116, 80, -93, -84, 61, 40, -17, -50,
        10, 17, -34, -1, 2, 61, 69, 49, 37, -6, -92, 104, -12, -49, 121, -8,
        -70, 66, -18, -70, -80, -76, 67, -52, -87, 82, -85, 120, -16, -73, -87, -19,
        -2, -49, -7, -25, 118, 92, 16, 3, -29, -70, -93, -41, -6, 113, 65, -77,
        30, -63, -116, 29, -108, 123, -81, 119, 52, -76, -43, -11, 27, -100, 118, -73,
        -41, 122, -54, -121, -40, 94, 31, 24, 110, 0, -48, 11, 116, 81, -100, -63,
        -3, -92, -101, 41, -59, -37, 72, -5, -94, 90, -76, -125, -1, 115, -19, -62,
        -103, -126, -97, -22, 103, 109, -29, -121, -28, 26, 36, 83, 68, 103, -19, 10,
        -23, 107, -114, 81, -27, -76, 60, 120, 85, -5, -101, 120, 23, 10, 68, -114,
        63, -95, -73, -80, 11, 15, -122, 91, -65, 40, 96, 21, 106, 31, -34, -44,
        77, -35, -48, -82, -22, -76, 99, 77,
    )

    fun resolveBaseUrl(): String =
        decodeShards(URL_SHARD_A, URL_SHARD_B, URL_SEED).toString(Charsets.UTF_8)

    fun resolveMasterSecret(): ByteArray =
        decodeShards(SECRET_SHARD_A, SECRET_SHARD_B, SECRET_SEED)

    fun resolveSpkiPin(): String =
        decodeShards(PIN_SHARD_A, PIN_SHARD_B, PIN_SEED).toString(Charsets.UTF_8)

    fun resolveExpectedCertSha256(): ByteArray =
        decodeShards(CERT_HASH_SHARD_A, CERT_HASH_SHARD_B, CERT_HASH_SEED)

    fun resolvePinnedCertificate(): X509Certificate {
        val derBytes = decodeShards(CERT_DER_SHARD_A, CERT_DER_SHARD_B, CERT_DER_SEED)
        val factory = CertificateFactory.getInstance("X.509")
        val cert = ByteArrayInputStream(derBytes).use { stream ->
            factory.generateCertificate(stream) as X509Certificate
        }
        val actualHash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        if (!MessageDigest.isEqual(actualHash, resolveExpectedCertSha256())) {
            throw SecurityException("Pinned overseas server certificate integrity check failed")
        }
        return cert
    }

    /**
     * Shared singleton [OkHttpClient] reusing connection pool, dispatcher, and TLS sessions
     * across repository instances.
     */
    val sharedPinnedHttpClient: OkHttpClient by lazy { buildPinnedHttpClient() }

    /**
     * Builds an [OkHttpClient] exclusively trusting the pinned X.509 v3 server certificate
     * (plus optional backup certificates/SPKI pins for zero-downtime rotation) with redirects
     * disabled so signed headers can never leak cross-origin.
     */
    fun buildPinnedHttpClient(
        connectTimeoutSec: Long = 10L,
        readTimeoutSec: Long = 15L,
        callTimeoutSec: Long = 20L,
        backupSpkiPins: List<String> = emptyList(),
        backupLeafSha256Fingerprints: List<ByteArray> = emptyList(),
        backupCertificates: List<X509Certificate> = emptyList(),
    ): OkHttpClient {
        val pinnedCert = resolvePinnedCertificate()
        val expectedLeafSha256 = resolveExpectedCertSha256()
        val trustedCerts = buildList {
            add(pinnedCert)
            addAll(backupCertificates)
        }
        val acceptedFingerprints = buildList {
            add(expectedLeafSha256)
            backupLeafSha256Fingerprints.forEach { if (it.size == 32) add(it.copyOf()) }
            backupCertificates.forEach { cert ->
                add(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
            }
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            trustedCerts.forEachIndexed { index, cert ->
                setCertificateEntry("overseas_intel_server_$index", cert)
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val delegateTrustManager = tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager initialized for pinned cert")

        val strictPinnedTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                delegateTrustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Empty server certificate chain")
                }
                delegateTrustManager.checkServerTrusted(chain, authType)
                val leafSha256 = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                val matched = acceptedFingerprints.any { expected ->
                    MessageDigest.isEqual(leafSha256, expected)
                }
                if (!matched) {
                    throw CertificateException("Server certificate SHA-256 fingerprint mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = trustedCerts.toTypedArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(strictPinnedTrustManager), SecureRandom())
        }

        val targetHost = resolveBaseUrl().toHttpUrl().host
        val pinnerBuilder = CertificatePinner.Builder()
            .add(targetHost, resolveSpkiPin())
        backupSpkiPins
            .map { it.trim() }
            .filter { it.startsWith("sha256/") || it.startsWith("sha1/") }
            .distinct()
            .forEach { backupPin -> pinnerBuilder.add(targetHost, backupPin) }
        val pinner = pinnerBuilder.build()

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, strictPinnedTrustManager)
            .certificatePinner(pinner)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(5, 5L, TimeUnit.MINUTES))
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            // Disabled here so OverseasIntelApiClient retries with a fresh HMAC nonce & backoff+jitter.
            .retryOnConnectionFailure(false)
            .build()
    }

    internal fun decodeShards(
        evenShard: ByteArray,
        oddShard: ByteArray,
        seed32: UInt,
    ): ByteArray {
        val total = evenShard.size + oddShard.size
        val out = ByteArray(total)
        for (i in 0 until total) {
            val raw = if ((i and 1) == 0) evenShard[i ushr 1] else oddShard[i ushr 1]
            val enc = raw.toInt() and 0xFF
            val shift = (i % 5) + 1
            val unmasked = enc xor maskByte(seed32, i)
            out[i] = rotr8(unmasked, shift).toByte()
        }
        return out
    }

    private fun maskByte(seed32: UInt, index: Int): Int {
        var x = (seed32 xor MASK_SALT) + ((index.toUInt() + 1u) * 0x9E3779B9u)
        x = (x xor (x shr 16)) * 0x85EBCA6Bu
        x = (x xor (x shr 13)) * 0xC2B2AE35u
        x = x xor (x shr 16)
        return (x and 0xFFu).toInt()
    }

    private fun rotr8(value: Int, shift: Int): Int {
        val v = value and 0xFF
        return ((v ushr shift) or (v shl (8 - shift))) and 0xFF
    }
}
