package com.nodeseek.app.data.fleet

import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.countryCodeToFlagEmoji
import com.nodeseek.app.domain.fleet.extractCountryCode
import com.nodeseek.app.domain.ssh.ProbeSyncMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.intOrNull
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
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Aggregated result of pulling machine metadata from configured probe panels for SSH Vault sync.
 */
data class ProbeVaultSyncFetchResult(
    val machines: List<ProbeSyncMachine>,
    val totalPanelsAttempted: Int,
    val succeededPanelCount: Int,
    val failedPanelErrors: Map<String, String>,
    val usedCachedFallbackPanelIds: Set<String> = emptySet(),
)

/**
 * Dedicated HTTP client that pulls vault-sync metadata (`name`, `ipv4`/`ipv6`, `region`, `group`,
 * `tags`, `remoteServerId`) from configured Nezha v1, Nezha v0, and Komari probe panels.
 *
 * Unlike [ProbePanelClient] (which focuses on public/live monitoring telemetry), this client
 * additionally queries authenticated endpoints when a panel token is configured:
 * - **Nezha v1**: `GET /api/v1/server` (reads `geoip.ip.ipv4_addr` / `ipv6_addr`) + `GET /api/v1/server-group`.
 * - **Nezha v0**: `GET /api/v1/server/details` and `GET /api/v1/server/list` (reads `ipv4`, `ipv6`, `tag`).
 * - **Komari**: `GET /api/admin/client/list` when token is present (exposes `ipv4`, `ipv6`, `group`, `tags`),
 *   gracefully falling back to `GET /api/nodes` and `POST /api/rpc2` (`common:getNodes`).
 *
 * Security boundaries match [ProbePanelClient]: strips foreign interceptors/cookies, disables
 * automatic redirects, blocks credentials over plaintext HTTP (unless [allowLoopbackHttpWithCredentials]),
 * and rejects cross-origin or HTTPS-downgrade redirects.
 */
class ProbeVaultSyncClient(
    httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
     * Fetches vault-sync machine records from all enabled [panels] concurrently.
     * For any panel whose live fetch fails, falls back to [fallbackSnapshots] (if available for
     * that panel) while recording the error in [ProbeVaultSyncFetchResult.failedPanelErrors].
     */
    suspend fun fetchAllPanels(
        panels: List<ProbePanelConfig>,
        fallbackSnapshots: List<ProbeServerSnapshot> = emptyList(),
    ): ProbeVaultSyncFetchResult = withContext(ioDispatcher) {
        val activePanels = panels.filter { it.enabled }
        if (activePanels.isEmpty()) {
            return@withContext ProbeVaultSyncFetchResult(
                machines = emptyList(),
                totalPanelsAttempted = 0,
                succeededPanelCount = 0,
                failedPanelErrors = emptyMap(),
            )
        }

        val snapshotsByPanel = fallbackSnapshots.groupBy { it.panelId }
        val results = coroutineScope {
            activePanels.map { panel ->
                async {
                    val outcome = try {
                        Result.success(fetchPanelMachines(panel))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    panel to outcome
                }
            }.awaitAll()
        }

        val allMachines = ArrayList<ProbeSyncMachine>()
        val errors = LinkedHashMap<String, String>()
        val fallbackUsedIds = LinkedHashSet<String>()
        var succeeded = 0

        for ((panel, outcome) in results) {
            outcome.fold(
                onSuccess = { machines ->
                    succeeded++
                    allMachines.addAll(machines)
                },
                onFailure = { err ->
                    errors[panel.id] = err.message?.takeIf { it.isNotBlank() } ?: "同步失败"
                    val cached = snapshotsByPanel[panel.id].orEmpty()
                    if (cached.isNotEmpty()) {
                        fallbackUsedIds += panel.id
                        allMachines.addAll(cached.map { ProbeSyncMachine.fromSnapshot(it, panel) })
                    }
                },
            )
        }

        ProbeVaultSyncFetchResult(
            machines = allMachines,
            totalPanelsAttempted = activePanels.size,
            succeededPanelCount = succeeded,
            failedPanelErrors = errors,
            usedCachedFallbackPanelIds = fallbackUsedIds,
        )
    }

    /**
     * Fetches syncable machine metadata from a single [config].
     */
    suspend fun fetchPanelMachines(config: ProbePanelConfig): List<ProbeSyncMachine> =
        withContext(ioDispatcher) {
            val rawTrimmed = config.baseUrl.trim()
            require(rawTrimmed.isNotEmpty()) { "探针地址不能为空" }

            val baseUrl = ProbePanelClient.normalizeBaseUrl(rawTrimmed)
            val originUrl = baseUrl.toHttpUrlOrNull()
                ?: throw ProbeCredentialBoundaryException("探针地址格式无效或协议不受支持：$rawTrimmed")
            if (hasUserInfo(originUrl)) {
                throw ProbeCredentialBoundaryException("安全校验失败：禁止在探针地址中内嵌账号或密码信息")
            }

            val cleanToken = sanitizeAndValidateApiToken(config.apiToken)
            if (cleanToken != null && !ProbePanelClient.isAllowedCredentialOrigin(originUrl, allowLoopbackHttpWithCredentials)) {
                throw ProbeCredentialBoundaryException(
                    "安全校验失败：配置了 API Token 或 Cookie 的探针面板必须使用 HTTPS 加密连接",
                )
            }

            val panelName = config.name.trim().ifBlank { baseUrl }
            when (config.effectivePanelType) {
                ProbePanelType.NEZHA_V1 -> fetchNezhaV1Machines(config.id, panelName, originUrl, cleanToken)
                ProbePanelType.NEZHA_V0 -> fetchNezhaV0Machines(config.id, panelName, originUrl, cleanToken)
                ProbePanelType.KOMARI -> fetchKomariMachines(config.id, panelName, originUrl, cleanToken)
                ProbePanelType.AUTO -> autoDiscoverAndFetchMachines(config.id, panelName, originUrl, cleanToken)
            }
        }

    // ── AUTO Discovery ────────────────────────────────────────────────────────

    private suspend fun autoDiscoverAndFetchMachines(
        panelId: String,
        panelName: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeSyncMachine> {
        var lastError: Throwable? = null

        // 1. Komari
        try {
            val komari = tryFetchKomariMachines(panelId, panelName, originUrl, apiToken)
            if (komari != null) return komari
        } catch (ce: CancellationException) {
            throw ce
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (t: Throwable) {
            lastError = t
        }

        // 2. Nezha v1
        try {
            val nezhaV1 = tryFetchNezhaV1Machines(panelId, panelName, originUrl, apiToken)
            if (nezhaV1 != null) return nezhaV1
        } catch (ce: CancellationException) {
            throw ce
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (t: Throwable) {
            if (lastError == null) lastError = t
        }

        // 3. Nezha v0
        try {
            val nezhaV0 = tryFetchNezhaV0Machines(panelId, panelName, originUrl, apiToken)
            if (nezhaV0 != null) return nezhaV0
        } catch (ce: CancellationException) {
            throw ce
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (t: Throwable) {
            if (lastError == null) lastError = t
        }

        throw IOException(lastError?.message ?: "未能自动识别探针协议（Komari / Nezha v1 / Nezha v0）")
    }

    // ── Nezha v1 ──────────────────────────────────────────────────────────────

    private suspend fun tryFetchNezhaV1Machines(
        panelId: String,
        panelName: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeSyncMachine>? {
        val (resp, winningHeaders) = executeNezhaV1Get(
            url = buildEndpointUrl(originUrl, "api", "v1", "server"),
            apiToken = apiToken,
            expectedOrigin = originUrl,
        )
        if (!resp.isSuccessful) return null
        val root = parseJsonOrNull(resp.body) ?: return null
        if (!looksLikeNezhaV1(root)) return null
        val groupsByServerId = fetchNezhaV1GroupsBestEffort(originUrl, winningHeaders)
        return parseNezhaV1Machines(
            panelId = panelId,
            panelName = panelName,
            root = root,
            groupsByServerId = groupsByServerId,
            hasPanelToken = !apiToken.isNullOrBlank(),
        )
    }

    private suspend fun fetchNezhaV1Machines(
        panelId: String,
        panelName: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeSyncMachine> {
        val (resp, winningHeaders) = executeNezhaV1Get(
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
        if (root is JsonObject && root["success"]?.booleanOrNull() == false) {
            val err = root["error"]?.stringOrNull() ?: root["message"]?.stringOrNull() ?: "请求失败"
            throw IOException("哪吒 v1 返回错误：$err")
        }
        val groupsByServerId = fetchNezhaV1GroupsBestEffort(originUrl, winningHeaders)
        return parseNezhaV1Machines(
            panelId = panelId,
            panelName = panelName,
            root = root,
            groupsByServerId = groupsByServerId,
            hasPanelToken = !apiToken.isNullOrBlank(),
        )
    }

    private suspend fun fetchNezhaV1GroupsBestEffort(
        originUrl: HttpUrl,
        headers: Map<String, String>,
    ): Map<String, List<String>> {
        val outcome = try {
            executeGet(
                initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server-group"),
                headers = headers,
                expectedOrigin = originUrl,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return emptyMap()
        }
        if (!outcome.isSuccessful) return emptyMap()
        val root = parseJsonOrNull(outcome.body) ?: return emptyMap()
        return parseNezhaV1ServerGroups(root)
    }

    internal fun parseNezhaV1ServerGroups(root: JsonElement): Map<String, List<String>> {
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray
            else -> null
        } ?: return emptyMap()

        val map = LinkedHashMap<String, MutableList<String>>()
        for (elem in array) {
            val obj = elem as? JsonObject ?: continue
            val groupObj = obj["group"] as? JsonObject
            val groupName = groupObj?.getFirstString("name", "Name")
                ?: obj.getFirstString("name", "Name")
                ?: continue
            val servers = obj["servers"] as? JsonArray ?: continue
            for (srv in servers) {
                val sid = srv.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val bucket = map.getOrPut(sid) { ArrayList() }
                if (bucket.none { it.equals(groupName, ignoreCase = true) }) {
                    bucket += groupName
                }
            }
        }
        return map
    }

    internal fun parseNezhaV1Machines(
        panelId: String,
        panelName: String,
        root: JsonElement,
        groupsByServerId: Map<String, List<String>> = emptyMap(),
        hasPanelToken: Boolean = false,
    ): List<ProbeSyncMachine> {
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val remoteId = obj["id"]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val name = obj["name"]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Server #$remoteId"
            val hostObj = obj["host"] as? JsonObject
            val geoipObj = obj["geoip"] as? JsonObject
            val geoipIpObj = geoipObj?.get("ip") as? JsonObject

            val ipv4 = sanitizeHostCandidate(
                geoipIpObj?.getFirstString("ipv4_addr", "ipv4", "IPv4Addr")
                    ?: geoipObj?.getFirstString("ipv4_addr", "ipv4", "ip")
                    ?: obj.getFirstString("ipv4", "ipv4_addr", "ip")
                    ?: hostObj?.getFirstString("ipv4", "ip")
                    ?: "",
            )
            val ipv6 = sanitizeHostCandidate(
                geoipIpObj?.getFirstString("ipv6_addr", "ipv6", "IPv6Addr")
                    ?: geoipObj?.getFirstString("ipv6_addr", "ipv6")
                    ?: obj.getFirstString("ipv6", "ipv6_addr")
                    ?: hostObj?.getFirstString("ipv6")
                    ?: "",
            )

            val rawCountry = geoipObj?.getFirstString("country_code", "CountryCode")
                ?: obj.getFirstString("country_code", "CountryCode")
                ?: hostObj?.getFirstString("country_code", "CountryCode")
                ?: ""
            val countryCode = extractCountryCode(rawCountry)
            val groups = groupsByServerId[remoteId].orEmpty()
            val noteTags = extractTagsFromPublicNote(obj["public_note"]?.stringOrNull())
            val allTags = (groups + noteTags).distinctBy { it.lowercase(Locale.ROOT) }

            val stateObj = (obj["state"] as? JsonObject) ?: (obj["status"] as? JsonObject)
            val isOnline = obj["online"]?.booleanOrNull()
                ?: obj["is_online"]?.booleanOrNull()
                ?: ((stateObj?.get("uptime")?.intOrNull() ?: 0) > 0)

            ProbeSyncMachine(
                panelId = panelId,
                panelName = panelName,
                panelType = ProbePanelType.NEZHA_V1,
                remoteServerId = remoteId,
                name = name,
                ipv4 = ipv4,
                ipv6 = ipv6,
                countryCode = countryCode,
                flagEmoji = countryCodeToFlagEmoji(countryCode),
                groupName = groups.firstOrNull().orEmpty(),
                tags = allTags,
                isOnline = isOnline,
                hasPanelToken = hasPanelToken,
            )
        }
    }

    private fun looksLikeNezhaV1(root: JsonElement): Boolean {
        val obj = root as? JsonObject ?: return false
        val success = obj["success"]?.booleanOrNull()
        val data = obj["data"] as? JsonArray ?: return false
        if (success == true && data.isEmpty()) return true
        val first = data.firstOrNull() as? JsonObject ?: return false
        return first.containsKey("state") ||
            first.containsKey("geoip") ||
            first.containsKey("display_index") ||
            (first.containsKey("host") && first.containsKey("last_active"))
    }

    // ── Nezha v0 ──────────────────────────────────────────────────────────────

    private suspend fun tryFetchNezhaV0Machines(
        panelId: String,
        panelName: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeSyncMachine>? {
        val headers = buildNezhaV0Headers(apiToken)
        val details = executeGet(
            initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "details"),
            headers = headers,
            expectedOrigin = originUrl,
        )
        if (details.isSuccessful) {
            val root = parseJsonOrNull(details.body)
            if (root != null && looksLikeNezhaV0(root)) {
                val parsed = parseNezhaV0Machines(panelId, panelName, root, !apiToken.isNullOrBlank())
                // Enrich missing IPv4/IPv6/tag from /server/list if details omitted them
                if (parsed.any { it.preferredHost.isEmpty() }) {
                    val listResp = try {
                        executeGet(
                            initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "list"),
                            headers = headers,
                            expectedOrigin = originUrl,
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (s: ProbeCredentialBoundaryException) {
                        throw s
                    } catch (_: Throwable) {
                        null
                    }
                    if (listResp?.isSuccessful == true) {
                        val listRoot = parseJsonOrNull(listResp.body)
                        if (listRoot != null) {
                            return mergeNezhaV0DetailsAndList(
                                parsed,
                                parseNezhaV0Machines(panelId, panelName, listRoot, !apiToken.isNullOrBlank()),
                            )
                        }
                    }
                }
                return parsed
            }
        }
        val list = executeGet(
            initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "list"),
            headers = headers,
            expectedOrigin = originUrl,
        )
        if (!list.isSuccessful) return null
        val listRoot = parseJsonOrNull(list.body) ?: return null
        if (!looksLikeNezhaV0(listRoot)) return null
        return parseNezhaV0Machines(panelId, panelName, listRoot, !apiToken.isNullOrBlank())
    }

    private suspend fun fetchNezhaV0Machines(
        panelId: String,
        panelName: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeSyncMachine> {
        val headers = buildNezhaV0Headers(apiToken)
        val detailsResp = executeGet(
            initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "details"),
            headers = headers,
            expectedOrigin = originUrl,
        )
        if (detailsResp.code == 401 || detailsResp.code == 403) {
            throw IOException("哪吒 v0 认证失败 (HTTP ${detailsResp.code})，请检查 API Token")
        }
        val detailsMachines = if (detailsResp.isSuccessful) {
            parseJsonOrNull(detailsResp.body)?.let { root ->
                validateNezhaV0Code(root)
                parseNezhaV0Machines(panelId, panelName, root, !apiToken.isNullOrBlank())
            }.orEmpty()
        } else {
            emptyList()
        }

        val listResp = try {
            executeGet(
                initialUrl = buildEndpointUrl(originUrl, "api", "v1", "server", "list"),
                headers = headers,
                expectedOrigin = originUrl,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (s: ProbeCredentialBoundaryException) {
            throw s
        } catch (t: Throwable) {
            if (detailsMachines.isNotEmpty()) return detailsMachines
            throw t
        }
        if (listResp.isSuccessful) {
            val listRoot = parseJsonOrNull(listResp.body)
            if (listRoot != null) {
                val listMachines = try {
                    validateNezhaV0Code(listRoot)
                    parseNezhaV0Machines(panelId, panelName, listRoot, !apiToken.isNullOrBlank())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (detailsMachines.isNotEmpty()) return detailsMachines
                    throw t
                }
                return if (detailsMachines.isNotEmpty()) {
                    mergeNezhaV0DetailsAndList(detailsMachines, listMachines)
                } else {
                    listMachines
                }
            }
        }
        if (detailsMachines.isNotEmpty()) return detailsMachines
        throw IOException("哪吒 v0 接口请求失败 (HTTP ${listResp.code})")
    }

    private fun mergeNezhaV0DetailsAndList(
        primary: List<ProbeSyncMachine>,
        secondary: List<ProbeSyncMachine>,
    ): List<ProbeSyncMachine> {
        val byId = secondary.associateBy { it.remoteServerId }
        return primary.map { item ->
            val extra = byId[item.remoteServerId] ?: return@map item
            item.copy(
                ipv4 = item.ipv4.ifBlank { extra.ipv4 },
                ipv6 = item.ipv6.ifBlank { extra.ipv6 },
                groupName = item.groupName.ifBlank { extra.groupName },
                tags = (item.tags + extra.tags).distinctBy { it.lowercase(Locale.ROOT) },
                countryCode = item.countryCode.ifBlank { extra.countryCode },
                flagEmoji = item.flagEmoji.ifBlank { extra.flagEmoji },
            )
        }
    }

    private fun validateNezhaV0Code(root: JsonElement) {
        val obj = root as? JsonObject ?: return
        val code = obj["code"]?.intOrNull()
        if (code != null && code != 0) {
            val msg = obj["message"]?.stringOrNull().orEmpty().ifBlank { "code=$code" }
            throw IOException("哪吒 v0 返回错误：$msg")
        }
    }

    private fun looksLikeNezhaV0(root: JsonElement): Boolean {
        val obj = root as? JsonObject ?: return false
        val code = obj["code"]?.intOrNull()
        val result = obj["result"] as? JsonArray ?: return false
        if (code == 0) return true
        val first = result.firstOrNull() as? JsonObject ?: return false
        return first.containsKey("status") || first.containsKey("host") || first.containsKey("ipv4")
    }

    internal fun parseNezhaV0Machines(
        panelId: String,
        panelName: String,
        root: JsonElement,
        hasPanelToken: Boolean = false,
    ): List<ProbeSyncMachine> {
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["result"] as? JsonArray) ?: (root["data"] as? JsonArray)
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val remoteId = obj["id"]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val name = obj["name"]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Server #$remoteId"
            val hostObj = obj["host"] as? JsonObject
            val tag = obj.getFirstString("tag", "Tag").orEmpty()
            val ipv4 = sanitizeHostCandidate(
                obj.getFirstString("ipv4", "IPv4")
                    ?: hostObj?.getFirstString("ipv4", "IPv4")
                    ?: "",
            )
            val ipv6 = sanitizeHostCandidate(
                obj.getFirstString("ipv6", "IPv6")
                    ?: hostObj?.getFirstString("ipv6", "IPv6")
                    ?: "",
            )
            val rawCountry = hostObj?.getFirstString("CountryCode", "country_code")
                ?: obj.getFirstString("country_code", "CountryCode")
                ?: ""
            val countryCode = extractCountryCode(rawCountry)
            val statusObj = obj["status"] as? JsonObject
            val isOnline = obj["online"]?.booleanOrNull()
                ?: ((statusObj?.getFirstString("Uptime", "uptime")?.toLongOrNull() ?: 0L) > 0L)

            ProbeSyncMachine(
                panelId = panelId,
                panelName = panelName,
                panelType = ProbePanelType.NEZHA_V0,
                remoteServerId = remoteId,
                name = name,
                ipv4 = ipv4,
                ipv6 = ipv6,
                countryCode = countryCode,
                flagEmoji = countryCodeToFlagEmoji(countryCode),
                groupName = tag,
                tags = if (tag.isNotBlank()) listOf(tag) else emptyList(),
                isOnline = isOnline,
                hasPanelToken = hasPanelToken,
            )
        }
    }

    // ── Komari ────────────────────────────────────────────────────────────────

    private suspend fun tryFetchKomariMachines(
        panelId: String,
        panelName: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeSyncMachine>? {
        val hasToken = !apiToken.isNullOrBlank()
        // 1. Prefer Komari admin endpoint when credentials exist (returns ipv4, ipv6, group, tags)
        if (hasToken) {
            val adminResp = executeKomariGet(
                url = buildEndpointUrl(originUrl, "api", "admin", "client", "list"),
                apiToken = apiToken,
                expectedOrigin = originUrl,
            )
            if (adminResp.isSuccessful) {
                val root = parseJsonOrNull(adminResp.body)
                if (root != null && looksLikeKomariNodes(root)) {
                    return parseKomariMachines(panelId, panelName, root, hasPanelToken = true)
                }
            }
        }

        // 2. Public / standard GET /api/nodes
        val nodesResp = executeKomariGet(
            url = buildEndpointUrl(originUrl, "api", "nodes"),
            apiToken = apiToken,
            expectedOrigin = originUrl,
        )
        if (nodesResp.isSuccessful) {
            val root = parseJsonOrNull(nodesResp.body)
            if (root != null && looksLikeKomariNodes(root)) {
                return parseKomariMachines(panelId, panelName, root, hasPanelToken = hasToken)
            }
        }

        // 3. Fallback JSON-RPC POST /api/rpc2 (common:getNodes)
        val rpcResp = executeKomariPostJson(
            url = buildEndpointUrl(originUrl, "api", "rpc2"),
            jsonBody = KOMARI_RPC_GET_NODES_BODY,
            apiToken = apiToken,
            expectedOrigin = originUrl,
        )
        if (!rpcResp.isSuccessful) return null
        val rpcRoot = parseJsonOrNull(rpcResp.body) ?: return null
        if (!looksLikeKomariNodes(rpcRoot)) return null
        return parseKomariMachines(panelId, panelName, rpcRoot, hasPanelToken = hasToken)
    }

    private suspend fun fetchKomariMachines(
        panelId: String,
        panelName: String,
        originUrl: HttpUrl,
        apiToken: String?,
    ): List<ProbeSyncMachine> {
        val hasToken = !apiToken.isNullOrBlank()
        if (hasToken) {
            val adminResp = executeKomariGet(
                url = buildEndpointUrl(originUrl, "api", "admin", "client", "list"),
                apiToken = apiToken,
                expectedOrigin = originUrl,
            )
            if (adminResp.isSuccessful) {
                val root = parseJsonOrNull(adminResp.body)
                if (root != null && looksLikeKomariNodes(root)) {
                    return parseKomariMachines(panelId, panelName, root, hasPanelToken = true)
                }
            }
        }

        val nodesResp = executeKomariGet(
            url = buildEndpointUrl(originUrl, "api", "nodes"),
            apiToken = apiToken,
            expectedOrigin = originUrl,
        )
        if (nodesResp.code == 401 || nodesResp.code == 403) {
            throw IOException("Komari 认证失败 (HTTP ${nodesResp.code})，请检查 API Token / Cookie")
        }
        if (nodesResp.isSuccessful) {
            val root = parseJsonOrNull(nodesResp.body)
                ?: throw IOException("Komari /api/nodes 返回数据非有效 JSON")
            if (root is JsonObject && root["error"] != null && root["error"] !is JsonNull) {
                val errMsg = (root["error"] as? JsonObject)?.getFirstString("message")
                    ?: root["error"]?.stringOrNull()
                    ?: "RPC 错误"
                throw IOException("Komari /api/nodes 返回错误：$errMsg")
            }
            return parseKomariMachines(panelId, panelName, root, hasPanelToken = hasToken)
        }

        val rpcResp = executeKomariPostJson(
            url = buildEndpointUrl(originUrl, "api", "rpc2"),
            jsonBody = KOMARI_RPC_GET_NODES_BODY,
            apiToken = apiToken,
            expectedOrigin = originUrl,
        )
        if (rpcResp.isSuccessful) {
            val root = parseJsonOrNull(rpcResp.body)
                ?: throw IOException("Komari /api/rpc2 返回数据非有效 JSON")
            if (root is JsonObject && root["error"] != null && root["error"] !is JsonNull) {
                val errMsg = (root["error"] as? JsonObject)?.getFirstString("message")
                    ?: root["error"]?.stringOrNull()
                    ?: "RPC 错误"
                throw IOException("Komari /api/rpc2 返回错误：$errMsg")
            }
            return parseKomariMachines(panelId, panelName, root, hasPanelToken = hasToken)
        }
        throw IOException("Komari 节点列表请求失败 (HTTP ${nodesResp.code})")
    }

    internal fun parseKomariMachines(
        panelId: String,
        panelName: String,
        root: JsonElement,
        hasPanelToken: Boolean = false,
    ): List<ProbeSyncMachine> {
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
            val name = obj.getFirstString("name", "hostname")?.trim()?.takeIf { it.isNotEmpty() }
                ?: remoteId
            val ipv4 = sanitizeHostCandidate(obj.getFirstString("ipv4", "ip", "ipv4_addr").orEmpty())
            val ipv6 = sanitizeHostCandidate(obj.getFirstString("ipv6", "ipv6_addr").orEmpty())
            val rawRegion = obj.getFirstString("region", "country_code", "country").orEmpty()
            val countryCode = extractCountryCode(rawRegion)
            val flag = if (rawRegion.any { it.code > 127 }) rawRegion else countryCodeToFlagEmoji(countryCode)
            val group = obj.getFirstString("group", "group_name").orEmpty()
            val rawTags = when (val tagsElem = obj["tags"]) {
                is JsonArray -> tagsElem.mapNotNull { it.stringOrNull() }
                is JsonPrimitive -> tagsElem.contentOrNull
                    ?.split(';', ',', '，')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                else -> emptyList()
            }
            val isOnline = obj["online"]?.booleanOrNull() ?: obj["is_online"]?.booleanOrNull() ?: false

            ProbeSyncMachine(
                panelId = panelId,
                panelName = panelName,
                panelType = ProbePanelType.KOMARI,
                remoteServerId = remoteId,
                name = name,
                ipv4 = ipv4,
                ipv6 = ipv6,
                countryCode = countryCode,
                flagEmoji = flag,
                groupName = group,
                tags = rawTags,
                isOnline = isOnline,
                hasPanelToken = hasPanelToken,
            )
        }
    }

    private fun looksLikeKomariNodes(root: JsonElement): Boolean {
        if (root is JsonObject && root["error"] != null && root["error"] !is JsonNull) return false
        val container = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val status = root["status"]?.stringOrNull()
                val isRpc = root["jsonrpc"]?.stringOrNull() == "2.0"
                val inner = root["data"] ?: root["result"] ?: root["nodes"]
                when (inner) {
                    is JsonArray -> if (inner.isEmpty() && (status.equals("success", true) || isRpc)) return true else inner
                    is JsonObject -> if (inner.isEmpty() && (status.equals("success", true) || isRpc)) return true else inner
                    else -> null
                }
            }
            else -> null
        } ?: return false
        if (container is JsonArray && container.isEmpty()) return true
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

    // ── Auth & Transport Helpers ──────────────────────────────────────────────

    private suspend fun executeNezhaV1Get(
        url: HttpUrl,
        apiToken: String?,
        expectedOrigin: HttpUrl,
    ): Pair<HttpOutcome, Map<String, String>> {
        val trimmed = apiToken?.trim().orEmpty()
        val primary = buildNezhaV1Headers(trimmed, asCookie = false)
        val first = executeGet(url, primary, expectedOrigin)
        if ((first.code == 401 || first.code == 403) &&
            trimmed.isNotEmpty() &&
            !trimmed.startsWith("Bearer ", ignoreCase = true) &&
            !trimmed.startsWith("Basic ", ignoreCase = true) &&
            !ProbePanelClient.looksLikeCookieHeader(trimmed)
        ) {
            val cookieHeaders = buildNezhaV1Headers(trimmed, asCookie = true)
            val second = executeGet(url, cookieHeaders, expectedOrigin)
            if (second.isSuccessful) return second to cookieHeaders
        }
        return first to primary
    }

    private suspend fun executeKomariGet(
        url: HttpUrl,
        apiToken: String?,
        expectedOrigin: HttpUrl,
    ): HttpOutcome {
        val trimmed = apiToken?.trim().orEmpty()
        val primary = buildKomariHeaders(trimmed, asCookie = false)
        val first = executeGet(url, primary, expectedOrigin)
        if ((first.code == 401 || first.code == 403) &&
            trimmed.isNotEmpty() &&
            !trimmed.startsWith("Bearer ", ignoreCase = true) &&
            !trimmed.startsWith("Basic ", ignoreCase = true) &&
            !ProbePanelClient.looksLikeCookieHeader(trimmed)
        ) {
            val cookieHeaders = buildKomariHeaders(trimmed, asCookie = true)
            val second = executeGet(url, cookieHeaders, expectedOrigin)
            if (second.isSuccessful) return second
        }
        return first
    }

    private suspend fun executeKomariPostJson(
        url: HttpUrl,
        jsonBody: String,
        apiToken: String?,
        expectedOrigin: HttpUrl,
    ): HttpOutcome {
        val trimmed = apiToken?.trim().orEmpty()
        val primary = buildKomariHeaders(trimmed, asCookie = false)
        val first = executePostJson(url, jsonBody, primary, expectedOrigin)
        if ((first.code == 401 || first.code == 403) &&
            trimmed.isNotEmpty() &&
            !trimmed.startsWith("Bearer ", ignoreCase = true) &&
            !trimmed.startsWith("Basic ", ignoreCase = true) &&
            !ProbePanelClient.looksLikeCookieHeader(trimmed)
        ) {
            val cookieHeaders = buildKomariHeaders(trimmed, asCookie = true)
            val second = executePostJson(url, jsonBody, cookieHeaders, expectedOrigin)
            if (second.isSuccessful) return second
        }
        return first
    }

    private fun buildNezhaV0Headers(apiToken: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) put("Authorization", token)
    }

    private fun buildNezhaV1Headers(apiToken: String?, asCookie: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            when {
                asCookie -> put("Cookie", "nz-jwt=$token")
                token.startsWith("Bearer ", ignoreCase = true) ||
                    token.startsWith("Basic ", ignoreCase = true) -> put("Authorization", token)
                ProbePanelClient.looksLikeCookieHeader(token) -> put("Cookie", token)
                else -> put("Authorization", "Bearer $token")
            }
        }
    }

    private fun buildKomariHeaders(apiToken: String?, asCookie: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            when {
                asCookie -> put("Cookie", "session_token=$token; temp_key=$token")
                token.startsWith("Basic ", ignoreCase = true) -> put("Authorization", token)
                token.startsWith("Bearer ", ignoreCase = true) -> {
                    put("Authorization", token)
                    val rawKey = token.substring("Bearer ".length).trim()
                    if (rawKey.isNotEmpty()) put("X-API-Key", rawKey)
                }
                ProbePanelClient.looksLikeCookieHeader(token) -> put("Cookie", token)
                else -> {
                    put("Authorization", "Bearer $token")
                    put("X-API-Key", token)
                }
            }
        }
    }

    private suspend fun executeGet(
        initialUrl: HttpUrl,
        headers: Map<String, String>,
        expectedOrigin: HttpUrl,
    ): HttpOutcome = executeRequest(
        initialUrl = initialUrl,
        jsonBody = null,
        headers = headers,
        expectedOrigin = expectedOrigin,
    )

    private suspend fun executePostJson(
        initialUrl: HttpUrl,
        jsonBody: String,
        headers: Map<String, String>,
        expectedOrigin: HttpUrl,
    ): HttpOutcome = executeRequest(
        initialUrl = initialUrl,
        jsonBody = jsonBody,
        headers = headers,
        expectedOrigin = expectedOrigin,
    )

    private suspend fun executeRequest(
        initialUrl: HttpUrl,
        jsonBody: String?,
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
        var usePost = jsonBody != null
        for (hop in 0..ProbePanelClient.MAX_REDIRECT_HOPS) {
            if (hasUserInfo(currentUrl)) {
                throw ProbeCredentialBoundaryException("安全校验失败：禁止在探针请求 URL 中内嵌账号或密码信息")
            }
            if (carriesCredentials) {
                if (!ProbePanelClient.isAllowedCredentialOrigin(currentUrl, allowLoopbackHttpWithCredentials)) {
                    throw ProbeCredentialBoundaryException("安全校验失败：配置了凭证的探针面板必须使用 HTTPS 加密连接")
                }
                if (!ProbePanelClient.isSameOrigin(expectedOrigin, currentUrl)) {
                    throw ProbeCredentialBoundaryException("安全校验失败：拒绝向非同源地址发送探针凭证")
                }
            }

            val reqBuilder = Request.Builder().url(currentUrl)
            if (usePost && jsonBody != null) {
                reqBuilder.post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            } else {
                reqBuilder.get()
            }
            sanitizedHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            val raw = executeSingleCall(reqBuilder.build())

            if (raw.code !in REDIRECT_STATUS_CODES) {
                return HttpOutcome(code = raw.code, body = raw.body)
            }
            if (hop >= ProbePanelClient.MAX_REDIRECT_HOPS) {
                throw ProbeCredentialBoundaryException("探针面板重定向次数过多")
            }
            val location = raw.location?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ProbeCredentialBoundaryException("探针面板重定向响应缺少 Location 头")
            val nextUrl = currentUrl.resolve(location)
                ?: throw ProbeCredentialBoundaryException("探针面板重定向目标地址无效")
            if (hasUserInfo(nextUrl)) {
                throw ProbeCredentialBoundaryException("安全校验失败：拒绝重定向至含用户信息的地址")
            }
            if (currentUrl.scheme.equals("https", true) && !nextUrl.scheme.equals("https", true)) {
                throw ProbeCredentialBoundaryException("安全校验失败：拒绝从 HTTPS 降级至 HTTP")
            }
            if (carriesCredentials && !ProbePanelClient.isSameOrigin(expectedOrigin, nextUrl)) {
                throw ProbeCredentialBoundaryException("安全校验失败：拒绝跨源重定向探针凭证")
            }
            if (raw.code == 301 || raw.code == 302 || raw.code == 303) {
                usePost = false
            }
            currentUrl = nextUrl
        }
        throw ProbeCredentialBoundaryException("探针面板重定向次数过多")
    }

    private suspend fun executeSingleCall(request: Request): RawWireResponse =
        suspendCancellableCoroutine { continuation ->
            val call = transportClient.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val location = resp.header("Location")
                        val bodyStr = if (resp.code in REDIRECT_STATUS_CODES) {
                            ""
                        } else {
                            try {
                                ProbePanelClient.readBoundedBodyString(resp)
                            } catch (e: IOException) {
                                if (continuation.isActive) continuation.resumeWithException(e)
                                return
                            }
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

    private fun parseJsonOrNull(raw: String): JsonElement? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }

    private fun extractTagsFromPublicNote(publicNote: String?): List<String> {
        val raw = publicNote?.trim().orEmpty()
        if (!raw.startsWith("{")) return emptyList()
        val obj = parseJsonOrNull(raw) as? JsonObject ?: return emptyList()
        val plan = obj["planDataMod"] as? JsonObject ?: obj
        val extra = plan.getFirstString("extra", "tag", "tags").orEmpty()
        if (extra.isBlank()) return emptyList()
        return extra.split(',', ';', '，', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 24 }
            .take(4)
    }

    private fun sanitizeHostCandidate(raw: String): String {
        val cleaned = raw.trim().removePrefix("[").removeSuffix("]")
        if (cleaned.isEmpty()) return ""
        if (cleaned.equals("null", ignoreCase = true) || cleaned.contains('*')) return ""
        return cleaned
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull ?: stringOrNull()?.toIntOrNull()
    private fun JsonElement.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.getFirstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } }

    companion object {
        private const val KOMARI_RPC_GET_NODES_BODY =
            """{"jsonrpc":"2.0","method":"common:getNodes","params":{},"id":1}"""
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        private fun hasUserInfo(url: HttpUrl): Boolean =
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()

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
                throw ProbeCredentialBoundaryException("安全校验失败：探针凭证格式无效")
            }
            return trimmed
        }

        fun defaultHttpClient(): OkHttpClient = ProbePanelClient.defaultHttpClient()
    }
}
