package com.nodeseek.app.data.fleet

import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.countryCodeToFlagEmoji
import com.nodeseek.app.domain.fleet.extractCountryCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of fetching live server snapshots from a single probe panel.
 */
data class ProbePanelFetchResult(
    val detectedPanelType: ProbePanelType,
    val snapshots: List<ProbeServerSnapshot>,
)

/**
 * Raised when a probe panel request violates credential transmission boundaries
 * (e.g., plaintext HTTP with credentials, HTTPS-to-HTTP downgrade, or cross-origin redirect).
 */
class ProbeCredentialBoundaryException(
    message: String,
) : IOException(message)

/**
 * Multi-protocol HTTP probe connector supporting:
 * - **Nezha v0** (`GET /api/v1/server/details` & `/api/v1/server/list` with `Authorization: <token>`)
 * - **Nezha v1** (`GET /api/v1/server` with Bearer token, `nz-jwt` cookie, or public access)
 * - **Komari** (`GET /api/nodes` + `GET /api/recent` or `/api/client` with public / API key / cookie)
 * - **AUTO discovery** probing endpoints gracefully to identify the panel flavor
 *
 * Security & isolation guarantees:
 * - Strips foreign interceptors and cookies ([CookieJar.NO_COOKIES]), disables automatic redirects,
 *   and validates strict origin equality (`scheme`, normalized `host`, effective `port`) on every hop
 *   before attaching `Authorization`, `X-API-Key`, or `Cookie` headers.
 * - Rejects plaintext `http://` URLs before sending any network traffic whenever an `apiToken` is configured
 *   (unless [allowLoopbackHttpWithCredentials] is explicitly enabled for local loopback tests).
 */
class ProbePanelClient(
    httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val offlineThresholdMillis: Long = DEFAULT_OFFLINE_THRESHOLD_MILLIS,
    private val allowLoopbackHttpWithCredentials: Boolean = false,
) {
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

    private data class HttpOutcome(
        val code: Int,
        val body: String,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private data class RawWireResponse(
        val code: Int,
        val location: String?,
        val body: String,
    )

    /**
     * Tests connectivity to [config], auto-detecting panel flavor when `panelType == AUTO`.
     */
    suspend fun testConnection(config: ProbePanelConfig): ProbeConnectivityResult =
        withContext(ioDispatcher) {
            try {
                val fetched = fetchPanelServers(config)
                ProbeConnectivityResult(
                    success = true,
                    detectedPanelType = fetched.detectedPanelType,
                    serverCount = fetched.snapshots.size,
                    onlineCount = fetched.snapshots.count { it.isOnline },
                    sampleServers = fetched.snapshots.take(5),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ProbeConnectivityResult(
                    success = false,
                    errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "连接探针面板失败",
                )
            }
        }

    /**
     * Fetches current server snapshots from [config] using its configured or auto-discovered protocol.
     */
    suspend fun fetchPanelServers(config: ProbePanelConfig): ProbePanelFetchResult =
        withContext(ioDispatcher) {
            val rawTrimmed = config.baseUrl.trim()
            require(rawTrimmed.isNotEmpty()) { "探针地址不能为空" }

            val baseUrl = normalizeBaseUrl(rawTrimmed)
            val originUrl = baseUrl.toHttpUrlOrNull()
                ?: throw ProbeCredentialBoundaryException("探针地址格式无效或协议不受支持：$rawTrimmed")
            if (hasUserInfo(originUrl)) {
                throw ProbeCredentialBoundaryException("安全校验失败：禁止在探针地址中内嵌账号或密码信息")
            }

            val cleanToken = sanitizeAndValidateApiToken(config.apiToken)
            if (cleanToken != null && !isAllowedCredentialOrigin(originUrl, allowLoopbackHttpWithCredentials)) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：配置了 API Token 或 Cookie 的探针面板必须使用 HTTPS 加密连接，拒绝通过明文 HTTP 发送凭证",
                )
            }

            when (config.effectivePanelType) {
                ProbePanelType.NEZHA_V0 -> ProbePanelFetchResult(
                    detectedPanelType = ProbePanelType.NEZHA_V0,
                    snapshots = fetchNezhaV0(config.id, originUrl, cleanToken),
                )
                ProbePanelType.NEZHA_V1 -> ProbePanelFetchResult(
                    detectedPanelType = ProbePanelType.NEZHA_V1,
                    snapshots = fetchNezhaV1(config.id, originUrl, cleanToken),
                )
                ProbePanelType.KOMARI -> ProbePanelFetchResult(
                    detectedPanelType = ProbePanelType.KOMARI,
                    snapshots = fetchKomari(config.id, originUrl, cleanToken),
                )
                ProbePanelType.AUTO -> autoDiscoverAndFetch(config.id, originUrl, cleanToken)
            }
        }

    // ── AUTO Discovery ────────────────────────────────────────────────────────

    private suspend fun autoDiscoverAndFetch(
        panelId: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): ProbePanelFetchResult {
        var authFailureHint: String? = null
        var networkFailureHint: String? = null

        // 1. Try Komari (`GET /api/nodes`)
        val komariAttempt = tryProbeKomari(panelId, originUrl, apiToken)
        if (komariAttempt.result != null) return komariAttempt.result
        if (komariAttempt.isAuthError) authFailureHint = komariAttempt.errorMessage
        if (komariAttempt.isNetworkError) networkFailureHint = komariAttempt.errorMessage

        // 2. Try Nezha v1 (`GET /api/v1/server`)
        val nezhaV1Attempt = tryProbeNezhaV1(panelId, originUrl, apiToken)
        if (nezhaV1Attempt.result != null) return nezhaV1Attempt.result
        if (nezhaV1Attempt.isAuthError && authFailureHint == null) authFailureHint = nezhaV1Attempt.errorMessage
        if (nezhaV1Attempt.isNetworkError && networkFailureHint == null) networkFailureHint = nezhaV1Attempt.errorMessage

        // 3. Try Nezha v0 (`GET /api/v1/server/details` then `/api/v1/server/list`)
        val nezhaV0Attempt = tryProbeNezhaV0(panelId, originUrl, apiToken)
        if (nezhaV0Attempt.result != null) return nezhaV0Attempt.result
        if (nezhaV0Attempt.isAuthError && authFailureHint == null) authFailureHint = nezhaV0Attempt.errorMessage
        if (nezhaV0Attempt.isNetworkError && networkFailureHint == null) networkFailureHint = nezhaV0Attempt.errorMessage

        val reason = authFailureHint
            ?: networkFailureHint
            ?: "未能自动识别探针协议（已依次检测 Komari / Nezha v1 / Nezha v0），请检查地址或手动指定面板类型"
        throw IOException(reason)
    }

    private data class DiscoveryAttempt(
        val result: ProbePanelFetchResult? = null,
        val isAuthError: Boolean = false,
        val isNetworkError: Boolean = false,
        val errorMessage: String? = null,
    )

    private suspend fun tryProbeKomari(
        panelId: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): DiscoveryAttempt {
        val (outcome, effectiveHeaders) = try {
            executeKomariGet(
                url = buildEndpointUrl(originUrl, "api", "nodes"),
                apiToken = apiToken,
                expectedOrigin = originUrl,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (e: IOException) {
            return DiscoveryAttempt(isNetworkError = true, errorMessage = "网络请求失败：${e.message}")
        }

        if (outcome.code == 401 || outcome.code == 403) {
            return DiscoveryAttempt(
                isAuthError = true,
                errorMessage = "Komari 面板需要认证 (HTTP ${outcome.code})，请填入 API Token 或 Cookie",
            )
        }
        if (outcome.isSuccessful) {
            val root = parseJsonOrNull(outcome.body)
            if (root != null && looksLikeKomariNodes(root)) {
                val nodes = parseKomariNodesList(panelId, root)
                val merged = enrichKomariWithLiveTelemetry(originUrl, effectiveHeaders, nodes)
                return DiscoveryAttempt(
                    result = ProbePanelFetchResult(
                        detectedPanelType = ProbePanelType.KOMARI,
                        snapshots = merged,
                    ),
                )
            }
        }

        // Fallback probe via Komari JSON-RPC 2.0 `POST /api/rpc2` (`common:getNodes`)
        val (rpcOutcome, rpcHeaders) = try {
            executeKomariPostJson(
                url = buildEndpointUrl(originUrl, "api", "rpc2"),
                jsonBody = KOMARI_RPC_GET_NODES_BODY,
                apiToken = apiToken,
                expectedOrigin = originUrl,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (_: IOException) {
            return DiscoveryAttempt()
        }
        if (rpcOutcome.code == 401 || rpcOutcome.code == 403) {
            return DiscoveryAttempt(
                isAuthError = true,
                errorMessage = "Komari 面板需要认证 (HTTP ${rpcOutcome.code})，请填入 API Token 或 Cookie",
            )
        }
        if (!rpcOutcome.isSuccessful) return DiscoveryAttempt()
        val rpcRoot = parseJsonOrNull(rpcOutcome.body) ?: return DiscoveryAttempt()
        if (!looksLikeKomariNodes(rpcRoot)) return DiscoveryAttempt()

        val nodes = parseKomariNodesList(panelId, rpcRoot)
        val merged = enrichKomariWithLiveTelemetry(originUrl, rpcHeaders, nodes)
        return DiscoveryAttempt(
            result = ProbePanelFetchResult(
                detectedPanelType = ProbePanelType.KOMARI,
                snapshots = merged,
            ),
        )
    }

    private suspend fun tryProbeNezhaV1(
        panelId: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): DiscoveryAttempt {
        val outcome = try {
            executeNezhaV1Get(
                url = buildEndpointUrl(originUrl, "api", "v1", "server"),
                apiToken = apiToken,
                expectedOrigin = originUrl,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (e: IOException) {
            return DiscoveryAttempt(isNetworkError = true, errorMessage = "网络请求失败：${e.message}")
        }

        if (outcome.code == 401 || outcome.code == 403) {
            return DiscoveryAttempt(
                isAuthError = true,
                errorMessage = "哪吒 v1 认证失败 (HTTP ${outcome.code})，请检查 JWT Token / Cookie",
            )
        }
        if (!outcome.isSuccessful) return DiscoveryAttempt()

        val root = parseJsonOrNull(outcome.body) ?: return DiscoveryAttempt()
        if (!looksLikeNezhaV1Response(root)) return DiscoveryAttempt()

        val snapshots = parseNezhaV1Snapshots(panelId, root)
        return DiscoveryAttempt(
            result = ProbePanelFetchResult(
                detectedPanelType = ProbePanelType.NEZHA_V1,
                snapshots = snapshots,
            ),
        )
    }

    private suspend fun tryProbeNezhaV0(
        panelId: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): DiscoveryAttempt {
        val headers = buildNezhaV0Headers(apiToken)
        val detailsOutcome = try {
            executeGet(
                initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "details"),
                headers = headers,
                expectedOrigin = originUrl,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (e: IOException) {
            return DiscoveryAttempt(isNetworkError = true, errorMessage = "网络请求失败：${e.message}")
        }

        if (detailsOutcome.code == 401 || detailsOutcome.code == 403) {
            return DiscoveryAttempt(
                isAuthError = true,
                errorMessage = "哪吒 v0 认证失败 (HTTP ${detailsOutcome.code})，请检查 API Token",
            )
        }
        if (detailsOutcome.isSuccessful) {
            val root = parseJsonOrNull(detailsOutcome.body)
            if (root != null && looksLikeNezhaV0Response(root)) {
                val snapshots = parseNezhaV0Snapshots(panelId, root)
                return DiscoveryAttempt(
                    result = ProbePanelFetchResult(
                        detectedPanelType = ProbePanelType.NEZHA_V0,
                        snapshots = snapshots,
                    ),
                )
            }
        }

        val listOutcome = try {
            executeGet(
                initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "list"),
                headers = headers,
                expectedOrigin = originUrl,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (e: IOException) {
            return DiscoveryAttempt(isNetworkError = true, errorMessage = "网络请求失败：${e.message}")
        }

        if (listOutcome.code == 401 || listOutcome.code == 403) {
            return DiscoveryAttempt(
                isAuthError = true,
                errorMessage = "哪吒 v0 认证失败 (HTTP ${listOutcome.code})，请检查 API Token",
            )
        }
        if (listOutcome.isSuccessful) {
            val root = parseJsonOrNull(listOutcome.body)
            if (root != null && looksLikeNezhaV0Response(root)) {
                val snapshots = parseNezhaV0Snapshots(panelId, root)
                return DiscoveryAttempt(
                    result = ProbePanelFetchResult(
                        detectedPanelType = ProbePanelType.NEZHA_V0,
                        snapshots = snapshots,
                    ),
                )
            }
        }
        return DiscoveryAttempt()
    }

    // ── Nezha v0 ──────────────────────────────────────────────────────────────

    private suspend fun fetchNezhaV0(
        panelId: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeServerSnapshot> {
        val headers = buildNezhaV0Headers(apiToken)
        val detailsResp = executeGet(
            initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "details"),
            headers = headers,
            expectedOrigin = originUrl,
        )
        if (detailsResp.isSuccessful) {
            val root = parseJsonOrNull(detailsResp.body)
            if (root != null) {
                validateNezhaV0Code(root)
                val parsed = parseNezhaV0Snapshots(panelId, root)
                if (parsed.isNotEmpty()) return parsed
            }
        } else if (detailsResp.code == 401 || detailsResp.code == 403) {
            throw IOException("哪吒 v0 认证失败 (HTTP ${detailsResp.code})，请检查 API Token")
        }

        val listResp = executeGet(
            initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "list"),
            headers = headers,
            expectedOrigin = originUrl,
        )
        if (!listResp.isSuccessful) {
            throw IOException("哪吒 v0 接口请求失败 (HTTP ${listResp.code})")
        }
        val listRoot = parseJsonOrNull(listResp.body)
            ?: throw IOException("哪吒 v0 返回数据非有效 JSON")
        validateNezhaV0Code(listRoot)
        return parseNezhaV0Snapshots(panelId, listRoot)
    }

    private fun validateNezhaV0Code(root: JsonElement) {
        val obj = root as? JsonObject ?: return
        val code = obj["code"]?.intOrNull()
        if (code != null && code != 0) {
            val msg = obj["message"]?.stringOrNull().orEmpty().ifBlank { "code=$code" }
            throw IOException("哪吒 v0 返回错误：$msg")
        }
    }

    private fun looksLikeNezhaV0Response(root: JsonElement): Boolean {
        val obj = root as? JsonObject ?: return false
        val code = obj["code"]?.intOrNull()
        val result = obj["result"] as? JsonArray
        if (code == 0 && result != null) return true
        if (result != null && result.isNotEmpty()) {
            val first = result.first() as? JsonObject ?: return false
            return first.containsKey("status") || first.containsKey("host") || first.containsKey("last_active")
        }
        return false
    }

    internal fun parseNezhaV0Snapshots(
        panelId: String,
        root: JsonElement,
    ): List<ProbeServerSnapshot> {
        val now = nowMillisProvider()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["result"] as? JsonArray) ?: (root["data"] as? JsonArray)
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val remoteId = obj["id"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj["name"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: "Server #$remoteId"
            val host = obj["host"] as? JsonObject
            val status = obj["status"] as? JsonObject

            val rawCountry = host?.getFirstString("CountryCode", "country_code")
                ?: obj.getFirstString("country_code", "CountryCode")
                ?: ""
            val countryCode = extractCountryCode(rawCountry)
            val platform = host?.getFirstString("Platform", "platform").orEmpty()
            val platformVersion = host?.getFirstString("PlatformVersion", "platform_version").orEmpty()
            val osText = listOf(platform, platformVersion).filter { it.isNotBlank() }.joinToString(" ")

            val cpuList = host?.get("CPU") as? JsonArray ?: host?.get("cpu") as? JsonArray
            val cpuModel = cpuList?.firstOrNull()?.stringOrNull().orEmpty()
            val cpuCores = parseCpuCoresHint(cpuModel).takeIf { it > 0 } ?: (cpuList?.size ?: 0)

            val memTotal = host?.getFirstLong("MemTotal", "mem_total") ?: 0L
            val diskTotal = host?.getFirstLong("DiskTotal", "disk_total") ?: 0L

            val cpuPercent = (status?.getFirstDouble("CPU", "cpu") ?: 0.0).coerceIn(0.0, 100.0)
            val memUsed = status?.getFirstLong("MemUsed", "mem_used") ?: 0L
            val diskUsed = status?.getFirstLong("DiskUsed", "disk_used") ?: 0L
            val netInSpeed = status?.getFirstLong("NetInSpeed", "net_in_speed") ?: 0L
            val netOutSpeed = status?.getFirstLong("NetOutSpeed", "net_out_speed") ?: 0L
            val netInTransfer = status?.getFirstLong("NetInTransfer", "net_in_transfer") ?: 0L
            val netOutTransfer = status?.getFirstLong("NetOutTransfer", "net_out_transfer") ?: 0L
            val uptime = status?.getFirstLong("Uptime", "uptime") ?: 0L
            val load1 = status?.getFirstDouble("Load1", "load_1", "load1")

            val lastActiveMillis = parseEpochMillis(obj["last_active"]) ?: 0L
            val explicitOnline = obj["online"]?.booleanOrNull() ?: obj["is_online"]?.booleanOrNull()
            val isOnline = resolveOnlineState(
                explicitOnline = explicitOnline,
                lastActiveMillis = lastActiveMillis,
                uptimeSeconds = uptime,
                cpuPercent = cpuPercent,
                memUsedBytes = memUsed,
                nowEpochMillis = now,
            )

            ProbeServerSnapshot(
                panelId = panelId,
                remoteServerId = remoteId,
                name = name,
                countryCode = countryCode,
                flagEmoji = countryCodeToFlagEmoji(countryCode),
                os = osText,
                cpuModel = cpuModel,
                cpuCores = cpuCores,
                cpuPercent = cpuPercent,
                memUsedBytes = memUsed.coerceAtLeast(0L),
                memTotalBytes = memTotal.coerceAtLeast(0L),
                diskUsedBytes = diskUsed.coerceAtLeast(0L),
                diskTotalBytes = diskTotal.coerceAtLeast(0L),
                netInSpeedBps = netInSpeed.coerceAtLeast(0L),
                netOutSpeedBps = netOutSpeed.coerceAtLeast(0L),
                monthlyTransferInBytes = netInTransfer.coerceAtLeast(0L),
                monthlyTransferOutBytes = netOutTransfer.coerceAtLeast(0L),
                trafficLimitBytes = obj.getFirstLong("traffic_limit", "TrafficLimit")?.takeIf { it > 0L },
                uptimeSeconds = uptime.coerceAtLeast(0L),
                load1 = load1,
                isOnline = isOnline,
                lastActiveEpochMillis = lastActiveMillis,
            )
        }
    }

    // ── Nezha v1 ──────────────────────────────────────────────────────────────

    private suspend fun fetchNezhaV1(
        panelId: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeServerSnapshot> {
        val resp = executeNezhaV1Get(
            url = buildEndpointUrl(originUrl, "api", "v1", "server"),
            apiToken = apiToken,
            expectedOrigin = originUrl,
        )
        if (resp.code == 401 || resp.code == 403) {
            throw IOException("哪吒 v1 认证失败 (HTTP ${resp.code})，请检查 JWT Token / Cookie")
        }
        if (!resp.isSuccessful) {
            throw IOException("哪吒 v1 接口请求失败 (HTTP ${resp.code})")
        }
        val root = parseJsonOrNull(resp.body)
            ?: throw IOException("哪吒 v1 返回数据非有效 JSON")
        val obj = root as? JsonObject
        if (obj != null && obj["success"]?.booleanOrNull() == false) {
            val err = obj["error"]?.stringOrNull() ?: obj["message"]?.stringOrNull() ?: "请求失败"
            throw IOException("哪吒 v1 返回错误：$err")
        }
        return parseNezhaV1Snapshots(panelId, root)
    }

    private suspend fun executeNezhaV1Get(
        url: HttpUrl,
        apiToken: String?,
        expectedOrigin: HttpUrl,
    ): HttpOutcome {
        val trimmedToken = apiToken?.trim().orEmpty()
        val primaryHeaders = buildNezhaV1Headers(trimmedToken, asCookie = false)
        val first = executeGet(initialUrl = url, headers = primaryHeaders, expectedOrigin = expectedOrigin)
        if ((first.code == 401 || first.code == 403) &&
            trimmedToken.isNotEmpty() &&
            !trimmedToken.startsWith("Bearer ", ignoreCase = true) &&
            !trimmedToken.contains('=')
        ) {
            val cookieHeaders = buildNezhaV1Headers(trimmedToken, asCookie = true)
            val second = executeGet(initialUrl = url, headers = cookieHeaders, expectedOrigin = expectedOrigin)
            if (second.isSuccessful) return second
        }
        return first
    }

    private fun looksLikeNezhaV1Response(root: JsonElement): Boolean {
        val obj = root as? JsonObject ?: return false
        val success = obj["success"]?.booleanOrNull()
        val data = obj["data"] as? JsonArray ?: return false
        if (success == true && data.isEmpty()) return true
        val first = data.firstOrNull() as? JsonObject ?: return false
        return first.containsKey("state") ||
            (first.containsKey("host") && first.containsKey("last_active")) ||
            first.containsKey("display_index")
    }

    internal fun parseNezhaV1Snapshots(
        panelId: String,
        root: JsonElement,
    ): List<ProbeServerSnapshot> {
        val now = nowMillisProvider()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val remoteId = obj["id"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj["name"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: "Server #$remoteId"
            val host = obj["host"] as? JsonObject
            val state = (obj["state"] as? JsonObject) ?: (obj["status"] as? JsonObject)
            val geoip = obj["geoip"] as? JsonObject

            val rawCountry = geoip?.getFirstString("country_code", "CountryCode")
                ?: obj.getFirstString("country_code", "CountryCode")
                ?: host?.getFirstString("country_code", "CountryCode")
                ?: ""
            val countryCode = extractCountryCode(rawCountry)

            val platform = host?.getFirstString("platform", "Platform").orEmpty()
            val platformVersion = host?.getFirstString("platform_version", "PlatformVersion").orEmpty()
            val osText = listOf(platform, platformVersion).filter { it.isNotBlank() }.joinToString(" ")

            val cpuList = host?.get("cpu") as? JsonArray ?: host?.get("CPU") as? JsonArray
            val cpuModel = cpuList?.firstOrNull()?.stringOrNull().orEmpty()
            val cpuCores = parseCpuCoresHint(cpuModel).takeIf { it > 0 } ?: (cpuList?.size ?: 0)

            val memTotal = host?.getFirstLong("mem_total", "MemTotal") ?: 0L
            val diskTotal = host?.getFirstLong("disk_total", "DiskTotal") ?: 0L

            val cpuPercent = (state?.getFirstDouble("cpu", "CPU") ?: 0.0).coerceIn(0.0, 100.0)
            val memUsed = state?.getFirstLong("mem_used", "MemUsed") ?: 0L
            val diskUsed = state?.getFirstLong("disk_used", "DiskUsed") ?: 0L
            val netInSpeed = state?.getFirstLong("net_in_speed", "NetInSpeed") ?: 0L
            val netOutSpeed = state?.getFirstLong("net_out_speed", "NetOutSpeed") ?: 0L
            val netInTransfer = state?.getFirstLong("net_in_transfer", "NetInTransfer") ?: 0L
            val netOutTransfer = state?.getFirstLong("net_out_transfer", "NetOutTransfer") ?: 0L
            val uptime = state?.getFirstLong("uptime", "Uptime") ?: 0L
            val load1 = state?.getFirstDouble("load_1", "load1", "Load1")

            val lastActiveMillis = parseEpochMillis(obj["last_active"]) ?: 0L
            val explicitOnline = obj["online"]?.booleanOrNull() ?: obj["is_online"]?.booleanOrNull()
            val isOnline = resolveOnlineState(
                explicitOnline = explicitOnline,
                lastActiveMillis = lastActiveMillis,
                uptimeSeconds = uptime,
                cpuPercent = cpuPercent,
                memUsedBytes = memUsed,
                nowEpochMillis = now,
            )

            val trafficLimit = obj.getFirstLong("traffic_limit")
                ?: parseTrafficLimitFromPublicNote(obj["public_note"]?.stringOrNull())

            ProbeServerSnapshot(
                panelId = panelId,
                remoteServerId = remoteId,
                name = name,
                countryCode = countryCode,
                flagEmoji = countryCodeToFlagEmoji(countryCode),
                os = osText,
                cpuModel = cpuModel,
                cpuCores = cpuCores,
                cpuPercent = cpuPercent,
                memUsedBytes = memUsed.coerceAtLeast(0L),
                memTotalBytes = memTotal.coerceAtLeast(0L),
                diskUsedBytes = diskUsed.coerceAtLeast(0L),
                diskTotalBytes = diskTotal.coerceAtLeast(0L),
                netInSpeedBps = netInSpeed.coerceAtLeast(0L),
                netOutSpeedBps = netOutSpeed.coerceAtLeast(0L),
                monthlyTransferInBytes = netInTransfer.coerceAtLeast(0L),
                monthlyTransferOutBytes = netOutTransfer.coerceAtLeast(0L),
                trafficLimitBytes = trafficLimit?.takeIf { it > 0L },
                uptimeSeconds = uptime.coerceAtLeast(0L),
                load1 = load1,
                isOnline = isOnline,
                lastActiveEpochMillis = lastActiveMillis,
            )
        }
    }

    // ── Komari ────────────────────────────────────────────────────────────────

    private suspend fun fetchKomari(
        panelId: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeServerSnapshot> {
        val (nodesResp, effectiveHeaders) = executeKomariGet(
            url = buildEndpointUrl(originUrl, "api", "nodes"),
            apiToken = apiToken,
            expectedOrigin = originUrl,
        )
        if (nodesResp.code == 401 || nodesResp.code == 403) {
            throw IOException("Komari 认证失败 (HTTP ${nodesResp.code})，请检查 API Token / Cookie")
        }

        if (nodesResp.isSuccessful) {
            val nodesRoot = parseJsonOrNull(nodesResp.body)
                ?: throw IOException("Komari /api/nodes 返回数据非有效 JSON")
            if (nodesRoot is JsonObject && nodesRoot["error"] != null && nodesRoot["error"] !is JsonNull) {
                val errMsg = (nodesRoot["error"] as? JsonObject)?.getFirstString("message")
                    ?: nodesRoot["error"]?.stringOrNull()
                    ?: "RPC 错误"
                throw IOException("Komari /api/nodes 返回错误：$errMsg")
            }
            val baseNodes = parseKomariNodesList(panelId, nodesRoot)
            return enrichKomariWithLiveTelemetry(originUrl, effectiveHeaders, baseNodes)
        }

        // Fallback to JSON-RPC 2.0 `common:getNodes` when `/api/nodes` is disabled (404/405)
        if (nodesResp.code == 404 || nodesResp.code == 405) {
            val (rpcResp, rpcHeaders) = executeKomariPostJson(
                url = buildEndpointUrl(originUrl, "api", "rpc2"),
                jsonBody = KOMARI_RPC_GET_NODES_BODY,
                apiToken = apiToken,
                expectedOrigin = originUrl,
            )
            if (rpcResp.code == 401 || rpcResp.code == 403) {
                throw IOException("Komari 认证失败 (HTTP ${rpcResp.code})，请检查 API Token / Cookie")
            }
            if (rpcResp.isSuccessful) {
                val rpcRoot = parseJsonOrNull(rpcResp.body)
                    ?: throw IOException("Komari /api/rpc2 返回数据非有效 JSON")
                val baseNodes = parseKomariNodesList(panelId, rpcRoot)
                return enrichKomariWithLiveTelemetry(originUrl, rpcHeaders, baseNodes)
            }
        }

        throw IOException("Komari /api/nodes 请求失败 (HTTP ${nodesResp.code})")
    }

    private suspend fun executeKomariGet(
        url: HttpUrl,
        apiToken: String?,
        expectedOrigin: HttpUrl,
    ): Pair<HttpOutcome, Map<String, String>> {
        val trimmedToken = apiToken?.trim().orEmpty()
        val primaryHeaders = buildKomariHeaders(trimmedToken, asCookie = false)
        val first = executeGet(initialUrl = url, headers = primaryHeaders, expectedOrigin = expectedOrigin)
        if ((first.code == 401 || first.code == 403) &&
            trimmedToken.isNotEmpty() &&
            !trimmedToken.startsWith("Bearer ", ignoreCase = true) &&
            !trimmedToken.startsWith("Basic ", ignoreCase = true) &&
            !trimmedToken.contains('=')
        ) {
            val cookieHeaders = buildKomariHeaders(trimmedToken, asCookie = true)
            val second = executeGet(initialUrl = url, headers = cookieHeaders, expectedOrigin = expectedOrigin)
            if (second.isSuccessful) return second to cookieHeaders
        }
        return first to primaryHeaders
    }

    private suspend fun executeKomariPostJson(
        url: HttpUrl,
        jsonBody: String,
        apiToken: String?,
        expectedOrigin: HttpUrl,
    ): Pair<HttpOutcome, Map<String, String>> {
        val trimmedToken = apiToken?.trim().orEmpty()
        val primaryHeaders = buildKomariHeaders(trimmedToken, asCookie = false)
        val first = executePostJson(
            initialUrl = url,
            jsonBody = jsonBody,
            headers = primaryHeaders,
            expectedOrigin = expectedOrigin,
        )
        if ((first.code == 401 || first.code == 403) &&
            trimmedToken.isNotEmpty() &&
            !trimmedToken.startsWith("Bearer ", ignoreCase = true) &&
            !trimmedToken.startsWith("Basic ", ignoreCase = true) &&
            !trimmedToken.contains('=')
        ) {
            val cookieHeaders = buildKomariHeaders(trimmedToken, asCookie = true)
            val second = executePostJson(
                initialUrl = url,
                jsonBody = jsonBody,
                headers = cookieHeaders,
                expectedOrigin = expectedOrigin,
            )
            if (second.isSuccessful) return second to cookieHeaders
        }
        return first to primaryHeaders
    }

    private fun looksLikeKomariNodes(root: JsonElement): Boolean {
        if (root is JsonObject && root["error"] != null && root["error"] !is JsonNull) {
            return false
        }
        val container = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val status = root["status"]?.stringOrNull()
                val isJsonRpc = root["jsonrpc"]?.stringOrNull() == "2.0"
                val inner = root["data"] ?: root["result"] ?: root["nodes"]
                when (inner) {
                    is JsonArray -> {
                        if (inner.isEmpty() && (status.equals("success", ignoreCase = true) || isJsonRpc)) {
                            return true
                        }
                        inner
                    }
                    is JsonObject -> {
                        if (inner.isEmpty() && (status.equals("success", ignoreCase = true) || isJsonRpc)) {
                            return true
                        }
                        inner
                    }
                    else -> null
                }
            }
            else -> null
        } ?: return false

        val first = when (container) {
            is JsonArray -> container.firstOrNull() as? JsonObject
            is JsonObject -> container.values.firstOrNull() as? JsonObject
            else -> null
        } ?: return false

        return first.containsKey("uuid") ||
            first.containsKey("cpu_cores") ||
            first.containsKey("cpu_name") ||
            (first.containsKey("region") && !first.containsKey("state"))
    }

    private data class KomariTelemetryBatch(
        val recordsByUuid: Map<String, KomariLiveTelemetry>,
        val authoritativeOnlineIds: Set<String>? = null,
        val isFullPanelSnapshot: Boolean = false,
    )

    private suspend fun enrichKomariWithLiveTelemetry(
        originUrl: HttpUrl,
        headers: Map<String, String>,
        baseNodes: List<ProbeServerSnapshot>,
    ): List<ProbeServerSnapshot> {
        if (baseNodes.isEmpty()) return emptyList()

        // 1. Official Komari JSON-RPC 2.0 bulk live status (`POST /api/rpc2` -> `common:getNodesLatestStatus`)
        // 2. Legacy/custom bulk HTTP endpoints (`/api/recent`, `/api/client`)
        // 3. Komari WebSocket `/api/clients` (`"get"` frame -> online list + latest reports)
        // 4. Per-node whitelisted ring-buffer endpoint (`GET /api/recent/{uuid}`)
        val batch = fetchKomariRpcLatestStatus(originUrl, headers)
            ?: fetchKomariTelemetryHttpBatch(buildEndpointUrl(originUrl, "api", "recent"), headers, originUrl)
            ?: fetchKomariTelemetryHttpBatch(buildEndpointUrl(originUrl, "api", "client"), headers, originUrl)
            ?: fetchKomariTelemetryWebSocketBatch(originUrl, headers)
            ?: fetchKomariPerNodeRecentBatch(originUrl, headers, baseNodes)
            ?: return baseNodes

        val now = nowMillisProvider()
        return baseNodes.map { node ->
            val live = batch.recordsByUuid[node.remoteServerId]
            if (live == null) {
                if (batch.authoritativeOnlineIds != null || batch.isFullPanelSnapshot) {
                    val isOnline = batch.authoritativeOnlineIds?.contains(node.remoteServerId) == true
                    return@map node.copy(
                        isOnline = isOnline,
                        cpuPercent = if (isOnline) node.cpuPercent else 0.0,
                        netInSpeedBps = if (isOnline) node.netInSpeedBps else 0L,
                        netOutSpeedBps = if (isOnline) node.netOutSpeedBps else 0L,
                    )
                }
                return@map node
            }

            val mergedMemTotal = live.memTotalBytes.takeIf { it > 0L } ?: node.memTotalBytes
            val mergedDiskTotal = live.diskTotalBytes.takeIf { it > 0L } ?: node.diskTotalBytes
            val mergedLastActive = live.lastActiveEpochMillis.takeIf { it > 0L } ?: node.lastActiveEpochMillis
            val explicitOnline = live.explicitOnline
                ?: batch.authoritativeOnlineIds?.contains(node.remoteServerId)
            val isOnline = explicitOnline ?: resolveOnlineState(
                explicitOnline = null,
                lastActiveMillis = mergedLastActive,
                uptimeSeconds = live.uptimeSeconds,
                cpuPercent = live.cpuPercent,
                memUsedBytes = live.memUsedBytes,
                nowEpochMillis = now,
            )
            node.copy(
                cpuPercent = if (isOnline) live.cpuPercent.coerceIn(0.0, 100.0) else 0.0,
                memUsedBytes = if (isOnline || live.memUsedBytes > 0L) live.memUsedBytes.coerceAtLeast(0L) else 0L,
                memTotalBytes = mergedMemTotal.coerceAtLeast(0L),
                diskUsedBytes = live.diskUsedBytes.coerceAtLeast(0L),
                diskTotalBytes = mergedDiskTotal.coerceAtLeast(0L),
                netInSpeedBps = if (isOnline) live.netInSpeedBps.coerceAtLeast(0L) else 0L,
                netOutSpeedBps = if (isOnline) live.netOutSpeedBps.coerceAtLeast(0L) else 0L,
                monthlyTransferInBytes = live.monthlyTransferInBytes.coerceAtLeast(0L),
                monthlyTransferOutBytes = live.monthlyTransferOutBytes.coerceAtLeast(0L),
                uptimeSeconds = if (isOnline) live.uptimeSeconds.coerceAtLeast(0L) else 0L,
                load1 = if (isOnline) (live.load1 ?: node.load1) else null,
                isOnline = isOnline,
                lastActiveEpochMillis = mergedLastActive,
            )
        }
    }

    private data class KomariLiveTelemetry(
        val cpuPercent: Double = 0.0,
        val memUsedBytes: Long = 0L,
        val memTotalBytes: Long = 0L,
        val diskUsedBytes: Long = 0L,
        val diskTotalBytes: Long = 0L,
        val netInSpeedBps: Long = 0L,
        val netOutSpeedBps: Long = 0L,
        val monthlyTransferInBytes: Long = 0L,
        val monthlyTransferOutBytes: Long = 0L,
        val uptimeSeconds: Long = 0L,
        val load1: Double? = null,
        val explicitOnline: Boolean? = null,
        val lastActiveEpochMillis: Long = 0L,
    )

    private suspend fun fetchKomariRpcLatestStatus(
        originUrl: HttpUrl,
        headers: Map<String, String>,
    ): KomariTelemetryBatch? {
        val resp = try {
            executePostJson(
                initialUrl = buildEndpointUrl(originUrl, "api", "rpc2"),
                jsonBody = KOMARI_RPC_LATEST_STATUS_BODY,
                headers = headers,
                expectedOrigin = originUrl,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (_: Throwable) {
            return null
        }
        if (!resp.isSuccessful) return null
        val root = parseJsonOrNull(resp.body) as? JsonObject ?: return null
        if (root["error"] != null && root["error"] !is JsonNull) return null
        val resultElem = root["result"] ?: return null
        val parsed = parseKomariTelemetryBatch(resultElem, inheritedOnlineSet = null)
        return parsed.copy(isFullPanelSnapshot = true)
    }

    private suspend fun fetchKomariTelemetryHttpBatch(
        url: HttpUrl,
        headers: Map<String, String>,
        expectedOrigin: HttpUrl,
    ): KomariTelemetryBatch? {
        val resp = try {
            executeGet(initialUrl = url, headers = headers, expectedOrigin = expectedOrigin)
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (_: Throwable) {
            return null
        }
        if (!resp.isSuccessful) return null
        val root = parseJsonOrNull(resp.body) ?: return null
        if (root is JsonObject && root["error"] != null && root["error"] !is JsonNull) return null
        val parsed = parseKomariTelemetryBatch(root, inheritedOnlineSet = null)
        return parsed.takeIf { it.recordsByUuid.isNotEmpty() || it.authoritativeOnlineIds != null }
    }

    private suspend fun fetchKomariTelemetryWebSocketBatch(
        originUrl: HttpUrl,
        headers: Map<String, String>,
    ): KomariTelemetryBatch? {
        val wsUrl = buildEndpointUrl(originUrl, "api", "clients")
        val rawMessage = try {
            fetchKomariSingleWebSocketFrame(
                url = wsUrl,
                headers = headers,
                expectedOrigin = originUrl,
                outboundPrompt = "get",
            )
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (_: Throwable) {
            return null
        } ?: return null

        val root = parseJsonOrNull(rawMessage) ?: return null
        val parsed = parseKomariTelemetryBatch(root, inheritedOnlineSet = null)
        return parsed.takeIf { it.recordsByUuid.isNotEmpty() || it.authoritativeOnlineIds != null }
            ?.copy(isFullPanelSnapshot = true)
    }

    private suspend fun fetchKomariPerNodeRecentBatch(
        originUrl: HttpUrl,
        headers: Map<String, String>,
        baseNodes: List<ProbeServerSnapshot>,
    ): KomariTelemetryBatch? {
        val semaphore = Semaphore(KOMARI_RECENT_CONCURRENCY)
        val pairs = coroutineScope {
            baseNodes.map { node ->
                async {
                    semaphore.withPermit {
                        val single = fetchKomariSingleNodeRecent(
                            originUrl = originUrl,
                            headers = headers,
                            uuid = node.remoteServerId,
                        )
                        if (single != null) node.remoteServerId to single else null
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        if (pairs.isEmpty()) return null
        return KomariTelemetryBatch(
            recordsByUuid = pairs.toMap(LinkedHashMap()),
            authoritativeOnlineIds = null,
            isFullPanelSnapshot = pairs.size == baseNodes.size,
        )
    }

    private suspend fun fetchKomariSingleNodeRecent(
        originUrl: HttpUrl,
        headers: Map<String, String>,
        uuid: String,
    ): KomariLiveTelemetry? {
        val url = buildEndpointUrl(originUrl, "api", "recent", uuid)
        val resp = try {
            executeGet(initialUrl = url, headers = headers, expectedOrigin = originUrl)
        } catch (c: CancellationException) {
            throw c
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (_: Throwable) {
            return null
        }
        if (!resp.isSuccessful) return null
        val root = parseJsonOrNull(resp.body) ?: return null
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                if (root["error"] != null && root["error"] !is JsonNull) return null
                (root["data"] as? JsonArray) ?: (root["result"] as? JsonArray) ?: (root["records"] as? JsonArray)
            }
            else -> null
        } ?: return null

        // `/api/recent/:uuid` holds the in-memory 1-minute sliding window; an empty array means offline.
        if (array.isEmpty()) {
            return KomariLiveTelemetry(explicitOnline = false)
        }

        var latest: KomariLiveTelemetry? = null
        for (elem in array) {
            val obj = elem as? JsonObject ?: continue
            val candidate = extractKomariTelemetryObject(obj, forcedOnline = null)
            if (latest == null || candidate.lastActiveEpochMillis >= latest.lastActiveEpochMillis) {
                latest = candidate
            }
        }
        val resolved = latest ?: return KomariLiveTelemetry(explicitOnline = false)
        return if (resolved.explicitOnline == null && resolved.lastActiveEpochMillis == 0L) {
            resolved.copy(explicitOnline = true)
        } else {
            resolved
        }
    }

    internal fun parseKomariNodesList(
        panelId: String,
        root: JsonElement,
    ): List<ProbeServerSnapshot> {
        val now = nowMillisProvider()
        val entries: List<Pair<String?, JsonObject>> = when (root) {
            is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let { obj -> null to obj } }
            is JsonObject -> {
                val container = root["data"] ?: root["result"] ?: root["nodes"]
                when (container) {
                    is JsonArray -> container.mapNotNull { (it as? JsonObject)?.let { obj -> null to obj } }
                    is JsonObject -> container.entries.mapNotNull { (k, v) ->
                        (v as? JsonObject)?.let { obj -> k to obj }
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
        if (entries.isEmpty()) return emptyList()

        return entries.mapNotNull { (keyHint, obj) ->
            val remoteId = (obj.getFirstString("uuid", "id", "client_id") ?: keyHint)
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = obj.getFirstString("name", "hostname")?.takeIf { it.isNotBlank() } ?: remoteId
            val rawRegion = obj.getFirstString("region", "country_code", "country").orEmpty()
            val countryCode = extractCountryCode(rawRegion)
            val flag = if (rawRegion.any { it.code > 127 }) rawRegion else countryCodeToFlagEmoji(countryCode)

            val osText = obj.getFirstString("os", "platform").orEmpty()
            val cpuModel = obj.getFirstString("cpu_name", "cpu_model").orEmpty()
            val cpuCores = obj["cpu_cores"]?.intOrNull() ?: parseCpuCoresHint(cpuModel)
            val memTotal = obj.getFirstLong("mem_total", "ram_total") ?: 0L
            val diskTotal = obj.getFirstLong("disk_total") ?: 0L
            val trafficLimit = obj.getFirstLong("traffic_limit", "traffic_quota")?.takeIf { it > 0L }

            // Standard Komari `/api/nodes` returns static `Client` rows where `updated_at` is DB edit time,
            // NOT live heartbeat time. Only infer online state here when embedded live metrics are present.
            val hasEmbeddedLive = hasKomariEmbeddedLiveTelemetry(obj)
            val embeddedTelemetry = if (hasEmbeddedLive) {
                extractKomariTelemetryObject(obj, forcedOnline = null)
            } else {
                KomariLiveTelemetry()
            }
            val isOnline = if (hasEmbeddedLive) {
                embeddedTelemetry.explicitOnline ?: resolveOnlineState(
                    explicitOnline = null,
                    lastActiveMillis = embeddedTelemetry.lastActiveEpochMillis,
                    uptimeSeconds = embeddedTelemetry.uptimeSeconds,
                    cpuPercent = embeddedTelemetry.cpuPercent,
                    memUsedBytes = embeddedTelemetry.memUsedBytes,
                    nowEpochMillis = now,
                )
            } else {
                false
            }

            ProbeServerSnapshot(
                panelId = panelId,
                remoteServerId = remoteId,
                name = name,
                countryCode = countryCode,
                flagEmoji = flag,
                os = osText,
                cpuModel = cpuModel,
                cpuCores = cpuCores.coerceAtLeast(0),
                cpuPercent = embeddedTelemetry.cpuPercent.coerceIn(0.0, 100.0),
                memUsedBytes = embeddedTelemetry.memUsedBytes.coerceAtLeast(0L),
                memTotalBytes = (embeddedTelemetry.memTotalBytes.takeIf { it > 0L } ?: memTotal).coerceAtLeast(0L),
                diskUsedBytes = embeddedTelemetry.diskUsedBytes.coerceAtLeast(0L),
                diskTotalBytes = (embeddedTelemetry.diskTotalBytes.takeIf { it > 0L } ?: diskTotal).coerceAtLeast(0L),
                netInSpeedBps = embeddedTelemetry.netInSpeedBps.coerceAtLeast(0L),
                netOutSpeedBps = embeddedTelemetry.netOutSpeedBps.coerceAtLeast(0L),
                monthlyTransferInBytes = embeddedTelemetry.monthlyTransferInBytes.coerceAtLeast(0L),
                monthlyTransferOutBytes = embeddedTelemetry.monthlyTransferOutBytes.coerceAtLeast(0L),
                trafficLimitBytes = trafficLimit,
                uptimeSeconds = embeddedTelemetry.uptimeSeconds.coerceAtLeast(0L),
                load1 = embeddedTelemetry.load1,
                isOnline = isOnline,
                lastActiveEpochMillis = if (hasEmbeddedLive) embeddedTelemetry.lastActiveEpochMillis else 0L,
            )
        }
    }

    private fun hasKomariEmbeddedLiveTelemetry(obj: JsonObject): Boolean =
        obj.containsKey("online") ||
            obj.containsKey("is_online") ||
            obj.containsKey("cpu") ||
            obj.containsKey("cpu_usage") ||
            obj.containsKey("ram") ||
            obj.containsKey("mem") ||
            obj.containsKey("ram_used") ||
            obj.containsKey("mem_used") ||
            obj.containsKey("disk") ||
            obj.containsKey("disk_used") ||
            obj.containsKey("network") ||
            obj.containsKey("net_in") ||
            obj.containsKey("net_in_speed") ||
            obj.containsKey("uptime") ||
            obj.containsKey("status") ||
            obj.containsKey("state") ||
            obj.containsKey("report")

    private fun parseKomariTelemetryBatch(
        root: JsonElement,
        inheritedOnlineSet: Set<String>?,
    ): KomariTelemetryBatch {
        val result = LinkedHashMap<String, KomariLiveTelemetry>()
        var discoveredOnlineSet: Set<String>? = inheritedOnlineSet

        fun processDataContainer(data: JsonElement, onlineSet: Set<String>?) {
            if (onlineSet != null) {
                discoveredOnlineSet = onlineSet
            }
            when (data) {
                is JsonArray -> {
                    for (item in data) {
                        val obj = item as? JsonObject ?: continue
                        val clientObj = obj["client"] as? JsonObject
                        val uuid = obj.getFirstString("uuid", "client", "id")
                            ?: clientObj?.getFirstString("uuid", "id")
                            ?: continue
                        val forcedOnline = onlineSet?.let { uuid in it }
                        val candidate = extractKomariTelemetryObject(obj, forcedOnline)
                        val existing = result[uuid]
                        if (existing == null ||
                            candidate.lastActiveEpochMillis >= existing.lastActiveEpochMillis
                        ) {
                            result[uuid] = candidate
                        }
                    }
                }
                is JsonObject -> {
                    // Check Komari WS/client or RPC envelope `{ "online": [...], "data"/"result": { "<uuid>": {...} } }`
                    val nestedOnline = (data["online"] as? JsonArray)
                        ?.mapNotNull { it.stringOrNull() }
                        ?.toSet()
                    val nestedData = data["data"] ?: data["result"] ?: data["records"]
                    if (nestedData != null &&
                        (nestedOnline != null || nestedData is JsonObject || nestedData is JsonArray)
                    ) {
                        processDataContainer(nestedData, nestedOnline ?: onlineSet)
                        return
                    }
                    if (nestedOnline != null) {
                        discoveredOnlineSet = nestedOnline
                    }

                    // Single record returned directly (e.g. `getNodesLatestStatus` filtered by single uuid)
                    val directUuid = (data["client"] as? JsonPrimitive)?.stringOrNull()
                        ?: (data["client"] as? JsonObject)?.getFirstString("uuid", "id")
                        ?: data.getFirstString("uuid")
                    if (directUuid != null && hasKomariEmbeddedLiveTelemetry(data)) {
                        val forcedOnline = (nestedOnline ?: onlineSet)?.let { directUuid in it }
                        result[directUuid] = extractKomariTelemetryObject(data, forcedOnline)
                        return
                    }

                    for ((uuid, value) in data) {
                        if (uuid == "online" || uuid == "status" || uuid == "message" || uuid == "jsonrpc" || uuid == "id") {
                            continue
                        }
                        val obj = value as? JsonObject ?: continue
                        val forcedOnline = (nestedOnline ?: onlineSet)?.let { uuid in it }
                        result[uuid] = extractKomariTelemetryObject(obj, forcedOnline)
                    }
                }
                else -> Unit
            }
        }

        when (root) {
            is JsonArray -> processDataContainer(root, inheritedOnlineSet)
            is JsonObject -> {
                val topOnline = (root["online"] as? JsonArray)
                    ?.mapNotNull { it.stringOrNull() }
                    ?.toSet()
                    ?: inheritedOnlineSet
                val dataElem = root["result"] ?: root["data"] ?: root["records"] ?: root
                processDataContainer(dataElem, topOnline)
            }
            else -> Unit
        }
        return KomariTelemetryBatch(
            recordsByUuid = result,
            authoritativeOnlineIds = discoveredOnlineSet,
        )
    }

    private fun extractKomariTelemetryObject(
        rawObj: JsonObject,
        forcedOnline: Boolean?,
    ): KomariLiveTelemetry {
        val obj = (rawObj["report"] as? JsonObject)
            ?: (rawObj["status"] as? JsonObject)
            ?: (rawObj["state"] as? JsonObject)
            ?: rawObj

        val cpuElem = obj.getIgnoreCase("cpu")
        val cpuPercent = when (cpuElem) {
            is JsonObject -> cpuElem.getFirstDoubleIgnoreCase("usage", "percent", "value") ?: 0.0
            else -> cpuElem?.doubleOrNull()
                ?: obj.getFirstDoubleIgnoreCase("cpu_usage", "cpu_percent")
                ?: 0.0
        }

        val ramElem = obj.getIgnoreCase("ram") ?: obj.getIgnoreCase("mem")
        val ramObj = ramElem as? JsonObject
        val memUsed = ramObj?.getFirstLongIgnoreCase("used", "value")
            ?: obj.getFirstLongIgnoreCase("ram_used", "mem_used")
            ?: (ramElem as? JsonPrimitive)?.longOrNull()
            ?: 0L
        val memTotal = ramObj?.getFirstLongIgnoreCase("total")
            ?: obj.getFirstLongIgnoreCase("ram_total", "mem_total")
            ?: 0L

        val diskElem = obj.getIgnoreCase("disk")
        val diskObj = diskElem as? JsonObject
        val diskUsed = diskObj?.getFirstLongIgnoreCase("used", "value")
            ?: obj.getFirstLongIgnoreCase("disk_used")
            ?: (diskElem as? JsonPrimitive)?.longOrNull()
            ?: 0L
        val diskTotal = diskObj?.getFirstLongIgnoreCase("total")
            ?: obj.getFirstLongIgnoreCase("disk_total")
            ?: 0L

        val netObj = obj.getIgnoreCase("network") as? JsonObject
        val netInSpeed = netObj?.getFirstLongIgnoreCase("down", "in", "rx_speed", "rx")
            ?: obj.getFirstLongIgnoreCase("net_in", "net_in_speed", "down_speed")
            ?: 0L
        val netOutSpeed = netObj?.getFirstLongIgnoreCase("up", "out", "tx_speed", "tx")
            ?: obj.getFirstLongIgnoreCase("net_out", "net_out_speed", "up_speed")
            ?: 0L
        val totalDown = netObj?.getFirstPositiveLongIgnoreCase("totalDown", "total_down", "net_total_down")
            ?: obj.getFirstPositiveLongIgnoreCase("traffic_down", "net_total_down", "total_down", "net_in_transfer")
            ?: netObj?.getFirstLongIgnoreCase("totalDown", "total_down", "net_total_down")
            ?: obj.getFirstLongIgnoreCase("traffic_down", "net_total_down", "total_down", "net_in_transfer")
            ?: 0L
        val totalUp = netObj?.getFirstPositiveLongIgnoreCase("totalUp", "total_up", "net_total_up")
            ?: obj.getFirstPositiveLongIgnoreCase("traffic_up", "net_total_up", "total_up", "net_out_transfer")
            ?: netObj?.getFirstLongIgnoreCase("totalUp", "total_up", "net_total_up")
            ?: obj.getFirstLongIgnoreCase("traffic_up", "net_total_up", "total_up", "net_out_transfer")
            ?: 0L

        val loadElem = obj.getIgnoreCase("load")
        val loadObj = loadElem as? JsonObject
        val load1 = loadObj?.getFirstDoubleIgnoreCase("load1", "load_1", "1")
            ?: (loadElem as? JsonPrimitive)?.doubleOrNull()
            ?: obj.getFirstDoubleIgnoreCase("load1", "load_1")

        val uptime = obj.getFirstLongIgnoreCase("uptime")
            ?: rawObj.getFirstLongIgnoreCase("uptime")
            ?: 0L
        val lastActive = parseEpochMillis(
            obj.getIgnoreCase("time")
                ?: obj.getIgnoreCase("updated_at")
                ?: obj.getIgnoreCase("last_active")
                ?: rawObj.getIgnoreCase("time")
                ?: rawObj.getIgnoreCase("updated_at")
                ?: rawObj.getIgnoreCase("last_active"),
        ) ?: 0L
        val explicitOnline = forcedOnline
            ?: obj.getIgnoreCase("online")?.booleanOrNull()
            ?: obj.getIgnoreCase("is_online")?.booleanOrNull()
            ?: rawObj.getIgnoreCase("online")?.booleanOrNull()
            ?: rawObj.getIgnoreCase("is_online")?.booleanOrNull()

        return KomariLiveTelemetry(
            cpuPercent = cpuPercent,
            memUsedBytes = memUsed,
            memTotalBytes = memTotal,
            diskUsedBytes = diskUsed,
            diskTotalBytes = diskTotal,
            netInSpeedBps = netInSpeed,
            netOutSpeedBps = netOutSpeed,
            monthlyTransferInBytes = totalDown,
            monthlyTransferOutBytes = totalUp,
            uptimeSeconds = uptime,
            load1 = load1,
            explicitOnline = explicitOnline,
            lastActiveEpochMillis = lastActive,
        )
    }

    // ── HTTP & Helper Utilities ───────────────────────────────────────────────

    private fun buildNezhaV0Headers(apiToken: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            put("Authorization", token)
        }
    }

    private fun buildNezhaV1Headers(apiToken: String?, asCookie: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            when {
                asCookie -> put("Cookie", "nz-jwt=$token")
                token.contains('=') -> put("Cookie", token)
                token.startsWith("Bearer ", ignoreCase = true) ||
                    token.startsWith("Basic ", ignoreCase = true) -> put("Authorization", token)
                else -> put("Authorization", "Bearer $token")
            }
        }
    }

    private fun buildKomariHeaders(apiToken: String?, asCookie: Boolean = false): Map<String, String> = buildMap {
        put("Accept", "application/json")
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            when {
                asCookie -> put("Cookie", "session_token=$token; temp_key=$token")
                token.contains('=') -> put("Cookie", token)
                else -> {
                    val hasBearerPrefix = token.startsWith("Bearer ", ignoreCase = true)
                    val bearer = if (hasBearerPrefix) token else "Bearer $token"
                    val rawKey = if (hasBearerPrefix) token.substring("Bearer ".length).trim() else token
                    put("Authorization", bearer)
                    if (rawKey.isNotEmpty()) {
                        put("X-API-Key", rawKey)
                    }
                }
            }
        }
    }

    private fun buildEndpointUrl(originUrl: HttpUrl, vararg segments: String): HttpUrl {
        val builder = originUrl.newBuilder()
        val existing = originUrl.pathSegments
        if (existing.isNotEmpty() && existing.last().isEmpty()) {
            builder.removePathSegment(existing.size - 1)
        }
        for (segment in segments) {
            if (segment.isNotEmpty()) {
                builder.addPathSegment(segment)
            }
        }
        return builder.build()
    }

    private suspend fun executeGet(
        initialUrl: HttpUrl,
        headers: Map<String, String>,
        expectedOrigin: HttpUrl,
    ): HttpOutcome {
        val carriesCredentials = hasCredentialHeaders(headers)
        val sanitizedHeaders = if (carriesCredentials) {
            headers
        } else {
            headers.filterKeys { !isCredentialHeaderName(it) }
        }

        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECT_HOPS) {
            if (hasUserInfo(currentUrl)) {
                throw ProbeCredentialBoundaryException("安全校验失败：禁止在探针请求 URL 中内嵌账号或密码信息")
            }
            if (carriesCredentials) {
                if (!isAllowedCredentialOrigin(currentUrl, allowLoopbackHttpWithCredentials)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：配置了 API Token 或 Cookie 的探针面板必须使用 HTTPS 加密连接，拒绝通过明文 HTTP 发送凭证",
                    )
                }
                if (!isSameOrigin(expectedOrigin, currentUrl)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：拒绝向非同源地址 (${formatOrigin(currentUrl)}) 发送探针凭证",
                    )
                }
            }

            val builder = Request.Builder().url(currentUrl).get()
            sanitizedHeaders.forEach { (k, v) -> builder.header(k, v) }
            val raw = executeSingleCall(builder.build())

            if (raw.code !in REDIRECT_STATUS_CODES) {
                if (carriesCredentials && raw.code in 300..399) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：探针面板返回不受支持的重定向状态 (HTTP ${raw.code})",
                    )
                }
                return HttpOutcome(code = raw.code, body = raw.body)
            }

            if (hop >= MAX_REDIRECT_HOPS) {
                throw ProbeCredentialBoundaryException(
                    "探针面板重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）",
                )
            }

            val rawLocation = raw.location?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ProbeCredentialBoundaryException(
                    "安全校验失败：探针面板重定向响应缺少 Location 头 (HTTP ${raw.code})",
                )
            val nextUrl = currentUrl.resolve(rawLocation)
                ?: throw ProbeCredentialBoundaryException(
                    "安全校验失败：探针面板重定向目标地址无效或协议不受支持 (HTTP ${raw.code})",
                )
            if (hasUserInfo(nextUrl)) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：拒绝重定向至包含内嵌用户信息的探针地址 (HTTP ${raw.code})",
                )
            }
            if (currentUrl.scheme.equals("https", ignoreCase = true) &&
                !nextUrl.scheme.equals("https", ignoreCase = true)
            ) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：拒绝将探针请求从 HTTPS 降级重定向至明文 HTTP (HTTP ${raw.code})",
                )
            }
            if (carriesCredentials) {
                if (!isAllowedCredentialOrigin(nextUrl, allowLoopbackHttpWithCredentials)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：拒绝将探针凭证重定向至非 HTTPS 地址 (HTTP ${raw.code})",
                    )
                }
                if (!isSameOrigin(expectedOrigin, nextUrl)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：拒绝跨源重定向探针请求 (${formatOrigin(expectedOrigin)} -> ${formatOrigin(nextUrl)})，已阻止泄露凭证",
                    )
                }
            }
            currentUrl = nextUrl
        }
        throw ProbeCredentialBoundaryException("探针面板重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）")
    }

    private suspend fun executePostJson(
        initialUrl: HttpUrl,
        jsonBody: String,
        headers: Map<String, String>,
        expectedOrigin: HttpUrl,
    ): HttpOutcome {
        val carriesCredentials = hasCredentialHeaders(headers)
        val sanitizedHeaders = if (carriesCredentials) {
            headers
        } else {
            headers.filterKeys { !isCredentialHeaderName(it) }
        }

        var currentUrl = initialUrl
        var usePost = true
        for (hop in 0..MAX_REDIRECT_HOPS) {
            if (hasUserInfo(currentUrl)) {
                throw ProbeCredentialBoundaryException("安全校验失败：禁止在探针请求 URL 中内嵌账号或密码信息")
            }
            if (carriesCredentials) {
                if (!isAllowedCredentialOrigin(currentUrl, allowLoopbackHttpWithCredentials)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：配置了 API Token 或 Cookie 的探针面板必须使用 HTTPS 加密连接，拒绝通过明文 HTTP 发送凭证",
                    )
                }
                if (!isSameOrigin(expectedOrigin, currentUrl)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：拒绝向非同源地址 (${formatOrigin(currentUrl)}) 发送探针凭证",
                    )
                }
            }

            val builder = Request.Builder().url(currentUrl)
            if (usePost) {
                builder.post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            } else {
                builder.get()
            }
            sanitizedHeaders.forEach { (k, v) -> builder.header(k, v) }
            val raw = executeSingleCall(builder.build())

            if (raw.code !in REDIRECT_STATUS_CODES) {
                if (carriesCredentials && raw.code in 300..399) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：探针面板返回不受支持的重定向状态 (HTTP ${raw.code})",
                    )
                }
                return HttpOutcome(code = raw.code, body = raw.body)
            }

            if (hop >= MAX_REDIRECT_HOPS) {
                throw ProbeCredentialBoundaryException(
                    "探针面板重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）",
                )
            }

            val rawLocation = raw.location?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ProbeCredentialBoundaryException(
                    "安全校验失败：探针面板重定向响应缺少 Location 头 (HTTP ${raw.code})",
                )
            val nextUrl = currentUrl.resolve(rawLocation)
                ?: throw ProbeCredentialBoundaryException(
                    "安全校验失败：探针面板重定向目标地址无效或协议不受支持 (HTTP ${raw.code})",
                )
            if (hasUserInfo(nextUrl)) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：拒绝重定向至包含内嵌用户信息的探针地址 (HTTP ${raw.code})",
                )
            }
            if (currentUrl.scheme.equals("https", ignoreCase = true) &&
                !nextUrl.scheme.equals("https", ignoreCase = true)
            ) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：拒绝将探针请求从 HTTPS 降级重定向至明文 HTTP (HTTP ${raw.code})",
                )
            }
            if (carriesCredentials) {
                if (!isAllowedCredentialOrigin(nextUrl, allowLoopbackHttpWithCredentials)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：拒绝将探针凭证重定向至非 HTTPS 地址 (HTTP ${raw.code})",
                    )
                }
                if (!isSameOrigin(expectedOrigin, nextUrl)) {
                    throw ProbeCredentialBoundaryException(
                        "安全校验失败：拒绝跨源重定向探针请求 (${formatOrigin(expectedOrigin)} -> ${formatOrigin(nextUrl)})，已阻止泄露凭证",
                    )
                }
            }
            if (raw.code == 301 || raw.code == 302 || raw.code == 303) {
                usePost = false
            }
            currentUrl = nextUrl
        }
        throw ProbeCredentialBoundaryException("探针面板重定向次数过多（超过 $MAX_REDIRECT_HOPS 次）")
    }

    private suspend fun fetchKomariSingleWebSocketFrame(
        url: HttpUrl,
        headers: Map<String, String>,
        expectedOrigin: HttpUrl,
        outboundPrompt: String,
    ): String? {
        if (hasUserInfo(url)) {
            throw ProbeCredentialBoundaryException("安全校验失败：禁止在探针请求 URL 中内嵌账号或密码信息")
        }
        val carriesCredentials = hasCredentialHeaders(headers)
        if (carriesCredentials) {
            if (!isAllowedCredentialOrigin(url, allowLoopbackHttpWithCredentials)) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：配置了 API Token 或 Cookie 的探针面板必须使用 HTTPS 加密连接，拒绝通过明文 HTTP 发送凭证",
                )
            }
            if (!isSameOrigin(expectedOrigin, url)) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：拒绝向非同源地址 (${formatOrigin(url)}) 发送探针凭证",
                )
            }
        }
        val sanitizedHeaders = if (carriesCredentials) {
            headers
        } else {
            headers.filterKeys { !isCredentialHeaderName(it) }
        }

        return withTimeoutOrNull(KOMARI_WS_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val builder = Request.Builder().url(url).get()
                sanitizedHeaders.forEach { (k, v) -> builder.header(k, v) }

                val socket = transportClient.newWebSocket(
                    builder.build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            runCatching { webSocket.send(outboundPrompt) }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (resumed.compareAndSet(false, true)) {
                                runCatching { webSocket.close(1000, null) }
                                if (continuation.isActive) {
                                    continuation.resume(text)
                                }
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            runCatching { webSocket.close(1000, null) }
                            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(null)
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            runCatching { webSocket.cancel() }
                            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    },
                )
                continuation.invokeOnCancellation {
                    runCatching { socket.cancel() }
                }
            }
        }
    }

    private suspend fun executeSingleCall(request: Request): RawWireResponse =
        suspendCancellableCoroutine { continuation ->
            val call = transportClient.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val location = resp.header("Location")
                        val bodyStr = if (resp.code in REDIRECT_STATUS_CODES) {
                            ""
                        } else {
                            runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        }
                        if (continuation.isActive) {
                            continuation.resume(
                                RawWireResponse(
                                    code = resp.code,
                                    location = location,
                                    body = bodyStr,
                                ),
                            )
                        }
                    }
                }
            })
        }

    private fun parseJsonOrNull(raw: String): JsonElement? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }

    private fun resolveOnlineState(
        explicitOnline: Boolean?,
        lastActiveMillis: Long,
        uptimeSeconds: Long,
        cpuPercent: Double,
        memUsedBytes: Long,
        nowEpochMillis: Long,
    ): Boolean {
        if (explicitOnline != null) return explicitOnline
        if (lastActiveMillis > 0L) {
            val ageMillis = nowEpochMillis - lastActiveMillis
            return ageMillis <= offlineThresholdMillis && ageMillis >= -CLOCK_SKEW_TOLERANCE_MILLIS
        }
        return uptimeSeconds > 0L && (cpuPercent > 0.0 || memUsedBytes > 0L)
    }

    private fun parseTrafficLimitFromPublicNote(publicNote: String?): Long? {
        val raw = publicNote?.trim().orEmpty()
        if (!raw.startsWith("{")) return null
        val obj = parseJsonOrNull(raw) as? JsonObject ?: return null
        val planData = obj["planDataMod"] as? JsonObject ?: obj
        val rawTraffic = planData.getFirstString("trafficVol", "traffic_limit") ?: return null
        val match = Regex("""(\d+(?:\.\d+)?)\s*(TB|GB|MB)""", RegexOption.IGNORE_CASE).find(rawTraffic)
            ?: return null
        val num = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].uppercase(Locale.US)) {
            "TB" -> (num * 1024.0 * 1024.0 * 1024.0 * 1024.0).toLong()
            "GB" -> (num * 1024.0 * 1024.0 * 1024.0).toLong()
            "MB" -> (num * 1024.0 * 1024.0).toLong()
            else -> null
        }
    }

    private fun parseCpuCoresHint(cpuModel: String): Int {
        if (cpuModel.isBlank()) return 0
        val match = Regex("""(\d+)\s*(?:Virtual\s*)?(?:v?Core|v?CPU|Core)s?""", RegexOption.IGNORE_CASE)
            .find(cpuModel)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun parseEpochMillis(element: JsonElement?): Long? {
        if (element == null || element is JsonNull) return null
        val prim = element as? JsonPrimitive ?: return null
        prim.longOrNull?.let { numeric ->
            if (numeric <= 0L) return null
            return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
        }
        val text = prim.contentOrNull?.trim().orEmpty()
        if (text.isEmpty() || text.startsWith("0001-01-01")) return null
        text.toLongOrNull()?.let { numeric ->
            if (numeric <= 0L) return null
            return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
        }
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching {
            val ldt = LocalDateTime.parse(text, SPACE_DATE_TIME_FORMATTER)
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        return null
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.intOrNull(): Int? =
        (this as? JsonPrimitive)?.intOrNull ?: stringOrNull()?.toIntOrNull()

    private fun JsonElement.longOrNull(): Long? =
        (this as? JsonPrimitive)?.longOrNull
            ?: (this as? JsonPrimitive)?.doubleOrNull?.toLong()
            ?: stringOrNull()?.toLongOrNull()

    private fun JsonElement.doubleOrNull(): Double? =
        (this as? JsonPrimitive)?.doubleOrNull ?: stringOrNull()?.toDoubleOrNull()

    private fun JsonElement.booleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.getFirstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.stringOrNull()?.takeIf { it.isNotBlank() } }

    private fun JsonObject.getFirstLong(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key -> this[key]?.longOrNull() }

    private fun JsonObject.getFirstDouble(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key -> this[key]?.doubleOrNull() }

    private fun JsonObject.getIgnoreCase(key: String): JsonElement? =
        this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun JsonObject.getFirstLongIgnoreCase(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key -> getIgnoreCase(key)?.longOrNull() }

    private fun JsonObject.getFirstPositiveLongIgnoreCase(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key -> getIgnoreCase(key)?.longOrNull()?.takeIf { it > 0L } }

    private fun JsonObject.getFirstDoubleIgnoreCase(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key -> getIgnoreCase(key)?.doubleOrNull() }

    companion object {
        const val DEFAULT_OFFLINE_THRESHOLD_MILLIS = 120_000L
        internal const val MAX_REDIRECT_HOPS = 5
        private const val CLOCK_SKEW_TOLERANCE_MILLIS = 300_000L
        private const val KOMARI_WS_TIMEOUT_MILLIS = 5_000L
        private const val KOMARI_RECENT_CONCURRENCY = 6
        private const val KOMARI_RPC_GET_NODES_BODY =
            """{"jsonrpc":"2.0","method":"common:getNodes","params":{},"id":1}"""
        private const val KOMARI_RPC_LATEST_STATUS_BODY =
            """{"jsonrpc":"2.0","method":"common:getNodesLatestStatus","params":{},"id":1}"""
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
        private val SPACE_DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

        private fun normalizeHost(host: String): String =
            host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")

        internal fun isLoopbackHost(host: String): Boolean =
            normalizeHost(host) in LOOPBACK_HOSTS

        internal fun isAllowedCredentialOrigin(url: HttpUrl, allowLoopbackHttp: Boolean): Boolean =
            url.scheme.equals("https", ignoreCase = true) ||
                (allowLoopbackHttp && url.scheme.equals("http", ignoreCase = true) && isLoopbackHost(url.host))

        internal fun isSameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                normalizeHost(a.host) == normalizeHost(b.host) &&
                a.port == b.port

        private fun hasUserInfo(url: HttpUrl): Boolean =
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()

        private fun formatOrigin(url: HttpUrl): String =
            "${url.scheme.lowercase(Locale.ROOT)}://${url.host}:${url.port}"

        private fun isCredentialHeaderName(name: String): Boolean =
            name.equals("Authorization", ignoreCase = true) ||
                name.equals("Proxy-Authorization", ignoreCase = true) ||
                name.equals("X-API-Key", ignoreCase = true) ||
                name.equals("Cookie", ignoreCase = true)

        private fun hasCredentialHeaders(headers: Map<String, String>): Boolean =
            headers.any { (k, v) -> isCredentialHeaderName(k) && v.isNotBlank() }

        private fun sanitizeAndValidateApiToken(rawToken: String?): String? {
            val trimmed = rawToken?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it == '\r' || it == '\n' || (it.isISOControl() && it != '\t') }) {
                throw ProbeCredentialBoundaryException("安全校验失败：探针凭证格式无效，不能包含换行或控制字符")
            }
            return trimmed
        }

        /**
         * Normalizes user-entered panel base URLs (adds `https://` when scheme omitted, trims
         * trailing slashes and accidental `/api/...` suffixes).
         */
        fun normalizeBaseUrl(rawUrl: String): String {
            var cleaned = rawUrl.trim()
            if (cleaned.isEmpty()) return ""
            if (!cleaned.startsWith("http://", ignoreCase = true) &&
                !cleaned.startsWith("https://", ignoreCase = true) &&
                !cleaned.contains("://")
            ) {
                cleaned = "https://$cleaned"
            }
            cleaned = cleaned.trimEnd('/')
            val knownSuffixes = listOf(
                "/api/v1/server/details",
                "/api/v1/server/list",
                "/api/v1/server",
                "/api/rpc2",
                "/api/nodes",
                "/api/recent",
                "/api/clients",
                "/api/client",
            )
            for (suffix in knownSuffixes) {
                if (cleaned.endsWith(suffix, ignoreCase = true)) {
                    cleaned = cleaned.dropLast(suffix.length).trimEnd('/')
                    break
                }
            }
            return cleaned
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .build()
    }
}
