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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
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
 * Multi-protocol HTTP probe connector supporting:
 * - **Nezha v0** (`GET /api/v1/server/details` & `/api/v1/server/list` with `Authorization: <token>`)
 * - **Nezha v1** (`GET /api/v1/server` with Bearer token, `nz-jwt` cookie, or public access)
 * - **Komari** (`GET /api/nodes` + `GET /api/recent` or `/api/client` with public / API key / cookie)
 * - **AUTO discovery** probing endpoints gracefully to identify the panel flavor
 */
class ProbePanelClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val offlineThresholdMillis: Long = DEFAULT_OFFLINE_THRESHOLD_MILLIS,
) {
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
            val baseUrl = normalizeBaseUrl(config.baseUrl)
            require(baseUrl.isNotBlank()) { "探针地址不能为空" }

            when (config.effectivePanelType) {
                ProbePanelType.NEZHA_V0 -> ProbePanelFetchResult(
                    detectedPanelType = ProbePanelType.NEZHA_V0,
                    snapshots = fetchNezhaV0(config.id, baseUrl, config.apiToken),
                )
                ProbePanelType.NEZHA_V1 -> ProbePanelFetchResult(
                    detectedPanelType = ProbePanelType.NEZHA_V1,
                    snapshots = fetchNezhaV1(config.id, baseUrl, config.apiToken),
                )
                ProbePanelType.KOMARI -> ProbePanelFetchResult(
                    detectedPanelType = ProbePanelType.KOMARI,
                    snapshots = fetchKomari(config.id, baseUrl, config.apiToken),
                )
                ProbePanelType.AUTO -> autoDiscoverAndFetch(config.id, baseUrl, config.apiToken)
            }
        }

    // ── AUTO Discovery ────────────────────────────────────────────────────────

    private suspend fun autoDiscoverAndFetch(
        panelId: String,
        baseUrl: String,
        apiToken: String?,
    ): ProbePanelFetchResult {
        var authFailureHint: String? = null
        var networkFailureHint: String? = null

        // 1. Try Komari (`GET /api/nodes`)
        val komariAttempt = tryProbeKomari(panelId, baseUrl, apiToken)
        if (komariAttempt.result != null) return komariAttempt.result
        if (komariAttempt.isAuthError) authFailureHint = komariAttempt.errorMessage
        if (komariAttempt.isNetworkError) networkFailureHint = komariAttempt.errorMessage

        // 2. Try Nezha v1 (`GET /api/v1/server`)
        val nezhaV1Attempt = tryProbeNezhaV1(panelId, baseUrl, apiToken)
        if (nezhaV1Attempt.result != null) return nezhaV1Attempt.result
        if (nezhaV1Attempt.isAuthError && authFailureHint == null) authFailureHint = nezhaV1Attempt.errorMessage
        if (nezhaV1Attempt.isNetworkError && networkFailureHint == null) networkFailureHint = nezhaV1Attempt.errorMessage

        // 3. Try Nezha v0 (`GET /api/v1/server/details` then `/api/v1/server/list`)
        val nezhaV0Attempt = tryProbeNezhaV0(panelId, baseUrl, apiToken)
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
        baseUrl: String,
        apiToken: String?,
    ): DiscoveryAttempt {
        val outcome = try {
            executeGet("$baseUrl/api/nodes", buildKomariHeaders(apiToken))
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            return DiscoveryAttempt(isNetworkError = true, errorMessage = "网络请求失败：${e.message}")
        }

        if (outcome.code == 401 || outcome.code == 403) {
            return DiscoveryAttempt(
                isAuthError = true,
                errorMessage = "Komari 面板需要认证 (HTTP ${outcome.code})，请填入 API Token 或 Cookie",
            )
        }
        if (!outcome.isSuccessful) return DiscoveryAttempt()

        val root = parseJsonOrNull(outcome.body) ?: return DiscoveryAttempt()
        if (!looksLikeKomariNodes(root)) return DiscoveryAttempt()

        val nodes = parseKomariNodesList(panelId, root)
        val merged = enrichKomariWithLiveTelemetry(panelId, baseUrl, apiToken, nodes)
        return DiscoveryAttempt(
            result = ProbePanelFetchResult(
                detectedPanelType = ProbePanelType.KOMARI,
                snapshots = merged,
            ),
        )
    }

    private suspend fun tryProbeNezhaV1(
        panelId: String,
        baseUrl: String,
        apiToken: String?,
    ): DiscoveryAttempt {
        val outcome = try {
            executeNezhaV1Get("$baseUrl/api/v1/server", apiToken)
        } catch (c: CancellationException) {
            throw c
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
        baseUrl: String,
        apiToken: String?,
    ): DiscoveryAttempt {
        val headers = buildNezhaV0Headers(apiToken)
        val detailsOutcome = try {
            executeGet("$baseUrl/api/v1/server/details", headers)
        } catch (c: CancellationException) {
            throw c
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
            executeGet("$baseUrl/api/v1/server/list", headers)
        } catch (c: CancellationException) {
            throw c
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
        baseUrl: String,
        apiToken: String?,
    ): List<ProbeServerSnapshot> {
        val headers = buildNezhaV0Headers(apiToken)
        val detailsResp = executeGet("$baseUrl/api/v1/server/details", headers)
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

        val listResp = executeGet("$baseUrl/api/v1/server/list", headers)
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
        baseUrl: String,
        apiToken: String?,
    ): List<ProbeServerSnapshot> {
        val resp = executeNezhaV1Get("$baseUrl/api/v1/server", apiToken)
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
        url: String,
        apiToken: String?,
    ): HttpOutcome {
        val trimmedToken = apiToken?.trim().orEmpty()
        val primaryHeaders = buildNezhaV1Headers(trimmedToken, asCookie = false)
        val first = executeGet(url, primaryHeaders)
        if ((first.code == 401 || first.code == 403) &&
            trimmedToken.isNotEmpty() &&
            !trimmedToken.startsWith("Bearer ", ignoreCase = true) &&
            !trimmedToken.contains('=')
        ) {
            val cookieHeaders = buildNezhaV1Headers(trimmedToken, asCookie = true)
            val second = executeGet(url, cookieHeaders)
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
        baseUrl: String,
        apiToken: String?,
    ): List<ProbeServerSnapshot> {
        val headers = buildKomariHeaders(apiToken)
        val nodesResp = executeGet("$baseUrl/api/nodes", headers)
        if (nodesResp.code == 401 || nodesResp.code == 403) {
            throw IOException("Komari 认证失败 (HTTP ${nodesResp.code})，请检查 API Token / Cookie")
        }
        if (!nodesResp.isSuccessful) {
            throw IOException("Komari /api/nodes 请求失败 (HTTP ${nodesResp.code})")
        }
        val nodesRoot = parseJsonOrNull(nodesResp.body)
            ?: throw IOException("Komari /api/nodes 返回数据非有效 JSON")
        val baseNodes = parseKomariNodesList(panelId, nodesRoot)
        return enrichKomariWithLiveTelemetry(panelId, baseUrl, apiToken, baseNodes)
    }

    private fun looksLikeKomariNodes(root: JsonElement): Boolean {
        val dataArray = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val status = root["status"]?.stringOrNull()
                val arr = root["data"] as? JsonArray
                if (status.equals("success", ignoreCase = true) && arr != null && arr.isEmpty()) {
                    return true
                }
                arr
            }
            else -> null
        } ?: return false

        val first = dataArray.firstOrNull() as? JsonObject ?: return false
        return first.containsKey("uuid") ||
            first.containsKey("cpu_cores") ||
            first.containsKey("cpu_name") ||
            (first.containsKey("region") && !first.containsKey("state"))
    }

    private suspend fun enrichKomariWithLiveTelemetry(
        panelId: String,
        baseUrl: String,
        apiToken: String?,
        baseNodes: List<ProbeServerSnapshot>,
    ): List<ProbeServerSnapshot> {
        if (baseNodes.isEmpty()) return emptyList()
        val headers = buildKomariHeaders(apiToken)

        // Try `/api/recent` first, then `/api/client`
        val liveMap = fetchKomariTelemetryMap("$baseUrl/api/recent", headers)
            ?: fetchKomariTelemetryMap("$baseUrl/api/client", headers)
            ?: return baseNodes

        val now = nowMillisProvider()
        return baseNodes.map { node ->
            val live = liveMap[node.remoteServerId] ?: return@map node
            val mergedMemTotal = live.memTotalBytes.takeIf { it > 0L } ?: node.memTotalBytes
            val mergedDiskTotal = live.diskTotalBytes.takeIf { it > 0L } ?: node.diskTotalBytes
            val mergedLastActive = live.lastActiveEpochMillis.takeIf { it > 0L } ?: node.lastActiveEpochMillis
            val isOnline = live.explicitOnline ?: resolveOnlineState(
                explicitOnline = null,
                lastActiveMillis = mergedLastActive,
                uptimeSeconds = live.uptimeSeconds,
                cpuPercent = live.cpuPercent,
                memUsedBytes = live.memUsedBytes,
                nowEpochMillis = now,
            )
            node.copy(
                cpuPercent = live.cpuPercent.coerceIn(0.0, 100.0),
                memUsedBytes = live.memUsedBytes.coerceAtLeast(0L),
                memTotalBytes = mergedMemTotal.coerceAtLeast(0L),
                diskUsedBytes = live.diskUsedBytes.coerceAtLeast(0L),
                diskTotalBytes = mergedDiskTotal.coerceAtLeast(0L),
                netInSpeedBps = live.netInSpeedBps.coerceAtLeast(0L),
                netOutSpeedBps = live.netOutSpeedBps.coerceAtLeast(0L),
                monthlyTransferInBytes = live.monthlyTransferInBytes.coerceAtLeast(0L),
                monthlyTransferOutBytes = live.monthlyTransferOutBytes.coerceAtLeast(0L),
                uptimeSeconds = live.uptimeSeconds.coerceAtLeast(0L),
                load1 = live.load1 ?: node.load1,
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

    private suspend fun fetchKomariTelemetryMap(
        url: String,
        headers: Map<String, String>,
    ): Map<String, KomariLiveTelemetry>? {
        val resp = runCatching { executeGet(url, headers) }.getOrNull() ?: return null
        if (!resp.isSuccessful) return null
        val root = parseJsonOrNull(resp.body) ?: return null
        val parsed = parseKomariTelemetryMap(root)
        return parsed.takeIf { it.isNotEmpty() }
    }

    internal fun parseKomariNodesList(
        panelId: String,
        root: JsonElement,
    ): List<ProbeServerSnapshot> {
        val now = nowMillisProvider()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["nodes"] as? JsonArray)
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val remoteId = obj.getFirstString("uuid", "id", "client_id")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
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

            // Some Komari deployments embed live metrics directly inside `/api/nodes`
            val embeddedTelemetry = extractKomariTelemetryObject(obj, forcedOnline = null)
            val isOnline = embeddedTelemetry.explicitOnline ?: resolveOnlineState(
                explicitOnline = null,
                lastActiveMillis = embeddedTelemetry.lastActiveEpochMillis,
                uptimeSeconds = embeddedTelemetry.uptimeSeconds,
                cpuPercent = embeddedTelemetry.cpuPercent,
                memUsedBytes = embeddedTelemetry.memUsedBytes,
                nowEpochMillis = now,
            )

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
                lastActiveEpochMillis = embeddedTelemetry.lastActiveEpochMillis,
            )
        }
    }

    private fun parseKomariTelemetryMap(root: JsonElement): Map<String, KomariLiveTelemetry> {
        val result = LinkedHashMap<String, KomariLiveTelemetry>()

        fun processDataContainer(data: JsonElement, onlineSet: Set<String>?) {
            when (data) {
                is JsonArray -> {
                    for (item in data) {
                        val obj = item as? JsonObject ?: continue
                        val uuid = obj.getFirstString("uuid", "client", "id") ?: continue
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
                    // Check Komari WS/client envelope `{ "online": [...], "data": { "<uuid>": {...} } }`
                    val nestedOnline = (data["online"] as? JsonArray)
                        ?.mapNotNull { it.stringOrNull() }
                        ?.toSet()
                    val nestedData = data["data"]
                    if (nestedData != null && (nestedOnline != null || nestedData is JsonObject || nestedData is JsonArray)) {
                        processDataContainer(nestedData, nestedOnline ?: onlineSet)
                        return
                    }

                    for ((uuid, value) in data) {
                        val obj = value as? JsonObject ?: continue
                        val forcedOnline = onlineSet?.let { uuid in it }
                        result[uuid] = extractKomariTelemetryObject(obj, forcedOnline)
                    }
                }
                else -> Unit
            }
        }

        when (root) {
            is JsonArray -> processDataContainer(root, null)
            is JsonObject -> {
                val topOnline = (root["online"] as? JsonArray)
                    ?.mapNotNull { it.stringOrNull() }
                    ?.toSet()
                val dataElem = root["data"] ?: root["records"] ?: root
                processDataContainer(dataElem, topOnline)
            }
            else -> Unit
        }
        return result
    }

    private fun extractKomariTelemetryObject(
        obj: JsonObject,
        forcedOnline: Boolean?,
    ): KomariLiveTelemetry {
        val cpuElem = obj["cpu"]
        val cpuPercent = when (cpuElem) {
            is JsonObject -> cpuElem.getFirstDouble("usage", "percent", "value") ?: 0.0
            else -> cpuElem?.doubleOrNull() ?: 0.0
        }

        val ramObj = obj["ram"] as? JsonObject ?: obj["mem"] as? JsonObject
        val memUsed = ramObj?.getFirstLong("used") ?: obj.getFirstLong("ram_used", "mem_used", "ram") ?: 0L
        val memTotal = ramObj?.getFirstLong("total") ?: obj.getFirstLong("ram_total", "mem_total") ?: 0L

        val diskObj = obj["disk"] as? JsonObject
        val diskUsed = diskObj?.getFirstLong("used") ?: obj.getFirstLong("disk_used", "disk") ?: 0L
        val diskTotal = diskObj?.getFirstLong("total") ?: obj.getFirstLong("disk_total") ?: 0L

        val netObj = obj["network"] as? JsonObject
        val netInSpeed = netObj?.getFirstLong("down", "in", "rx_speed")
            ?: obj.getFirstLong("net_in", "net_in_speed", "down_speed") ?: 0L
        val netOutSpeed = netObj?.getFirstLong("up", "out", "tx_speed")
            ?: obj.getFirstLong("net_out", "net_out_speed", "up_speed") ?: 0L
        val totalDown = netObj?.getFirstLong("totalDown", "total_down", "net_total_down")
            ?: obj.getFirstLong("net_total_down", "total_down", "traffic_down", "net_in_transfer") ?: 0L
        val totalUp = netObj?.getFirstLong("totalUp", "total_up", "net_total_up")
            ?: obj.getFirstLong("net_total_up", "total_up", "traffic_up", "net_out_transfer") ?: 0L

        val loadObj = obj["load"] as? JsonObject
        val load1 = loadObj?.getFirstDouble("load1", "load_1", "1")
            ?: obj.getFirstDouble("load", "load1", "load_1")

        val uptime = obj.getFirstLong("uptime") ?: 0L
        val lastActive = parseEpochMillis(obj["updated_at"] ?: obj["time"] ?: obj["last_active"]) ?: 0L
        val explicitOnline = forcedOnline ?: obj["online"]?.booleanOrNull() ?: obj["is_online"]?.booleanOrNull()

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

    private fun buildKomariHeaders(apiToken: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            if (token.contains('=')) {
                put("Cookie", token)
            } else {
                val bearer = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                put("Authorization", bearer)
                put("X-API-Key", token.removePrefix("Bearer ").removePrefix("bearer ").trim())
            }
        }
    }

    private suspend fun executeGet(
        url: String,
        headers: Map<String, String>,
    ): HttpOutcome {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()

        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val bodyStr = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        if (continuation.isActive) {
                            continuation.resume(HttpOutcome(code = resp.code, body = bodyStr))
                        }
                    }
                }
            })
        }
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

    companion object {
        const val DEFAULT_OFFLINE_THRESHOLD_MILLIS = 120_000L
        private const val CLOCK_SKEW_TOLERANCE_MILLIS = 300_000L
        private val SPACE_DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

        /**
         * Normalizes user-entered panel base URLs (adds `https://` when scheme omitted, trims
         * trailing slashes and accidental `/api/...` suffixes).
         */
        fun normalizeBaseUrl(rawUrl: String): String {
            var cleaned = rawUrl.trim()
            if (cleaned.isEmpty()) return ""
            if (!cleaned.startsWith("http://", ignoreCase = true) &&
                !cleaned.startsWith("https://", ignoreCase = true)
            ) {
                cleaned = "https://$cleaned"
            }
            cleaned = cleaned.trimEnd('/')
            val knownSuffixes = listOf(
                "/api/v1/server/details",
                "/api/v1/server/list",
                "/api/v1/server",
                "/api/nodes",
                "/api/recent",
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .build()
    }
}
