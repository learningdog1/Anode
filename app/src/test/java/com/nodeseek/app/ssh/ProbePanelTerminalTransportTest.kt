package com.nodeseek.app.ssh

import com.nodeseek.app.data.fleet.PanelTerminalCommandFraming
import com.nodeseek.app.data.fleet.ProbeCredentialBoundaryException
import com.nodeseek.app.data.fleet.ProbePanelTerminalTransport
import com.nodeseek.app.data.fleet.StreamingUtf8Decoder
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshPtySize
import com.nodeseek.app.domain.ssh.SshStdinWriter
import com.nodeseek.app.domain.ssh.SshStreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class ProbePanelTerminalTransportTest {

    @Test
    fun `StreamingUtf8Decoder reassembles multibyte UTF-8 characters split across binary frames`() {
        val decoder = StreamingUtf8Decoder()
        val original = "节点测试✓中文输出🚀"
        val raw = original.toByteArray(StandardCharsets.UTF_8)
        // Split right in the middle of a 3-byte Chinese character
        val part1 = raw.copyOfRange(0, 4)
        val part2 = raw.copyOfRange(4, raw.size)
        val out = decoder.decode(part1) + decoder.decode(part2) + decoder.decode(ByteArray(0), endOfInput = true)
        assertEquals(original, out)
    }

    @Test
    fun `PanelTerminalCommandFraming suppresses MOTD and echo extracts exit code and handles split markers`() {
        val framing = PanelTerminalCommandFraming(nonce = "a1b2c3d4e5f6")
        val wrapped = framing.wrapCommand("echo hello && false")
        // Echoed command uses split printf arguments so it must never contain literal start/exit markers
        assertFalse(wrapped.contains(framing.startSentinel))
        assertFalse(wrapped.contains(framing.exitSentinelPrefix))

        val r1 = framing.feed("Welcome to Ubuntu\r\nroot@vps:~# $wrapped\r\n")
        assertEquals("", r1.outputToEmit)
        assertEquals(null, r1.completedExitCode)

        val r2 = framing.feed("__ANODE_START_a1b2c3d4e5f6\r\nhello world\n__ANODE_EXIT_")
        assertEquals(null, r2.completedExitCode)

        val r3 = framing.feed("a1b2c3d4e5f6:7__\r\n")
        assertEquals(7, r3.completedExitCode)
        val fullOutput = r2.outputToEmit + r3.outputToEmit
        assertTrue(fullOutput.contains("hello world"))
        assertFalse(fullOutput.contains("__ANODE_START_"))
        assertFalse(fullOutput.contains("__ANODE_EXIT_"))
    }

    @Test
    fun `PanelTerminalCommandFraming wrapCommand safely wraps trailing semicolons multiline comments and blank commands`() {
        val framing = PanelTerminalCommandFraming(nonce = "112233445566")

        // Trailing semicolon must not produce POSIX-invalid `;;`
        val trailingSemi = framing.wrapCommand("echo ok;")
        assertFalse("Must not contain ';;': $trailingSemi", trailingSemi.contains(";;"))
        assertTrue(
            "Expected wrapped command block, got: $trailingSemi",
            trailingSemi.contains("(\necho ok;\n);") || trailingSemi.contains("{\necho ok;\n};"),
        )

        // Multi-line script ending with a `# comment` must place closing delimiter on its own line
        val trailingComment = framing.wrapCommand("echo line1\r\n# trailing comment")
        assertTrue(
            "Expected multiline comment safely closed, got: $trailingComment",
            trailingComment.contains("(\necho line1\n# trailing comment\n);") ||
                trailingComment.contains("{\necho line1\n# trailing comment\n};"),
        )
        assertFalse("CRLF should be normalized to LF", trailingComment.contains("\r"))

        // Blank command falls back to POSIX no-op `:`
        val blankWrapped = framing.wrapCommand("   \n  ")
        assertTrue(
            "Expected blank no-op block, got: $blankWrapped",
            blankWrapped.contains("(\n:\n);") || blankWrapped.contains("{\n:\n};"),
        )
    }

    @Test
    fun `panel terminal backpressure preserves all bursty frames beyond 64-slot callbackFlow buffer`() = runBlocking {
        val frameCount = 150
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/admin/client/burst-node/terminal" -> MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"ready"}""")
                            for (i in 0 until frameCount) {
                                webSocket.send("BURST_LINE_${"%03d".format(i)}\n".encodeUtf8())
                            }
                            webSocket.send("""{"type":"terminate"}""")
                            webSocket.close(1000, "done")
                        }
                    },
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val panel = ProbePanelConfig(
                id = "km-burst",
                name = "Komari Burst",
                baseUrl = server.url("/").toString().trimEnd('/'),
                panelType = ProbePanelType.KOMARI,
                apiToken = "burst_token",
            )
            val transport = ProbePanelTerminalTransport(
                panelLookup = { if (it == "km-burst") panel else null },
                allowLoopbackHttpWithCredentials = true,
            )

            val receivedChunks = mutableListOf<String>()
            var sawConnected = false
            var sawCompleted = false
            transport.execute(
                request = SshConnectionRequest(
                    host = "",
                    connectionMode = SshConnectionMode.PANEL_TERMINAL,
                    interactiveShell = true,
                    probePanelId = "km-burst",
                    probeServerId = "burst-node",
                ),
                command = "",
            ).collect { event ->
                // Simulate a slower UI consumer so the 64-slot callbackFlow buffer fills up
                delay(2L)
                when (event) {
                    SshStreamEvent.Connected -> sawConnected = true
                    is SshStreamEvent.OutputChunk -> receivedChunks += event.rawText
                    is SshStreamEvent.Completed -> sawCompleted = true
                    is SshStreamEvent.HostKeyObserved -> Unit
                }
            }

            assertTrue("Connected event must not be dropped", sawConnected)
            assertTrue("Completed event must be emitted", sawCompleted)
            val combined = receivedChunks.joinToString("")
            for (i in 0 until frameCount) {
                val expectedLine = "BURST_LINE_${"%03d".format(i)}\n"
                assertTrue("Missing burst line $i (received ${receivedChunks.size} chunks)", combined.contains(expectedLine))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Nezha v1 panel terminal creates session upgrades WebSocket sends binary resize and stdin and parses exit code`() = runBlocking {
        val observedPosts = CopyOnWriteArrayList<String>()
        val observedAuthHeaders = CopyOnWriteArrayList<String>()
        val receivedOpcodes = CopyOnWriteArrayList<Byte>()
        val receivedResizePayloads = CopyOnWriteArrayList<String>()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                observedAuthHeaders += request.getHeader("Authorization").orEmpty()
                return when (request.path) {
                    "/api/v1/terminal" -> {
                        observedPosts += request.body.readUtf8()
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"success":true,"data":{"session_id":"sess-uuid-42","server_id":42,"server_name":"JP-01"}}""")
                    }
                    "/api/v1/ws/terminal/sess-uuid-42" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("root@jp01:~# ".encodeUtf8())
                            }

                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                val arr = bytes.toByteArray()
                                if (arr.isEmpty()) return
                                val opcode = arr[0]
                                receivedOpcodes += opcode
                                val payload = String(arr, 1, arr.size - 1, StandardCharsets.UTF_8)
                                if (opcode == ProbePanelTerminalTransport.NEZHA_OPCODE_RESIZE) {
                                    receivedResizePayloads += payload
                                } else if (opcode == ProbePanelTerminalTransport.NEZHA_OPCODE_INPUT) {
                                    val nonceMatch = Regex("""'__ANODE_START_' '([0-9a-f]+)'""").find(payload)
                                    val nonce = nonceMatch?.groupValues?.get(1)
                                    if (nonce != null) {
                                        webSocket.send(
                                            "\n__ANODE_START_$nonce\nNezha PTY 输出正常\n__ANODE_EXIT_$nonce:0__\n".encodeUtf8(),
                                        )
                                        webSocket.close(1000, "done")
                                    }
                                }
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val panel = ProbePanelConfig(
                id = "nz1",
                name = "Nezha V1",
                baseUrl = server.url("/").toString().trimEnd('/'),
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = "pat_secret_token",
            )
            val transport = ProbePanelTerminalTransport(
                panelLookup = { if (it == "nz1") panel else null },
                allowLoopbackHttpWithCredentials = true,
                nezhaPromptSettleMillis = 300L,
            )

            val events = transport.execute(
                request = SshConnectionRequest(
                    host = "198.51.100.1",
                    connectionMode = SshConnectionMode.PANEL_TERMINAL,
                    probePanelId = "nz1",
                    probeServerId = "42",
                    ptyCols = 110,
                    ptyRows = 36,
                ),
                command = "uname -a",
            ).toList()

            assertTrue(observedPosts.single().contains("\"server_id\":42"))
            assertTrue(observedAuthHeaders.all { it == "Bearer pat_secret_token" })
            assertTrue(ProbePanelTerminalTransport.NEZHA_OPCODE_RESIZE in receivedOpcodes)
            assertTrue(ProbePanelTerminalTransport.NEZHA_OPCODE_INPUT in receivedOpcodes)
            assertTrue(receivedResizePayloads.any { it.contains("\"Cols\":110") && it.contains("\"Rows\":36") })

            assertTrue(events.any { it is SshStreamEvent.Connected })
            val output = events.filterIsInstance<SshStreamEvent.OutputChunk>().joinToString("") { it.rawText }
            assertTrue("Expected PTY output, got: $output", output.contains("Nezha PTY 输出正常"))
            assertFalse(output.contains("__ANODE_EXIT_"))
            val completed = events.filterIsInstance<SshStreamEvent.Completed>().single()
            assertEquals(0, completed.exitCode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Komari panel terminal speaks dual-channel control text and binary PTY frames`() = runBlocking {
        val observedTextControlFrames = CopyOnWriteArrayList<String>()
        val observedBinaryStdin = CopyOnWriteArrayList<String>()
        val observedOrigins = CopyOnWriteArrayList<String>()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                observedOrigins += request.getHeader("Origin").orEmpty()
                return when (request.path) {
                    "/api/admin/client/node-uuid-1/terminal" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"type":"assigned","request_id":"req-123"}""")
                                webSocket.send("""{"type":"ready"}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                observedTextControlFrames += text
                            }

                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                val text = bytes.utf8()
                                observedBinaryStdin += text
                                if (text.contains("whoami")) {
                                    webSocket.send("""{"type":"heartbeat"}""")
                                    webSocket.send("""{"type":"future_unknown_event"}""")
                                    webSocket.send("root\r\n".encodeUtf8())
                                    webSocket.send("""{"type":"terminate"}""")
                                    webSocket.close(1000, "terminated")
                                }
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val panel = ProbePanelConfig(
                id = "km1",
                name = "Komari",
                baseUrl = server.url("/").toString().trimEnd('/'),
                panelType = ProbePanelType.KOMARI,
                apiToken = "komari_admin_key",
            )
            val transport = ProbePanelTerminalTransport(
                panelLookup = { if (it == "km1") panel else null },
                allowLoopbackHttpWithCredentials = true,
            )

            var stdinWriter: SshStdinWriter? = null
            val events = transport.execute(
                request = SshConnectionRequest(
                    host = "",
                    connectionMode = SshConnectionMode.PANEL_TERMINAL,
                    interactiveShell = true,
                    probePanelId = "km1",
                    probeServerId = "node-uuid-1",
                    ptyCols = 96,
                    ptyRows = 28,
                ),
                command = "",
                onStdinReady = { writer ->
                    stdinWriter = writer
                    writer.write("whoami\r")
                },
            ).toList()

            assertTrue(stdinWriter != null)
            assertTrue(observedOrigins.single().startsWith("http://"))
            assertTrue(observedTextControlFrames.any { it.contains("\"type\":\"resize\"") && it.contains("\"cols\":96") })
            assertTrue(observedBinaryStdin.any { it == "whoami\r" })

            val output = events.filterIsInstance<SshStreamEvent.OutputChunk>().joinToString("") { it.rawText }
            assertEquals("root\r\n", output)
            // Control frames must never leak into PTY output
            assertFalse(output.contains("heartbeat"))
            assertFalse(output.contains("future_unknown_event"))
            assertEquals(0, events.filterIsInstance<SshStreamEvent.Completed>().single().exitCode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Nezha v0 fails with clear Direct SSH guidance and plaintext remote HTTP is blocked`() = runBlocking {
        val v0Panel = ProbePanelConfig(
            id = "nz0",
            name = "Old Nezha",
            baseUrl = "https://probe.example.com",
            panelType = ProbePanelType.NEZHA_V0,
            apiToken = "v0-token",
        )
        val v0Transport = ProbePanelTerminalTransport(
            panelLookup = { if (it == "nz0") v0Panel else null },
        )
        val v0Err = runCatching {
            v0Transport.execute(
                request = SshConnectionRequest(
                    host = "203.0.113.1",
                    connectionMode = SshConnectionMode.PANEL_TERMINAL,
                    probePanelId = "nz0",
                    probeServerId = "1",
                ),
                command = "id",
            ).toList()
        }.exceptionOrNull()
        assertTrue(v0Err is IOException)
        assertTrue(v0Err?.message.orEmpty().contains("哪吒 v0"))
        assertTrue(v0Err?.message.orEmpty().contains("直连 SSH"))

        // Plaintext HTTP with credentials must be rejected by credential boundary check
        val insecurePanel = ProbePanelConfig(
            id = "insecure",
            name = "Insecure",
            baseUrl = "http://probe.example.com",
            panelType = ProbePanelType.NEZHA_V1,
            apiToken = "secret-jwt",
        )
        val insecureTransport = ProbePanelTerminalTransport(
            panelLookup = { if (it == "insecure") insecurePanel else null },
            allowLoopbackHttpWithCredentials = false,
        )
        val secErr = runCatching {
            insecureTransport.execute(
                request = SshConnectionRequest(
                    host = "203.0.113.1",
                    connectionMode = SshConnectionMode.PANEL_TERMINAL,
                    probePanelId = "insecure",
                    probeServerId = "1",
                ),
                command = "id",
            ).toList()
        }.exceptionOrNull()
        assertTrue(secErr is ProbeCredentialBoundaryException)
    }

    @Test
    fun `Komari 2FA 401 surfaces clear Chinese diagnostic`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"status":"error","message":"2FA TOTP verification required"}"""),
        )
        server.start()
        try {
            val panel = ProbePanelConfig(
                id = "km2fa",
                name = "Komari 2FA",
                baseUrl = server.url("/").toString().trimEnd('/'),
                panelType = ProbePanelType.KOMARI,
                apiToken = "admin_token",
            )
            val transport = ProbePanelTerminalTransport(
                panelLookup = { if (it == "km2fa") panel else null },
                allowLoopbackHttpWithCredentials = true,
            )
            val err = runCatching {
                transport.execute(
                    request = SshConnectionRequest(
                        host = "",
                        connectionMode = SshConnectionMode.PANEL_TERMINAL,
                        interactiveShell = true,
                        probePanelId = "km2fa",
                        probeServerId = "u-1",
                    ),
                    command = "",
                ).toList()
            }.exceptionOrNull()
            assertTrue(err is IOException)
            assertTrue("Expected 2FA hint, got: ${err?.message}", err?.message.orEmpty().contains("2FA"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Nezha resize helper encodes 0x01 opcode prefix with Cols and Rows JSON`() {
        val transport = ProbePanelTerminalTransport(panelLookup = { null })
        val frame = transport.buildNezhaResizeBytes(SshPtySize(cols = 120, rows = 40)).toByteArray()
        assertEquals(ProbePanelTerminalTransport.NEZHA_OPCODE_RESIZE, frame[0])
        val payload = String(frame, 1, frame.size - 1, StandardCharsets.UTF_8)
        assertEquals("""{"Cols":120,"Rows":40}""", payload)
    }
}
