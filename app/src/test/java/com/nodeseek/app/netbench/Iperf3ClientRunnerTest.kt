package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.Iperf3ClientRunner
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3ErrorKind
import com.nodeseek.app.domain.netbench.Iperf3Stage
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.concurrent.thread

class Iperf3ClientRunnerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `cookie generator produces 36 base32 chars plus trailing NUL byte`() {
        val cookie = Iperf3ClientRunner.generateCookieBytes()
        assertEquals(37, cookie.size)
        assertEquals(0.toByte(), cookie[36])
        val prefix = String(cookie, 0, 36, StandardCharsets.US_ASCII)
        assertTrue("Cookie must match [a-z2-7]{36}, got: $prefix", Regex("^[a-z2-7]{36}$").matches(prefix))
    }

    @Test
    fun `upload mode executes full iperf3 v3 protocol state machine without reverse flag`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable?>(null)
        val receivedParamsJson = AtomicReference<String?>(null)
        val receivedClientResultsJson = AtomicReference<String?>(null)
        val receivedDataBytes = AtomicLong(0L)
        val receivedDoneState = AtomicBoolean(false)

        val serverThread = thread(isDaemon = true) {
            try {
                server.soTimeout = 6_000
                val ctrlSocket = server.accept()
                ctrlSocket.soTimeout = 6_000
                val ctrlIn = DataInputStream(ctrlSocket.getInputStream())
                val ctrlOut = DataOutputStream(ctrlSocket.getOutputStream())

                // 1. Read 37-byte cookie
                val ctrlCookie = ByteArray(37)
                ctrlIn.readFully(ctrlCookie)
                assertEquals(0.toByte(), ctrlCookie[36])

                // 2. Send PARAM_EXCHANGE (9) & read framed params JSON
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_PARAM_EXCHANGE.toInt())
                ctrlOut.flush()
                val paramLen = ctrlIn.readInt()
                val paramBytes = ByteArray(paramLen)
                ctrlIn.readFully(paramBytes)
                receivedParamsJson.set(String(paramBytes, StandardCharsets.UTF_8))

                // 3. Send CREATE_STREAMS (10) & accept data stream with identical cookie
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_CREATE_STREAMS.toInt())
                ctrlOut.flush()
                val dataSocket = server.accept()
                dataSocket.soTimeout = 4_000
                val dataIn = DataInputStream(dataSocket.getInputStream())
                val dataCookie = ByteArray(37)
                dataIn.readFully(dataCookie)
                assertArrayEquals(ctrlCookie, dataCookie)

                // 4. Send TEST_START (1) -> TEST_RUNNING (2)
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_START.toInt())
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_RUNNING.toInt())
                ctrlOut.flush()

                // Drain uploaded data chunks concurrently while waiting for TEST_END (4) on control
                val stopDrain = AtomicBoolean(false)
                val drainThread = thread(isDaemon = true) {
                    val buf = ByteArray(65536)
                    while (!stopDrain.get()) {
                        val n = runCatching { dataIn.read(buf) }.getOrDefault(-1)
                        if (n <= 0) break
                        receivedDataBytes.addAndGet(n.toLong())
                    }
                }

                val endState = ctrlIn.readByte()
                assertEquals(Iperf3ClientRunner.STATE_TEST_END, endState)
                stopDrain.set(true)
                runCatching { dataSocket.close() }
                drainThread.join(1_000)

                // 5. Send EXCHANGE_RESULTS (13)
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_EXCHANGE_RESULTS.toInt())
                ctrlOut.flush()

                val clientResLen = ctrlIn.readInt()
                val clientResBytes = ByteArray(clientResLen)
                ctrlIn.readFully(clientResBytes)
                receivedClientResultsJson.set(String(clientResBytes, StandardCharsets.UTF_8))

                val serverResJson = """
                    {
                      "cpu_util_total": 1.2,
                      "streams": [
                        {
                          "id": 1,
                          "bytes": ${receivedDataBytes.get().coerceAtLeast(131072L)},
                          "retransmits": 2,
                          "start_time": 0.0,
                          "end_time": 1.0
                        }
                      ]
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                ctrlOut.writeInt(serverResJson.size)
                ctrlOut.write(serverResJson)
                ctrlOut.flush()

                // 6. Send DISPLAY_RESULTS (14) and expect client to send IPERF_DONE (16)
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_DISPLAY_RESULTS.toInt())
                ctrlOut.flush()

                val doneByte = runCatching { ctrlIn.readByte() }.getOrNull()
                if (doneByte == Iperf3ClientRunner.STATE_IPERF_DONE) {
                    receivedDoneState.set(true)
                }
                ctrlSocket.close()
            } catch (t: Throwable) {
                serverError.set(t)
            } finally {
                runCatching { server.close() }
            }
        }

        val runner = Iperf3ClientRunner(
            ioDispatcher = Dispatchers.IO,
            progressIntervalMs = 150L,
        )
        val emissions = runner.run(
            Iperf3Config(
                target = "127.0.0.1",
                port = server.localPort,
                durationSec = 1,
                direction = Iperf3Direction.UPLOAD,
            ),
        ).toList()

        serverThread.join(4_000)
        assertNull("Mock iperf3 server threw: ${serverError.get()}", serverError.get())

        // Verify JSON params sent in PARAM_EXCHANGE (must NOT contain "reverse")
        val paramsObj = json.parseToJsonElement(receivedParamsJson.get()!!).jsonObject
        assertTrue(paramsObj["tcp"]!!.jsonPrimitive.boolean)
        assertEquals(1, paramsObj["time"]!!.jsonPrimitive.int)
        assertEquals(1, paramsObj["parallel"]!!.jsonPrimitive.int)
        assertEquals(131072, paramsObj["len"]!!.jsonPrimitive.int)
        assertFalse("Upload mode must omit 'reverse' key", paramsObj.containsKey("reverse"))

        // Verify client results JSON
        val clientResObj = json.parseToJsonElement(receivedClientResultsJson.get()!!).jsonObject
        val streamBytes = clientResObj["streams"]!!.jsonArray[0].jsonObject["bytes"]!!.jsonPrimitive.long
        assertTrue("Expected positive uploaded bytes, got $streamBytes", streamBytes > 0L)
        assertTrue("Client should send IPERF_DONE (16)", receivedDoneState.get())

        // Verify flow emissions & final result
        assertTrue(emissions.any { it.stage == Iperf3Stage.PARAM_EXCHANGE })
        assertTrue(emissions.any { it.stage == Iperf3Stage.CREATE_STREAMS })
        assertTrue(emissions.any { it.stage == Iperf3Stage.TEST_RUNNING })
        val finalProgress = emissions.last()
        assertEquals(Iperf3Stage.COMPLETED, finalProgress.stage)
        val result = finalProgress.finalResult
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals(Iperf3Direction.UPLOAD, result.direction)
        assertTrue(result.bytesTransferred > 0L)
        assertTrue(result.clientMbps > 0.0)
        assertNotNull(result.serverMbps)
        assertTrue(result.serverMbps!! > 0.0)
        assertEquals(2L, result.retransmits)
    }

    @Test
    fun `download reverse mode sends reverse true and measures downloaded Mbps`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable?>(null)
        val receivedParamsJson = AtomicReference<String?>(null)
        val sentDownloadBytes = AtomicLong(0L)

        val serverThread = thread(isDaemon = true) {
            try {
                server.soTimeout = 6_000
                val ctrlSocket = server.accept()
                ctrlSocket.soTimeout = 6_000
                val ctrlIn = DataInputStream(ctrlSocket.getInputStream())
                val ctrlOut = DataOutputStream(ctrlSocket.getOutputStream())

                val ctrlCookie = ByteArray(37)
                ctrlIn.readFully(ctrlCookie)

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_PARAM_EXCHANGE.toInt())
                ctrlOut.flush()
                val paramLen = ctrlIn.readInt()
                val paramBytes = ByteArray(paramLen)
                ctrlIn.readFully(paramBytes)
                receivedParamsJson.set(String(paramBytes, StandardCharsets.UTF_8))

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_CREATE_STREAMS.toInt())
                ctrlOut.flush()
                val dataSocket = server.accept()
                val dataIn = DataInputStream(dataSocket.getInputStream())
                val dataCookie = ByteArray(37)
                dataIn.readFully(dataCookie)
                assertArrayEquals(ctrlCookie, dataCookie)

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_START.toInt())
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_RUNNING.toInt())
                ctrlOut.flush()

                // Stream chunks from server -> client until client signals TEST_END (4)
                val stopSend = AtomicBoolean(false)
                val senderThread = thread(isDaemon = true) {
                    val chunk = ByteArray(65536) { 0x5A }
                    val out = dataSocket.getOutputStream()
                    while (!stopSend.get()) {
                        try {
                            out.write(chunk)
                            sentDownloadBytes.addAndGet(chunk.size.toLong())
                            Thread.sleep(5)
                        } catch (_: Exception) {
                            break
                        }
                    }
                }

                val endState = ctrlIn.readByte()
                assertEquals(Iperf3ClientRunner.STATE_TEST_END, endState)
                stopSend.set(true)
                runCatching { dataSocket.close() }
                senderThread.join(1_000)

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_EXCHANGE_RESULTS.toInt())
                ctrlOut.flush()

                val clientResLen = ctrlIn.readInt()
                val clientResBytes = ByteArray(clientResLen)
                ctrlIn.readFully(clientResBytes)

                val serverResJson = """
                    {"streams":[{"id":1,"bytes":${sentDownloadBytes.get()},"retransmits":0,"start_time":0.0,"end_time":1.0}]}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                ctrlOut.writeInt(serverResJson.size)
                ctrlOut.write(serverResJson)
                ctrlOut.flush()

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_DISPLAY_RESULTS.toInt())
                ctrlOut.flush()
                runCatching { ctrlIn.readByte() }
                ctrlSocket.close()
            } catch (t: Throwable) {
                serverError.set(t)
            } finally {
                runCatching { server.close() }
            }
        }

        val runner = Iperf3ClientRunner(
            ioDispatcher = Dispatchers.IO,
            progressIntervalMs = 150L,
        )
        val emissions = runner.run(
            Iperf3Config(
                target = "127.0.0.1",
                port = server.localPort,
                durationSec = 1,
                direction = Iperf3Direction.DOWNLOAD,
            ),
        ).toList()

        serverThread.join(4_000)
        assertNull("Mock iperf3 server threw: ${serverError.get()}", serverError.get())

        val paramsObj = json.parseToJsonElement(receivedParamsJson.get()!!).jsonObject
        assertTrue("Download mode must send reverse=true", paramsObj["reverse"]!!.jsonPrimitive.boolean)

        val finalProgress = emissions.last()
        assertEquals(Iperf3Stage.COMPLETED, finalProgress.stage)
        val result = finalProgress.finalResult!!
        assertTrue(result.success)
        assertEquals(Iperf3Direction.DOWNLOAD, result.direction)
        assertTrue("Expected positive downloaded bytes", result.bytesTransferred > 0L)
        assertTrue("Expected positive download Mbps", result.clientMbps > 0.0)
    }

    @Test
    fun `ACCESS_DENIED (-1) emits friendly Chinese busy diagnostic`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            try {
                server.soTimeout = 4_000
                val socket = server.accept()
                val input = DataInputStream(socket.getInputStream())
                val cookie = ByteArray(37)
                input.readFully(cookie)
                socket.getOutputStream().write(Iperf3ClientRunner.STATE_ACCESS_DENIED.toInt())
                socket.getOutputStream().flush()
                socket.close()
            } finally {
                runCatching { server.close() }
            }
        }

        val runner = Iperf3ClientRunner(ioDispatcher = Dispatchers.IO)
        val emissions = runner.run(
            Iperf3Config(
                target = "127.0.0.1",
                port = server.localPort,
                durationSec = 1,
                direction = Iperf3Direction.DOWNLOAD,
            ),
        ).toList()
        serverThread.join(2_000)

        val last = emissions.last()
        assertEquals(Iperf3Stage.FAILED, last.stage)
        assertEquals(Iperf3ErrorKind.ACCESS_DENIED, last.errorKind)
        assertNotNull(last.errorMessage)
        assertTrue(last.errorMessage!!.contains("服务器正忙"))
        assertFalse(last.finalResult!!.success)
    }

    @Test
    fun `SERVER_ERROR (-2) and connection refused emit friendly Chinese diagnostics`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            try {
                server.soTimeout = 4_000
                val socket = server.accept()
                val input = DataInputStream(socket.getInputStream())
                input.readFully(ByteArray(37))
                val out = DataOutputStream(socket.getOutputStream())
                out.writeByte(Iperf3ClientRunner.STATE_SERVER_ERROR.toInt())
                out.writeInt(108)
                out.writeInt(0)
                out.flush()
                socket.close()
            } finally {
                runCatching { server.close() }
            }
        }

        val runner = Iperf3ClientRunner(ioDispatcher = Dispatchers.IO)
        val errEmissions = runner.run(
            Iperf3Config(target = "127.0.0.1", port = server.localPort, durationSec = 1),
        ).toList()
        serverThread.join(2_000)

        val errLast = errEmissions.last()
        assertEquals(Iperf3Stage.FAILED, errLast.stage)
        assertEquals(Iperf3ErrorKind.SERVER_ERROR, errLast.errorKind)
        assertTrue(errLast.errorMessage!!.contains("108"))

        // Connection refused on unused port
        val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val refusedEmissions = runner.run(
            Iperf3Config(target = "127.0.0.1", port = unusedPort, durationSec = 1),
        ).toList()
        val refusedLast = refusedEmissions.last()
        assertEquals(Iperf3Stage.FAILED, refusedLast.stage)
        assertEquals(Iperf3ErrorKind.CONNECTION_REFUSED, refusedLast.errorKind)
        assertTrue(refusedLast.errorMessage!!.contains("iperf3 -s"))

        // Non-iperf3 service (e.g. OpenSSH banner) on wrong port fails immediately with clear diagnostic
        val sshBannerServer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val sshThread = thread(isDaemon = true) {
            try {
                sshBannerServer.soTimeout = 4_000
                val socket = sshBannerServer.accept()
                socket.getOutputStream().write("SSH-2.0-OpenSSH_9.6\r\n".toByteArray(StandardCharsets.US_ASCII))
                socket.getOutputStream().flush()
                Thread.sleep(100)
                socket.close()
            } finally {
                runCatching { sshBannerServer.close() }
            }
        }
        val nonIperfEmissions = runner.run(
            Iperf3Config(target = "127.0.0.1", port = sshBannerServer.localPort, durationSec = 1),
        ).toList()
        sshThread.join(2_000)
        val nonIperfLast = nonIperfEmissions.last()
        assertEquals(Iperf3Stage.FAILED, nonIperfLast.stage)
        assertEquals(Iperf3ErrorKind.PROTOCOL_ERROR, nonIperfLast.errorKind)
        assertTrue(nonIperfLast.errorMessage!!.contains("非 iperf3 协议响应"))
    }

    @Test
    fun `binds control and data sockets to direct underlying network by default and reports EPERM lockdown cleanly`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            try {
                server.soTimeout = 4_000
                repeat(2) {
                    val s = server.accept()
                    val input = DataInputStream(s.getInputStream())
                    input.readFully(ByteArray(37))
                    s.getOutputStream().write(Iperf3ClientRunner.STATE_ACCESS_DENIED.toInt())
                    s.getOutputStream().flush()
                    s.close()
                }
            } finally {
                runCatching { server.close() }
            }
        }

        val directSocketCalls = AtomicInteger(0)
        val proxySocketCalls = AtomicInteger(0)
        val directChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket {
                directSocketCalls.incrementAndGet()
                return Socket()
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val proxyChannel = object : NetBenchSocketChannel {
            override val networkId: String = "vpn-200"
            override val transportLabel: String = "VPN 代理"
            override val isVpn: Boolean = true
            override val isDirectPhysical: Boolean = false
            override val bypassSystemHttpProxy: Boolean = false
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket {
                proxySocketCalls.incrementAndGet()
                return Socket()
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(proxyChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        NetworkDescriptor(directChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI)),
                    ),
                )
                return ResolvedNetworkBinding(plan, directChannel, proxyChannel)
            }
        }

        val runner = Iperf3ClientRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
        )
        runner.run(Iperf3Config(target = "127.0.0.1", port = server.localPort, durationSec = 1, useProxy = false)).toList()
        assertEquals(1, directSocketCalls.get())
        assertEquals(0, proxySocketCalls.get())

        runner.run(Iperf3Config(target = "127.0.0.1", port = server.localPort, durationSec = 1, useProxy = true)).toList()
        assertEquals(1, directSocketCalls.get())
        assertEquals(1, proxySocketCalls.get())
        serverThread.join(2_000)

        // Verify EPERM lockdown error emitted cleanly inside Flow
        val blockedDirectChannel = object : NetBenchSocketChannel by directChannel {
            override fun createSocket(): Socket = throw SocketException("bind failed: EPERM (Operation not permitted)")
        }
        val blockedBridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(proxyChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        NetworkDescriptor(blockedDirectChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI)),
                    ),
                )
                return ResolvedNetworkBinding(plan, blockedDirectChannel, proxyChannel)
            }
        }
        val blockedRunner = Iperf3ClientRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = blockedBridge),
            ioDispatcher = Dispatchers.IO,
            networkBridge = blockedBridge,
        )
        val blockedLast = blockedRunner.run(
            Iperf3Config(target = "127.0.0.1", port = 5201, durationSec = 1, useProxy = false),
        ).toList().last()
        assertEquals(Iperf3Stage.FAILED, blockedLast.stage)
        assertEquals(Iperf3ErrorKind.IO_ERROR, blockedLast.errorKind)
        assertTrue(blockedLast.errorMessage!!.contains("经由代理测试"))
    }
}
