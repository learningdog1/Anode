package com.nodeseek.app.data.fleet

import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshPtyResizeCallbackContext
import com.nodeseek.app.domain.ssh.SshPtyResizer
import com.nodeseek.app.domain.ssh.SshPtySize
import com.nodeseek.app.domain.ssh.SshStdinCallbackContext
import com.nodeseek.app.domain.ssh.SshStdinWriter
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pure framing helper for running non-interactive commands inside a persistent panel PTY shell
 * (Nezha v1 / Komari WebSocket terminal) while accurately extracting exit status and suppressing
 * login banner / command echo noise.
 *
 * Uses split `printf` arguments so the echoed command line itself NEVER contains the literal
 * start/exit sentinel strings.
 */
internal class PanelTerminalCommandFraming(
    val nonce: String = generateNonce(),
) {
    val startSentinel: String = "${START_PREFIX}$nonce"
    val exitSentinelPrefix: String = "${EXIT_PREFIX}$nonce:"

    private val exitMarkerRegex = Regex("""${Regex.escape(EXIT_PREFIX)}${Regex.escape(nonce)}:(-?\d+)__""")
    private val pendingBuffer = StringBuilder()
    private var started: Boolean = false
    private var completed: Boolean = false

    data class FilterChunkResult(
        val outputToEmit: String,
        val completedExitCode: Int? = null,
    )

    fun wrapCommand(command: String): String {
        val cleanCmd = command.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        val body = if (cleanCmd.isBlank()) ":" else cleanCmd
        return "printf '\\n%s%s\\n' '$START_PREFIX' '$nonce'; (\n$body\n); __anode_rc=\$?; " +
            "printf '\\n%s%s:%d__\\n' '$EXIT_PREFIX' '$nonce' \"\$__anode_rc\"; exit \"\$__anode_rc\"\n"
    }

    /**
     * Enables pass-through mode when the remote shell never echoed our start marker (e.g., agent
     * immediately printed a permission/disabled-command error and closed).
     */
    fun flushFallbackWithoutStartMarker(): String {
        if (started || completed) return ""
        started = true
        val raw = pendingBuffer.toString()
        pendingBuffer.setLength(0)
        return raw
    }

    fun feed(chunk: String): FilterChunkResult {
        if (completed || chunk.isEmpty()) return FilterChunkResult("")
        pendingBuffer.append(chunk)

        if (!started) {
            val startIdx = pendingBuffer.indexOf(startSentinel)
            if (startIdx < 0) {
                // Keep buffer bounded if MOTD is unexpectedly large
                if (pendingBuffer.length > MAX_PRE_START_BUFFER_CHARS) {
                    val keepFrom = (pendingBuffer.length - startSentinel.length - 32).coerceAtLeast(0)
                    pendingBuffer.delete(0, keepFrom)
                }
                return FilterChunkResult("")
            }
            var afterStart = startIdx + startSentinel.length
            // Consume optional trailing CR/LF right after the start sentinel
            if (afterStart < pendingBuffer.length && pendingBuffer[afterStart] == '\r') afterStart++
            if (afterStart < pendingBuffer.length && pendingBuffer[afterStart] == '\n') afterStart++
            pendingBuffer.delete(0, afterStart)
            started = true
        }

        val current = pendingBuffer.toString()
        val match = exitMarkerRegex.find(current)
        if (match != null) {
            completed = true
            val beforeMarker = current.substring(0, match.range.first)
                .trimEnd('\r', '\n')
            val exitCode = match.groupValues[1].toIntOrNull() ?: 0
            pendingBuffer.setLength(0)
            return FilterChunkResult(
                outputToEmit = if (beforeMarker.isNotEmpty()) "$beforeMarker\n" else "",
                completedExitCode = exitCode,
            )
        }

        // Hold back a small tail so a split exit sentinel across frames is never emitted prematurely
        val holdBack = (exitSentinelPrefix.length + 16).coerceAtMost(current.length)
        val safeEmitLen = current.length - holdBack
        if (safeEmitLen <= 0) {
            return FilterChunkResult("")
        }
        val emitText = current.substring(0, safeEmitLen)
        pendingBuffer.delete(0, safeEmitLen)
        return FilterChunkResult(outputToEmit = emitText)
    }

    fun flushRemainingOnClose(): String {
        if (completed) return ""
        val text = pendingBuffer.toString()
        pendingBuffer.setLength(0)
        return text
    }

    companion object {
        private const val START_PREFIX = "__ANODE_START_"
        private const val EXIT_PREFIX = "__ANODE_EXIT_"
        private const val MAX_PRE_START_BUFFER_CHARS = 16_384
        private val random = SecureRandom()

        fun generateNonce(): String {
            val bytes = ByteArray(6)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(Locale.US, it) }
        }
    }
}

/**
 * Streaming UTF-8 decoder that cleanly preserves incomplete trailing multi-byte sequences across
 * WebSocket binary frame boundaries (preventing split CJK/box-drawing characters from corrupting).
 */
internal class StreamingUtf8Decoder {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)

    fun decode(bytes: ByteArray, endOfInput: Boolean = false): String {
        if (bytes.isEmpty() && pendingBytes.isEmpty()) return ""
        val combined = if (pendingBytes.isEmpty()) {
            bytes
        } else {
            ByteArray(pendingBytes.size + bytes.size).also { out ->
                System.arraycopy(pendingBytes, 0, out, 0, pendingBytes.size)
                System.arraycopy(bytes, 0, out, pendingBytes.size, bytes.size)
            }
        }
        val inBuf = ByteBuffer.wrap(combined)
        val outBuf = CharBuffer.allocate(combined.size + 4)
        val result = decoder.decode(inBuf, outBuf, endOfInput)
        if (endOfInput) {
            decoder.flush(outBuf)
            decoder.reset()
        }
        val remaining = inBuf.remaining()
        pendingBytes = if (!endOfInput && remaining > 0 && result.isUnderflow) {
            ByteArray(remaining).also { inBuf.get(it) }
        } else {
            ByteArray(0)
        }
        outBuf.flip()
        return outBuf.toString()
    }
}

/**
 * WebSocket PTY terminal transport implementing [SshTransport] for configured Nezha v1/v2 and
 * Komari probe panels using credentials already stored in [FleetRepository].
 *
 * Supported panel terminal protocols:
 * - **Nezha v1 / v2**:
 *   1. `POST /api/v1/terminal` (`{"server_id": <id>, "protocol": "grpc"}`) using `Authorization: Bearer <token>`
 *      (or `Cookie: nz-jwt=<token>`, forwarding `X-CSRF-Token` if `nz-csrf` cookie is provided) to allocate `session_id`.
 *   2. `GET /api/v1/ws/terminal/<session_id>` WebSocket upgrade (same auth + `Origin`).
 *   3. Wire framing (verified against `nezhahq/agent` `terminal_session.go`):
 *      - Stdin: Binary frame `[0x00, ...utf8Bytes]`
 *      - Resize: Binary frame `[0x01, ...utf8Json({"Cols": cols, "Rows": rows})]`
 *      - Stdout: Raw binary frames decoded as streaming UTF-8.
 * - **Komari**:
 *   1. `GET /api/admin/client/<uuid>/terminal` WebSocket upgrade using `Authorization: Bearer <key>`
 *      (with `Cookie: session_token=<token>` fallback) + same-origin `Origin` header.
 *   2. Wire framing (verified against `komari-monitor/komari` `web/api/terminal/control.go`):
 *      - Control (Text frames): JSON `assigned`, `ready`, `heartbeat`, `resize`, `abort`, `terminate`.
 *      - Data (Binary frames): Raw bidirectional PTY UTF-8 byte stream.
 * - **Nezha v0**:
 *   - Not supported via API token (v0 web terminal requires browser OAuth session cookies); fails
 *     fast with a clear diagnostic guiding the user to Direct SSH (JSch).
 */
class ProbePanelTerminalTransport(
    private val panelLookup: suspend (panelId: String) -> ProbePanelConfig?,
    httpClient: OkHttpClient = defaultWebSocketClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val allowLoopbackHttpWithCredentials: Boolean = false,
    private val readyWaitTimeoutMillis: Long = DEFAULT_READY_TIMEOUT_MILLIS,
    private val nezhaPromptSettleMillis: Long = DEFAULT_NEZHA_PROMPT_SETTLE_MILLIS,
    private val komariHeartbeatIntervalMillis: Long = DEFAULT_KOMARI_HEARTBEAT_INTERVAL_MILLIS,
    private val startMarkerFallbackMillis: Long = DEFAULT_START_MARKER_FALLBACK_MILLIS,
) : SshTransport {

    constructor(
        fleetRepository: FleetRepository,
        httpClient: OkHttpClient = defaultWebSocketClient(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        allowLoopbackHttpWithCredentials: Boolean = false,
    ) : this(
        panelLookup = createPanelLookup(fleetRepository),
        httpClient = httpClient,
        ioDispatcher = ioDispatcher,
        allowLoopbackHttpWithCredentials = allowLoopbackHttpWithCredentials,
    )

    private val transportClient: OkHttpClient = httpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun execute(
        request: SshConnectionRequest,
        command: String,
    ): Flow<SshStreamEvent> = callbackFlow {
        val closedFlag = AtomicBoolean(false)
        val terminateSentFlag = AtomicBoolean(false)
        val activeSocketRef = AtomicReference<WebSocket?>(null)
        val panelTypeRef = AtomicReference<ProbePanelType?>(null)

        fun closeSocketQuietly(sendKomariTerminate: Boolean = false) {
            closedFlag.set(true)
            val ws = activeSocketRef.getAndSet(null) ?: return
            if (sendKomariTerminate &&
                panelTypeRef.get() == ProbePanelType.KOMARI &&
                terminateSentFlag.compareAndSet(false, true)
            ) {
                runCatching { ws.send(KOMARI_TERMINATE_FRAME) }
            }
            runCatching { ws.close(1000, "closed") }
        }

        val workerJob = launch(ioDispatcher) {
            try {
                val panelId = request.probePanelId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("当前主机未绑定探针面板，请先同步探针或切换为「直连 SSH」模式")
                val remoteServerId = request.probeServerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("当前主机缺少探针节点 ID，请重新同步探针")
                val panel = panelLookup(panelId)
                    ?: throw IOException("未找到对应的探针面板配置（可能已被删除），请在「我的服务器」检查探针配置或切换为「直连 SSH」")

                val panelType = panel.effectivePanelType.takeIf { it != ProbePanelType.AUTO }
                    ?: request.probePanelType
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { raw -> runCatching { ProbePanelType.valueOf(raw) }.getOrNull() }
                    ?: ProbePanelType.AUTO
                panelTypeRef.set(panelType)

                val baseUrl = ProbePanelClient.normalizeBaseUrl(panel.baseUrl)
                val originUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw ProbeCredentialBoundaryException("探针地址格式无效：$baseUrl")
                if (hasUserInfo(originUrl)) {
                    throw ProbeCredentialBoundaryException("安全校验失败：探针地址禁止内嵌账号密码")
                }

                val cleanToken = sanitizeToken(panel.apiToken)
                if (cleanToken == null) {
                    val hint = when (panelType) {
                        ProbePanelType.NEZHA_V1 ->
                            "哪吒 v1 探针终端需要管理员 JWT / PAT 令牌（需具备 nezha:server:exec 权限）。请在「我的服务器 → 探针管理」补充 API Token，或为该主机配置密码/私钥并切换为「直连 SSH」。"
                        ProbePanelType.KOMARI ->
                            "Komari 探针终端需要管理员 API Key 或 session_token Cookie。请在「我的服务器 → 探针管理」补充令牌，或切换为「直连 SSH」。"
                        else ->
                            "探针终端需要管理员认证凭证，请先在探针管理中配置 API Token，或切换为「直连 SSH」。"
                    }
                    throw IOException(hint)
                }
                if (!ProbePanelClient.isAllowedCredentialOrigin(originUrl, allowLoopbackHttpWithCredentials)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：探针终端携带认证凭证，必须通过 HTTPS/WSS 加密连接",
                    )
                }

                when (panelType) {
                    ProbePanelType.NEZHA_V1 -> runNezhaV1Terminal(
                        originUrl = originUrl,
                        apiToken = cleanToken,
                        remoteServerId = remoteServerId,
                        request = request,
                        command = command,
                        activeSocketRef = activeSocketRef,
                        closedFlag = closedFlag,
                    )
                    ProbePanelType.KOMARI -> runKomariTerminal(
                        originUrl = originUrl,
                        apiToken = cleanToken,
                        remoteServerId = remoteServerId,
                        request = request,
                        command = command,
                        activeSocketRef = activeSocketRef,
                        closedFlag = closedFlag,
                    )
                    ProbePanelType.NEZHA_V0 -> throw IOException(
                        "哪吒 v0 的 API Token 仅支持只读监控接口，网页终端依赖浏览器 OAuth Cookie，无法免密打开。请编辑该主机补充 SSH 密码/私钥并切换为「直连 SSH」。",
                    )
                    ProbePanelType.AUTO -> throw IOException(
                        "该探针面板尚未完成协议识别，请先在「我的服务器」刷新或测试一次面板连接后重试。",
                    )
                }
                close()
            } catch (ce: CancellationException) {
                closeSocketQuietly(sendKomariTerminate = true)
                throw ce
            } catch (t: Throwable) {
                if (closedFlag.get()) {
                    close()
                } else {
                    close(t)
                }
            } finally {
                closeSocketQuietly(sendKomariTerminate = true)
            }
        }

        awaitClose {
            closeSocketQuietly(sendKomariTerminate = true)
            workerJob.cancel()
        }
    }

    // ── Nezha v1 / v2 WebSocket Terminal ──────────────────────────────────────

    private suspend fun kotlinx.coroutines.channels.ProducerScope<SshStreamEvent>.runNezhaV1Terminal(
        originUrl: HttpUrl,
        apiToken: String,
        remoteServerId: String,
        request: SshConnectionRequest,
        command: String,
        activeSocketRef: AtomicReference<WebSocket?>,
        closedFlag: AtomicBoolean,
    ) {
        val numericServerId = remoteServerId.toLongOrNull()
            ?: throw IOException("哪吒 v1 服务器 ID 格式无效：$remoteServerId")

        val (sessionId, winningHeaders) = createNezhaV1TerminalSession(
            originUrl = originUrl,
            apiToken = apiToken,
            serverId = numericServerId,
        )

        val wsUrl = buildEndpointUrl(originUrl, "api", "v1", "ws", "terminal", sessionId)
        val initialPty = SshPtySize(
            cols = request.ptyCols,
            rows = request.ptyRows,
            widthPx = request.ptyWidthPx,
            heightPx = request.ptyHeightPx,
        ).normalized()
        val isInteractive = request.interactiveShell || command.isBlank()
        val framing = if (isInteractive) null else PanelTerminalCommandFraming()
        val utf8Decoder = StreamingUtf8Decoder()
        val decodeLock = Any()

        val wsOpened = CompletableDeferred<WebSocket>()
        val firstOutputArrived = CompletableDeferred<Unit>()
        val sessionFinished = CompletableDeferred<Int>()
        val commandSent = AtomicBoolean(false)

        val wsReqBuilder = Request.Builder()
            .url(wsUrl)
            .header("Origin", formatOriginHeader(originUrl))
        winningHeaders.forEach { (k, v) -> wsReqBuilder.header(k, v) }

        val socket = transportClient.newWebSocket(
            wsReqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closedFlag.get()) {
                        runCatching { webSocket.cancel() }
                        wsOpened.completeExceptionally(CancellationException("哪吒 v1 探针终端会话已关闭"))
                        return
                    }
                    activeSocketRef.set(webSocket)
                    if (closedFlag.get()) {
                        activeSocketRef.compareAndSet(webSocket, null)
                        runCatching { webSocket.cancel() }
                        wsOpened.completeExceptionally(CancellationException("哪吒 v1 探针终端会话已关闭"))
                        return
                    }
                    sendNezhaResizeFrame(webSocket, initialPty)
                    wsOpened.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val decoded = synchronized(decodeLock) { utf8Decoder.decode(bytes.toByteArray()) }
                    handleIncomingOutput(decoded)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingOutput(text)
                }

                private fun handleIncomingOutput(chunk: String) {
                    if (chunk.isEmpty() || closedFlag.get()) return
                    firstOutputArrived.complete(Unit)
                    if (framing == null) {
                        emitFromWebSocketCallback(SshStreamEvent.OutputChunk(chunk), closedFlag)
                        return
                    }
                    if (!commandSent.get()) {
                        // Ignore pre-command MOTD/prompt noise unless agent explicitly rejected exec
                        if (chunk.contains("禁止命令执行") || chunk.contains("disable", ignoreCase = true)) {
                            emitFromWebSocketCallback(SshStreamEvent.OutputChunk(chunk), closedFlag)
                        }
                        return
                    }
                    val filtered = synchronized(decodeLock) { framing.feed(chunk) }
                    if (filtered.outputToEmit.isNotEmpty()) {
                        emitFromWebSocketCallback(SshStreamEvent.OutputChunk(filtered.outputToEmit), closedFlag)
                    }
                    if (filtered.completedExitCode != null) {
                        sessionFinished.complete(filtered.completedExitCode)
                        runCatching { activeSocketRef.get()?.close(1000, "done") }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(1000, null) }
                    finishOnClose()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finishOnClose()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detail = buildWsFailureMessage(
                        panelLabel = "哪吒 v1 探针终端",
                        throwable = t,
                        response = response,
                    )
                    val err = IOException(detail, t)
                    if (!wsOpened.isCompleted) {
                        wsOpened.completeExceptionally(err)
                    }
                    if (!sessionFinished.isCompleted) {
                        if (closedFlag.get()) {
                            sessionFinished.complete(0)
                        } else {
                            sessionFinished.completeExceptionally(err)
                        }
                    }
                }

                private fun finishOnClose() {
                    val tailUtf8 = synchronized(decodeLock) { utf8Decoder.decode(ByteArray(0), endOfInput = true) }
                    if (tailUtf8.isNotEmpty()) handleIncomingOutput(tailUtf8)
                    if (framing != null && !sessionFinished.isCompleted) {
                        val remaining = synchronized(decodeLock) {
                            framing.flushFallbackWithoutStartMarker() + framing.flushRemainingOnClose()
                        }
                        if (remaining.isNotEmpty()) {
                            emitFromWebSocketCallback(SshStreamEvent.OutputChunk(remaining), closedFlag)
                        }
                        if (closedFlag.get()) {
                            sessionFinished.complete(0)
                        } else {
                            sessionFinished.completeExceptionally(
                                IOException("哪吒 v1 探针终端连接意外断开，未收到命令退出状态"),
                            )
                        }
                        return
                    }
                    sessionFinished.complete(0)
                }
            },
        )
        activeSocketRef.set(socket)
        if (closedFlag.get()) {
            activeSocketRef.compareAndSet(socket, null)
            runCatching { socket.cancel() }
            return
        }

        val openedSocket = withTimeoutOrNull(request.connectTimeoutMillis.toLong().coerceAtLeast(3_000L)) {
            wsOpened.await()
        } ?: run {
            runCatching { socket.cancel() }
            throw IOException("连接哪吒 v1 探针终端超时，请确认面板反代已开启 /api/v1/ws/terminal WebSocket 转发")
        }

        send(SshStreamEvent.Connected)
        registerCallbacks(
            stdinWriter = SshStdinWriter { input ->
                if (!closedFlag.get() && input.isNotEmpty()) {
                    sendNezhaStdinFrame(openedSocket, input)
                }
            },
            ptyResizer = SshPtyResizer { nextSize ->
                if (!closedFlag.get()) {
                    sendNezhaResizeFrame(openedSocket, nextSize.normalized())
                }
            },
        )

        var fallbackFlushJob: Job? = null
        if (framing != null) {
            // Wait briefly for agent PTY initial prompt so our wrapped command isn't dropped
            withTimeoutOrNull(nezhaPromptSettleMillis) { firstOutputArrived.await() }
            commandSent.set(true)
            sendNezhaStdinFrame(openedSocket, framing.wrapCommand(command))
            fallbackFlushJob = launch {
                delay(startMarkerFallbackMillis)
                if (!sessionFinished.isCompleted && !closedFlag.get()) {
                    val fallback = synchronized(decodeLock) { framing.flushFallbackWithoutStartMarker() }
                    if (fallback.isNotEmpty()) {
                        send(SshStreamEvent.OutputChunk(fallback))
                    }
                }
            }
        }

        try {
            val exitCode = sessionFinished.await()
            if (!closedFlag.get()) {
                send(SshStreamEvent.Completed(exitCode = exitCode))
            }
        } finally {
            fallbackFlushJob?.cancel()
        }
    }

    private suspend fun createNezhaV1TerminalSession(
        originUrl: HttpUrl,
        apiToken: String,
        serverId: Long,
    ): Pair<String, Map<String, String>> {
        val url = buildEndpointUrl(originUrl, "api", "v1", "terminal")
        val bodyJson = """{"server_id":$serverId,"protocol":"grpc"}"""

        val primaryHeaders = buildNezhaV1TerminalHeaders(apiToken, asCookie = false)
        var outcome = executePostJsonSameOrigin(url, bodyJson, primaryHeaders, originUrl)
        var winningHeaders = primaryHeaders

        if ((outcome.code == 401 || outcome.code == 403) &&
            !apiToken.startsWith("Bearer ", ignoreCase = true) &&
            !apiToken.startsWith("Basic ", ignoreCase = true) &&
            !ProbePanelClient.looksLikeCookieHeader(apiToken)
        ) {
            val cookieHeaders = buildNezhaV1TerminalHeaders(apiToken, asCookie = true)
            val retry = executePostJsonSameOrigin(url, bodyJson, cookieHeaders, originUrl)
            if (retry.isSuccessful || retry.code !in setOf(401, 403)) {
                outcome = retry
                winningHeaders = cookieHeaders
            }
        }

        if (outcome.code == 401 || outcome.code == 403) {
            throw IOException(
                "哪吒 v1 探针终端鉴权失败 (HTTP ${outcome.code})：请确认配置的是有效管理员 JWT 或具备 nezha:server:exec 权限的 PAT 令牌",
            )
        }
        if (!outcome.isSuccessful) {
            val parsedErr = extractJsonError(outcome.body)
            val suffix = parsedErr?.let { "：$it" }.orEmpty()
            throw IOException("创建哪吒 v1 终端会话失败 (HTTP ${outcome.code})$suffix")
        }

        val root = parseJsonOrNull(outcome.body) as? JsonObject
            ?: throw IOException("哪吒 v1 终端接口返回非有效 JSON")
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) {
            val err = root["error"]?.stringOrNull()
                ?: root["message"]?.stringOrNull()
                ?: "终端创建被面板拒绝"
            throw IOException("哪吒 v1 终端创建失败：$err")
        }
        val dataObj = root["data"] as? JsonObject
        val sessionId = dataObj?.getFirstString("session_id", "SessionID")
            ?: root.getFirstString("session_id", "SessionID")
            ?: throw IOException("哪吒 v1 终端接口未返回 session_id")

        return sessionId to winningHeaders
    }

    // ── Komari WebSocket Terminal ─────────────────────────────────────────────

    private suspend fun kotlinx.coroutines.channels.ProducerScope<SshStreamEvent>.runKomariTerminal(
        originUrl: HttpUrl,
        apiToken: String,
        remoteServerId: String,
        request: SshConnectionRequest,
        command: String,
        activeSocketRef: AtomicReference<WebSocket?>,
        closedFlag: AtomicBoolean,
    ) {
        val wsUrl = buildEndpointUrl(originUrl, "api", "admin", "client", remoteServerId, "terminal")
        val primaryHeaders = buildKomariTerminalHeaders(apiToken, asCookie = false)
        val allowCookieRetry = !apiToken.startsWith("Bearer ", ignoreCase = true) &&
            !apiToken.startsWith("Basic ", ignoreCase = true) &&
            !ProbePanelClient.looksLikeCookieHeader(apiToken)

        val firstAttempt = connectKomariWebSocketAttempt(
            originUrl = originUrl,
            wsUrl = wsUrl,
            headers = primaryHeaders,
            request = request,
            command = command,
            activeSocketRef = activeSocketRef,
            closedFlag = closedFlag,
        )
        if (firstAttempt.isAuthRetryable && allowCookieRetry && !closedFlag.get()) {
            val cookieHeaders = buildKomariTerminalHeaders(apiToken, asCookie = true)
            val secondAttempt = connectKomariWebSocketAttempt(
                originUrl = originUrl,
                wsUrl = wsUrl,
                headers = cookieHeaders,
                request = request,
                command = command,
                activeSocketRef = activeSocketRef,
                closedFlag = closedFlag,
            )
            secondAttempt.throwIfFailed()
            return
        }
        firstAttempt.throwIfFailed()
    }

    private data class KomariAttemptOutcome(
        val error: Throwable? = null,
        val isAuthRetryable: Boolean = false,
    ) {
        fun throwIfFailed() {
            if (error != null) throw error
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<SshStreamEvent>.connectKomariWebSocketAttempt(
        originUrl: HttpUrl,
        wsUrl: HttpUrl,
        headers: Map<String, String>,
        request: SshConnectionRequest,
        command: String,
        activeSocketRef: AtomicReference<WebSocket?>,
        closedFlag: AtomicBoolean,
    ): KomariAttemptOutcome {
        val initialPty = SshPtySize(
            cols = request.ptyCols,
            rows = request.ptyRows,
            widthPx = request.ptyWidthPx,
            heightPx = request.ptyHeightPx,
        ).normalized()
        val isInteractive = request.interactiveShell || command.isBlank()
        val framing = if (isInteractive) null else PanelTerminalCommandFraming()
        val utf8Decoder = StreamingUtf8Decoder()
        val decodeLock = Any()

        val wsOpened = CompletableDeferred<WebSocket>()
        val agentReady = CompletableDeferred<Unit>()
        val sessionFinished = CompletableDeferred<Int>()
        val commandSent = AtomicBoolean(false)
        val authRetryable = AtomicBoolean(false)

        val wsReqBuilder = Request.Builder()
            .url(wsUrl)
            .header("Origin", formatOriginHeader(originUrl))
        headers.forEach { (k, v) -> wsReqBuilder.header(k, v) }

        val socket = transportClient.newWebSocket(
            wsReqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closedFlag.get()) {
                        runCatching { webSocket.cancel() }
                        wsOpened.completeExceptionally(CancellationException("Komari 探针终端会话已关闭"))
                        return
                    }
                    activeSocketRef.set(webSocket)
                    if (closedFlag.get()) {
                        activeSocketRef.compareAndSet(webSocket, null)
                        runCatching { webSocket.cancel() }
                        wsOpened.completeExceptionally(CancellationException("Komari 探针终端会话已关闭"))
                        return
                    }
                    wsOpened.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Komari forwards raw PTY stdout/stderr exclusively in binary frames
                    agentReady.complete(Unit)
                    val decoded = synchronized(decodeLock) { utf8Decoder.decode(bytes.toByteArray()) }
                    handlePtyOutput(decoded)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val controlType = parseKomariControlType(text)
                    if (controlType != null) {
                        when (controlType) {
                            KOMARI_CTRL_ASSIGNED -> Unit
                            KOMARI_CTRL_READY -> {
                                sendKomariResizeFrame(webSocket, initialPty)
                                agentReady.complete(Unit)
                            }
                            KOMARI_CTRL_HEARTBEAT -> Unit
                            KOMARI_CTRL_ABORT -> {
                                if (!sessionFinished.isCompleted) {
                                    if (isInteractive) {
                                        emitFromWebSocketCallback(
                                            SshStreamEvent.OutputChunk("\r\n[Komari 提示：被控端连接暂时中断]\r\n"),
                                            closedFlag,
                                        )
                                    } else {
                                        sessionFinished.completeExceptionally(
                                            IOException("Komari 被控端终端连接中断（节点可能离线）"),
                                        )
                                    }
                                }
                            }
                            KOMARI_CTRL_TERMINATE -> {
                                finishOnClose()
                                runCatching { webSocket.close(1000, "terminated") }
                            }
                            else -> Unit // Forward-compatible: ignore unknown control message types
                        }
                        return
                    }
                    // Per Komari control.go spec: non-JSON text frames are forwarded as terminal output
                    agentReady.complete(Unit)
                    handlePtyOutput(text)
                }

                private fun handlePtyOutput(chunk: String) {
                    if (chunk.isEmpty() || closedFlag.get()) return
                    if (framing == null) {
                        emitFromWebSocketCallback(SshStreamEvent.OutputChunk(chunk), closedFlag)
                        return
                    }
                    if (!commandSent.get()) return
                    val filtered = synchronized(decodeLock) { framing.feed(chunk) }
                    if (filtered.outputToEmit.isNotEmpty()) {
                        emitFromWebSocketCallback(SshStreamEvent.OutputChunk(filtered.outputToEmit), closedFlag)
                    }
                    if (filtered.completedExitCode != null) {
                        sessionFinished.complete(filtered.completedExitCode)
                        runCatching { activeSocketRef.get()?.send(KOMARI_TERMINATE_FRAME) }
                        runCatching { activeSocketRef.get()?.close(1000, "done") }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(1000, null) }
                    finishOnClose()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finishOnClose()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    val respBody = runCatching { response?.body?.string().orEmpty() }.getOrDefault("")
                    val is2faError = respBody.contains("2fa", ignoreCase = true) ||
                        respBody.contains("totp", ignoreCase = true) ||
                        respBody.contains("two-factor", ignoreCase = true) ||
                        respBody.contains("two factor", ignoreCase = true)
                    if (!wsOpened.isCompleted && (code == 401 || code == 403) && !is2faError) {
                        authRetryable.set(true)
                    }
                    val detail = when {
                        is2faError ->
                            "Komari 面板开启了终端二次验证 (2FA/TOTP)，暂不支持通过非交互 API 直接打开探针终端；请使用「直连 SSH」或在 Komari 面板关闭敏感操作 2FA。"
                        code == 401 || code == 403 ->
                            "Komari 探针终端鉴权失败 (HTTP $code)：请确认配置的是管理员 API Key 或有效 session_token Cookie"
                        code == 400 ->
                            "Komari 未找到对应节点或节点不支持终端 (HTTP 400)：${extractJsonError(respBody) ?: "请重新同步探针"}"
                        else -> buildWsFailureMessage("Komari 探针终端", t, response, respBody)
                    }
                    val err = IOException(detail, t)
                    if (!wsOpened.isCompleted) wsOpened.completeExceptionally(err)
                    if (!agentReady.isCompleted) agentReady.completeExceptionally(err)
                    if (!sessionFinished.isCompleted) {
                        if (closedFlag.get()) {
                            sessionFinished.complete(0)
                        } else {
                            sessionFinished.completeExceptionally(err)
                        }
                    }
                }

                private fun finishOnClose() {
                    val tailUtf8 = synchronized(decodeLock) { utf8Decoder.decode(ByteArray(0), endOfInput = true) }
                    if (tailUtf8.isNotEmpty()) handlePtyOutput(tailUtf8)
                    if (!agentReady.isCompleted) {
                        agentReady.completeExceptionally(
                            IOException("Komari 探针终端在被控端就绪前已关闭（节点可能离线或未开启终端权限）"),
                        )
                    }
                    if (framing != null && !sessionFinished.isCompleted) {
                        val remaining = synchronized(decodeLock) {
                            framing.flushFallbackWithoutStartMarker() + framing.flushRemainingOnClose()
                        }
                        if (remaining.isNotEmpty()) {
                            emitFromWebSocketCallback(SshStreamEvent.OutputChunk(remaining), closedFlag)
                        }
                        if (closedFlag.get()) {
                            sessionFinished.complete(0)
                        } else {
                            sessionFinished.completeExceptionally(
                                IOException("Komari 探针终端连接意外断开，未收到命令退出状态"),
                            )
                        }
                        return
                    }
                    sessionFinished.complete(0)
                }
            },
        )
        activeSocketRef.set(socket)
        if (closedFlag.get()) {
            activeSocketRef.compareAndSet(socket, null)
            runCatching { socket.cancel() }
            return KomariAttemptOutcome()
        }

        val openedSocket = try {
            withTimeoutOrNull(request.connectTimeoutMillis.toLong().coerceAtLeast(3_000L)) {
                wsOpened.await()
            } ?: run {
                runCatching { socket.cancel() }
                return KomariAttemptOutcome(
                    error = IOException("连接 Komari 探针终端超时，请检查面板地址与 WebSocket 反代配置"),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return KomariAttemptOutcome(error = t, isAuthRetryable = authRetryable.get())
        }

        // Wait for Komari agent to attach PTY (`{"type":"ready"}` or first PTY output frame)
        try {
            val becameReady = withTimeoutOrNull(readyWaitTimeoutMillis) {
                agentReady.await()
                true
            }
            if (becameReady != true) {
                runCatching { openedSocket.send(KOMARI_TERMINATE_FRAME) }
                runCatching { openedSocket.close(1000, "timeout") }
                return KomariAttemptOutcome(
                    error = IOException("等待 Komari 被控端开启 PTY 终端超时，请确认节点在线且 Agent 已启用终端功能"),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return KomariAttemptOutcome(error = t, isAuthRetryable = false)
        }

        send(SshStreamEvent.Connected)
        registerCallbacks(
            stdinWriter = SshStdinWriter { input ->
                if (!closedFlag.get() && input.isNotEmpty()) {
                    openedSocket.send(input.encodeUtf8())
                }
            },
            ptyResizer = SshPtyResizer { nextSize ->
                if (!closedFlag.get()) {
                    sendKomariResizeFrame(openedSocket, nextSize.normalized())
                }
            },
        )

        val heartbeatJob = launch {
            while (isActive && !closedFlag.get() && !sessionFinished.isCompleted) {
                delay(komariHeartbeatIntervalMillis)
                if (!closedFlag.get() && !sessionFinished.isCompleted) {
                    runCatching { openedSocket.send(KOMARI_HEARTBEAT_FRAME) }
                }
            }
        }

        var fallbackFlushJob: Job? = null
        if (framing != null) {
            commandSent.set(true)
            openedSocket.send(framing.wrapCommand(command).encodeUtf8())
            fallbackFlushJob = launch {
                delay(startMarkerFallbackMillis)
                if (!sessionFinished.isCompleted && !closedFlag.get()) {
                    val fallback = synchronized(decodeLock) { framing.flushFallbackWithoutStartMarker() }
                    if (fallback.isNotEmpty()) {
                        send(SshStreamEvent.OutputChunk(fallback))
                    }
                }
            }
        }

        return try {
            val exitCode = sessionFinished.await()
            if (!closedFlag.get()) {
                send(SshStreamEvent.Completed(exitCode = exitCode))
            }
            KomariAttemptOutcome()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            KomariAttemptOutcome(error = t)
        } finally {
            heartbeatJob.cancel()
            fallbackFlushJob?.cancel()
        }
    }

    // ── Framing & HTTP Helpers ────────────────────────────────────────────────

    /**
     * Delivers events from OkHttp's non-suspending [WebSocketListener] thread into [callbackFlow]
     * using [trySendBlocking] so bursty stdout applies natural backpressure instead of silently
     * dropping frames when the 64-slot buffer fills, while unblocking immediately on channel close.
     */
    private fun kotlinx.coroutines.channels.ProducerScope<SshStreamEvent>.emitFromWebSocketCallback(
        event: SshStreamEvent,
        closedFlag: AtomicBoolean,
    ) {
        if (closedFlag.get()) return
        runCatching { trySendBlocking(event) }
    }

    private fun kotlinx.coroutines.channels.ProducerScope<SshStreamEvent>.registerCallbacks(
        stdinWriter: SshStdinWriter,
        ptyResizer: SshPtyResizer,
    ) {
        coroutineContext[SshStdinCallbackContext]?.onStdinReady?.invoke(stdinWriter)
        coroutineContext[SshPtyResizeCallbackContext]?.onPtyResizerReady?.invoke(ptyResizer)
    }

    internal fun buildNezhaStdinBytes(input: String): ByteString {
        val utf8 = input.toByteArray(StandardCharsets.UTF_8)
        val packet = ByteArray(utf8.size + 1)
        packet[0] = NEZHA_OPCODE_INPUT
        System.arraycopy(utf8, 0, packet, 1, utf8.size)
        return packet.toByteString()
    }

    internal fun buildNezhaResizeBytes(size: SshPtySize): ByteString {
        val payload = """{"Cols":${size.cols},"Rows":${size.rows}}""".toByteArray(StandardCharsets.UTF_8)
        val packet = ByteArray(payload.size + 1)
        packet[0] = NEZHA_OPCODE_RESIZE
        System.arraycopy(payload, 0, packet, 1, payload.size)
        return packet.toByteString()
    }

    private fun sendNezhaStdinFrame(ws: WebSocket, input: String) {
        runCatching { ws.send(buildNezhaStdinBytes(input)) }
    }

    private fun sendNezhaResizeFrame(ws: WebSocket, size: SshPtySize) {
        runCatching { ws.send(buildNezhaResizeBytes(size)) }
    }

    private fun sendKomariResizeFrame(ws: WebSocket, size: SshPtySize) {
        runCatching { ws.send("""{"type":"resize","cols":${size.cols},"rows":${size.rows}}""") }
    }

    internal fun parseKomariControlType(rawText: String): String? {
        val trimmed = rawText.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val obj = parseJsonOrNull(trimmed) as? JsonObject ?: return null
        return obj["type"]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun buildNezhaV1TerminalHeaders(apiToken: String, asCookie: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        when {
            asCookie -> put("Cookie", "nz-jwt=$apiToken")
            apiToken.startsWith("Bearer ", ignoreCase = true) ||
                apiToken.startsWith("Basic ", ignoreCase = true) -> put("Authorization", apiToken)
            ProbePanelClient.looksLikeCookieHeader(apiToken) -> {
                put("Cookie", apiToken)
                extractCookieValue(apiToken, "nz-csrf")?.let { csrf ->
                    put("X-CSRF-Token", csrf)
                }
            }
            else -> put("Authorization", "Bearer $apiToken")
        }
    }

    private fun buildKomariTerminalHeaders(apiToken: String, asCookie: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        when {
            asCookie -> put("Cookie", "session_token=$apiToken; temp_key=$apiToken")
            apiToken.startsWith("Basic ", ignoreCase = true) -> put("Authorization", apiToken)
            apiToken.startsWith("Bearer ", ignoreCase = true) -> {
                put("Authorization", apiToken)
                val rawKey = apiToken.substring("Bearer ".length).trim()
                if (rawKey.isNotEmpty()) put("X-API-Key", rawKey)
            }
            ProbePanelClient.looksLikeCookieHeader(apiToken) -> put("Cookie", apiToken)
            else -> {
                put("Authorization", "Bearer $apiToken")
                put("X-API-Key", apiToken)
            }
        }
    }

    private data class HttpOutcome(
        val code: Int,
        val body: String,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private suspend fun executePostJsonSameOrigin(
        url: HttpUrl,
        jsonBody: String,
        headers: Map<String, String>,
        expectedOrigin: HttpUrl,
    ): HttpOutcome {
        if (!ProbePanelClient.isSameOrigin(expectedOrigin, url)) {
            throw ProbeCredentialBoundaryException("安全校验失败：拒绝向非同源地址发送探针凭证")
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Origin", formatOriginHeader(expectedOrigin))
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        return suspendCancellableCoroutine { continuation ->
            val call = transportClient.newCall(reqBuilder.build())
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bodyStr = try {
                            ProbePanelClient.readBoundedBodyString(resp)
                        } catch (e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                            return
                        }
                        if (continuation.isActive) {
                            continuation.resume(HttpOutcome(code = resp.code, body = bodyStr))
                        }
                    }
                }
            })
        }
    }

    private fun buildEndpointUrl(originUrl: HttpUrl, vararg segments: String): HttpUrl {
        val builder = originUrl.newBuilder()
        val existing = originUrl.pathSegments
        if (existing.isNotEmpty() && existing.last().isEmpty()) {
            builder.removePathSegment(existing.size - 1)
        }
        for (segment in segments) {
            if (segment.isNotEmpty()) builder.addPathSegment(segment)
        }
        return builder.build()
    }

    private fun formatOriginHeader(originUrl: HttpUrl): String {
        val scheme = originUrl.scheme.lowercase(Locale.ROOT)
        val isDefaultPort = (scheme == "https" && originUrl.port == 443) ||
            (scheme == "http" && originUrl.port == 80)
        return if (isDefaultPort) {
            "$scheme://${originUrl.host}"
        } else {
            "$scheme://${originUrl.host}:${originUrl.port}"
        }
    }

    private fun buildWsFailureMessage(
        panelLabel: String,
        throwable: Throwable,
        response: Response?,
        prefetchedBody: String? = null,
    ): String {
        val code = response?.code
        val body = prefetchedBody ?: runCatching { response?.body?.string().orEmpty() }.getOrDefault("")
        val jsonErr = extractJsonError(body)
        return when {
            code == 401 || code == 403 -> "$panelLabel 认证被拒绝 (HTTP $code)${jsonErr?.let { "：$it" }.orEmpty()}"
            code == 404 -> "$panelLabel 接口不存在 (HTTP 404)，请确认面板版本及 WebSocket 反向代理配置"
            code != null -> "$panelLabel WebSocket 握手失败 (HTTP $code)${jsonErr?.let { "：$it" }.orEmpty()}"
            else -> "$panelLabel 连接失败：${throwable.message?.takeIf { it.isNotBlank() } ?: "网络异常"}"
        }
    }

    private fun extractJsonError(rawBody: String): String? {
        val obj = parseJsonOrNull(rawBody) as? JsonObject ?: return rawBody.trim().takeIf { it.isNotEmpty() && it.length < 160 }
        return obj.getFirstString("error", "message", "msg")
    }

    private fun parseJsonOrNull(raw: String): JsonElement? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }

    private fun extractCookieValue(cookieHeader: String, cookieName: String): String? =
        cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$cookieName=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.getFirstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } }

    companion object {
        const val NEZHA_OPCODE_INPUT: Byte = 0x00
        const val NEZHA_OPCODE_RESIZE: Byte = 0x01
        private const val KOMARI_CTRL_ASSIGNED = "assigned"
        private const val KOMARI_CTRL_READY = "ready"
        private const val KOMARI_CTRL_HEARTBEAT = "heartbeat"
        private const val KOMARI_CTRL_ABORT = "abort"
        private const val KOMARI_CTRL_TERMINATE = "terminate"
        private const val KOMARI_HEARTBEAT_FRAME = """{"type":"heartbeat"}"""
        private const val KOMARI_TERMINATE_FRAME = """{"type":"terminate"}"""
        private const val DEFAULT_READY_TIMEOUT_MILLIS = 15_000L
        private const val DEFAULT_NEZHA_PROMPT_SETTLE_MILLIS = 700L
        private const val DEFAULT_KOMARI_HEARTBEAT_INTERVAL_MILLIS = 25_000L
        private const val DEFAULT_START_MARKER_FALLBACK_MILLIS = 5_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun hasUserInfo(url: HttpUrl): Boolean =
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()

        private fun sanitizeToken(rawToken: String?): String? {
            val trimmed = rawToken?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it == '\r' || it == '\n' || (it.isISOControl() && it != '\t') }) {
                throw ProbeCredentialBoundaryException("安全校验失败：探针凭证格式无效")
            }
            return trimmed
        }

        private fun createPanelLookup(
            fleetRepository: FleetRepository,
        ): suspend (String) -> ProbePanelConfig? = { panelId ->
            if (!fleetRepository.isPersistedStateLoadedFlow.value) {
                try {
                    fleetRepository.loadPersistedState()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Best-effort cold load; fall back to whatever panels are currently in memory.
                }
            }
            fleetRepository.panelsFlow.value.firstOrNull { it.id == panelId }
        }

        private val sharedDefaultWebSocketClient: OkHttpClient by lazy {
            ProbePanelClient.defaultHttpClient().newBuilder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // Required for persistent WebSockets
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        fun defaultWebSocketClient(): OkHttpClient = sharedDefaultWebSocketClient
    }
}
