package com.nodeseek.app.data.overseas

import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Aggregated fetch output from LowEndTalk and LowEndBox feeds.
 */
data class OverseasFeedFetchResult(
    val offers: List<OverseasDealOffer>,
    val sourceErrors: Map<OverseasSource, String>,
    val usedLetJsonFallback: Boolean = false,
)

/**
 * Fetches overseas VPS deal feeds concurrently using OkHttp:
 * - LowEndTalk Offers RSS (`letRssUrl`) with automatic Vanilla JSON endpoint fallback (`letJsonFallbackUrl`)
 * - LowEndBox RSS (`lebRssUrl`)
 */
class OverseasFeedFetcher(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val letRssUrl: String = LetFeedParser.LET_RSS_URL,
    private val letJsonFallbackUrl: String = LetFeedParser.LET_JSON_URL,
    private val lebRssUrl: String = LebFeedParser.LEB_RSS_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun fetchAll(fxRatesState: FxRatesState): OverseasFeedFetchResult = withContext(ioDispatcher) {
        coroutineScope {
            val letDeferred = async { fetchLetOffers(fxRatesState) }
            val lebDeferred = async { fetchLebOffers(fxRatesState) }

            val letResult = letDeferred.await()
            val lebResult = lebDeferred.await()

            val errors = LinkedHashMap<OverseasSource, String>()
            letResult.error?.let { errors[OverseasSource.LET] = it }
            lebResult.error?.let { errors[OverseasSource.LEB] = it }

            val merged = (letResult.offers + lebResult.offers)
                .distinctBy { it.id }
                .sortedByDescending { it.publishedAtEpochMillis }

            OverseasFeedFetchResult(
                offers = merged,
                sourceErrors = errors,
                usedLetJsonFallback = letResult.usedJsonFallback,
            )
        }
    }

    private data class SingleSourceResult(
        val offers: List<OverseasDealOffer> = emptyList(),
        val error: String? = null,
        val usedJsonFallback: Boolean = false,
    )

    private suspend fun fetchLetOffers(fxRatesState: FxRatesState): SingleSourceResult {
        var rssFailureReason: String? = null
        try {
            val rssXml = executeGet(
                url = letRssUrl,
                acceptHeader = "application/rss+xml, application/xml, text/xml, */*",
            )
            val parsedRss = LetFeedParser.parseRss(xmlContent = rssXml, fxRatesState = fxRatesState)
            if (parsedRss.isNotEmpty()) {
                return SingleSourceResult(offers = parsedRss, usedJsonFallback = false)
            }
            rssFailureReason = "LET RSS 未返回有效条目"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            rssFailureReason = t.message?.takeIf { it.isNotBlank() } ?: "LET RSS 请求失败"
        }

        // Fallback to Vanilla JSON endpoint (`categories/offers.json` / `discussions.json`)
        return try {
            val jsonBody = executeGet(
                url = letJsonFallbackUrl,
                acceptHeader = "application/json, */*",
            )
            val parsedJson = LetFeedParser.parseVanillaJson(jsonContent = jsonBody, fxRatesState = fxRatesState)
            if (parsedJson.isNotEmpty()) {
                SingleSourceResult(offers = parsedJson, usedJsonFallback = true)
            } else {
                SingleSourceResult(
                    offers = emptyList(),
                    error = "LowEndTalk 拉取失败（RSS: $rssFailureReason；JSON 返回为空）",
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val jsonReason = t.message?.takeIf { it.isNotBlank() } ?: "JSON 接口不可达"
            SingleSourceResult(
                offers = emptyList(),
                error = "LowEndTalk 暂时不可达（RSS: $rssFailureReason；JSON: $jsonReason）",
            )
        }
    }

    private suspend fun fetchLebOffers(fxRatesState: FxRatesState): SingleSourceResult {
        return try {
            val xml = executeGet(
                url = lebRssUrl,
                acceptHeader = "application/rss+xml, application/xml, text/xml, */*",
            )
            val parsed = LebFeedParser.parseRss(xmlContent = xml, fxRatesState = fxRatesState)
            if (parsed.isNotEmpty()) {
                SingleSourceResult(offers = parsed)
            } else {
                SingleSourceResult(
                    offers = emptyList(),
                    error = "LowEndBox RSS 返回为空",
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val reason = t.message?.takeIf { it.isNotBlank() } ?: "网络异常"
            SingleSourceResult(
                offers = emptyList(),
                error = "LowEndBox 拉取失败（$reason）",
            )
        }
    }

    private suspend fun executeGet(url: String, acceptHeader: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", acceptHeader)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

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
                        if (!resp.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("HTTP ${resp.code}"))
                            }
                            return
                        }
                        val bodyStr = resp.body?.string().orEmpty()
                        if (continuation.isActive) {
                            continuation.resume(bodyStr)
                        }
                    }
                }
            })
        }
    }

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
