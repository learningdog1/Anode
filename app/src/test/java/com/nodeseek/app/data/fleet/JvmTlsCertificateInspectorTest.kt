package com.nodeseek.app.data.fleet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

@OptIn(ExperimentalCoroutinesApi::class)
class JvmTlsCertificateInspectorTest {

    private data class SelfSignedServerMaterial(
        val sslContext: SSLContext,
        val trustManager: X509ExtendedTrustManager,
        val certificate: X509Certificate,
    )

    private fun generateSelfSignedMaterial(
        commonName: String,
        sanDnsNames: List<String>,
        validityDays: Int = 30,
        startDateOffsetDays: Int? = null,
    ): SelfSignedServerMaterial {
        val tempDir = Files.createTempDirectory("anode_jvm_tls_test_").toFile()
        try {
            val keystoreFile = File(tempDir, "server.p12")
            val password = "changeit".toCharArray()
            val keytoolBin = File(System.getProperty("java.home"), "bin/keytool")
                .takeIf { it.isFile && it.canExecute() }
                ?.absolutePath
                ?: "keytool"
            val sanExt = sanDnsNames.distinct().joinToString(",") { "DNS:$it" }
            val cmd = buildList {
                add(keytoolBin)
                add("-genkeypair")
                add("-alias"); add("server")
                add("-keyalg"); add("EC")
                add("-groupname"); add("secp256r1")
                add("-sigalg"); add("SHA256withECDSA")
                if (startDateOffsetDays != null) {
                    add("-startdate"); add("${startDateOffsetDays}d")
                }
                add("-validity"); add(validityDays.toString())
                add("-dname"); add("CN=$commonName")
                if (sanExt.isNotEmpty()) {
                    add("-ext"); add("SAN=$sanExt")
                }
                add("-storetype"); add("PKCS12")
                add("-keystore"); add(keystoreFile.absolutePath)
                add("-storepass"); add(String(password))
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "keytool failed: $out" }

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                keystoreFile.inputStream().use { load(it, password) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val tm = tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().single()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf(tm), SecureRandom())
            }
            return SelfSignedServerMaterial(
                sslContext = sslContext,
                trustManager = tm,
                certificate = keyStore.getCertificate("server") as X509Certificate,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun startLoopbackTlsServer(material: SelfSignedServerMaterial): Pair<Int, AutoCloseable> {
        val closed = AtomicBoolean(false)
        val serverSocket = (material.sslContext.serverSocketFactory.createServerSocket(
            0,
            8,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket).apply {
            reuseAddress = true
        }
        val thread = Thread {
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
            isDaemon = true
            start()
        }
        val closeable = AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                runCatching { serverSocket.close() }
                thread.interrupt()
            }
        }
        return serverSocket.localPort to closeable
    }

    @Test
    fun `JvmTlsSocketOps enforces HTTPS SAN verification via X509ExtendedTrustManager`() = runTest {
        val material = generateSelfSignedMaterial(
            commonName = "valid.nodeseek.test",
            sanDnsNames = listOf("valid.nodeseek.test", "*.sub.nodeseek.test"),
        )
        val (port, server) = startLoopbackTlsServer(material)
        server.use {
            val socketOps = JvmTlsSocketOps(
                ioDispatcher = Dispatchers.IO,
                trustManagerProvider = { material.trustManager },
                connectAddressResolver = { host, _ ->
                    InetSocketAddress(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)), port)
                },
            )
            val inspector = JvmTlsCertificateInspector(socketOps = socketOps)

            // 1. Matching SAN succeeds and isTrusted == true
            val validResult = inspector.inspect("valid.nodeseek.test", port).getOrThrow()
            assertTrue("Expected matching SAN to be trusted", validResult.isTrusted)
            assertNull(validResult.warningMessage)
            assertTrue("valid.nodeseek.test" in validResult.sanDnsNames)

            // 2. Matching wildcard SAN succeeds
            val wildcardResult = inspector.inspect("api.sub.nodeseek.test", port).getOrThrow()
            assertTrue("Expected wildcard SAN to be trusted", wildcardResult.isTrusted)
            assertNull(wildcardResult.warningMessage)

            // 3. Mismatched SAN MUST be flagged untrusted (isTrusted == false) with warningMessage
            val mismatchResult = inspector.inspect("wrong-host.nodeseek.test", port).getOrThrow()
            assertFalse("Mismatched SAN must fail HTTPS endpoint verification", mismatchResult.isTrusted)
            assertNotNull("Mismatched SAN should expose warningMessage", mismatchResult.warningMessage)
            assertEquals("wrong-host.nodeseek.test", mismatchResult.host)
        }
    }

    @Test
    fun `JvmTlsSocketOps captures expired certificate chain with negative daysRemaining`() = runTest {
        val expiredMaterial = generateSelfSignedMaterial(
            commonName = "expired.nodeseek.test",
            sanDnsNames = listOf("expired.nodeseek.test"),
            validityDays = 5,
            startDateOffsetDays = -20,
        )
        // Use a distinct trust manager (not containing the expired leaf as a TrustAnchor)
        // so PKIX evaluates leaf validity and rejects the expired certificate chain.
        val unrelatedTrustMaterial = generateSelfSignedMaterial(
            commonName = "ca.nodeseek.test",
            sanDnsNames = listOf("ca.nodeseek.test"),
        )
        val (port, server) = startLoopbackTlsServer(expiredMaterial)
        server.use {
            val socketOps = JvmTlsSocketOps(
                ioDispatcher = Dispatchers.IO,
                trustManagerProvider = { unrelatedTrustMaterial.trustManager },
                connectAddressResolver = { host, _ ->
                    InetSocketAddress(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)), port)
                },
            )
            val inspector = JvmTlsCertificateInspector(socketOps = socketOps)

            val res = inspector.inspect("expired.nodeseek.test", port).getOrThrow()
            assertFalse("Expired/untrusted certificate must not be trusted", res.isTrusted)
            assertTrue("Expired certificate must report isExpired = true", res.isExpired)
            assertTrue("Expired certificate must have negative daysRemaining", res.daysRemaining < 0L)
            assertNotNull(res.warningMessage)
        }
    }

    @Test
    fun `JvmTlsSocketOps wraps plain non-extended X509TrustManager gracefully`() = runTest {
        val material = generateSelfSignedMaterial(
            commonName = "plain-tm.nodeseek.test",
            sanDnsNames = listOf("plain-tm.nodeseek.test"),
        )
        val plainTm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                material.trustManager.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                material.trustManager.checkServerTrusted(chain, authType)

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                material.trustManager.acceptedIssuers
        }
        val (port, server) = startLoopbackTlsServer(material)
        server.use {
            val socketOps = JvmTlsSocketOps(
                ioDispatcher = Dispatchers.IO,
                trustManagerProvider = { plainTm },
                connectAddressResolver = { host, _ ->
                    InetSocketAddress(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)), port)
                },
            )
            val inspector = JvmTlsCertificateInspector(socketOps = socketOps)
            val res = inspector.inspect("plain-tm.nodeseek.test", port).getOrThrow()
            assertTrue(res.isTrusted)
        }
    }
}
