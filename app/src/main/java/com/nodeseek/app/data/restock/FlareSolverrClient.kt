package com.nodeseek.app.data.restock

import com.nodeseek.app.domain.restock.FlareSolverrConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Outcome of a single FlareSolverr `request.get` solve attempt.
 */
sealed interface FlareSolverrOutcome {
    /**
     * Challenge solved (or page simply fetched); [html] is the rendered page content
     * ready for [StockPageAnalyzer.analyzeHtml].
     */
    data class Solved(
        val html: String,
        val finalUrl: String,
        val userAgent: String,
    ) : FlareSolverrOutcome

    data class Failure(
        val reason: String,
        val isTransient: Boolean,
    ) : FlareSolverrOutcome
}

/**
 * Minimal JSON client for a self-hosted FlareSolverr instance (`POST {base}/v1`).
 *
 * The app never re-uses `cf_clearance` cookies on-device: clearance is bound to the
 * solving browser's User-Agent + egress IP, so only the solved HTML returned by the
 * service is consumed and handed to [StockPageAnalyzer.analyzeHtml].
 */
class FlareSolverrClient(
    private val httpClient: OkHttpClient = createDefaultHttpClient(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    /**
     * Solves (or fetches) [targetUrl] via `request.get` using the user's FlareSolverr
     * endpoint from [config]. The per-call OkHttp client widens its read timeout to
     * `maxTimeout + margin` so slow challenges are not cut off client-side.
     */
    suspend fun requestGet(
        config: FlareSolverrConfig,
        targetUrl: String,
    ): FlareSolverrOutcome = withContext(Dispatchers.IO) {
        val base = config.normalizedBaseUrl
        if (base.isBlank()) {
            return@withContext FlareSolverrOutcome.Failure(
                reason = "FlareSolverr 地址未配置",
                isTransient = false,
            )
        }

        val payload = json.encodeToString(
            FlareSolverrRequestPayload(
                cmd = CMD_REQUEST_GET,
                url = targetUrl,
                maxTimeout = config.maxTimeoutMillis.toLong(),
            ),
        )

        val callClient = httpClient.newBuilder()
            .readTimeout(config.maxTimeoutMillis.toLong() + READ_TIMEOUT_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        val outcome = when (val result = executeCommand(callClient, base, payload)) {
            is CommandResult.Rejected -> FlareSolverrOutcome.Failure(
                reason = result.reason,
                isTransient = result.isTransient,
            )
            is CommandResult.Ok -> {
                val solution = result.solution
                if (solution == null) {
                    FlareSolverrOutcome.Failure(
                        reason = "FlareSolverr 响应缺少 solution",
                        isTransient = false,
                    )
                } else if (solution.response.isBlank()) {
                    FlareSolverrOutcome.Failure(
                        reason = "FlareSolverr 返回了空页面",
                        isTransient = false,
                    )
                } else {
                    FlareSolverrOutcome.Solved(
                        html = solution.response,
                        finalUrl = solution.url.ifBlank { targetUrl },
                        userAgent = solution.userAgent,
                    )
                }
            }
        }
        outcome
    }

    /**
     * Cheap connectivity probe (`sessions.list`) used by the settings UI test button.
     * `sessions.list` replies with `status=ok` and no `solution`, so only transport
     * and protocol-level success is checked here.
     */
    suspend fun ping(config: FlareSolverrConfig): Boolean = withContext(Dispatchers.IO) {
        val base = config.normalizedBaseUrl
        if (base.isBlank()) return@withContext false
        val payload = json.encodeToString(FlareSolverrRequestPayload(cmd = CMD_SESSIONS_LIST))
        executeCommand(httpClient, base, payload) is CommandResult.Ok
    }

    /**
     * Raw command transport result: [Ok] means FlareSolverr answered `status=ok`
     * (solution may legitimately be absent for commands like `sessions.list`).
     */
    private sealed interface CommandResult {
        data class Ok(val solution: FlareSolverrSolution?, val message: String) : CommandResult
        data class Rejected(val reason: String, val isTransient: Boolean) : CommandResult
    }

    private fun executeCommand(
        client: OkHttpClient,
        base: String,
        payloadJson: String,
    ): CommandResult {
        val request = try {
            Request.Builder()
                .url("$base/v1")
                .post(payloadJson.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .build()
        } catch (t: Throwable) {
            return CommandResult.Rejected(
                reason = "FlareSolverr 地址无效：${t.message ?: base}",
                isTransient = false,
            )
        }

        return try {
            client.newCall(request).execute().use { response ->
                val statusCode = response.code
                val rawBody = response.body?.string().orEmpty()
                if (statusCode != 200) {
                    return CommandResult.Rejected(
                        reason = "FlareSolverr 服务响应 HTTP $statusCode",
                        isTransient = statusCode in 500..599,
                    )
                }
                val decoded = runCatching { json.decodeFromString<FlareSolverrResponse>(rawBody) }
                    .getOrElse {
                        return CommandResult.Rejected(
                            reason = "FlareSolverr 响应解析失败",
                            isTransient = true,
                        )
                    }
                if (!decoded.status.equals("ok", ignoreCase = true)) {
                    val reason = decoded.message.ifBlank { "FlareSolverr 未能解开验证" }
                    return CommandResult.Rejected(
                        reason = "FlareSolverr：$reason",
                        isTransient = false,
                    )
                }
                CommandResult.Ok(solution = decoded.solution, message = decoded.message)
            }
        } catch (e: IOException) {
            CommandResult.Rejected(
                reason = "FlareSolverr 服务不可达：${e.message?.take(80) ?: "网络连接失败"}",
                isTransient = true,
            )
        } catch (t: Throwable) {
            CommandResult.Rejected(
                reason = "FlareSolverr 调用异常：${t.message?.take(80) ?: "未知错误"}",
                isTransient = false,
            )
        }
    }

    @Serializable
    private data class FlareSolverrRequestPayload(
        val cmd: String,
        val url: String? = null,
        val maxTimeout: Long? = null,
    )

    @Serializable
    private data class FlareSolverrSolution(
        val url: String = "",
        val status: Int = 0,
        val userAgent: String = "",
        val response: String = "",
    )

    @Serializable
    private data class FlareSolverrResponse(
        val status: String = "",
        val message: String = "",
        val solution: FlareSolverrSolution? = null,
    )

    companion object {
        private const val CMD_REQUEST_GET = "request.get"
        private const val CMD_SESSIONS_LIST = "sessions.list"
        private const val READ_TIMEOUT_MARGIN_MILLIS = 15_000L

        fun createDefaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(
                FlareSolverrConfig.DEFAULT_MAX_TIMEOUT_MILLIS.toLong() + READ_TIMEOUT_MARGIN_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}
