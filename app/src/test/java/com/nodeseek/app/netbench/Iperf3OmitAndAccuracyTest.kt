package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.Iperf3ClientRunner
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.Iperf3Stage
import com.nodeseek.app.domain.netbench.NetBenchHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Accuracy fixes verified against a real Evoxt VPS:
 * - upload headline uses the receiver (server) figure instead of bytes written into the local send buffer;
 * - `-O` warm-up seconds are excluded from averages like official iperf3;
 * - corrupted latency values persisted by older builds are dropped from history.
 */
class Iperf3OmitAndAccuracyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `upload effectiveMbps prefers receiver-side server rate`() {
        val upload = Iperf3Result(
            direction = Iperf3Direction.UPLOAD,
            port = 5201,
            resolvedTarget = null,
            durationSec = 10.0,
            bytesTransferred = 138_936_320L,
            clientMbps = 111.1,
            serverMbps = 97.8,
            success = true,
        )
        assertEquals(97.8, upload.effectiveMbps, 1e-9)
        assertEquals(111.1, upload.copy(serverMbps = null).effectiveMbps, 1e-9)

        val download = upload.copy(direction = Iperf3Direction.DOWNLOAD, clientMbps = 378.5, serverMbps = 378.1)
        assertEquals(378.5, download.effectiveMbps, 1e-9)
        assertEquals(378.1, download.copy(clientMbps = 0.0).effectiveMbps, 1e-9)
    }

    @Test
    fun `history drops corrupted latency values from older builds`() {
        val dirty = NetBenchHistoryEntry(
            host = "1.1.1.1",
            port = 443,
            lastAvgMs = 13.4,
            lastPingAvgMs = 745123913967336.8,
            lastPingLossPercent = 0.0,
        )
        val clean = dirty.sanitizedMetrics()
        assertNull(clean.lastPingAvgMs)
        assertEquals(13.4, clean.lastAvgMs!!, 1e-9)
        assertEquals(0.0, clean.lastPingLossPercent!!, 1e-9)
        assertNull(dirty.copy(lastAvgMs = Double.NaN).sanitizedMetrics().lastAvgMs)
        assertNull(dirty.copy(lastPingLossPercent = 140.0).sanitizedMetrics().lastPingLossPercent)
    }

    @Test
    fun `history store sanitizes entries already persisted on disk`() = runBlocking {
        val dir = Files.createTempDirectory("netbench-history").toFile()
        try {
            val file = File(dir, NetBenchHistoryStore.HISTORY_FILE_NAME)
            file.writeText(
                """{"version":2,"entries":[{"host":"1.1.1.1","port":443,"lastAvgMs":13.4,""" +
                    """"lastPingAvgMs":745123913967336.8,"lastPingLossPercent":0.0,"testedAtEpochMillis":1}]}""",
            )
            val store = NetBenchHistoryStore(historyFile = file, ioDispatcher = Dispatchers.IO)
            val entry = store.entries.value.single()
            assertNull(entry.lastPingAvgMs)
            assertEquals(13.4, entry.lastAvgMs!!, 1e-9)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `omit warm-up seconds are flagged and excluded from upload averages`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverError = AtomicReference<Throwable?>(null)
        val receivedParamsJson = AtomicReference<String?>(null)
        val receivedClientResultsJson = AtomicReference<String?>(null)
        val serverReportedBytes = 1_000_000L

        val serverThread = thread(isDaemon = true) {
            try {
                server.soTimeout = 8_000
                val ctrlSocket = server.accept()
                ctrlSocket.soTimeout = 8_000
                val ctrlIn = DataInputStream(ctrlSocket.getInputStream())
                val ctrlOut = DataOutputStream(ctrlSocket.getOutputStream())
                ctrlIn.readFully(ByteArray(37))

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_PARAM_EXCHANGE.toInt())
                ctrlOut.flush()
                val paramBytes = ByteArray(ctrlIn.readInt())
                ctrlIn.readFully(paramBytes)
                receivedParamsJson.set(String(paramBytes, StandardCharsets.UTF_8))

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_CREATE_STREAMS.toInt())
                ctrlOut.flush()
                val dataSocket = server.accept()
                val dataIn = DataInputStream(dataSocket.getInputStream())
                dataIn.readFully(ByteArray(37))

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_START.toInt())
                ctrlOut.writeByte(Iperf3ClientRunner.STATE_TEST_RUNNING.toInt())
                ctrlOut.flush()

                val stopDrain = AtomicBoolean(false)
                val drainThread = thread(isDaemon = true) {
                    val buf = ByteArray(65536)
                    while (!stopDrain.get()) {
                        val n = runCatching { dataIn.read(buf) }.getOrDefault(-1)
                        if (n <= 0) break
                    }
                }
                assertEquals(Iperf3ClientRunner.STATE_TEST_END, ctrlIn.readByte())
                stopDrain.set(true)
                runCatching { dataSocket.close() }
                drainThread.join(1_000)

                ctrlOut.writeByte(Iperf3ClientRunner.STATE_EXCHANGE_RESULTS.toInt())
                ctrlOut.flush()
                val clientResBytes = ByteArray(ctrlIn.readInt())
                ctrlIn.readFully(clientResBytes)
                receivedClientResultsJson.set(String(clientResBytes, StandardCharsets.UTF_8))

                val serverResJson = """
                    {"streams":[{"id":1,"bytes":$serverReportedBytes,"retransmits":0,"start_time":0.0,"end_time":1.0}]}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                ctrlOut.writeInt(serverResJson.size)
                ctrlOut.write(serverResJson)
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

        val emissions = Iperf3ClientRunner(ioDispatcher = Dispatchers.IO, progressIntervalMs = 150L).run(
            Iperf3Config(
                target = "127.0.0.1",
                port = server.localPort,
                durationSec = 1,
                omitSec = 1,
                direction = Iperf3Direction.UPLOAD,
            ),
        ).toList()
        serverThread.join(6_000)
        assertNull("Mock iperf3 server threw: ${serverError.get()}", serverError.get())

        val params = json.parseToJsonElement(receivedParamsJson.get()!!).jsonObject
        assertEquals(1, params["omit"]!!.jsonPrimitive.int)
        assertEquals(1, params["time"]!!.jsonPrimitive.int)

        assertTrue("Warm-up progress must be flagged", emissions.any { it.inOmitPhase })
        val result = emissions.last().finalResult
        assertNotNull(result)
        assertEquals(Iperf3Stage.COMPLETED, emissions.last().stage)
        assertEquals(1, result!!.omitSec)
        // Measured window equals -t only (warm-up excluded from the denominator).
        assertTrue("durationSec=${result.durationSec}", result.durationSec in 0.85..1.4)

        val omitted = result.intervals.filter { it.omitted }
        val measured = result.intervals.filter { !it.omitted }
        assertTrue("Expected warm-up intervals, got ${result.intervals}", omitted.isNotEmpty())
        assertTrue(measured.isNotEmpty())
        assertEquals(0.0, measured.first().startSec, 1e-3)
        assertEquals(result.bytesTransferred, measured.sumOf { it.bytesTransferred })

        // Client results exchanged with the server exclude warm-up bytes as well.
        val clientRes = json.parseToJsonElement(receivedClientResultsJson.get()!!).jsonObject
        val sentBytes = clientRes["streams"]!!.jsonArray[0].jsonObject["bytes"]!!.jsonPrimitive.long
        assertEquals(result.bytesTransferred, sentBytes)

        // Upload headline uses the receiver-side server figure.
        val expectedServerMbps = Iperf3ClientRunner.computeMbps(serverReportedBytes, 1.0)
        assertEquals(expectedServerMbps, result.serverMbps!!, 1e-6)
        assertEquals(expectedServerMbps, result.effectiveMbps, 1e-6)
    }
}
