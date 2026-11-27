package com.nodeseek.app.data.activity

import com.nodeseek.app.domain.activity.DrandBeacon
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
internal data class DrandBeaconDto(
    val round: Long,
    val randomness: String = "",
    val signature: String = "",
    @SerialName("previous_signature")
    val previousSignature: String = "",
)

class DrandApiException(
    val httpCode: Int?,
    val isRoundNotYetPublished: Boolean = false,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Isolated cookie-free, interceptor-free HTTP client for Drand League of Entropy public beacons.
 * Never shares cookies, User-Agent overrides, or forum signing headers with `apiHttpClient`.
 */
class DrandApiClient(
    baseHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val chainHash: String = DrandLuckyEngine.DRAND_CHAIN_HASH,
    private val json: Json = DEFAULT_JSON,
) {
    internal val httpClient: OkHttpClient = baseHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(20L, TimeUnit.SECONDS)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .build()

    suspend fun getBeacon(round: Long): DrandBeacon {
        require(round >= 1L) { "Drand round 必须 >= 1" }
        val requestUrl = baseUrl.newBuilder()
            .addPathSegment(chainHash)
            .addPathSegment("public")
            .addPathSegment(round.toString())
            .build()
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json")
            .get()
            .build()

        val (statusCode, rawBody) = executeAsync(request)
        if (statusCode == 404) {
            throw DrandApiException(
                httpCode = 404,
                isRoundNotYetPublished = true,
                message = "Drand 随机信标尚未上链（Round $round 预计数秒内公布）",
            )
        }
        if (statusCode !in 200..299) {
            throw DrandApiException(
                httpCode = statusCode,
                message = "Drand 随机信标请求失败 (HTTP $statusCode)",
            )
        }

        val dto = withContext(Dispatchers.Default) {
            json.decodeFromString(DrandBeaconDto.serializer(), rawBody)
        }
        val cleanRandomness = dto.randomness.trim()
        if (cleanRandomness.isEmpty()) {
            throw DrandApiException(
                httpCode = statusCode,
                message = "Drand 响应缺少 randomness 字段",
            )
        }
        return DrandBeacon(
            round = dto.round,
            randomness = cleanRandomness,
            signature = dto.signature.trim(),
            previousSignature = dto.previousSignature.trim(),
        )
    }

    private suspend fun executeAsync(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body.string()
                        if (cont.isActive) {
                            cont.resume(resp.code to body)
                        }
                    }
                }
            })
        }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.drand.sh/".toHttpUrl()
        internal val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
