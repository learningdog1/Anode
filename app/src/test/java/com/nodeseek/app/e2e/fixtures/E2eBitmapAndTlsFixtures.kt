package com.nodeseek.app.e2e.fixtures

import com.nodeseek.app.domain.fleet.TlsPeerChain
import com.nodeseek.app.domain.fleet.TlsSocketOps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Pure-JVM deterministic PNG byte builders and self-signed TLS PKCS12 fixture helpers
 * for `MockWebServer.useHttps(...)` and image-upload / TLS inspection E2E tests.
 */
object E2eBitmapAndTlsFixtures {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47,
        0x0D, 0x0A, 0x1A, 0x0A,
    )

    data class TlsServerFixture(
        val sslContext: SSLContext,
        val trustManager: X509TrustManager,
        val certificate: X509Certificate,
    )

    /**
     * Returns a deterministic valid 1x1 RGBA PNG byte array.
     */
    fun minimalPngBytes(): ByteArray = solidRgbaPngBytes(width = 1, height = 1, argb = 0xFF2D637E.toInt())

    /**
     * Generates a valid uncompressed-filter RGBA PNG of [width]x[height] filled with [argb]
     * using pure JVM `Deflater` + `CRC32` (no `android.graphics.Bitmap` dependency).
     */
    fun solidRgbaPngBytes(
        width: Int,
        height: Int,
        argb: Int = 0xFF2D637E.toInt(),
    ): ByteArray {
        require(width > 0 && height > 0) { "PNG dimensions must be positive: ${width}x$height" }
        val a = ((argb ushr 24) and 0xFF).toByte()
        val r = ((argb ushr 16) and 0xFF).toByte()
        val g = ((argb ushr 8) and 0xFF).toByte()
        val b = (argb and 0xFF).toByte()

        val rowStride = 1 + width * 4
        val rawPixels = ByteArray(rowStride * height)
        for (y in 0 until height) {
            val rowStart = y * rowStride
            rawPixels[rowStart] = 0 // Filter type 0 (None)
            for (x in 0 until width) {
                val pxOffset = rowStart + 1 + x * 4
                rawPixels[pxOffset] = r
                rawPixels[pxOffset + 1] = g
                rawPixels[pxOffset + 2] = b
                rawPixels[pxOffset + 3] = a
            }
        }

        val compressedPixels = deflateBytes(rawPixels)
        val ihdrPayload = ByteArrayOutputStream(13).apply {
            DataOutputStream(this).use { dos ->
                dos.writeInt(width)
                dos.writeInt(height)
                dos.writeByte(8) // bit depth
                dos.writeByte(6) // color type: RGBA
                dos.writeByte(0) // compression method
                dos.writeByte(0) // filter method
                dos.writeByte(0) // interlace method
            }
        }.toByteArray()

        return ByteArrayOutputStream().use { out ->
            out.write(PNG_SIGNATURE)
            writeChunk(out, "IHDR", ihdrPayload)
            writeChunk(out, "IDAT", compressedPixels)
            writeChunk(out, "IEND", ByteArray(0))
            out.toByteArray()
        }
    }

    /**
     * Generates an in-memory PKCS12 self-signed ECDSA TLS fixture via JDK `keytool`.
     *
     * Pass [startDateOffsetDays] = `-30` and [validityDays] = `7` to produce an expired certificate
     * for TLS expiration E2E verification.
     */
    fun createSelfSignedTlsFixture(
        commonName: String = "localhost",
        sanDnsNames: List<String> = listOf("localhost", "api.nodeimage.com", "www.nodeseek.com", "rdap.org"),
        sanIps: List<String> = listOf("127.0.0.1"),
        validityDays: Int = 7,
        startDateOffsetDays: Int? = null,
    ): TlsServerFixture {
        require(validityDays > 0) { "validityDays must be positive" }
        val tempDir = Files.createTempDirectory("anode_e2e_tls_").toFile()
        try {
            val keystoreFile = File(tempDir, "e2e_tls.p12")
            val passwordChars = "changeit".toCharArray()
            val keytoolBin = File(System.getProperty("java.home"), "bin/keytool")
                .takeIf { it.isFile && it.canExecute() }
                ?.absolutePath
                ?: "keytool"

            val sanEntries = buildList {
                sanDnsNames.distinct().forEach { add("DNS:$it") }
                sanIps.distinct().forEach { add("IP:$it") }
            }.joinToString(",")

            val command = buildList {
                add(keytoolBin)
                add("-genkeypair")
                add("-alias")
                add("e2etest")
                add("-keyalg")
                add("EC")
                add("-groupname")
                add("secp256r1")
                add("-sigalg")
                add("SHA256withECDSA")
                if (startDateOffsetDays != null) {
                    add("-startdate")
                    add("${startDateOffsetDays}d")
                }
                add("-validity")
                add(validityDays.toString())
                add("-dname")
                add("CN=$commonName")
                if (sanEntries.isNotEmpty()) {
                    add("-ext")
                    add("SAN=$sanEntries")
                }
                add("-storetype")
                add("PKCS12")
                add("-keystore")
                add(keystoreFile.absolutePath)
                add("-storepass")
                add(String(passwordChars))
            }

            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "keytool failed generating TLS fixture: $output" }

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                keystoreFile.inputStream().use { load(it, passwordChars) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, passwordChars)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
            }
            val cert = keyStore.getCertificate("e2etest") as X509Certificate
            return TlsServerFixture(
                sslContext = sslContext,
                trustManager = trustManager,
                certificate = cert,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun deflateBytes(input: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size + 16)
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4) { "PNG chunk type must be 4 bytes: $type" }
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        dos.writeInt(crc)
        dos.flush()
    }

    /**
     * In-process loopback [SSLServerSocket] serving a [TlsServerFixture] over real TLS handshakes
     * on `127.0.0.1:<ephemeral-port>` for pure-JVM R8 certificate inspection E2E tests.
     */
    class LocalTlsServer(
        val fixture: TlsServerFixture,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val serverSocket: SSLServerSocket =
            (fixture.sslContext.serverSocketFactory.createServerSocket(
                0,
                16,
                InetAddress.getByName("127.0.0.1"),
            ) as SSLServerSocket).apply {
                reuseAddress = true
            }

        val port: Int
            get() = serverSocket.localPort

        private val acceptThread = Thread {
            while (!closed.get() && !serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept() as SSLSocket
                } catch (_: Throwable) {
                    break
                }
                Thread {
                    client.use { sock ->
                        runCatching {
                            sock.soTimeout = 3_000
                            sock.useClientMode = false
                            sock.startHandshake()
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
        }.apply {
            name = "e2e-local-tls-${serverSocket.localPort}"
            isDaemon = true
            start()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { serverSocket.close() }
                acceptThread.interrupt()
            }
        }
    }

    /**
     * Pure-JVM [TlsSocketOps] connecting to a loopback [LocalTlsServer] (or closed ephemeral port)
     * while preserving real SNI, optional HTTPS SAN/hostname verification, and peer certificate
     * capture for expired/self-signed certificates.
     */
    class LoopbackTlsSocketOps(
        private val targetIp: String = "127.0.0.1",
        private val portOverride: Int? = null,
        private val trustManager: X509TrustManager? = null,
        private val enforceHttpsHostnameMatch: Boolean = true,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : TlsSocketOps {

        override suspend fun fetchPeerCertificates(
            host: String,
            port: Int,
            timeoutMillis: Int,
        ): TlsPeerChain = withContext(ioDispatcher) {
            val delegateTm = trustManager ?: loadDefaultTrustManager()
            var capturedChain: List<X509Certificate> = emptyList()
            var isTrusted = true
            var trustWarning: String? = null

            val recordingTm = object : X509ExtendedTrustManager() {
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    delegateTm?.acceptedIssuers ?: emptyArray()

                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    delegateTm?.checkClientTrusted(chain, authType)
                }

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                    socket: Socket?,
                ) {
                    val extended = delegateTm as? X509ExtendedTrustManager
                    if (extended != null) {
                        extended.checkClientTrusted(chain, authType, socket)
                    } else {
                        delegateTm?.checkClientTrusted(chain, authType)
                    }
                }

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                    engine: SSLEngine?,
                ) {
                    val extended = delegateTm as? X509ExtendedTrustManager
                    if (extended != null) {
                        extended.checkClientTrusted(chain, authType, engine)
                    } else {
                        delegateTm?.checkClientTrusted(chain, authType)
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
                    if (delegateTm == null) {
                        isTrusted = false
                        trustWarning = "系统根证书库不可用"
                        return
                    }
                    try {
                        delegateCheck(delegateTm)
                    } catch (ce: CertificateException) {
                        isTrusted = false
                        trustWarning = ce.message?.takeIf { it.isNotBlank() } ?: "证书未受系统信任或已过期"
                    }
                }
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(recordingTm), null)
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            try {
                val connectPort = portOverride ?: port
                socket.soTimeout = timeoutMillis
                socket.connect(InetSocketAddress(targetIp, connectPort), timeoutMillis)
                socket.sslParameters = SSLParameters().apply {
                    serverNames = listOf(SNIHostName(host))
                    if (enforceHttpsHostnameMatch) {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                }
                socket.startHandshake()
                val chain = if (capturedChain.isNotEmpty()) {
                    capturedChain
                } else {
                    socket.session.peerCertificates.filterIsInstance<X509Certificate>()
                }
                if (enforceHttpsHostnameMatch && isTrusted) {
                    val leaf = chain.firstOrNull()
                    if (leaf != null && !matchesTlsHostname(host, leaf)) {
                        isTrusted = false
                        trustWarning = trustWarning ?: "No subject alternative DNS name matching $host found."
                    }
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

        private fun loadDefaultTrustManager(): X509TrustManager? = runCatching {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()

        private fun matchesTlsHostname(host: String, cert: X509Certificate): Boolean {
            val normalizedHost = host.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (normalizedHost.isEmpty()) return false
            val sans = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
            val dnsNames = sans.mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int
                val value = entry.getOrNull(1) as? String
                if (type == 2 && !value.isNullOrBlank()) value.trim().trimEnd('.').lowercase(Locale.ROOT) else null
            }
            val ipNames = sans.mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int
                val value = entry.getOrNull(1) as? String
                if (type == 7 && !value.isNullOrBlank()) {
                    runCatching { InetAddress.getByName(value.trim()).hostAddress }.getOrNull()
                } else {
                    null
                }
            }
            val isIpLiteral = normalizedHost.all { it.isDigit() || it == '.' || it == ':' }
            if (isIpLiteral) {
                val targetIpAddr = runCatching { InetAddress.getByName(normalizedHost).hostAddress }.getOrNull()
                return targetIpAddr != null && targetIpAddr in ipNames
            }
            if (dnsNames.isNotEmpty()) {
                return dnsNames.any { pattern -> matchesDnsPattern(normalizedHost, pattern) }
            }
            val cn = cert.subjectX500Principal?.name
                ?.split(',')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("CN=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.trimEnd('.')
                ?.lowercase(Locale.ROOT)
            return cn != null && matchesDnsPattern(normalizedHost, cn)
        }

        private fun matchesDnsPattern(host: String, pattern: String): Boolean {
            if (host == pattern) return true
            if (!pattern.startsWith("*.")) return false
            val suffix = pattern.removePrefix("*.")
            if (suffix.isEmpty() || !suffix.contains('.')) return false
            if (!host.endsWith(".$suffix")) return false
            val prefix = host.removeSuffix(".$suffix")
            return prefix.isNotEmpty() && !prefix.contains('.')
        }
    }
}
