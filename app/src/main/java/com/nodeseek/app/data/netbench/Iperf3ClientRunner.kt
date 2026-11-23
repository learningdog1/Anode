package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3ErrorKind
import com.nodeseek.app.domain.netbench.Iperf3IntervalSample
import com.nodeseek.app.domain.netbench.Iperf3Progress
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.Iperf3Stage
import com.nodeseek.app.domain.netbench.ResolvedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-Kotlin implementation of the native **iperf3 v3.x TCP client protocol** (no NDK/root binary).
 *
 * Wire protocol overview:
 * 1. Connect control TCP socket to `host:port`, immediately send 37-byte ASCII cookie (`[a-z2-7]{36}\0`).
 * 2. Enter state loop reading 1-byte signed state codes from server:
 *    - [STATE_PARAM_EXCHANGE] (9): send 4-byte BE length + JSON test parameters (`"reverse":true`
 *      only in Download mode).
 *    - [STATE_CREATE_STREAMS] (10): open `parallel` data TCP socket(s) and send the same 37-byte
 *      cookie on each without locking `SO_RCVBUF`/`SO_SNDBUF` so OS TCP auto-tuning scales `rwnd`/`cwnd`.
 *    - [STATE_TEST_START] (1) -> [STATE_TEST_RUNNING] (2): transfer 128 KiB chunks in a tight
 *      blocking I/O loop while emitting live [Iperf3Progress] every ~500ms (the first `omit` seconds are
 *      flagged as warm-up and excluded, like `iperf3 -O`), then signal [STATE_TEST_END] (4)
 *      (keeping reverse-mode receivers draining until [STATE_IPERF_DONE] so server writes never stall).
 *    - [STATE_EXCHANGE_RESULTS] (13): send client results JSON & parse server results JSON.
 *    - [STATE_DISPLAY_RESULTS] (14) -> send [STATE_IPERF_DONE] (16).
 * 3. Cleanly handles [STATE_ACCESS_DENIED] (-1), [STATE_SERVER_ERROR] (-2), timeouts, and refusal.
 */
class Iperf3ClientRunner(
    private val resolver: NetBenchTargetResolver = NetBenchTargetResolver(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
    private val defaultParallelStreams: Int = DEFAULT_PARALLEL_STREAMS,
    private val socketBufferBytes: Int? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun run(config: Iperf3Config): Flow<Iperf3Progress> {
        val effectiveStreams = if (config.parallelStreams != Iperf3Config.DEFAULT_PARALLEL_STREAMS) {
            config.normalizedParallelStreams
        } else {
            defaultParallelStreams
        }
        return run(config = config, parallelStreams = effectiveStreams)
    }

    fun run(
        config: Iperf3Config,
        parallelStreams: Int,
    ): Flow<Iperf3Progress> = flow {
        val direction = config.direction
        val durationSec = config.normalizedDurationSec
        val omitSec = config.normalizedOmitSec
        val chunkSize = config.normalizedChunkSize
        val connectTimeoutMs = config.connectTimeoutMs.coerceIn(1_000, 15_000)
        val streamsCount = parallelStreams.coerceIn(MIN_PARALLEL_STREAMS, MAX_PARALLEL_STREAMS)
        val explicitSocketBuf = socketBufferBytes?.takeIf { it > 0 }

        emit(
            Iperf3Progress(
                direction = direction,
                stage = Iperf3Stage.CONNECTING,
                elapsedSec = 0.0,
                targetDurationSec = durationSec,
                bytesTransferred = 0L,
                instantMbps = 0.0,
                averageMbps = 0.0,
                peakMbps = 0.0,
                parallelStreams = streamsCount,
            ),
        )

        val resolvedResult = resolver.resolve(
            rawTarget = config.target,
            defaultPort = config.normalizedPort,
            preferredFamily = config.preferredFamily,
            useProxy = config.useProxy,
        )

        val endpoint = resolvedResult.getOrElse { err ->
            val msg = err.message ?: "无法解析 iperf3 目标主机"
            val kind = if (err is UnknownHostException) Iperf3ErrorKind.DNS_ERROR else Iperf3ErrorKind.IO_ERROR
            emit(failedProgress(direction, config.normalizedPort, null, durationSec, kind, msg, streamsCount))
            return@flow
        }

        val port = endpoint.effectivePort
        val resolvedTarget = endpoint.resolvedTarget
        val cookieBytes = generateCookieBytes()

        val binding = networkBridge.inspectCurrentNetworks()
        val activeChannel: NetBenchSocketChannel = when (val selection = binding.selectChannel(config.useProxy)) {
            is ChannelSelectionResult.Selected -> selection.channel
            is ChannelSelectionResult.Blocked -> {
                emit(
                    failedProgress(
                        direction = direction,
                        port = port,
                        resolvedTarget = resolvedTarget,
                        durationSec = durationSec,
                        kind = Iperf3ErrorKind.IO_ERROR,
                        message = selection.message,
                        parallelStreams = streamsCount,
                    ),
                )
                return@flow
            }
        }

        val controlSocket = try {
            activeChannel.createSocket()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val fallbackMsg = if (!config.useProxy && binding.plan.isVpnActive) {
                "绑定底层网络失败：${t.message ?: t.javaClass.simpleName}"
            } else {
                "创建测速连接失败：${t.message ?: t.javaClass.simpleName}"
            }
            val msg = binding.formatBindOrIoError(t, config.useProxy, fallbackMsg)
            emit(failedProgress(direction, port, resolvedTarget, durationSec, Iperf3ErrorKind.IO_ERROR, msg, streamsCount))
            return@flow
        }

        val dataSockets = CopyOnWriteArrayList<Socket>()
        val stopTransferFlag = AtomicBoolean(false)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion {
            stopTransferFlag.set(true)
            runCatching { controlSocket.close() }
            for (sock in dataSockets) {
                runCatching { sock.close() }
            }
        }

        try {
            controlSocket.tcpNoDelay = true
            controlSocket.connect(InetSocketAddress(endpoint.address, port), connectTimeoutMs)
            controlSocket.soTimeout = CONTROL_READ_TIMEOUT_MS

            val controlIn = DataInputStream(BufferedInputStream(controlSocket.getInputStream()))
            val controlOut = DataOutputStream(BufferedOutputStream(controlSocket.getOutputStream()))

            // Handshake step 1: send 37-byte cookie on control stream
            controlOut.write(cookieBytes)
            controlOut.flush()

            var measuredBytes = 0L
            var measuredPerStreamBytes = LongArray(streamsCount)
            var measuredElapsedSec = 0.0
            var peakMbps = 0.0
            val throughputHistory = mutableListOf<Double>()
            var measuredIntervals: List<Iperf3IntervalSample> = emptyList()
            var appliedOmitSec = 0
            var serverStats: ParsedServerResults? = null
            var testExecuted = false

            coroutineScope {
                val transferScope: CoroutineScope = this
                val activeStreamJobs = mutableListOf<Job>()

                fun closeDataStreams() {
                    stopTransferFlag.set(true)
                    for (sock in dataSockets) {
                        runCatching { sock.close() }
                    }
                    for (streamJob in activeStreamJobs) {
                        streamJob.cancel()
                    }
                }

                try {
                    stateLoop@ while (true) {
                        currentCoroutineContext().ensureActive()
                        val rawState = controlIn.read()
                        if (rawState == -1) {
                            if (testExecuted && serverStats != null) {
                                val finalRes = buildSuccessResult(
                                    direction = direction,
                                    port = port,
                                    resolvedTarget = resolvedTarget,
                                    measuredElapsedSec = measuredElapsedSec,
                                    measuredBytes = measuredBytes,
                                    peakMbps = peakMbps,
                                    serverStats = serverStats,
                                    parallelStreams = streamsCount,
                                    intervals = measuredIntervals,
                                    omitSec = appliedOmitSec,
                                )
                                emit(completedProgress(finalRes, durationSec, throughputHistory))
                            } else {
                                emit(
                                    failedProgress(
                                        direction = direction,
                                        port = port,
                                        resolvedTarget = resolvedTarget,
                                        durationSec = durationSec,
                                        kind = Iperf3ErrorKind.PROTOCOL_ERROR,
                                        message = "iperf3 服务端提前断开控制连接",
                                        parallelStreams = streamsCount,
                                    ),
                                )
                            }
                            break@stateLoop
                        }

                        when (val state = rawState.toByte()) {
                            STATE_PARAM_EXCHANGE -> {
                                emit(
                                    Iperf3Progress(
                                        direction = direction,
                                        stage = Iperf3Stage.PARAM_EXCHANGE,
                                        elapsedSec = 0.0,
                                        targetDurationSec = durationSec,
                                        bytesTransferred = 0L,
                                        instantMbps = 0.0,
                                        averageMbps = 0.0,
                                        peakMbps = 0.0,
                                        parallelStreams = streamsCount,
                                    ),
                                )
                                val paramsJson = buildClientParamsJson(
                                    durationSec = durationSec,
                                    omitSec = omitSec,
                                    chunkSize = chunkSize,
                                    direction = direction,
                                    parallelStreams = streamsCount,
                                    socketBufferBytes = explicitSocketBuf,
                                )
                                writeFramedJson(controlOut, paramsJson)
                            }

                            STATE_CREATE_STREAMS -> {
                                emit(
                                    Iperf3Progress(
                                        direction = direction,
                                        stage = Iperf3Stage.CREATE_STREAMS,
                                        elapsedSec = 0.0,
                                        targetDurationSec = durationSec,
                                        bytesTransferred = 0L,
                                        instantMbps = 0.0,
                                        averageMbps = 0.0,
                                        peakMbps = 0.0,
                                        parallelStreams = streamsCount,
                                    ),
                                )
                                val needed = (streamsCount - dataSockets.size).coerceAtLeast(0)
                                repeat(needed) {
                                    val dataSocket = activeChannel.createSocket()
                                    dataSockets.add(dataSocket)
                                    // Leave SO_RCVBUF / SO_SNDBUF untouched by default (matching official
                                    // `iperf3` when `-w` is omitted). Explicitly setting a small SO_RCVBUF
                                    // on Android/Linux sets SOCK_RCVBUF_LOCK, disabling kernel TCP receive
                                    // buffer auto-tuning (`tcp_moderate_rcvbuf` / `tcp_rmem[2]`) and clamping
                                    // TCP Window Scale (`wscale`) during SYN handshake.
                                    if (explicitSocketBuf != null) {
                                        runCatching {
                                            dataSocket.sendBufferSize = explicitSocketBuf
                                            dataSocket.receiveBufferSize = explicitSocketBuf
                                        }
                                    }
                                    dataSocket.connect(InetSocketAddress(endpoint.address, port), connectTimeoutMs)
                                    val dataOut = dataSocket.getOutputStream()
                                    dataOut.write(cookieBytes)
                                    dataOut.flush()
                                }
                            }

                            STATE_TEST_START -> {
                                emit(
                                    Iperf3Progress(
                                        direction = direction,
                                        stage = Iperf3Stage.TEST_START,
                                        elapsedSec = 0.0,
                                        targetDurationSec = durationSec,
                                        bytesTransferred = 0L,
                                        instantMbps = 0.0,
                                        averageMbps = 0.0,
                                        peakMbps = 0.0,
                                        parallelStreams = streamsCount,
                                    ),
                                )
                            }

                            STATE_TEST_RUNNING -> {
                                if (testExecuted) continue@stateLoop
                                if (dataSockets.isEmpty()) {
                                    throw IOException("未能在测速开始前建立数据通道")
                                }
                                testExecuted = true

                                val transferSummary = runDataTransferLoop(
                                    transferScope = transferScope,
                                    activeStreamJobs = activeStreamJobs,
                                    stopTransferFlag = stopTransferFlag,
                                    direction = direction,
                                    durationSec = durationSec,
                                    omitSec = omitSec,
                                    chunkSize = chunkSize,
                                    dataSockets = dataSockets,
                                    controlIn = controlIn,
                                    controlOut = controlOut,
                                    onProgress = { progress ->
                                        if (progress.instantMbps > 0.0) {
                                            throughputHistory.add(progress.instantMbps)
                                            if (throughputHistory.size > MAX_HISTORY_SAMPLES) {
                                                throughputHistory.removeAt(0)
                                            }
                                        }
                                        peakMbps = maxOf(peakMbps, progress.peakMbps)
                                        emit(progress.copy(throughputHistoryMbps = throughputHistory.toList()))
                                    },
                                )
                                measuredBytes = transferSummary.bytesTransferred
                                measuredPerStreamBytes = transferSummary.perStreamBytes
                                measuredElapsedSec = transferSummary.elapsedSec
                                measuredIntervals = transferSummary.intervals
                                appliedOmitSec = transferSummary.appliedOmitSec
                                peakMbps = maxOf(peakMbps, transferSummary.peakMbps)

                                if (transferSummary.sentTestEndByClient) {
                                    emit(
                                        Iperf3Progress(
                                            direction = direction,
                                            stage = Iperf3Stage.TEST_END,
                                            elapsedSec = measuredElapsedSec,
                                            targetDurationSec = durationSec,
                                            bytesTransferred = measuredBytes,
                                            instantMbps = 0.0,
                                            averageMbps = computeMbps(measuredBytes, measuredElapsedSec),
                                            peakMbps = peakMbps,
                                            throughputHistoryMbps = throughputHistory.toList(),
                                            intervals = measuredIntervals,
                                            parallelStreams = streamsCount,
                                        ),
                                    )
                                }
                            }

                            STATE_TEST_END -> {
                                emit(
                                    Iperf3Progress(
                                        direction = direction,
                                        stage = Iperf3Stage.TEST_END,
                                        elapsedSec = measuredElapsedSec,
                                        targetDurationSec = durationSec,
                                        bytesTransferred = measuredBytes,
                                        instantMbps = 0.0,
                                        averageMbps = computeMbps(measuredBytes, measuredElapsedSec),
                                        peakMbps = peakMbps,
                                        throughputHistoryMbps = throughputHistory.toList(),
                                        intervals = measuredIntervals,
                                        parallelStreams = streamsCount,
                                    ),
                                )
                            }

                            STATE_EXCHANGE_RESULTS -> {
                                emit(
                                    Iperf3Progress(
                                        direction = direction,
                                        stage = Iperf3Stage.EXCHANGE_RESULTS,
                                        elapsedSec = measuredElapsedSec,
                                        targetDurationSec = durationSec,
                                        bytesTransferred = measuredBytes,
                                        instantMbps = 0.0,
                                        averageMbps = computeMbps(measuredBytes, measuredElapsedSec),
                                        peakMbps = peakMbps,
                                        throughputHistoryMbps = throughputHistory.toList(),
                                        intervals = measuredIntervals,
                                        parallelStreams = streamsCount,
                                    ),
                                )
                                val clientResultsJson = buildClientResultsJson(
                                    perStreamBytes = measuredPerStreamBytes,
                                    elapsedSec = measuredElapsedSec,
                                    direction = direction,
                                )
                                writeFramedJson(controlOut, clientResultsJson)
                                val serverPayload = readFramedJson(controlIn)
                                serverStats = parseServerResults(serverPayload)
                            }

                            STATE_DISPLAY_RESULTS, STATE_IPERF_DONE -> {
                                if (state == STATE_DISPLAY_RESULTS) {
                                    runCatching {
                                        controlOut.writeByte(STATE_IPERF_DONE.toInt())
                                        controlOut.flush()
                                    }
                                }
                                closeDataStreams()
                                val finalRes = buildSuccessResult(
                                    direction = direction,
                                    port = port,
                                    resolvedTarget = resolvedTarget,
                                    measuredElapsedSec = measuredElapsedSec,
                                    measuredBytes = measuredBytes,
                                    peakMbps = peakMbps,
                                    serverStats = serverStats,
                                    parallelStreams = streamsCount,
                                    intervals = measuredIntervals,
                                    omitSec = appliedOmitSec,
                                )
                                emit(completedProgress(finalRes, durationSec, throughputHistory))
                                break@stateLoop
                            }

                            STATE_ACCESS_DENIED -> {
                                emit(
                                    failedProgress(
                                        direction = direction,
                                        port = port,
                                        resolvedTarget = resolvedTarget,
                                        durationSec = durationSec,
                                        kind = Iperf3ErrorKind.ACCESS_DENIED,
                                        message = "服务器正忙，另一个测速正在进行，请稍后重试",
                                        parallelStreams = streamsCount,
                                    ),
                                )
                                break@stateLoop
                            }

                            STATE_SERVER_ERROR -> {
                                val errCode = readOptionalServerErrorCode(controlSocket, controlIn)
                                val suffix = if (errCode != null && errCode != 0) " (错误码 $errCode)" else ""
                                emit(
                                    failedProgress(
                                        direction = direction,
                                        port = port,
                                        resolvedTarget = resolvedTarget,
                                        durationSec = durationSec,
                                        kind = Iperf3ErrorKind.SERVER_ERROR,
                                        message = "iperf3 服务端返回错误$suffix，请确认服务端版本兼容或稍后重试",
                                        parallelStreams = streamsCount,
                                    ),
                                )
                                break@stateLoop
                            }

                            STATE_SERVER_TERMINATE -> {
                                emit(
                                    failedProgress(
                                        direction = direction,
                                        port = port,
                                        resolvedTarget = resolvedTarget,
                                        durationSec = durationSec,
                                        kind = Iperf3ErrorKind.SERVER_ERROR,
                                        message = "iperf3 服务端主动终止了本次测速连接",
                                        parallelStreams = streamsCount,
                                    ),
                                )
                                break@stateLoop
                            }

                            else -> {
                                // Valid iperf3 server control state codes are 1..16; anything outside (such as
                                // ASCII 'H' from HTTP or 'S' from OpenSSH) means the port is not an iperf3 server.
                                if (state < 1 || state > STATE_IPERF_DONE) {
                                    val nonIperfHint = if (state == 'H'.code.toByte() || state == 'S'.code.toByte()) {
                                        "目标端口 $port 返回了非 iperf3 协议响应（可能是 Web/SSH 服务），请确认端口填写正确且已在 VPS 开启 iperf3 -s -p $port"
                                    } else {
                                        "收到非标准 iperf3 服务端状态码 ($state)，请确认目标端口 $port 运行的是 iperf3 v3 服务端"
                                    }
                                    emit(
                                        failedProgress(
                                            direction = direction,
                                            port = port,
                                            resolvedTarget = resolvedTarget,
                                            durationSec = durationSec,
                                            kind = Iperf3ErrorKind.PROTOCOL_ERROR,
                                            message = nonIperfHint,
                                            parallelStreams = streamsCount,
                                        ),
                                    )
                                    break@stateLoop
                                }
                            }
                        }
                    }
                } finally {
                    closeDataStreams()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: ConnectException) {
            emit(
                failedProgress(
                    direction = direction,
                    port = port,
                    resolvedTarget = resolvedTarget,
                    durationSec = durationSec,
                    kind = Iperf3ErrorKind.CONNECTION_REFUSED,
                    message = "无法连接 $port 端口（连接被拒绝）：对应 VPS 尚未开启 iperf3 服务端或监听端口不一致，请先开启 iperf3 -s -p $port",
                    parallelStreams = streamsCount,
                ),
            )
        } catch (e: SocketTimeoutException) {
            emit(
                failedProgress(
                    direction = direction,
                    port = port,
                    resolvedTarget = resolvedTarget,
                    durationSec = durationSec,
                    kind = Iperf3ErrorKind.TIMEOUT,
                    message = "连接或响应超时（端口 $port）：请确认 VPS 已运行 iperf3 -s -p $port，且防火墙（ufw/firewalld/iptables）与云安全组已放行 TCP $port",
                    parallelStreams = streamsCount,
                ),
            )
        } catch (e: EOFException) {
            emit(
                failedProgress(
                    direction = direction,
                    port = port,
                    resolvedTarget = resolvedTarget,
                    durationSec = durationSec,
                    kind = Iperf3ErrorKind.PROTOCOL_ERROR,
                    message = "iperf3 协议数据流意外中断，请检查目标端口 $port 是否为标准 iperf3 服务端",
                    parallelStreams = streamsCount,
                ),
            )
        } catch (e: IOException) {
            val rawMsg = e.message.orEmpty()
            val (kind, friendlyMsg) = when {
                rawMsg.contains("ECONNREFUSED", ignoreCase = true) ||
                    rawMsg.contains("Connection refused", ignoreCase = true) ->
                    Iperf3ErrorKind.CONNECTION_REFUSED to
                        "无法连接 $port 端口（连接被拒绝）：对应 VPS 尚未开启 iperf3 服务端或监听端口不一致，请先开启 iperf3 -s -p $port"
                rawMsg.contains("NoRouteToHost", ignoreCase = true) ||
                    rawMsg.contains("EHOSTUNREACH", ignoreCase = true) ||
                    rawMsg.contains("ENETUNREACH", ignoreCase = true) ||
                    rawMsg.contains("unreachable", ignoreCase = true) ->
                    Iperf3ErrorKind.IO_ERROR to
                        "目标主机或网络不可达（端口 $port）：请检查目标 IPv4/IPv6 地址与当前网络连接"
                rawMsg.contains("ECONNRESET", ignoreCase = true) ||
                    rawMsg.contains("Connection reset", ignoreCase = true) ->
                    Iperf3ErrorKind.IO_ERROR to
                        "连接被对端重置（端口 $port）：请确认 VPS 上的 iperf3 服务端正常运行且未被防火墙/安全策略阻断"
                else ->
                    Iperf3ErrorKind.IO_ERROR to
                        "网络测速中断（端口 $port）：${rawMsg.ifBlank { e.javaClass.simpleName }}"
            }
            emit(
                failedProgress(
                    direction = direction,
                    port = port,
                    resolvedTarget = resolvedTarget,
                    durationSec = durationSec,
                    kind = kind,
                    message = friendlyMsg,
                    parallelStreams = streamsCount,
                ),
            )
        } finally {
            stopTransferFlag.set(true)
            cancelHandle?.dispose()
            for (sock in dataSockets) {
                runCatching { sock.close() }
            }
            runCatching { controlSocket.close() }
        }
    }.flowOn(ioDispatcher)

    private class TransferLoopSummary(
        val bytesTransferred: Long,
        val perStreamBytes: LongArray,
        val elapsedSec: Double,
        val peakMbps: Double,
        val intervals: List<Iperf3IntervalSample>,
        val sentTestEndByClient: Boolean,
        val appliedOmitSec: Int,
    )

    private suspend fun runDataTransferLoop(
        transferScope: CoroutineScope,
        activeStreamJobs: MutableList<Job>,
        stopTransferFlag: AtomicBoolean,
        direction: Iperf3Direction,
        durationSec: Int,
        omitSec: Int,
        chunkSize: Int,
        dataSockets: List<Socket>,
        controlIn: DataInputStream,
        controlOut: DataOutputStream,
        onProgress: suspend (Iperf3Progress) -> Unit,
    ): TransferLoopSummary {
        val streamCount = dataSockets.size
        val streamCounters = Array(streamCount) { AtomicLong(0L) }
        val streamFinishNanos = Array(streamCount) { AtomicLong(0L) }
        val streamFinished = Array(streamCount) { AtomicBoolean(false) }
        val streamEof = Array(streamCount) { AtomicBoolean(false) }
        val countingActive = AtomicBoolean(true)

        // Pre-allocate transfer buffer(s) before starting the benchmark clock so allocation/zeroing
        // does not eat into measured transfer time.
        val sharedUploadPayload = if (direction == Iperf3Direction.UPLOAD) {
            ByteArray(chunkSize) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }
        } else {
            null
        }
        val downloadBuffers = if (direction == Iperf3Direction.DOWNLOAD) {
            Array(streamCount) { ByteArray(chunkSize) }
        } else {
            emptyArray()
        }

        val startNanos = System.nanoTime()
        val omitNanos = omitSec.coerceAtLeast(0) * 1_000_000_000L
        val durationNanos = durationSec * 1_000_000_000L
        // Measurement window opens once the warm-up (`-O`) seconds elapse; it is re-anchored to the exact
        // switch instant so the counted window is always the full `durationSec`.
        var omitDone = omitNanos <= 0L
        var measureStartNanos = startNanos + omitNanos
        var deadlineNanos = measureStartNanos + durationNanos
        val omitBaseline = LongArray(streamCount)
        var omitBaselineSum = 0L

        for (streamIdx in 0 until streamCount) {
            val socket = dataSockets[streamIdx]
            val counter = streamCounters[streamIdx]
            val finishNanosRef = streamFinishNanos[streamIdx]
            val finishedFlag = streamFinished[streamIdx]
            val eofFlag = streamEof[streamIdx]

            val workerJob = transferScope.launch(ioDispatcher) {
                try {
                    if (direction == Iperf3Direction.UPLOAD) {
                        val payload = sharedUploadPayload!!
                        val out = socket.getOutputStream()
                        var localBytes = 0L
                        while (countingActive.get() && !stopTransferFlag.get()) {
                            out.write(payload)
                            localBytes += payload.size
                            counter.lazySet(localBytes)
                        }
                        runCatching { out.flush() }
                    } else {
                        // Keep SO_TIMEOUT = 0 (blocking recv): on Android (IoBridge.recvfrom) and
                        // OpenJDK (NioSocketImpl), setting soTimeout > 0 forces an extra poll() syscall
                        // or O_NONBLOCK timedRead on every single socket read.
                        // Also keep draining after countingActive turns false until IPERF_DONE so the
                        // remote `iperf3 -s` sender never stalls on a full TCP receive window.
                        val buffer = downloadBuffers[streamIdx]
                        val input = socket.getInputStream()
                        var localBytes = 0L
                        while (!stopTransferFlag.get()) {
                            val read = input.read(buffer)
                            if (read <= 0) {
                                if (read == -1) {
                                    eofFlag.set(true)
                                }
                                break
                            }
                            if (countingActive.get()) {
                                localBytes += read
                                counter.lazySet(localBytes)
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: IOException) {
                    // Expected when data socket closes at test completion or server stops transfer.
                } finally {
                    finishNanosRef.set(System.nanoTime())
                    finishedFlag.set(true)
                }
            }
            activeStreamJobs.add(workerJob)
        }

        fun sumTransferredBytes(): Long {
            var sum = 0L
            for (counter in streamCounters) {
                sum += counter.get()
            }
            return sum
        }

        fun measuredPerStreamBytes(): LongArray =
            LongArray(streamCount) { idx -> (streamCounters[idx].get() - omitBaseline[idx]).coerceAtLeast(0L) }

        fun allStreamsFinished(): Boolean = streamFinished.all { it.get() }
        fun allStreamsEof(): Boolean = streamEof.all { it.get() }

        val totalTargetSec = omitSec.coerceAtLeast(0) + durationSec
        var lastReportNanos = startNanos
        var lastReportBytes = 0L // raw (warm-up inclusive) byte counter
        var lastInstantMbps = 0.0
        var peakMbps = 0.0
        val reportStepNanos = progressIntervalMs.coerceIn(50L, 1_000L) * 1_000_000L

        // Per-phase interval bookkeeping. Like official `iperf3 -O`, warm-up intervals are labelled
        // relative to the test start and flagged `omitted`, then measured intervals restart at 0.00s.
        var phaseOriginNanos = startNanos
        var phaseLenSec = if (omitDone) durationSec else omitSec
        var phaseOmitted = !omitDone
        var intervalStartNanos = startNanos
        var intervalStartBytes = 0L // raw byte counter
        var nextIntervalBoundaryIdx = 1
        val recordedIntervals = mutableListOf<Iperf3IntervalSample>()

        fun recordInterval(endNanos: Long, rawBytesAtEnd: Long) {
            val deltaBytes = (rawBytesAtEnd - intervalStartBytes).coerceAtLeast(0L)
            val startSec = ((intervalStartNanos - phaseOriginNanos) / 1_000_000_000.0).coerceAtLeast(0.0)
            val endSec = ((endNanos - phaseOriginNanos) / 1_000_000_000.0).coerceAtLeast(startSec + 0.001)
            val segMbps = computeMbps(deltaBytes, (endSec - startSec).coerceAtLeast(0.001))
            recordedIntervals += Iperf3IntervalSample(
                index = recordedIntervals.size + 1,
                startSec = startSec,
                endSec = endSec,
                bytesTransferred = deltaBytes,
                bitrateMbps = segMbps,
                streams = streamCount,
                omitted = phaseOmitted,
            )
            if (!phaseOmitted) peakMbps = maxOf(peakMbps, segMbps)
            intervalStartNanos = endNanos
            intervalStartBytes = rawBytesAtEnd
        }

        fun phaseEndNanos(): Long = if (phaseOmitted) measureStartNanos else deadlineNanos

        while (!allStreamsFinished()) {
            currentCoroutineContext().ensureActive()
            val now = System.nanoTime()

            if (!omitDone && now >= measureStartNanos) {
                // Close the warm-up window: freeze per-stream baselines and restart the clock.
                for (idx in 0 until streamCount) omitBaseline[idx] = streamCounters[idx].get()
                omitBaselineSum = omitBaseline.sum()
                if (now > intervalStartNanos) recordInterval(now, omitBaselineSum)
                omitDone = true
                measureStartNanos = now
                deadlineNanos = now + durationNanos
                phaseOriginNanos = now
                phaseLenSec = durationSec
                phaseOmitted = false
                intervalStartNanos = now
                intervalStartBytes = omitBaselineSum
                nextIntervalBoundaryIdx = 1
                lastReportNanos = now
                lastReportBytes = omitBaselineSum
            }

            if (omitDone && now >= deadlineNanos) {
                break
            }
            if (runCatching { controlIn.available() > 0 }.getOrDefault(false)) {
                // Server sent an early control state (e.g. TEST_END, EXCHANGE_RESULTS, SERVER_ERROR).
                break
            }

            if (nextIntervalBoundaryIdx < phaseLenSec) {
                val boundaryNanos = phaseOriginNanos + nextIntervalBoundaryIdx * INTERVAL_STEP_NANOS
                if (now >= boundaryNanos && phaseEndNanos() - now >= MIN_TAIL_INTERVAL_NANOS) {
                    recordInterval(now, sumTransferredBytes())
                    val endSec = (now - phaseOriginNanos) / 1_000_000_000.0
                    nextIntervalBoundaryIdx = maxOf(nextIntervalBoundaryIdx + 1, endSec.toInt() + 1)
                }
            }

            if (now - lastReportNanos >= reportStepNanos) {
                val rawBytes = sumTransferredBytes()
                val deltaBytes = (rawBytes - lastReportBytes).coerceAtLeast(0L)
                val deltaSec = ((now - lastReportNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                val phaseElapsedSec = ((now - phaseOriginNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                val phaseBytes = (rawBytes - omitBaselineSum).coerceAtLeast(0L)
                val instantMbps = computeMbps(deltaBytes, deltaSec)
                val avgMbps = computeMbps(phaseBytes, phaseElapsedSec)
                lastInstantMbps = instantMbps
                if (omitDone) {
                    peakMbps = maxOf(peakMbps, instantMbps, avgMbps)
                }
                val totalElapsedSec = ((now - startNanos) / 1_000_000_000.0).coerceAtLeast(0.001)

                onProgress(
                    Iperf3Progress(
                        direction = direction,
                        stage = Iperf3Stage.TEST_RUNNING,
                        elapsedSec = totalElapsedSec.coerceAtMost(totalTargetSec.toDouble()),
                        targetDurationSec = totalTargetSec,
                        bytesTransferred = phaseBytes,
                        instantMbps = instantMbps,
                        averageMbps = avgMbps,
                        peakMbps = peakMbps,
                        intervals = recordedIntervals.toList(),
                        parallelStreams = streamCount,
                        inOmitPhase = !omitDone,
                    ),
                )
                lastReportNanos = now
                lastReportBytes = rawBytes
            }

            val afterReportNanos = System.nanoTime()
            val remainingToPhaseEndMs = ((phaseEndNanos() - afterReportNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
            val remainingToReportMs = ((reportStepNanos - (afterReportNanos - lastReportNanos) + 999_999L) / 1_000_000L)
                .coerceAtLeast(1L)
            val remainingToIntervalMs = if (nextIntervalBoundaryIdx < phaseLenSec) {
                val nextBoundaryNanos = phaseOriginNanos + nextIntervalBoundaryIdx * INTERVAL_STEP_NANOS
                ((nextBoundaryNanos - afterReportNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
            } else {
                remainingToPhaseEndMs
            }
            delay(minOf(POLL_STEP_MS, remainingToPhaseEndMs, remainingToReportMs, remainingToIntervalMs))
        }

        // Freeze measurement counters immediately before any join/control-channel round-trip so
        // post-deadline teardown latency never inflates the elapsed-time denominator.
        val endedBeforeDeadline = allStreamsFinished()
        countingActive.set(false)

        // If the server ended the test while still inside the warm-up window, there is no measured window:
        // fall back to counting everything from the start (equivalent to `-O 0`).
        val appliedOmitSec: Int
        if (!omitDone) {
            appliedOmitSec = 0
            for (i in recordedIntervals.indices) {
                recordedIntervals[i] = recordedIntervals[i].copy(omitted = false)
                peakMbps = maxOf(peakMbps, recordedIntervals[i].bitrateMbps)
            }
            measureStartNanos = startNanos
            phaseOriginNanos = startNanos
        } else {
            appliedOmitSec = omitSec.coerceAtLeast(0)
        }

        var snapshotNanos = if (endedBeforeDeadline) {
            streamFinishNanos.maxOfOrNull { it.get() }?.takeIf { it > measureStartNanos } ?: System.nanoTime()
        } else {
            System.nanoTime()
        }
        var perStreamBytes = measuredPerStreamBytes()

        val sentTestEnd: Boolean
        if (direction == Iperf3Direction.UPLOAD) {
            // Let any in-flight write() complete cleanly before signaling TEST_END on control socket.
            val joinedEarly = withTimeoutOrNull(250L) {
                for (streamJob in activeStreamJobs) {
                    streamJob.join()
                }
                true
            } ?: false

            if (joinedEarly) {
                val updatedBytes = measuredPerStreamBytes()
                if (updatedBytes.sum() > perStreamBytes.sum()) {
                    perStreamBytes = updatedBytes
                    snapshotNanos = System.nanoTime()
                }
            }

            val serverAlreadySignaled = runCatching { controlIn.available() > 0 }.getOrDefault(false)
            if (!serverAlreadySignaled) {
                controlOut.writeByte(STATE_TEST_END.toInt())
                controlOut.flush()
                sentTestEnd = true
            } else {
                sentTestEnd = false
            }

            val joined = if (joinedEarly) {
                true
            } else {
                withTimeoutOrNull(300L) {
                    for (streamJob in activeStreamJobs) {
                        streamJob.join()
                    }
                    true
                } ?: false
            }
            if (!joined) {
                stopTransferFlag.set(true)
                for (streamJob in activeStreamJobs) {
                    streamJob.cancel()
                }
                for (sock in dataSockets) {
                    runCatching { sock.close() }
                }
            } else {
                for (sock in dataSockets) {
                    runCatching { sock.shutdownOutput() }
                }
            }
        } else {
            // DOWNLOAD (-R): If server closed all data streams early, check briefly whether it also
            // sent TEST_END / EXCHANGE_RESULTS; otherwise immediately send TEST_END while keeping
            // activeStreamJobs draining in-flight bytes until IPERF_DONE.
            if (allStreamsEof() && runCatching { controlIn.available() == 0 }.getOrDefault(false)) {
                delay(50L)
            }
            val serverAlreadySignaled = runCatching { controlIn.available() > 0 }.getOrDefault(false)
            if (!serverAlreadySignaled) {
                controlOut.writeByte(STATE_TEST_END.toInt())
                controlOut.flush()
                sentTestEnd = true
            } else {
                sentTestEnd = false
            }
        }

        val finalBytes = perStreamBytes.sum()
        val finalRawBytes = finalBytes + omitBaselineSum
        val finalElapsedSec = ((snapshotNanos - measureStartNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
        val finalAvgMbps = computeMbps(finalBytes, finalElapsedSec)

        val tailIntervalNanos = snapshotNanos - intervalStartNanos
        val tailIntervalBytes = (finalRawBytes - intervalStartBytes).coerceAtLeast(0L)
        val lastMeasuredIdx = recordedIntervals.indexOfLast { !it.omitted }
        if (lastMeasuredIdx < 0 || tailIntervalNanos >= MIN_TAIL_INTERVAL_NANOS) {
            val startSec = ((intervalStartNanos - phaseOriginNanos) / 1_000_000_000.0).coerceAtLeast(0.0)
            val endSec = finalElapsedSec.coerceAtLeast(startSec + 0.001)
            val segDuration = (endSec - startSec).coerceAtLeast(0.001)
            val segMbps = computeMbps(tailIntervalBytes, segDuration)
            recordedIntervals += Iperf3IntervalSample(
                index = recordedIntervals.size + 1,
                startSec = startSec,
                endSec = endSec,
                bytesTransferred = tailIntervalBytes,
                bitrateMbps = segMbps,
                streams = streamCount,
            )
        } else {
            val prev = recordedIntervals[lastMeasuredIdx]
            val mergedBytes = prev.bytesTransferred + tailIntervalBytes
            val mergedEndSec = finalElapsedSec.coerceAtLeast(prev.startSec + 0.001)
            val mergedDuration = (mergedEndSec - prev.startSec).coerceAtLeast(0.001)
            recordedIntervals[lastMeasuredIdx] = prev.copy(
                endSec = mergedEndSec,
                bytesTransferred = mergedBytes,
                bitrateMbps = computeMbps(mergedBytes, mergedDuration),
            )
        }

        val rawTailNanos = snapshotNanos - lastReportNanos
        val tailInstantMbps = if (rawTailNanos >= MIN_TAIL_SAMPLE_NANOS) {
            val tailDeltaBytes = (finalRawBytes - lastReportBytes).coerceAtLeast(0L)
            computeMbps(tailDeltaBytes, rawTailNanos / 1_000_000_000.0)
        } else if (lastInstantMbps > 0.0) {
            lastInstantMbps
        } else {
            finalAvgMbps
        }
        val maxIntervalMbps = recordedIntervals.filter { !it.omitted }.maxOfOrNull { it.bitrateMbps } ?: 0.0
        peakMbps = maxOf(peakMbps, finalAvgMbps, tailInstantMbps, maxIntervalMbps)
        val finalizedIntervals = recordedIntervals.toList()

        onProgress(
            Iperf3Progress(
                direction = direction,
                stage = Iperf3Stage.TEST_RUNNING,
                elapsedSec = finalElapsedSec,
                targetDurationSec = durationSec,
                bytesTransferred = finalBytes,
                instantMbps = tailInstantMbps,
                averageMbps = finalAvgMbps,
                peakMbps = peakMbps,
                intervals = finalizedIntervals,
                parallelStreams = streamCount,
            ),
        )

        return TransferLoopSummary(
            bytesTransferred = finalBytes,
            perStreamBytes = perStreamBytes,
            elapsedSec = finalElapsedSec,
            peakMbps = peakMbps,
            intervals = finalizedIntervals,
            sentTestEndByClient = sentTestEnd,
            appliedOmitSec = appliedOmitSec,
        )
    }

    private fun buildClientParamsJson(
        durationSec: Int,
        omitSec: Int,
        chunkSize: Int,
        direction: Iperf3Direction,
        parallelStreams: Int,
        socketBufferBytes: Int?,
    ): String {
        val obj = buildJsonObject {
            put("tcp", true)
            put("omit", omitSec.coerceAtLeast(0))
            put("time", durationSec)
            put("num", 0)
            put("blockcount", 0)
            put("parallel", parallelStreams)
            put("len", chunkSize)
            if (socketBufferBytes != null && socketBufferBytes > 0) {
                put("window", socketBufferBytes)
            }
            if (direction == Iperf3Direction.DOWNLOAD) {
                put("reverse", true)
            }
            put("pacing_timer", 1000)
            put("client_version", CLIENT_VERSION)
        }
        return obj.toString()
    }

    private fun buildClientResultsJson(
        perStreamBytes: LongArray,
        elapsedSec: Double,
        direction: Iperf3Direction,
    ): String {
        val senderHasRetransmits = if (direction == Iperf3Direction.UPLOAD) 0 else -1
        val obj = buildJsonObject {
            put("cpu_util_total", 0.0)
            put("cpu_util_user", 0.0)
            put("cpu_util_system", 0.0)
            put("sender_has_retransmits", senderHasRetransmits)
            putJsonArray("streams") {
                for (idx in perStreamBytes.indices) {
                    addJsonObject {
                        put("id", streamProtocolId(idx))
                        put("bytes", perStreamBytes[idx])
                        put("retransmits", -1)
                        put("jitter", 0.0)
                        put("errors", 0)
                        put("omitted_errors", 0)
                        put("packets", 0)
                        put("omitted_packets", 0)
                        put("start_time", 0.0)
                        put("end_time", elapsedSec)
                    }
                }
            }
        }
        return obj.toString()
    }

    private fun writeFramedJson(out: DataOutputStream, payload: String) {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readFramedJson(input: DataInputStream): String {
        val length = input.readInt()
        if (length <= 0 || length > MAX_JSON_FRAME_BYTES) {
            throw IOException("非法的 iperf3 JSON 帧长度: $length")
        }
        val buf = ByteArray(length)
        input.readFully(buf)
        return String(buf, StandardCharsets.UTF_8)
    }

    private data class ParsedServerResults(
        val bytes: Long?,
        val durationSec: Double?,
        val mbps: Double?,
        val retransmits: Long?,
    )

    private fun parseServerResults(rawJson: String): ParsedServerResults? {
        return runCatching {
            val root: JsonObject = json.parseToJsonElement(rawJson).jsonObject
            val streams = root["streams"]?.jsonArray.orEmpty()
            var totalBytes = 0L
            var hasBytes = false
            var maxDuration = 0.0
            var totalRetransmits = 0L
            var hasRetransmits = false

            for (el in streams) {
                val obj = el.jsonObject
                val b = obj["bytes"]?.jsonPrimitive?.longOrNull
                if (b != null) {
                    totalBytes += b
                    hasBytes = true
                }
                val start = obj["start_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val end = obj["end_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val dur = (end - start).coerceAtLeast(0.0)
                if (dur > maxDuration) maxDuration = dur

                val retx = obj["retransmits"]?.jsonPrimitive?.longOrNull
                if (retx != null && retx >= 0L) {
                    totalRetransmits += retx
                    hasRetransmits = true
                }
            }

            val mbps = if (hasBytes && maxDuration > 0.0) {
                computeMbps(totalBytes, maxDuration)
            } else {
                null
            }

            ParsedServerResults(
                bytes = if (hasBytes) totalBytes else null,
                durationSec = maxDuration.takeIf { it > 0.0 },
                mbps = mbps,
                retransmits = if (hasRetransmits) totalRetransmits else null,
            )
        }.getOrNull()
    }

    private fun readOptionalServerErrorCode(socket: Socket, input: DataInputStream): Int? {
        return runCatching {
            val prevTimeout = socket.soTimeout
            socket.soTimeout = 200
            try {
                val iErrno = input.readInt()
                runCatching { input.readInt() } // optional system errno
                iErrno
            } finally {
                runCatching { socket.soTimeout = prevTimeout }
            }
        }.getOrNull()
    }

    private fun buildSuccessResult(
        direction: Iperf3Direction,
        port: Int,
        resolvedTarget: ResolvedTarget,
        measuredElapsedSec: Double,
        measuredBytes: Long,
        peakMbps: Double,
        serverStats: ParsedServerResults?,
        parallelStreams: Int = DEFAULT_PARALLEL_STREAMS,
        intervals: List<Iperf3IntervalSample> = emptyList(),
        omitSec: Int = 0,
    ): Iperf3Result {
        val safeDuration = measuredElapsedSec.coerceAtLeast(0.001)
        val clientMbps = computeMbps(measuredBytes, safeDuration)
        val intervalPeak = intervals.filter { !it.omitted }.maxOfOrNull { it.bitrateMbps } ?: 0.0
        return Iperf3Result(
            direction = direction,
            port = port,
            resolvedTarget = resolvedTarget,
            durationSec = safeDuration,
            bytesTransferred = measuredBytes,
            clientMbps = clientMbps,
            serverMbps = serverStats?.mbps,
            peakMbps = maxOf(peakMbps, clientMbps, intervalPeak),
            // In iperf3 TCP mode, only the sender reports TCP_INFO retransmits:
            // - DOWNLOAD (-R): server is sender -> serverStats.retransmits is valid.
            // - UPLOAD: client is sender, Java Socket cannot read TCP_INFO tcpi_retrans, and
            //   server (receiver) always reports 0 (or -1), which would falsely display "0 retransmits".
            retransmits = if (direction == Iperf3Direction.DOWNLOAD) serverStats?.retransmits else null,
            success = true,
            parallelStreams = parallelStreams,
            intervals = intervals,
            omitSec = omitSec,
            serverBytesTransferred = serverStats?.bytes,
            serverDurationSec = serverStats?.durationSec,
        )
    }

    private fun completedProgress(
        result: Iperf3Result,
        targetDurationSec: Int,
        history: List<Double>,
    ): Iperf3Progress = Iperf3Progress(
        direction = result.direction,
        stage = Iperf3Stage.COMPLETED,
        elapsedSec = result.durationSec,
        targetDurationSec = targetDurationSec,
        bytesTransferred = result.bytesTransferred,
        instantMbps = result.effectiveMbps,
        averageMbps = result.effectiveMbps,
        peakMbps = result.peakMbps,
        throughputHistoryMbps = history.toList(),
        intervals = result.intervals,
        finalResult = result,
        parallelStreams = result.parallelStreams,
    )

    private fun failedProgress(
        direction: Iperf3Direction,
        port: Int,
        resolvedTarget: ResolvedTarget?,
        durationSec: Int,
        kind: Iperf3ErrorKind,
        message: String,
        parallelStreams: Int = DEFAULT_PARALLEL_STREAMS,
    ): Iperf3Progress {
        val res = Iperf3Result(
            direction = direction,
            port = port,
            resolvedTarget = resolvedTarget,
            durationSec = 0.0,
            bytesTransferred = 0L,
            clientMbps = 0.0,
            success = false,
            errorKind = kind,
            errorMessage = message,
            parallelStreams = parallelStreams,
        )
        return Iperf3Progress(
            direction = direction,
            stage = Iperf3Stage.FAILED,
            elapsedSec = 0.0,
            targetDurationSec = durationSec,
            bytesTransferred = 0L,
            instantMbps = 0.0,
            averageMbps = 0.0,
            peakMbps = 0.0,
            finalResult = res,
            errorKind = kind,
            errorMessage = message,
            parallelStreams = parallelStreams,
        )
    }

    companion object {
        const val COOKIE_SIZE: Int = 37
        const val CLIENT_VERSION: String = "3.16"
        /**
         * Live UI sample cadence. 500 ms halves Compose recomposition / gauge animation and Markdown
         * regeneration work during the transfer compared with 250 ms, while per-second interval
         * accounting is unaffected.
         */
        const val DEFAULT_PROGRESS_INTERVAL_MS: Long = 500L
        const val DEFAULT_PARALLEL_STREAMS: Int = 1
        const val MIN_PARALLEL_STREAMS: Int = 1
        const val MAX_PARALLEL_STREAMS: Int = 16

        const val STATE_TEST_START: Byte = 1
        const val STATE_TEST_RUNNING: Byte = 2
        const val STATE_TEST_END: Byte = 4
        const val STATE_PARAM_EXCHANGE: Byte = 9
        const val STATE_CREATE_STREAMS: Byte = 10
        const val STATE_SERVER_TERMINATE: Byte = 11
        const val STATE_CLIENT_TERMINATE: Byte = 12
        const val STATE_EXCHANGE_RESULTS: Byte = 13
        const val STATE_DISPLAY_RESULTS: Byte = 14
        const val STATE_IPERF_START: Byte = 15
        const val STATE_IPERF_DONE: Byte = 16
        const val STATE_ACCESS_DENIED: Byte = -1
        const val STATE_SERVER_ERROR: Byte = -2

        private const val COOKIE_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
        private const val CONTROL_READ_TIMEOUT_MS = 10_000
        private const val POLL_STEP_MS = 50L
        private const val INTERVAL_STEP_NANOS = 1_000_000_000L
        private const val MIN_TAIL_SAMPLE_NANOS = 50_000_000L
        private const val MIN_TAIL_INTERVAL_NANOS = 120_000_000L
        private const val MAX_JSON_FRAME_BYTES = 4 * 1024 * 1024
        private const val MAX_HISTORY_SAMPLES = 120

        private val secureRandom = SecureRandom()

        /**
         * Official `iperf3` assigns stream IDs `1, 3, 4, 5, ...` (skipping reserved ID `2`).
         */
        internal fun streamProtocolId(streamIndex: Int): Int =
            if (streamIndex <= 0) 1 else streamIndex + 2

        /**
         * Generates a standard iperf3 37-byte cookie: 36 chars from `[a-z2-7]` + trailing NUL (`0x00`).
         */
        fun generateCookieBytes(random: SecureRandom = secureRandom): ByteArray {
            val bytes = ByteArray(COOKIE_SIZE)
            for (i in 0 until COOKIE_SIZE - 1) {
                bytes[i] = COOKIE_ALPHABET[random.nextInt(COOKIE_ALPHABET.length)].code.toByte()
            }
            bytes[COOKIE_SIZE - 1] = 0
            return bytes
        }

        fun computeMbps(bytes: Long, seconds: Double): Double {
            if (bytes <= 0L || seconds <= 0.0) return 0.0
            return (bytes.toDouble() * 8.0) / (seconds * 1_000_000.0)
        }
    }
}
