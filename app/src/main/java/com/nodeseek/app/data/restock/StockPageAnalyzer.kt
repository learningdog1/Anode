package com.nodeseek.app.data.restock

import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.domain.restock.FlareSolverrConfig
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.StockAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches and inspects VPS merchant product pages (WHMCS, SolusVM, HostBill, VirtFusion,
 * and generic Chinese/English storefronts) using OkHttp + Jsoup to determine stock status,
 * billing panel type, product title, and price hint.
 *
 * When a merchant page is protected by a Cloudflare challenge and a self-hosted
 * FlareSolverr service is configured via [settingsProvider], the solved HTML returned
 * by the service is analyzed instead of failing the check. Hosts that have served a
 * challenge once skip the doomed direct fetch on subsequent polls ([cfProtectedHosts]).
 */
class StockPageAnalyzer(
    private val httpClient: OkHttpClient = createDefaultHttpClient(),
    private val userAgentProvider: () -> String = { UserAgentProvider.DEFAULT_ANDROID_USER_AGENT },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val flareSolverrClient: FlareSolverrClient? = null,
    private val settingsProvider: (suspend () -> RestockMonitorSettings)? = null,
) {

    private val cfProtectedHosts: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    private fun isHostCfProtected(host: String, nowMillis: Long): Boolean {
        if (host.isEmpty()) return false
        val recordedAt = cfProtectedHosts[host] ?: return false
        val age = nowMillis - recordedAt
        if (age in 0L until CF_HOST_CACHE_TTL_MILLIS) {
            return true
        }
        cfProtectedHosts.remove(host, recordedAt)
        return false
    }

    private fun markHostCfProtected(host: String, nowMillis: Long) {
        if (host.isEmpty()) return
        cfProtectedHosts[host] = nowMillis
    }

    suspend fun analyzeUrl(
        rawUrl: String,
        customInStockKeyword: String = "",
        customOutOfStockKeyword: String = "",
    ): StockAnalysisResult = withContext(Dispatchers.IO) {
        val checkedAt = clockMillis()
        val resolvedUrl = MerchantLinkDetector.resolveOrderUrl(rawUrl)
        val initialPanel = MerchantLinkDetector.inferPanelTypeFromUrl(resolvedUrl)

        if (!resolvedUrl.startsWith("http://", ignoreCase = true) &&
            !resolvedUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                finalUrl = resolvedUrl,
                panelType = initialPanel,
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "链接格式无效",
                errorMessage = "请输入有效的 http:// 或 https:// 商品链接",
                isTransientNetworkError = false,
                checkedAtEpochMillis = checkedAt,
            )
        }

        val flareSolverrConfig = currentFlareSolverrConfigOrNull()
        val targetHost = runCatching { URI(resolvedUrl).host?.lowercase()?.trim() }.getOrNull().orEmpty()

        // Hosts that previously served a CF challenge go straight to FlareSolverr while within TTL:
        // the direct OkHttp fetch is doomed and typically burns ~5s waiting on the challenge page.
        if (flareSolverrConfig != null && isHostCfProtected(targetHost, checkedAt)) {
            return@withContext analyzeViaFlareSolverr(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                targetUrl = resolvedUrl,
                httpStatusCode = 0,
                panelType = initialPanel,
                checkedAt = checkedAt,
                customInStockKeyword = customInStockKeyword,
                customOutOfStockKeyword = customOutOfStockKeyword,
                flareSolverrConfig = flareSolverrConfig,
            )
        }

        val request = try {
            Request.Builder()
                .url(resolvedUrl)
                .get()
                .header("User-Agent", userAgentProvider())
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                )
                .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "no-cache")
                .build()
        } catch (t: Throwable) {
            return@withContext StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                finalUrl = resolvedUrl,
                panelType = initialPanel,
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "无效 URL",
                errorMessage = t.message ?: "链接解析失败",
                isTransientNetworkError = false,
                checkedAtEpochMillis = checkedAt,
            )
        }

        try {
            val fetched = executeFetchCancellable(request)
            val statusCode = fetched.statusCode
            val finalUrl = fetched.finalUrl
            val cfMitigated = fetched.cfMitigated
            val rawBody = fetched.body

            if (isCloudflareChallenge(statusCode, cfMitigated, rawBody)) {
                markHostCfProtected(targetHost, checkedAt)
                if (flareSolverrConfig != null) {
                    return@withContext analyzeViaFlareSolverr(
                        rawUrl = rawUrl,
                        resolvedOrderUrl = resolvedUrl,
                        targetUrl = finalUrl,
                        httpStatusCode = statusCode,
                        panelType = detectPanelFromUrls(resolvedUrl, finalUrl),
                        checkedAt = checkedAt,
                        customInStockKeyword = customInStockKeyword,
                        customOutOfStockKeyword = customOutOfStockKeyword,
                        flareSolverrConfig = flareSolverrConfig,
                    )
                }
                return@withContext StockAnalysisResult(
                    rawUrl = rawUrl,
                    resolvedOrderUrl = resolvedUrl,
                    finalUrl = finalUrl,
                    httpStatusCode = statusCode,
                    panelType = detectPanelFromUrls(resolvedUrl, finalUrl),
                    stockStatus = RestockStockStatus.CHECK_ERROR,
                    matchedSignal = "触发 Cloudflare 安全验证",
                    errorMessage = "站点触发 Cloudflare 验证 (HTTP $statusCode)，可在补货设置中配置 FlareSolverr 自动突破",
                    isTransientNetworkError = false,
                    checkedAtEpochMillis = checkedAt,
                )
            }

            validateHttpErrorStatus(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                finalUrl = finalUrl,
                statusCode = statusCode,
                checkedAt = checkedAt,
            )?.let { return@withContext it }

            analyzeHtml(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                finalUrl = finalUrl,
                html = rawBody,
                httpStatusCode = statusCode,
                customInStockKeyword = customInStockKeyword,
                customOutOfStockKeyword = customOutOfStockKeyword,
                checkedAtEpochMillis = checkedAt,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: SocketTimeoutException) {
            StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                finalUrl = resolvedUrl,
                panelType = initialPanel,
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "连接超时",
                errorMessage = "请求超时，商家站点响应较慢",
                isTransientNetworkError = true,
                checkedAtEpochMillis = checkedAt,
            )
        } catch (e: IOException) {
            StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                finalUrl = resolvedUrl,
                panelType = initialPanel,
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "网络异常",
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "网络连接失败",
                isTransientNetworkError = true,
                checkedAtEpochMillis = checkedAt,
            )
        } catch (t: Throwable) {
            StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedUrl,
                finalUrl = resolvedUrl,
                panelType = initialPanel,
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "检测失败",
                errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "页面解析异常",
                isTransientNetworkError = false,
                checkedAtEpochMillis = checkedAt,
            )
        }
    }

    /**
     * Pure HTML analyzer (deterministic and offline-testable) that identifies merchant panel type,
     * auto-extracts product title & price hint, and resolves stock availability.
     */
    fun analyzeHtml(
        rawUrl: String,
        resolvedOrderUrl: String = MerchantLinkDetector.resolveOrderUrl(rawUrl),
        finalUrl: String = resolvedOrderUrl,
        html: String,
        httpStatusCode: Int = 200,
        customInStockKeyword: String = "",
        customOutOfStockKeyword: String = "",
        checkedAtEpochMillis: Long = clockMillis(),
    ): StockAnalysisResult {
        val doc = Jsoup.parse(html, finalUrl.ifBlank { resolvedOrderUrl })
        val panelType = detectPanelType(
            resolvedUrl = resolvedOrderUrl,
            finalUrl = finalUrl,
            doc = doc,
            rawHtml = html,
        )
        val detectedTitle = extractProductTitle(doc, finalUrl.ifBlank { resolvedOrderUrl })
        val detectedPriceHint = extractPriceHint(doc)

        val evaluation = evaluateStockStatus(
            panelType = panelType,
            resolvedUrl = resolvedOrderUrl,
            finalUrl = finalUrl,
            doc = doc,
            customInStockKeyword = customInStockKeyword,
            customOutOfStockKeyword = customOutOfStockKeyword,
        )

        return StockAnalysisResult(
            rawUrl = rawUrl,
            resolvedOrderUrl = resolvedOrderUrl,
            finalUrl = finalUrl,
            httpStatusCode = httpStatusCode,
            panelType = panelType,
            stockStatus = evaluation.status,
            detectedTitle = detectedTitle,
            detectedPriceHint = detectedPriceHint,
            matchedSignal = evaluation.signal,
            errorMessage = null,
            isTransientNetworkError = false,
            checkedAtEpochMillis = checkedAtEpochMillis,
        )
    }

    /**
     * FlareSolverr fallback path for Cloudflare-protected merchant pages: delegates the
     * fetch to the user's self-hosted service and reuses the deterministic [analyzeHtml]
     * on the solved HTML (no on-device `cf_clearance` re-use, so UA/IP binding is moot).
     */
    private suspend fun analyzeViaFlareSolverr(
        rawUrl: String,
        resolvedOrderUrl: String,
        targetUrl: String,
        httpStatusCode: Int,
        panelType: MerchantPanelType,
        checkedAt: Long,
        customInStockKeyword: String,
        customOutOfStockKeyword: String,
        flareSolverrConfig: FlareSolverrConfig,
    ): StockAnalysisResult {
        val client = flareSolverrClient
            ?: return cfChallengeErrorResult(rawUrl, resolvedOrderUrl, targetUrl, httpStatusCode, panelType, checkedAt, failure = null)

        return when (val outcome = client.requestGet(flareSolverrConfig, targetUrl)) {
            is FlareSolverrOutcome.Solved -> {
                val solvedUrl = outcome.finalUrl.ifBlank { targetUrl }
                val solvedStatus = outcome.httpStatusCode
                val resolvedPanel = detectPanelFromUrls(resolvedOrderUrl, solvedUrl)

                if (isCloudflareChallenge(solvedStatus, "", outcome.html)) {
                    return cfChallengeErrorResult(
                        rawUrl = rawUrl,
                        resolvedOrderUrl = resolvedOrderUrl,
                        targetUrl = solvedUrl,
                        httpStatusCode = solvedStatus,
                        panelType = resolvedPanel,
                        checkedAt = checkedAt,
                        failure = FlareSolverrOutcome.Failure(
                            reason = "解盾后页面仍停留在 Cloudflare 安全验证 (HTTP $solvedStatus)",
                            isTransient = false,
                        ),
                    )
                }

                validateHttpErrorStatus(
                    rawUrl = rawUrl,
                    resolvedOrderUrl = resolvedOrderUrl,
                    finalUrl = solvedUrl,
                    statusCode = solvedStatus,
                    checkedAt = checkedAt,
                )?.let { errorResult ->
                    return errorResult.copy(usedFlareSolverr = true)
                }

                val analysis = analyzeHtml(
                    rawUrl = rawUrl,
                    resolvedOrderUrl = resolvedOrderUrl,
                    finalUrl = solvedUrl,
                    html = outcome.html,
                    httpStatusCode = solvedStatus,
                    customInStockKeyword = customInStockKeyword,
                    customOutOfStockKeyword = customOutOfStockKeyword,
                    checkedAtEpochMillis = checkedAt,
                )
                analysis.copy(
                    matchedSignal = "CF 盾已突破（FlareSolverr）· ${analysis.matchedSignal}",
                    usedFlareSolverr = true,
                )
            }
            is FlareSolverrOutcome.Failure -> cfChallengeErrorResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedOrderUrl,
                targetUrl = targetUrl,
                httpStatusCode = httpStatusCode,
                panelType = panelType,
                checkedAt = checkedAt,
                failure = outcome,
            )
        }
    }

    private data class FetchedHttpPage(
        val statusCode: Int,
        val finalUrl: String,
        val cfMitigated: String,
        val body: String,
    )

    private suspend fun executeFetchCancellable(request: Request): FetchedHttpPage =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val fetched = response.use { resp ->
                            FetchedHttpPage(
                                statusCode = resp.code,
                                finalUrl = resp.request.url.toString(),
                                cfMitigated = resp.header("cf-mitigated").orEmpty(),
                                body = readBoundedBody(resp),
                            )
                        }
                        if (continuation.isActive) {
                            continuation.resume(fetched)
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(t)
                        }
                    }
                }
            })
        }

    private fun validateHttpErrorStatus(
        rawUrl: String,
        resolvedOrderUrl: String,
        finalUrl: String,
        statusCode: Int,
        checkedAt: Long,
    ): StockAnalysisResult? {
        if (statusCode == 429) {
            return StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedOrderUrl,
                finalUrl = finalUrl,
                httpStatusCode = statusCode,
                panelType = detectPanelFromUrls(resolvedOrderUrl, finalUrl),
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "请求频率受限 (HTTP 429)",
                errorMessage = "商家限流 (HTTP 429)，建议调大轮询间隔",
                isTransientNetworkError = true,
                checkedAtEpochMillis = checkedAt,
            )
        }
        if (statusCode in 500..599) {
            return StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedOrderUrl,
                finalUrl = finalUrl,
                httpStatusCode = statusCode,
                panelType = detectPanelFromUrls(resolvedOrderUrl, finalUrl),
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "商家服务器异常 (HTTP $statusCode)",
                errorMessage = "目标服务器暂不可用 (HTTP $statusCode)",
                isTransientNetworkError = true,
                checkedAtEpochMillis = checkedAt,
            )
        }
        if (statusCode >= 400) {
            return StockAnalysisResult(
                rawUrl = rawUrl,
                resolvedOrderUrl = resolvedOrderUrl,
                finalUrl = finalUrl,
                httpStatusCode = statusCode,
                panelType = detectPanelFromUrls(resolvedOrderUrl, finalUrl),
                stockStatus = RestockStockStatus.CHECK_ERROR,
                matchedSignal = "HTTP $statusCode 错误",
                errorMessage = "页面访问失败 (HTTP $statusCode)",
                isTransientNetworkError = false,
                checkedAtEpochMillis = checkedAt,
            )
        }
        return null
    }

    private fun cfChallengeErrorResult(
        rawUrl: String,
        resolvedOrderUrl: String,
        targetUrl: String,
        httpStatusCode: Int,
        panelType: MerchantPanelType,
        checkedAt: Long,
        failure: FlareSolverrOutcome.Failure?,
    ): StockAnalysisResult = StockAnalysisResult(
        rawUrl = rawUrl,
        resolvedOrderUrl = resolvedOrderUrl,
        finalUrl = targetUrl,
        httpStatusCode = httpStatusCode,
        panelType = panelType,
        stockStatus = RestockStockStatus.CHECK_ERROR,
        matchedSignal = "触发 Cloudflare 安全验证",
        errorMessage = if (failure != null) {
            "Cloudflare 验证突破失败：${failure.reason}"
        } else {
            "站点触发 Cloudflare 验证，可在补货设置中配置 FlareSolverr 自动突破"
        },
        isTransientNetworkError = failure?.isTransient ?: false,
        checkedAtEpochMillis = checkedAt,
    )

    private suspend fun currentFlareSolverrConfigOrNull(): FlareSolverrConfig? {
        val provider = settingsProvider ?: return null
        if (flareSolverrClient == null) return null
        val config = runCatching { provider().flareSolverr }.getOrNull() ?: return null
        return config.takeIf { it.isConfigured }?.normalized()
    }

    private data class StockEvaluation(
        val status: RestockStockStatus,
        val signal: String,
    )

    private fun evaluateStockStatus(
        panelType: MerchantPanelType,
        resolvedUrl: String,
        finalUrl: String,
        doc: Document,
        customInStockKeyword: String,
        customOutOfStockKeyword: String,
    ): StockEvaluation {
        // Clone and strip non-visible script/style tags so inline JS error strings never false-trigger.
        val cleanedDoc = doc.clone()
        cleanedDoc.select("script, style, noscript, template, svg").remove()

        val fullVisibleText = collectVisibleTextWithInputValues(cleanedDoc.body())

        // ------------------------------------------------------------------
        // 1. Custom User Keyword Overrides (Highest Priority)
        // ------------------------------------------------------------------
        val customOosTerms = parseCustomKeywords(customOutOfStockKeyword)
        val customInTerms = parseCustomKeywords(customInStockKeyword)

        if (customOosTerms.isNotEmpty()) {
            val matchedOos = customOosTerms.firstOrNull { term ->
                fullVisibleText.contains(term, ignoreCase = true)
            }
            if (matchedOos != null) {
                return StockEvaluation(
                    status = RestockStockStatus.OUT_OF_STOCK,
                    signal = "命中自定义缺货词：$matchedOos",
                )
            }
        }

        if (customInTerms.isNotEmpty()) {
            val matchedIn = customInTerms.firstOrNull { term ->
                fullVisibleText.contains(term, ignoreCase = true)
            }
            return if (matchedIn != null) {
                StockEvaluation(
                    status = RestockStockStatus.IN_STOCK,
                    signal = "命中自定义有货词：$matchedIn",
                )
            } else {
                StockEvaluation(
                    status = RestockStockStatus.OUT_OF_STOCK,
                    signal = "未命中自定义有货词：${customInTerms.joinToString(" / ")}",
                )
            }
        }

        // ------------------------------------------------------------------
        // 2. Final URL Redirect Check (e.g. WHMCS redirect to outofstock)
        // ------------------------------------------------------------------
        val finalUrlLower = finalUrl.lowercase()
        if (finalUrlLower.contains("outofstock") || finalUrlLower.contains("out-of-stock")) {
            return StockEvaluation(
                status = RestockStockStatus.OUT_OF_STOCK,
                signal = "页面已跳转至缺货提示链接",
            )
        }

        // Narrow scope to main product container when present to avoid navbar/footer noise.
        val focusRoot = selectFocusContainer(cleanedDoc, panelType)
        val focusText = collectVisibleTextWithInputValues(focusRoot)
        val sanitizedFocusText = stripNegatedOutOfStockPhrases(focusText)

        // ------------------------------------------------------------------
        // 3. Dedicated OOS Alert / Badge / Element Inspection
        // ------------------------------------------------------------------
        val oosAlertSignal = findOutOfStockElementSignal(focusRoot)
        if (oosAlertSignal != null) {
            return StockEvaluation(
                status = RestockStockStatus.OUT_OF_STOCK,
                signal = oosAlertSignal,
            )
        }

        // Check explicit numeric stock counts first (`0 Available` vs `12 Available`)
        val stockCountMatch = extractExplicitStockCount(sanitizedFocusText)
        if (stockCountMatch != null) {
            if (stockCountMatch.count == 0) {
                return StockEvaluation(
                    status = RestockStockStatus.OUT_OF_STOCK,
                    signal = "检测到库存为 0（${stockCountMatch.rawPhrase}）",
                )
            }
        }

        // Check explicit OOS phrases in focused main content
        val matchedOosPhrase = findFirstOutOfStockKeyword(sanitizedFocusText)
        if (matchedOosPhrase != null) {
            return StockEvaluation(
                status = RestockStockStatus.OUT_OF_STOCK,
                signal = "命中缺货标识：$matchedOosPhrase",
            )
        }

        // ------------------------------------------------------------------
        // 4. Purchase Button State (Disabled vs Enabled Order CTA)
        // ------------------------------------------------------------------
        val ctaInspection = inspectPurchaseButtons(focusRoot)
        if (ctaInspection.hasAnyPurchaseButton &&
            ctaInspection.enabledPurchaseButtons.isEmpty() &&
            ctaInspection.disabledPurchaseButtons.isNotEmpty()
        ) {
            val label = ctaInspection.disabledPurchaseButtons.first()
            return StockEvaluation(
                status = RestockStockStatus.OUT_OF_STOCK,
                signal = "购买按钮已禁用：$label",
            )
        }

        // ------------------------------------------------------------------
        // 5. Panel-Specific & General In-Stock Signals
        // ------------------------------------------------------------------
        if (panelType == MerchantPanelType.WHMCS) {
            val whmcsConfigForm = focusRoot.selectFirst(
                "#frmConfigureProduct, form[action*=cart.php], #btnCompleteProductConfig",
            )
            val hasBillingCycleSelect = focusRoot.selectFirst("select[name=billingcycle], #inputBillingcycle") != null
            val completeBtn = focusRoot.selectFirst("#btnCompleteProductConfig, #btnCheckout")
            val completeBtnDisabled = completeBtn != null && isElementDisabled(completeBtn)
            if (completeBtnDisabled) {
                return StockEvaluation(
                    status = RestockStockStatus.OUT_OF_STOCK,
                    signal = "WHMCS 下单按钮不可用",
                )
            }
            if ((whmcsConfigForm != null && hasBillingCycleSelect) ||
                (completeBtn != null && !completeBtnDisabled)
            ) {
                return StockEvaluation(
                    status = RestockStockStatus.IN_STOCK,
                    signal = "WHMCS 检测到可配置下单表单",
                )
            }
        }

        if (stockCountMatch != null && stockCountMatch.count > 0) {
            return StockEvaluation(
                status = RestockStockStatus.IN_STOCK,
                signal = "检测到可用库存：${stockCountMatch.rawPhrase}",
            )
        }

        if (ctaInspection.enabledPurchaseButtons.isNotEmpty()) {
            val label = ctaInspection.enabledPurchaseButtons.first()
            return StockEvaluation(
                status = RestockStockStatus.IN_STOCK,
                signal = "检测到可点击下单按钮：$label",
            )
        }

        val activeOrderForm = hasActiveOrderForm(focusRoot)
        if (activeOrderForm) {
            return StockEvaluation(
                status = RestockStockStatus.IN_STOCK,
                signal = "${panelType.displayName} 检测到活跃订购表单",
            )
        }

        val matchedInPhrase = findFirstInStockKeyword(sanitizedFocusText)
        if (matchedInPhrase != null) {
            return StockEvaluation(
                status = RestockStockStatus.IN_STOCK,
                signal = "命中现货标识：$matchedInPhrase",
            )
        }

        // Last fallback: check broader body text for OOS in case notice was rendered outside focus container
        val broaderOos = findFirstOutOfStockKeyword(stripNegatedOutOfStockPhrases(fullVisibleText))
        if (broaderOos != null) {
            return StockEvaluation(
                status = RestockStockStatus.OUT_OF_STOCK,
                signal = "页面提示缺货：$broaderOos",
            )
        }

        return StockEvaluation(
            status = RestockStockStatus.UNKNOWN,
            signal = "未识别到明确库存标识，建议配置自定义关键词",
        )
    }

    private fun selectFocusContainer(doc: Document, panelType: MerchantPanelType): Element {
        val preferredSelectors = when (panelType) {
            MerchantPanelType.WHMCS -> listOf(
                "#order-standard_cart",
                "#order-boxes",
                "#order-modern",
                "#order-pure_comparison",
                "#frmConfigureProduct",
                "#main-body",
                "main",
            )
            MerchantPanelType.HOSTBILL -> listOf(
                "#orderpage",
                ".hb-order",
                "#cart",
                "main",
            )
            MerchantPanelType.VIRTFUSION -> listOf(
                ".vf-order",
                "#app main",
                "main",
            )
            MerchantPanelType.SOLUSVM -> listOf(
                ".solus-order",
                ".order-container",
                "main",
            )
            MerchantPanelType.GENERIC -> listOf(
                "main",
                "#main",
                "#main-body",
                ".product-detail",
                ".product-page",
                ".order-container",
                "#order-boxes",
            )
        }

        for (selector in preferredSelectors) {
            val found = doc.selectFirst(selector)
            if (found != null && found.text().trim().length >= 8) {
                return found
            }
        }

        val bodyClone = (doc.body() ?: doc).clone()
        bodyClone.select("header, nav, footer, .navbar, .site-header, .site-footer").remove()
        return if (bodyClone.text().trim().length >= 8) bodyClone else (doc.body() ?: doc)
    }

    private fun findOutOfStockElementSignal(root: Element): String? {
        // Explicit OOS containers/badges/attributes
        val explicitOosEls = root.select(
            ".out-of-stock, .outofstock, .sold-out, .soldout, .stock-empty, [data-stock=0], [data-in-stock=false]",
        )
        for (el in explicitOosEls) {
            if (isElementHidden(el)) continue
            val text = el.text().trim()
            if (text.isNotBlank()) {
                return "检测到缺货元素：${text.take(40)}"
            }
            if (el.attr("data-stock") == "0" || el.attr("data-in-stock").equals("false", ignoreCase = true)) {
                return "检测到库存属性为 0"
            }
        }

        // WHMCS / Bootstrap alert banners & qty badges
        val alertEls = root.select(
            ".alert-danger, .alert-warning, .errorbox, .qty, .stock-status, .availability, .product-status",
        )
        for (el in alertEls) {
            if (isElementHidden(el)) continue
            val text = stripNegatedOutOfStockPhrases(el.text().trim())
            val oosKw = findFirstOutOfStockKeyword(text)
            if (oosKw != null) {
                return "提示栏命中缺货：$oosKw"
            }
            val count = extractExplicitStockCount(text)
            if (count != null && count.count == 0) {
                return "库存提示为 0（${count.rawPhrase}）"
            }
        }

        return null
    }

    private data class PurchaseCtaInspection(
        val enabledPurchaseButtons: List<String>,
        val disabledPurchaseButtons: List<String>,
    ) {
        val hasAnyPurchaseButton: Boolean
            get() = enabledPurchaseButtons.isNotEmpty() || disabledPurchaseButtons.isNotEmpty()
    }

    private fun inspectPurchaseButtons(root: Element): PurchaseCtaInspection {
        val enabled = mutableListOf<String>()
        val disabled = mutableListOf<String>()

        val candidates = root.select(
            "button, input[type=submit], input[type=button], a.btn, a.button, a[class*=btn], a[href*=cart.php?a=add], a[href*=checkout], [role=button]",
        )
        for (el in candidates) {
            if (isElementHidden(el)) continue
            val label = when {
                el.tagName().equals("input", ignoreCase = true) -> el.attr("value").trim()
                else -> el.text().trim()
            }.ifBlank { el.attr("aria-label").trim() }
            if (label.isBlank() || label.length > 60) continue

            val lower = label.lowercase()
            // Ignore non-checkout navigation actions such as "Continue Shopping" or "Back"
            if (IGNORED_BUTTON_PHRASES.any { lower.contains(it) }) continue

            val isOosButtonText = OOS_BUTTON_PHRASES.any { lower.contains(it) }
            val isPurchaseCta = PURCHASE_CTA_PHRASES.any { lower.contains(it) } ||
                el.id().equals("btnCompleteProductConfig", ignoreCase = true) ||
                el.id().equals("btnCheckout", ignoreCase = true) ||
                el.hasClass("btn-order-now") ||
                el.hasClass("order-button") ||
                (lower == "continue" && isSubmitInsideOrderForm(el))

            if (isOosButtonText) {
                disabled.add(label)
            } else if (isPurchaseCta) {
                if (isElementDisabled(el)) {
                    disabled.add(label)
                } else {
                    enabled.add(label)
                }
            }
        }

        return PurchaseCtaInspection(
            enabledPurchaseButtons = enabled,
            disabledPurchaseButtons = disabled,
        )
    }

    private fun isSubmitInsideOrderForm(el: Element): Boolean {
        val isSubmit = el.tagName().equals("button", ignoreCase = true) &&
            !el.attr("type").equals("button", ignoreCase = true) ||
            el.attr("type").equals("submit", ignoreCase = true)
        if (!isSubmit) return false
        var parent = el.parent()
        while (parent != null) {
            if (parent.tagName().equals("form", ignoreCase = true)) {
                val action = parent.attr("action").lowercase()
                val id = parent.id().lowercase()
                if (id.contains("configure") || id.contains("order") || id.contains("cart") ||
                    action.contains("cart") || action.contains("order") || action.contains("checkout") ||
                    parent.selectFirst("select[name=billingcycle], input[name=pid]") != null
                ) {
                    return true
                }
            }
            parent = parent.parent()
        }
        return false
    }

    private fun hasActiveOrderForm(root: Element): Boolean {
        val forms = root.select("form")
        for (form in forms) {
            val action = form.attr("action").lowercase()
            val id = form.id().lowercase()
            val isOrderForm = id.contains("configure") ||
                id.contains("order") ||
                id.contains("checkout") ||
                action.contains("cart") ||
                action.contains("order") ||
                action.contains("checkout") ||
                form.selectFirst("select[name=billingcycle], input[name=pid], input[name=hostname]") != null
            if (!isOrderForm) continue
            val submitBtn = form.selectFirst("button[type=submit], input[type=submit], button:not([type])")
            if (submitBtn != null && !isElementDisabled(submitBtn)) {
                return true
            }
        }
        return false
    }

    private fun isElementDisabled(el: Element): Boolean {
        if (el.hasAttr("disabled")) return true
        if (el.attr("aria-disabled").equals("true", ignoreCase = true)) return true
        val classes = el.classNames().map { it.lowercase() }
        return classes.any { cls ->
            cls == "disabled" ||
                cls.contains("btn-disabled") ||
                cls.contains("unavailable") ||
                cls.contains("out-of-stock") ||
                cls.contains("sold-out")
        }
    }

    private fun isElementHidden(el: Element): Boolean {
        if (el.hasAttr("hidden")) return true
        val style = el.attr("style").lowercase().replace(" ", "")
        if (style.contains("display:none") || style.contains("visibility:hidden")) return true
        val classes = el.classNames().map { it.lowercase() }
        return "d-none" in classes || "hidden" in classes || "sr-only" in classes
    }

    internal fun detectPanelType(
        resolvedUrl: String,
        finalUrl: String,
        doc: Document,
        rawHtml: String,
    ): MerchantPanelType {
        val urlPanel = detectPanelFromUrls(resolvedUrl, finalUrl)
        if (urlPanel != MerchantPanelType.GENERIC) return urlPanel

        val htmlLower = rawHtml.lowercase()
        val generator = doc.selectFirst("meta[name=generator]")?.attr("content").orEmpty().lowercase()

        return when {
            doc.selectFirst("#order-boxes, #order-standard_cart, #order-modern, #order-pure_comparison, #frmConfigureProduct") != null ||
                htmlLower.contains("whmcs") ||
                htmlLower.contains("templates/orderforms/") ||
                htmlLower.contains("cart.php?a=") ||
                generator.contains("whmcs") -> MerchantPanelType.WHMCS

            htmlLower.contains("virtfusion") ||
                doc.selectFirst(".vf-order, .vf-package, [data-virtfusion]") != null ||
                generator.contains("virtfusion") -> MerchantPanelType.VIRTFUSION

            htmlLower.contains("hostbill") ||
                doc.selectFirst(".hb-order, [data-hostbill]") != null ||
                htmlLower.contains("templates/orderpages/") -> MerchantPanelType.HOSTBILL

            htmlLower.contains("solusvm") ||
                htmlLower.contains("solus.io") ||
                htmlLower.contains("solusio") ||
                generator.contains("solus") -> MerchantPanelType.SOLUSVM

            else -> MerchantPanelType.GENERIC
        }
    }

    private fun detectPanelFromUrls(resolvedUrl: String, finalUrl: String): MerchantPanelType {
        val fromFinal = MerchantLinkDetector.inferPanelTypeFromUrl(finalUrl)
        if (fromFinal != MerchantPanelType.GENERIC) return fromFinal
        return MerchantLinkDetector.inferPanelTypeFromUrl(resolvedUrl)
    }

    internal fun extractProductTitle(doc: Document, url: String): String {
        val specificSelectors = listOf(
            ".product-info .product-title",
            ".product-info p.product-title",
            ".product-info strong",
            ".order-summary .product-name",
            ".summary-container .product-name",
            "#frmConfigureProduct .product-title",
            ".product_title",
            "[class*=product-title]",
            "[class*=product-name]",
            "[class*=plan-name]",
            ".package-name",
            "h1",
            "h2",
        )

        for (selector in specificSelectors) {
            val candidate = doc.selectFirst(selector)?.text()?.trim().orEmpty()
            val cleaned = cleanTitleCandidate(candidate)
            if (cleaned.isNotEmpty() && !isGenericHeading(cleaned)) {
                return cleaned.take(96)
            }
        }

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val cleanedOg = cleanTitleCandidate(ogTitle)
        if (cleanedOg.isNotEmpty() && !isGenericHeading(cleanedOg)) {
            return cleanedOg.take(96)
        }

        val rawTitle = doc.title().trim()
        val cleanedDocTitle = cleanTitleCandidate(rawTitle)
        if (cleanedDocTitle.isNotEmpty() && !isGenericHeading(cleanedDocTitle)) {
            return cleanedDocTitle.take(96)
        }

        val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        return if (host.isNotBlank()) "$host VPS" else "VPS 商品监控"
    }

    internal fun extractPriceHint(doc: Document): String {
        val priceSelectors = listOf(
            "select[name=billingcycle] option[selected]",
            "select[name=billingcycle] option",
            "#producttotal .amt",
            "#producttotal",
            ".total-due-today .amt",
            ".product-pricing",
            ".price",
            "[class*=price]",
            ".order-summary",
        )

        for (selector in priceSelectors) {
            val elements = doc.select(selector)
            for (el in elements) {
                val text = el.text().trim()
                if (text.isBlank() || text.length > 180) continue
                val matched = matchPriceInText(text)
                if (matched != null) return matched
            }
        }

        val bodySample = doc.body()?.text().orEmpty().take(3_000)
        return matchPriceInText(bodySample).orEmpty()
    }

    private fun matchPriceInText(text: String): String? {
        val match = PRICE_REGEX.find(text) ?: return null
        val symbol = match.groupValues[1].trim()
        val amount = match.groupValues[2].trim().ifEmpty { match.groupValues[4].trim() }
        val currencyCode = match.groupValues[3].trim().ifEmpty { match.groupValues[5].trim() }
        val cycleRaw = match.groupValues[6].trim().lowercase()
        if (amount.isEmpty()) return null

        val prefix = when {
            symbol.isNotEmpty() -> if (symbol == "￥") "¥" else symbol
            currencyCode.equals("USD", ignoreCase = true) -> "$"
            currencyCode.equals("EUR", ignoreCase = true) -> "€"
            currencyCode.equals("GBP", ignoreCase = true) -> "£"
            currencyCode.equals("CNY", ignoreCase = true) || currencyCode.equals("RMB", ignoreCase = true) -> "¥"
            currencyCode.isNotEmpty() -> "$currencyCode "
            else -> ""
        }
        val suffix = when {
            cycleRaw.contains("annu") || cycleRaw.contains("year") || cycleRaw.contains("yr") || cycleRaw.contains("年") -> "/年"
            cycleRaw.contains("month") || cycleRaw.contains("mo") || cycleRaw.contains("月") -> "/月"
            cycleRaw.contains("quarter") || cycleRaw.contains("季") -> "/季"
            cycleRaw.contains("semi") || cycleRaw.contains("半年") -> "/半年"
            cycleRaw.contains("bienn") -> "/2年"
            cycleRaw.contains("trienn") -> "/3年"
            else -> ""
        }
        val codeSuffix = if (symbol.isNotEmpty() && currencyCode.isNotEmpty() && suffix.isEmpty()) {
            " ${currencyCode.uppercase()}"
        } else {
            ""
        }
        return "$prefix$amount$codeSuffix$suffix".trim()
    }

    private fun cleanTitleCandidate(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = raw.replace(Regex("""\s+"""), " ").trim()
        for (suffixRegex in TITLE_NOISE_SUFFIXES) {
            cleaned = suffixRegex.replace(cleaned, "").trim()
        }
        return cleaned.trim('-', '|', '·', '—', ' ')
    }

    private fun isGenericHeading(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in GENERIC_HEADINGS || lower.length <= 1
    }

    private data class StockCountMatch(
        val count: Int,
        val rawPhrase: String,
    )

    private fun extractExplicitStockCount(text: String): StockCountMatch? {
        for (regex in STOCK_COUNT_REGEXES) {
            val match = regex.find(text)
            if (match != null) {
                val number = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                if (number != null) {
                    return StockCountMatch(count = number, rawPhrase = match.value.trim())
                }
            }
        }
        return null
    }

    private fun findFirstOutOfStockKeyword(text: String): String? {
        for (kw in OUT_OF_STOCK_KEYWORDS) {
            if (text.contains(kw, ignoreCase = true)) {
                return kw
            }
        }
        return null
    }

    private fun findFirstInStockKeyword(text: String): String? {
        for (kw in IN_STOCK_KEYWORDS) {
            if (text.contains(kw, ignoreCase = true)) {
                return kw
            }
        }
        return null
    }

    private fun stripNegatedOutOfStockPhrases(text: String): String {
        var result = text
        for (neg in NEGATED_OOS_PHRASES) {
            result = result.replace(neg, "", ignoreCase = true)
        }
        return result
    }

    private fun parseCustomKeywords(raw: String): List<String> =
        raw.split(CUSTOM_KEYWORD_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun collectVisibleTextWithInputValues(root: Element?): String {
        if (root == null) return ""
        val sb = StringBuilder(root.text())
        for (input in root.select("input[type=submit], input[type=button]")) {
            val value = input.attr("value").trim()
            if (value.isNotEmpty()) {
                sb.append(' ').append(value)
            }
        }
        return sb.toString()
    }

    private fun isCloudflareChallenge(
        statusCode: Int,
        cfMitigatedHeader: String,
        body: String,
    ): Boolean {
        if (cfMitigatedHeader.equals("challenge", ignoreCase = true)) return true
        val lower = body.lowercase()
        val hasStrongInterstitialMarker = lower.contains("cdn-cgi/challenge-platform") ||
            lower.contains("attention required! | cloudflare") ||
            lower.contains("<title>just a moment...</title>")
        if (statusCode == 200) {
            return hasStrongInterstitialMarker
        }
        if (statusCode != 403 && statusCode != 503) return false
        return hasStrongInterstitialMarker ||
            lower.contains("just a moment...") ||
            lower.contains("cf-turnstile")
    }

    private fun readBoundedBody(response: okhttp3.Response): String {
        val body = response.body ?: return ""
        val source = body.source()
        source.request(MAX_BODY_BYTES)
        val buffer = source.buffer.clone()
        val byteCount = minOf(buffer.size, MAX_BODY_BYTES)
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return buffer.readString(byteCount, charset)
    }

    companion object {
        private const val MAX_BODY_BYTES = 1_500_000L
        internal const val CF_HOST_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L

        private val CUSTOM_KEYWORD_SPLIT_REGEX = Regex("""[,，;；\n\r]+""")

        // Require either a leading currency symbol (groups 1..3) or trailing currency code (groups 4..5),
        // followed by an optional billing cycle (group 6), so bare numbers like "2026" don't match.
        private val PRICE_REGEX = Regex(
            pattern = """(?:([\$€£¥￥])\s*(\d{1,5}(?:\.\d{1,2})?)(?:\s*(USD|EUR|GBP|CNY|RMB|HKD|JPY|CAD|AUD))?|(\d{1,5}(?:\.\d{1,2})?)\s*(USD|EUR|GBP|CNY|RMB|HKD|JPY|CAD|AUD))(?:\s*(?:/|per\s+)?(annually|annual|yearly|year|yr|monthly|month|mo|quarterly|quarter|semi-annually|biennially|triennially|年|月|季|半年))?""",
            option = RegexOption.IGNORE_CASE,
        )

        private val STOCK_COUNT_REGEXES = listOf(
            Regex("""\b(\d+)\s*(?:available|in\s+stock|left\s+in\s+stock|units?\s+available)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:available|stock|qty|quantity)\s*[:：]\s*(\d+)\b""", RegexOption.IGNORE_CASE),
            // Explicit Chinese stock prefixes (库存/可用库存/剩余库存/可售) exclude non-stock units like 天/元/GB/%
            Regex("""(?:可用库存|剩余库存|库存|可售)\s*[:：]?\s*(\d+)(?!\s*(?:天|日|小时|时|分|分钟|秒|周|月|年|元|块|刀|美元|美金|港币|港元|欧元|英镑|日元|CNY|USD|EUR|GBP|GB|TB|MB|KB|GiB|TiB|MiB|G|M|T|%|次|张|折))\s*(?:台|件|个|份|席|名额)?""", RegexOption.IGNORE_CASE),
            // Generic "剩余/仅剩" must carry an explicit inventory unit (台/件/份/席/名额) and not be preceded by coupon/balance/traffic terms
            Regex("""(?<!余额|优惠券|折扣券|代金券|活动|倒计时|流量|带宽|时间|天数|免费备份)(?:剩余|仅剩)\s*[:：]?\s*(\d+)\s*(?:台|件|份|席|名额)(?!\s*(?:天|日|小时|元|GB|TB|MB|%|次|张))"""),
            Regex("""(?<![.\d%])(\d+)\s*(?:台|件|个|份|席|名额)?\s*(?:可用|可售)"""),
        )

        private val OUT_OF_STOCK_KEYWORDS = listOf(
            "out of stock",
            "currently out of stock",
            "sold out",
            "0 available",
            "0 in stock",
            "no stock",
            "no nodes available",
            "currently unavailable",
            "temporarily unavailable",
            "out-of-stock",
            "sold-out",
            "notify me when available",
            "暂时缺货",
            "已售罄",
            "已售完",
            "已抢光",
            "已抢完",
            "库存不足",
            "库存为0",
            "库存为 0",
            "暂不可购买",
            "缺货",
            "售罄",
            "无货",
            "补货中",
            "到货通知",
        )

        private val NEGATED_OOS_PHRASES = listOf(
            "不缺货",
            "非缺货",
            "无缺货",
            "never out of stock",
            "not out of stock",
        )

        private val IN_STOCK_KEYWORDS = listOf(
            "in stock",
            "available now",
            "instant deployment",
            "现货",
            "有货",
            "库存充足",
        )

        private val PURCHASE_CTA_PHRASES = listOf(
            "order now",
            "buy now",
            "add to cart",
            "configure",
            "checkout",
            "deploy now",
            "deploy server",
            "create server",
            "purchase",
            "立即购买",
            "立即订购",
            "立即抢购",
            "加入购物车",
            "立即下单",
            "去结账",
            "立即开通",
            "配置产品",
            "继续下单",
        )

        private val OOS_BUTTON_PHRASES = listOf(
            "out of stock",
            "sold out",
            "unavailable",
            "缺货",
            "售罄",
            "无货",
            "已抢光",
            "已售完",
        )

        private val IGNORED_BUTTON_PHRASES = listOf(
            "continue shopping",
            "back to store",
            "view cart",
            "继续购物",
            "返回商店",
            "登录",
            "sign in",
            "login",
            "register",
        )

        private val GENERIC_HEADINGS = setOf(
            "shopping cart",
            "cart",
            "configure",
            "checkout",
            "out of stock",
            "client area",
            "order",
            "store",
            "购物车",
            "配置产品",
            "结账",
            "缺货",
            "产品列表",
        )

        private val TITLE_NOISE_SUFFIXES = listOf(
            Regex("""\s*[-|·—]\s*Shopping Cart.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[-|·—]\s*Client Area.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[-|·—]\s*WHMCS.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[-|·—]\s*购物车.*$"""),
            Regex("""^Shopping Cart\s*[-|·—]\s*""", RegexOption.IGNORE_CASE),
        )

        fun createDefaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
