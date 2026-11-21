package com.nodeseek.app.data.trust

import com.nodeseek.app.domain.trust.OfficialTradeRoleStat
import com.nodeseek.app.domain.trust.OfficialTradeUserStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class OfficialTradeErrorKind {
    UNAUTHORIZED,
    NOT_FOUND,
    RATE_LIMITED,
    NETWORK,
    SERVER,
}

internal class OfficialTradeApiException(
    val kind: OfficialTradeErrorKind,
    override val message: String,
    val statusCode: Int? = null,
    val retryAfterMillis: Long? = null,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

@Serializable
internal data class OfficialTradeUsersResponseDto(
    val users: List<OfficialTradeUserSummaryDto> = emptyList(),
)

@Serializable
internal data class OfficialTradeUserSummaryDto(
    val id: Long,
    val username: String = "",
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    val bio: String? = null,
    val role: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
)

@Serializable
internal data class OfficialTradeUserStatsResponseDto(
    val user: OfficialTradeUserSummaryDto,
    val stats: OfficialTradeStatsPayloadDto,
)

@Serializable
internal data class OfficialTradeStatsPayloadDto(
    val trades: Int = 0,
    val amount: String = "0.00",
    @SerialName("last_completed_at")
    val lastCompletedAt: String? = null,
    @SerialName("by_role")
    val byRole: List<OfficialTradeRoleStatDto> = emptyList(),
)

@Serializable
internal data class OfficialTradeRoleStatDto(
    val role: String = "",
    val trades: Int = 0,
    val amount: String = "0.00",
)

/**
 * Isolated OpenAPI HTTP client for the NodeSeek Official Trade Platform
 * (`https://agent-api.nodeseek.org`).
 *
 * Security & protocol guarantees:
 * - Enforces HTTPS-only origins in production ([allowLoopbackHttpForTesting] defaults to `false`).
 * - Strips all application/network interceptors (including any logging or forum cookie interceptors),
 *   sets [CookieJar.NO_COOKIES], and disables redirects so `Authorization: Bearer <key>` is never
 *   logged or forwarded cross-origin.
 * - Percent-encodes query usernames per RFC 3986 (`%20` for spaces, UTF-8 hex for CJK & symbols).
 * - Never includes the API Key in any exception message, log, or `toString()`.
 */
class OfficialTradeApiClient(
    baseHttpClient: OkHttpClient = OkHttpClient(),
    val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val allowLoopbackHttpForTesting: Boolean = false,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    writeTimeoutMillis: Long = DEFAULT_WRITE_TIMEOUT_MILLIS,
    callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
) {
    init {
        require(isAllowedOrigin(baseUrl, allowLoopbackHttpForTesting)) {
            "OfficialTradeApiClient baseUrl must use HTTPS, got scheme=${baseUrl.scheme} host=${baseUrl.host}"
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = true
    }

    private val httpClient: OkHttpClient = baseHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .build()

    internal suspend fun searchUsersByUsername(
        apiKey: String,
        username: String,
    ): List<OfficialTradeUserSummaryDto> {
        val cleanUsername = OfficialTradeCacheStore.normalizeCleanUsername(username)
        if (cleanUsername.isEmpty()) return emptyList()
        val encodedUsername = encodeQueryComponentRfc3986(cleanUsername)
        val url = buildEndpointUrl("open", "v1", "users")
            .newBuilder()
            .addEncodedQueryParameter("username", encodedUsername)
            .build()
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = url,
            serializer = OfficialTradeUsersResponseDto.serializer(),
        )
        return dto.users
    }

    internal suspend fun fetchUserStats(
        apiKey: String,
        userId: Long,
    ): OfficialTradeUserStats {
        val url = buildEndpointUrl("open", "v1", "users", userId.toString(), "stats")
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = url,
            serializer = OfficialTradeUserStatsResponseDto.serializer(),
        )
        return OfficialTradeUserStats(
            userId = dto.user.id,
            username = dto.user.username,
            trades = dto.stats.trades,
            amount = dto.stats.amount,
            lastCompletedAt = dto.stats.lastCompletedAt,
            byRole = dto.stats.byRole.map { item ->
                OfficialTradeRoleStat(
                    role = item.role,
                    trades = item.trades,
                    amount = item.amount,
                )
            },
            userRole = dto.user.role?.takeIf { it.isNotBlank() } ?: "user",
            avatarUrl = dto.user.avatarUrl?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun <T> executeJsonGet(
        apiKey: String,
        url: HttpUrl,
        serializer: KSerializer<T>,
    ): T {
        val request = buildAuthorizedGetRequest(apiKey = apiKey, url = url)
        val rawBody = try {
            httpClient.newCall(request).awaitOfficialString { statusCode, headers, body ->
                validateHttpStatus(statusCode = statusCode, headers = headers, rawBody = body)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (apiEx: OfficialTradeApiException) {
            throw apiEx
        } catch (io: IOException) {
            throw OfficialTradeApiException(
                kind = OfficialTradeErrorKind.NETWORK,
                message = "连接官方交易平台失败，请检查网络后重试",
                cause = io,
            )
        }

        return try {
            withContext(Dispatchers.Default) {
                json.decodeFromString(serializer, rawBody)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (s: SerializationException) {
            throw OfficialTradeApiException(
                kind = OfficialTradeErrorKind.SERVER,
                message = "官方交易平台响应数据解析失败",
                cause = s,
            )
        } catch (e: IllegalArgumentException) {
            throw OfficialTradeApiException(
                kind = OfficialTradeErrorKind.SERVER,
                message = "官方交易平台响应数据格式异常",
                cause = e,
            )
        }
    }

    private fun buildEndpointUrl(vararg segments: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        val existingSegments = baseUrl.pathSegments
        if (existingSegments.isNotEmpty() && existingSegments.last().isEmpty()) {
            builder.removePathSegment(existingSegments.size - 1)
        }
        for (segment in segments) {
            if (segment.isNotEmpty()) {
                builder.addPathSegment(segment)
            }
        }
        return builder.build()
    }

    private fun buildAuthorizedGetRequest(
        apiKey: String,
        url: HttpUrl,
    ): Request {
        val normalizedKey = OfficialTradeKeyStore.validateAndNormalizeKey(apiKey)
        verifySameOriginAndHttpsBeforeAuth(url)
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Authorization", "Bearer $normalizedKey")
            .build()
    }

    private fun verifySameOriginAndHttpsBeforeAuth(url: HttpUrl) {
        val allowed = isAllowedOrigin(url, allowLoopbackHttpForTesting) &&
            url.scheme.equals(baseUrl.scheme, ignoreCase = true) &&
            url.host.equals(baseUrl.host, ignoreCase = true) &&
            url.port == baseUrl.port
        if (!allowed) {
            throw OfficialTradeApiException(
                kind = OfficialTradeErrorKind.NETWORK,
                message = "安全校验失败：仅允许通过 HTTPS 访问官方交易平台源站",
            )
        }
    }

    private fun validateHttpStatus(
        statusCode: Int,
        headers: Headers,
        rawBody: String,
    ) {
        if (statusCode in 200..299) return

        if (statusCode in 300..399) {
            throw OfficialTradeApiException(
                kind = OfficialTradeErrorKind.NETWORK,
                message = "安全校验失败：官方交易平台接口禁止重定向 ($statusCode)",
                statusCode = statusCode,
            )
        }

        val now = clockMillis()
        val retryAfterMillis = parseRetryAfterMillis(headers["Retry-After"], now)
        val parsedError = parseErrorPayload(rawBody)

        val kind = when (statusCode) {
            401, 403 -> OfficialTradeErrorKind.UNAUTHORIZED
            404 -> OfficialTradeErrorKind.NOT_FOUND
            429 -> OfficialTradeErrorKind.RATE_LIMITED
            else -> OfficialTradeErrorKind.SERVER
        }

        val fallbackMessage = when (kind) {
            OfficialTradeErrorKind.UNAUTHORIZED -> "API Key 无效或已吊销"
            OfficialTradeErrorKind.NOT_FOUND -> "平台无此用户"
            OfficialTradeErrorKind.RATE_LIMITED -> "官方交易平台请求过于频繁 (429)"
            OfficialTradeErrorKind.SERVER -> if (statusCode in 500..599) {
                "官方交易平台服务暂时不可用 ($statusCode)"
            } else {
                "官方交易平台请求失败 ($statusCode)"
            }
            OfficialTradeErrorKind.NETWORK -> "网络连接失败"
        }

        throw OfficialTradeApiException(
            kind = kind,
            message = parsedError.first?.takeIf { it.isNotBlank() } ?: fallbackMessage,
            statusCode = statusCode,
            retryAfterMillis = retryAfterMillis,
            errorCode = parsedError.second,
        )
    }

    private fun parseErrorPayload(rawBody: String): Pair<String?, String?> {
        val trimmed = rawBody.trim()
        if (!trimmed.startsWith("{")) return null to null
        return runCatching {
            val obj = json.parseToJsonElement(trimmed) as? JsonObject ?: return@runCatching null to null
            val code = (obj["code"] as? JsonPrimitive)?.contentOrNull
            val message = when (val errElement = obj["error"]) {
                is JsonPrimitive -> errElement.contentOrNull
                is JsonObject -> (errElement["message"] as? JsonPrimitive)?.contentOrNull
                else -> (obj["message"] as? JsonPrimitive)?.contentOrNull
            }
            message?.take(200) to code
        }.getOrDefault(null to null)
    }

    override fun toString(): String = "OfficialTradeApiClient(baseUrl=$baseUrl)"

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://agent-api.nodeseek.org".toHttpUrl()
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 10_000L
        const val DEFAULT_READ_TIMEOUT_MILLIS: Long = 15_000L
        const val DEFAULT_WRITE_TIMEOUT_MILLIS: Long = 10_000L
        const val DEFAULT_CALL_TIMEOUT_MILLIS: Long = 20_000L

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

        internal fun encodeQueryComponentRfc3986(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%7E", "~")

        internal fun isAllowedOrigin(
            url: HttpUrl,
            allowLoopbackHttpForTesting: Boolean,
        ): Boolean {
            if (url.scheme.equals("https", ignoreCase = true)) return true
            if (!allowLoopbackHttpForTesting) return false
            if (!url.scheme.equals("http", ignoreCase = true)) return false
            val host = url.host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
            return host in LOOPBACK_HOSTS
        }
    }
}

private suspend fun Call.awaitOfficialString(
    validator: (statusCode: Int, headers: Headers, body: String) -> Unit,
): String = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCompleted) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val bodyString = resp.body?.string().orEmpty()
                        validator(resp.code, resp.headers, bodyString)
                        if (!cont.isCompleted) {
                            cont.resume(bodyString)
                        }
                    } catch (t: Throwable) {
                        if (!cont.isCompleted) {
                            cont.resumeWithException(t)
                        }
                    }
                }
            }
        },
    )
}
