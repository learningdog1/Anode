package com.nodeseek.app.data.activity

import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
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
 * Compile-time obfuscated credential & TLS pinning vault for the ForumWatch Activity API.
 *
 * All sensitive material (API endpoint, master shared secret, pinned X.509 certificate,
 * and SPKI pin) is split across interleaved byte shards with per-index bit rotation and a
 * 32-bit SplitMix/Murmur rolling XOR stream so plaintext strings never appear in source or DEX.
 */
object ForumWatchActivitySecretVault {

    private const val MASK_SALT: UInt = 0x6B4D87F2u

    private const val URL_SEED: UInt = 0x2E84B73Cu
    private val URL_SHARD_A = byteArrayOf(
        -112, -31, -98, -99, -91, -13, 43, -76, 58, 55, -76, -38, -124, -80,
    )
    private val URL_SHARD_B = byteArrayOf(
        57, -112, 99, 76, 81, 27, 116, 86, -20, 76, 86, -51, -4,
    )

    private const val SECRET_SEED: UInt = 0x7A5F9D26u
    private val SECRET_SHARD_A = byteArrayOf(
        -5, 64, 55, -97, 105, -28, 120, 69, -65, -104, 102, -83, -104, 76, -33, -82,
        -29, -18, 107, -4, -57, 107, -118, 115, -17, 31, 59, 16, -14, -31, -10, 40,
    )
    private val SECRET_SHARD_B = byteArrayOf(
        59, 72, 123, -40, 17, -73, 30, -87, -57, 34, 18, 7, -17, -54, 56, 59,
        93, -113, -59, -108, -40, -79, 103, 89, 39, 84, -64, 101, 34, 87, 101, 11,
    )

    private const val PIN_SEED: UInt = 0x4C12F58Bu
    private val PIN_SHARD_A = byteArrayOf(
        24, -69, 68, -128, -56, -29, -4, -105, 66, -114, -11, -67, 126, 110, 85, -77,
        70, 73, 86, -65, -50, 20, -14, 9, -86, 15,
    )
    private val PIN_SHARD_B = byteArrayOf(
        49, -29, -52, -122, 60, 13, -46, 99, -49, 47, 94, 118, -36, -65, 108, -88,
        76, 111, -15, 65, 127, 117, -50, 47, 99,
    )

    private const val CERT_HASH_SEED: UInt = 0x8D36E19Fu
    private val CERT_HASH_SHARD_A = byteArrayOf(
        -126, 105, -111, 102, 106, 37, -62, -102, -93, -111, 124, 12, 66, 72, 10, -83,
    )
    private val CERT_HASH_SHARD_B = byteArrayOf(
        7, 3, 103, -14, -12, 106, -119, 103, -76, 117, 95, -85, 126, -101, -59, 92,
    )

    private const val CERT_DER_SEED: UInt = 0x5F93C74Eu
    private val CERT_DER_SHARD_A = byteArrayOf(
        36, -98, 34, 7, 114, 69, -21, -83, -128, -70, 0, -61, -39, 22, 84, -23,
        -85, 69, -79, 123, -86, 4, -9, -107, -75, -44, -1, 70, -79, -86, -68, 76,
        -24, 99, 82, -7, 25, 67, 39, -44, 22, -46, -84, -69, 116, -121, 74, 73,
        -48, -83, 112, 52, 115, 98, -26, -104, 95, 80, -118, -116, 101, -79, -29, 79,
        10, 127, -20, -115, -125, 12, -29, 78, 115, -56, -87, -123, 25, 110, 29, 80,
        13, -96, 109, -41, -30, 35, 27, -116, 108, -55, 83, -75, -89, -46, -35, -22,
        -55, 81, -96, 78, 64, -59, 48, -47, -105, 31, -63, -124, -108, 16, -125, 9,
        -54, -81, -13, 73, 113, 98, -79, -11, 60, -94, 1, -97, -92, -14, -121, 4,
        -20, 112, -21, 98, -8, 74, -33, 127, 31, 74, 117, 56, -114, -51, -64, 8,
        -112, -89, 113, 64, -112, 21, 14, -85, -97, 89, -48, 12, -90, 109, 73, 1,
        79, 61, -94, -75, 59, -118, -66, 88, 79, -113, 15, -41, -11, -88, -9, -25,
        -17, 10, 86, -112, 80, 88, 27, -114, 3, -125, -126, 51, -97, -30, -34, -85,
        -48, -91, 106, 122, -78, 41, 44, -26, 75, 15, 101, 92, -93, -81, -62, 94,
        -92, -41, -57, 4, -45, 105, -66, -74, 104, -3, 42, -83, 106, -60, -35, 40,
        80, -50, -73, -26, 79, 21, -66, -105, 46, -78, -111, 12, -42, 40, -45, -126,
        -14, 30, 105, -34, -71, -91, -23, -1, -124, 4, 62, -97, 117, 38, 61, 88,
        117, -101, 26, 70, 127, 9, -84, 2, 57, -84, -81, -76, 25, 40, -117, -17,
        -125, -62, 56, -24, 74, 118, -79, 31, -77, 28, 90, -16, 60, 117, -3, -22,
        16, 77, 127, 55, 0, 74, -33, 45, 95, 56, 11, -85, -89, -5, 22, -29,
        117, -75, -71, -78, 78, 40, 109, -5, -27, 59, 102, 88, 20, 0, -48, -65,
        -103, 3, -82, -75, 106, -58, 112, -50, -41, 66, -2, -49, -83, 68, 85, -27,
        -88, 75, -15, 49, -123, 113, -105, -69, -31, -24, -40, 71, 56, -23, 38, -103,
        82, 86, -118, -37, 6, -57, 28, 65, -81, 27, -102, -99, 105, -97, 97, 86,
        -51, -67, 55, 43, -37, 70, 41, 40, -15, -8, -127, 45, 104, 86, -109, 124,
        79, -49, 73, -24, 95, 6, -60, 84, -22, -7, 127, 21, -25, 30, 7, -34,
        18, 7, 71, 123, 40, -47, -85, 34, 67, -19, 57, 122, -38, -52, -81, -21,
        -15, 105, 2, -82, -43, 84, -114, 107, 123, 40, 48, 27, 82, 73, -112, -20,
        -38, -44, -70, 58, 22, 124, 77, -6, 122, 56, -21, -116, -1, 16, 45, -65,
        -13, -115, -114, -9, -116,
    )
    private val CERT_DER_SHARD_B = byteArrayOf(
        -91, 88, -41, -30, -54, 50, -97, -66, -31, 13, -95, -65, 61, -89, 13, -69,
        -15, -21, -52, -11, -92, 52, -6, -108, 33, -17, 12, 81, -71, 119, 94, -63,
        117, 5, -35, 119, 97, -7, -38, -7, -69, -22, 56, -15, -14, 30, -6, 62,
        77, 127, 24, 91, -116, -11, -94, -43, -4, 89, -68, -127, 14, 122, 83, -1,
        41, 83, 12, -89, -83, -49, -115, 93, -18, 100, 87, 82, -111, 67, -38, 44,
        121, -30, 65, -95, 88, 104, -84, 34, -36, 38, 80, -62, 60, 97, -74, -59,
        42, 91, -98, -1, 83, 10, -95, -59, -8, 105, 114, 101, -27, -18, 55, 30,
        42, -61, -83, -37, -17, 42, -35, -125, -59, 14, -85, 103, 75, -94, 0, -30,
        -101, 26, 23, 38, 111, -106, -31, 72, -109, -125, 73, 98, -111, -115, -74, -87,
        -110, -79, -122, -68, 24, -106, 88, 76, 88, -123, 34, 34, -43, 43, 85, 46,
        -98, -71, -118, -85, -90, -122, -82, 45, -113, -86, -32, -86, -101, -30, -106, 97,
        -69, -18, 107, -7, 117, 30, 62, -109, -63, 93, 57, -75, 103, 82, -120, -85,
        -121, -106, -126, 80, -42, 16, 59, -10, 7, -32, -14, 22, 31, -113, -76, 24,
        93, -110, 12, 80, -88, -73, 27, 94, -117, -103, 53, 101, -1, -25, 98, 28,
        -123, -70, 85, -46, -80, 28, -59, -14, -55, -52, 53, 9, -47, -77, 55, 90,
        96, -24, 57, 23, -44, -128, 53, 118, -54, 81, 25, 123, 21, -36, 26, 3,
        102, 107, -120, -22, -63, 125, 100, -108, -46, -105, 86, -57, 11, -32, -82, 89,
        99, 7, 102, -93, -89, -125, 67, -104, 2, 82, 9, -58, 105, -71, -117, 102,
        11, -25, -21, -104, 111, -88, 61, 18, 63, -79, 54, -11, 112, -108, -110, 47,
        -6, 109, -18, 25, -86, 13, -58, -63, 77, 99, 35, -12, -88, -117, -125, -39,
        -17, -50, 14, 123, -25, 38, -91, 31, 81, -66, 14, 95, 72, -115, -50, -98,
        104, 24, 19, -32, 78, -8, 88, 119, -124, -34, -59, -75, -53, 78, -112, -25,
        -23, -28, -111, 18, 3, -112, -21, -45, -72, 43, 117, -59, 105, 52, -13, 18,
        -5, 90, -28, 81, 38, -15, -64, 12, 84, 51, 63, -97, -110, 64, -18, 120,
        -42, -4, -108, 85, 73, -51, -118, 78, 105, 71, -61, -1, 5, 83, 113, 54,
        65, -75, -121, 75, 28, -59, 121, 124, 71, -111, -42, -116, -18, 82, -13, -12,
        78, -33, -6, -69, -121, -75, 93, -27, 72, -17, -107, 104, -8, -14, -95, 61,
        -17, 120, 47, 4, 32, -54, 108, 3, -15, 6, -79, -72, 126, 104, -14, 10,
        -45, -85, 7, 94, 116,
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
            throw SecurityException("Pinned ForumWatch activity server certificate integrity check failed")
        }
        return cert
    }

    /**
     * Shared singleton [OkHttpClient] reusing connection pool, dispatcher, and HTTP/2 + TLS 1.3
     * sessions across ForumWatch activity repository instances.
     */
    val sharedPinnedHttpClient: OkHttpClient by lazy { buildPinnedHttpClient() }

    /**
     * Builds an [OkHttpClient] exclusively trusting the pinned X.509 v3 server certificate
     * plus SPKI [CertificatePinner] verification.
     */
    fun buildPinnedHttpClient(
        connectTimeoutSec: Long = 10L,
        readTimeoutSec: Long = 15L,
        callTimeoutSec: Long = 20L,
    ): OkHttpClient {
        val pinnedCert = resolvePinnedCertificate()
        val expectedLeafSha256 = resolveExpectedCertSha256()

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("forumwatch_activity_server", pinnedCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val delegateTrustManager = tmf.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager initialized for pinned ForumWatch cert")

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
                if (!MessageDigest.isEqual(leafSha256, expectedLeafSha256)) {
                    throw CertificateException("ForumWatch server certificate SHA-256 fingerprint mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinnedCert)
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(strictPinnedTrustManager), SecureRandom())
        }

        val targetHost = resolveBaseUrl().toHttpUrl().host
        val pinner = CertificatePinner.Builder()
            .add(targetHost, resolveSpkiPin())
            .build()

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, strictPinnedTrustManager)
            .certificatePinner(pinner)
            .connectionPool(ConnectionPool(5, 5L, TimeUnit.MINUTES))
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            // Disabled here so ForumWatchActivityApiClient retries with a fresh HMAC X-Nonce & backoff+jitter.
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
