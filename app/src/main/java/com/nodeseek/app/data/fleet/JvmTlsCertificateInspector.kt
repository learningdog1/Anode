package com.nodeseek.app.data.fleet

import com.nodeseek.app.domain.fleet.TlsCertificateInspectionResult
import com.nodeseek.app.domain.fleet.TlsCertificateInspector
import com.nodeseek.app.domain.fleet.TlsPeerChain
import com.nodeseek.app.domain.fleet.TlsSocketOps
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.ZoneId
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Production [TlsSocketOps] using `SSLSocket` + SNI + a delegating [X509ExtendedTrustManager] that
 * enforces HTTPS endpoint identification (SAN/hostname) while capturing the peer certificate chain
 * even when the certificate is expired, untrusted, or has a mismatched SAN, allowing callers to
 * inspect exact `notAfter` dates and negative `daysRemaining`.
 */
class JvmTlsSocketOps(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val trustManagerProvider: () -> X509TrustManager? = { loadDefaultX509TrustManager() },
    private val connectAddressResolver: (String, Int) -> InetSocketAddress = { host, port ->
        InetSocketAddress(host, port)
    },
) : TlsSocketOps {

    override suspend fun fetchPeerCertificates(
        host: String,
        port: Int,
        timeoutMillis: Int,
    ): TlsPeerChain = withContext(ioDispatcher) {
        val defaultTrustManager = trustManagerProvider()
        var capturedChain: List<X509Certificate> = emptyList()
        var isTrusted = true
        var trustWarning: String? = null

        val recordingTrustManager = object : X509ExtendedTrustManager() {
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                defaultTrustManager?.acceptedIssuers ?: emptyArray()

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTrustManager?.checkClientTrusted(chain, authType)
            }

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?,
            ) {
                val extended = defaultTrustManager as? X509ExtendedTrustManager
                if (extended != null) {
                    extended.checkClientTrusted(chain, authType, socket)
                } else {
                    defaultTrustManager?.checkClientTrusted(chain, authType)
                }
            }

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) {
                val extended = defaultTrustManager as? X509ExtendedTrustManager
                if (extended != null) {
                    extended.checkClientTrusted(chain, authType, engine)
                } else {
                    defaultTrustManager?.checkClientTrusted(chain, authType)
                }
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                verifyServerTrust(chain) { tm -> tm.checkServerTrusted(chain, authType) }
            }

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?,
            ) {
                verifyServerTrust(chain) { tm ->
                    val extended = tm as? X509ExtendedTrustManager
                    if (extended != null) {
                        extended.checkServerTrusted(chain, authType, socket)
                    } else {
                        tm.checkServerTrusted(chain, authType)
                    }
                }
            }

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) {
                verifyServerTrust(chain) { tm ->
                    val extended = tm as? X509ExtendedTrustManager
                    if (extended != null) {
                        extended.checkServerTrusted(chain, authType, engine)
                    } else {
                        tm.checkServerTrusted(chain, authType)
                    }
                }
            }

            private inline fun verifyServerTrust(
                chain: Array<out X509Certificate>?,
                delegateCheck: (X509TrustManager) -> Unit,
            ) {
                capturedChain = chain?.toList().orEmpty()
                if (defaultTrustManager == null) {
                    isTrusted = false
                    trustWarning = "系统根证书库不可用"
                    return
                }
                try {
                    delegateCheck(defaultTrustManager)
                } catch (ce: CertificateException) {
                    isTrusted = false
                    trustWarning = ce.message?.takeIf { it.isNotBlank() } ?: "证书未受系统信任或域名不匹配"
                }
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(recordingTrustManager), null)

        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = timeoutMillis
            socket.connect(connectAddressResolver(host, port), timeoutMillis)

            val params = SSLParameters().apply {
                serverNames = listOf(SNIHostName(host))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            socket.sslParameters = params
            socket.startHandshake()

            val chain = if (capturedChain.isNotEmpty()) {
                capturedChain
            } else {
                socket.session.peerCertificates.filterIsInstance<X509Certificate>()
            }
            TlsPeerChain(
                certificates = chain,
                isTrusted = isTrusted,
                trustWarning = trustWarning,
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        fun loadDefaultX509TrustManager(): X509TrustManager? = runCatching {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()
    }
}

/**
 * Default [TlsCertificateInspector] backed by [JvmTlsSocketOps].
 */
class JvmTlsCertificateInspector(
    private val socketOps: TlsSocketOps = JvmTlsSocketOps(),
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId = VALUATION_ZONE_ID,
) : TlsCertificateInspector {

    override suspend fun inspect(
        rawHost: String,
        port: Int,
    ): Result<TlsCertificateInspectionResult> {
        val cleanHost = VpsAssetRecord.normalizeDomainName(rawHost)
            ?: return Result.failure(IllegalArgumentException("请输入有效的 HTTPS 主机名或域名"))
        val cleanPort = port.coerceIn(1, 65535)

        return try {
            val peerChain = socketOps.fetchPeerCertificates(
                host = cleanHost,
                port = cleanPort,
                timeoutMillis = timeoutMillis,
            )
            val leaf = peerChain.certificates.firstOrNull()
                ?: return Result.failure(IOException("未能获取服务器 TLS 证书"))
            val extracted = TlsCertificateInspector.extractFromCertificate(
                host = cleanHost,
                port = cleanPort,
                cert = leaf,
                isTrusted = peerChain.isTrusted,
                warningMessage = peerChain.trustWarning,
                nowEpochMillis = nowMillisProvider(),
                zoneId = zoneId,
            )
            Result.success(extracted)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(
                IOException("HTTPS 证书检测失败：${t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName}", t),
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Int = 8_000
    }
}
