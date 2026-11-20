package com.nodeseek.app.data.netbench

import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3ErrorKind
import com.nodeseek.app.domain.netbench.Iperf3Progress
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.Iperf3Stage
import com.nodeseek.app.domain.netbench.ResolvedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-Kotlin implementation of the native **iperf3 v3.x TCP client protocol** (no NDK/root binary).
 *
 * Wire protocol overview:
 * 1. Connect control TCP socket to `host:port`, immediately send 37-byte ASCII cookie (`[a-z2-7]{36}\0`).
 * 2. Enter state loop reading 1-byte signed state codes from server:
 *    - [STATE_PARAM_EXCHANGE] (9): send 4-byte BE length + JSON test parameters (`"reverse":true`
 *      only in Download mode).
 *    - [STATE_CREATE_STREAMS] (10): open data TCP socket and send the same 37-byte cookie.
 *    - [STATE_TEST_START] (1) -> [STATE_TEST_RUNNING] (2): transfer 128 KiB chunks while emitting
 *      live [Iperf3Progress] every ~250ms, then signal [STATE_TEST_END] (4).
 *    - [STATE_EXCHANGE_RESULTS] (13): send client results JSON & parse server results JSON.
 *    - [STATE_DISPLAY_RESULTS] (14) -> send [STATE_IPERF_DONE] (16).
 * 3. Cleanly handles [STATE_ACCESS_DENIED] (-1), [STATE_SERVER_ERROR] (-2), timeouts, and refusal.
 */
class Iperf3ClientRunner(
    private val resolver: NetBenchTargetResolver = NetBenchTargetResolver(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun run(config: Iperf3Config): Flow<Iperf3Progress> = flow {
        val direction = config.direction
        val durationSec = config.normalizedDurationSec
        val chunkSize = config.normalizedChunkSize
        val connectTimeoutMs = config.connectTimeoutMs.coerceIn(1_000, 15_000)

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
            emit(failedProgress(direction, config.normalizedPort, null, durationSec, kind, msg))
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
            emit(failedProgress(direction, port, resolvedTarget, durationSec, Iperf3ErrorKind.IO_ERROR, msg))
            return@flow
        }
        val dataSocketRef = AtomicReference<Socket?>(null)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion {
            runCatching { controlSocket.close() }
            runCatching { dataSocketRef.get()?.close() }
        }

        try {
            controlSocket.tcpNoDelay = true
            controlSocket.connect(InetSocketAddress(endpoint.address, port), connectTimeoutMs)
            controlSocket.soTimeout = CONTROL_READ_TIMEOUT_MS

            val controlIn = DataInputStream(BufferedInputStream(controlSocket.getInputStream()))
            val controlOut = DataOutputStream(controlSocket.getOutputStream())

            // Handshake step 1: send 37-byte cookie on control stream
            controlOut.write(cookieBytes)
            controlOut.flush()

            var measuredBytes = 0L
            var measuredElapsedSec = 0.0
            var peakMbps = 0.0
            val throughputHistory = mutableListOf<Double>()
            var serverStats: ParsedServerResults? = null
            var testExecuted = false

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
                            ),
                        )
                        val paramsJson = buildClientParamsJson(
                            durationSec = durationSec,
                            chunkSize = chunkSize,
                            direction = direction,
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
                            ),
                        )
                        val dataSocket = activeChannel.createSocket()
                        dataSocketRef.set(dataSocket)
                        runCatching {
                            dataSocket.sendBufferSize = SOCKET_BUFFER_BYTES
                            dataSocket.receiveBufferSize = SOCKET_BUFFER_BYTES
                        }
                        dataSocket.connect(InetSocketAddress(endpoint.address, port), connectTimeoutMs)
                        val dataOut = dataSocket.getOutputStream()
                        dataOut.write(cookieBytes)
                        dataOut.flush()
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
                            ),
                        )
                    }

                    STATE_TEST_RUNNING -> {
                        val dataSocket = dataSocketRef.get()
                            ?: throw IOException("未能在测速开始前建立数据通道")
                        testExecuted = true

                        val transferSummary = runDataTransferLoop(
                            direction = direction,
                            durationSec = durationSec,
                            chunkSize = chunkSize,
                            dataSocket = dataSocket,
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
                        measuredElapsedSec = transferSummary.elapsedSec
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
                            ),
                        )
                        val clientResultsJson = buildClientResultsJson(
                            bytesTransferred = measuredBytes,
                            elapsedSec = measuredElapsedSec,
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
                        val finalRes = buildSuccessResult(
                            direction = direction,
                            port = port,
                            resolvedTarget = resolvedTarget,
                            measuredElapsedSec = measuredElapsedSec,
                            measuredBytes = measuredBytes,
                            peakMbps = peakMbps,
                            serverStats = serverStats,
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
                                ),
                            )
                            break@stateLoop
                        }
                    }
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
                ),
            )
        } finally {
            cancelHandle?.dispose()
            runCatching { dataSocketRef.get()?.close() }
            runCatching { controlSocket.close() }
        }
    }.flowOn(ioDispatcher)

    private data class TransferLoopSummary(
        val bytesTransferred: Long,
        val elapsedSec: Double,
        val peakMbps: Double,
        val sentTestEndByClient: Boolean,
    )

    private suspend fun runDataTransferLoop(
        direction: Iperf3Direction,
        durationSec: Int,
        chunkSize: Int,
        dataSocket: Socket,
        controlIn: DataInputStream,
        controlOut: DataOutputStream,
        onProgress: suspend (Iperf3Progress) -> Unit,
    ): TransferLoopSummary = coroutineScope {
        val totalBytes = AtomicLong(0L)
        val stopRequested = AtomicBoolean(false)
        val workerFinished = AtomicBoolean(false)
        val workerEof = AtomicBoolean(false)
        val startNanos = System.nanoTime()
        val deadlineNanos = startNanos + durationSec * 1_000_000_000L

        val workerJob = launch(ioDispatcher) {
            try {
                if (direction == Iperf3Direction.UPLOAD) {
                    val payload = ByteArray(chunkSize) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }
                    val out = dataSocket.getOutputStream()
                    while (!stopRequested.get() && isActive) {
                        if (System.nanoTime() >= deadlineNanos) break
                        out.write(payload)
                        totalBytes.addAndGet(payload.size.toLong())
                    }
                    runCatching { out.flush() }
                } else {
                    dataSocket.soTimeout = DOWNLOAD_POLL_SO_TIMEOUT_MS
                    val buffer = ByteArray(chunkSize)
                    val input = dataSocket.getInputStream()
                    while (!stopRequested.get() && isActive) {
                        if (System.nanoTime() >= deadlineNanos) break
                        val read = try {
                            input.read(buffer)
                        } catch (_: SocketTimeoutException) {
                            0
                        }
                        if (read == -1) {
                            workerEof.set(true)
                            break
                        }
                        if (read > 0) {
                            totalBytes.addAndGet(read.toLong())
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                // Socket may close when server finishes or stops transfer; control channel decides outcome.
            } finally {
                workerFinished.set(true)
            }
        }

        var lastReportNanos = startNanos
        var lastReportBytes = 0L
        var peakMbps = 0.0
        val reportStepNanos = progressIntervalMs.coerceIn(50L, 1_000L) * 1_000_000L

        while (!workerFinished.get()) {
            currentCoroutineContext().ensureActive()
            val now = System.nanoTime()
            if (now >= deadlineNanos) {
                break
            }
            if (runCatching { controlIn.available() > 0 }.getOrDefault(false)) {
                // Server sent an early control state (e.g. TEST_END, EXCHANGE_RESULTS, SERVER_ERROR).
                break
            }
            if (now - lastReportNanos >= reportStepNanos) {
                val curBytes = totalBytes.get()
                val deltaBytes = (curBytes - lastReportBytes).coerceAtLeast(0L)
                val deltaSec = ((now - lastReportNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                val elapsedSec = ((now - startNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                val instantMbps = computeMbps(deltaBytes, deltaSec)
                val avgMbps = computeMbps(curBytes, elapsedSec)
                peakMbps = maxOf(peakMbps, instantMbps, avgMbps)

                onProgress(
                    Iperf3Progress(
                        direction = direction,
                        stage = Iperf3Stage.TEST_RUNNING,
                        elapsedSec = elapsedSec.coerceAtMost(durationSec.toDouble()),
                        targetDurationSec = durationSec,
                        bytesTransferred = curBytes,
                        instantMbps = instantMbps,
                        averageMbps = avgMbps,
                        peakMbps = peakMbps,
                    ),
                )
                lastReportNanos = now
                lastReportBytes = curBytes
            }
            delay(50L)
        }

        stopRequested.set(true)

        // Wait briefly for worker to notice stopRequested without closing dataSocket yet, so
        // real iperf3 -s receives TEST_END on the control channel before seeing a data EOF/reset.
        val joinedEarly = withTimeoutOrNull(250L) {
            workerJob.join()
            true
        } ?: false

        // If download stream hit EOF cleanly, wait briefly to see if server already sent TEST_END/EXCHANGE_RESULTS.
        if (workerEof.get() && runCatching { controlIn.available() == 0 }.getOrDefault(false)) {
            delay(80L)
        }

        val serverAlreadySignaled = runCatching { controlIn.available() > 0 }.getOrDefault(false)
        var sentTestEnd = false
        if (!serverAlreadySignaled) {
            controlOut.writeByte(STATE_TEST_END.toInt())
            controlOut.flush()
            sentTestEnd = true
        }

        val joined = if (joinedEarly) {
            true
        } else {
            withTimeoutOrNull(300L) {
                workerJob.join()
                true
            } ?: false
        }
        if (!joined) {
            workerJob.cancel()
            runCatching { dataSocket.close() }
        } else if (direction == Iperf3Direction.UPLOAD) {
            runCatching { dataSocket.shutdownOutput() }
        }

        val endNanos = System.nanoTime()
        val finalBytes = totalBytes.get()
        val finalElapsedSec = ((endNanos - startNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
        val finalAvgMbps = computeMbps(finalBytes, finalElapsedSec)
        val tailDeltaBytes = (finalBytes - lastReportBytes).coerceAtLeast(0L)
        val tailDeltaSec = ((endNanos - lastReportNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
        val tailInstantMbps = computeMbps(tailDeltaBytes, tailDeltaSec)
        peakMbps = maxOf(peakMbps, finalAvgMbps, tailInstantMbps)

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
            ),
        )

        TransferLoopSummary(
            bytesTransferred = finalBytes,
            elapsedSec = finalElapsedSec,
            peakMbps = peakMbps,
            sentTestEndByClient = sentTestEnd,
        )
    }

    private fun buildClientParamsJson(
        durationSec: Int,
        chunkSize: Int,
        direction: Iperf3Direction,
    ): String {
        val obj = buildJsonObject {
            put("tcp", true)
            put("omit", 0)
            put("time", durationSec)
            put("parallel", 1)
            put("len", chunkSize)
            if (direction == Iperf3Direction.DOWNLOAD) {
                put("reverse", true)
            }
            put("client_version", CLIENT_VERSION)
        }
        return obj.toString()
    }

    private fun buildClientResultsJson(
        bytesTransferred: Long,
        elapsedSec: Double,
    ): String {
        val obj = buildJsonObject {
            put("cpu_util_total", 0.0)
            put("cpu_util_user", 0.0)
            put("cpu_util_system", 0.0)
            put("sender_has_retransmits", 0)
            put("congestion_used", "cubic")
            putJsonArray("streams") {
                addJsonObject {
                    put("id", 1)
                    put("bytes", bytesTransferred)
                    put("retransmits", -1)
                    put("jitter", 0.0)
                    put("errors", 0)
                    put("packets", 0)
                    put("start_time", 0.0)
                    put("end_time", elapsedSec)
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
    ): Iperf3Result {
        val safeDuration = measuredElapsedSec.coerceAtLeast(0.001)
        val clientMbps = computeMbps(measuredBytes, safeDuration)
        return Iperf3Result(
            direction = direction,
            port = port,
            resolvedTarget = resolvedTarget,
            durationSec = safeDuration,
            bytesTransferred = measuredBytes,
            clientMbps = clientMbps,
            serverMbps = serverStats?.mbps,
            peakMbps = maxOf(peakMbps, clientMbps),
            retransmits = serverStats?.retransmits,
            success = true,
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
        finalResult = result,
    )

    private fun failedProgress(
        direction: Iperf3Direction,
        port: Int,
        resolvedTarget: ResolvedTarget?,
        durationSec: Int,
        kind: Iperf3ErrorKind,
        message: String,
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
        )
    }

    companion object {
        const val COOKIE_SIZE: Int = 37
        const val CLIENT_VERSION: String = "3.16"
        const val DEFAULT_PROGRESS_INTERVAL_MS: Long = 250L

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
        private const val DOWNLOAD_POLL_SO_TIMEOUT_MS = 250
        private const val SOCKET_BUFFER_BYTES = 262_144
        private const val MAX_JSON_FRAME_BYTES = 4 * 1024 * 1024
        private const val MAX_HISTORY_SAMPLES = 120

        private val secureRandom = SecureRandom()

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
