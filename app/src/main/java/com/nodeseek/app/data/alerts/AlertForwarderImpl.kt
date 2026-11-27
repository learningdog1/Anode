package com.nodeseek.app.data.alerts

import com.nodeseek.app.domain.alerts.AlertForwarder
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.AlertHttpRequestSpec
import com.nodeseek.app.domain.alerts.AlertUrlValidator
import com.nodeseek.app.domain.alerts.BarkFormatter
import com.nodeseek.app.domain.alerts.ChannelDeliveryResult
import com.nodeseek.app.domain.alerts.ForwardableAlertEvent
import com.nodeseek.app.domain.alerts.TelegramBotFormatter
import com.nodeseek.app.domain.alerts.WebhookFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Production [AlertForwarder] dispatching alerts to Telegram Bot, Bark, and Webhook endpoints
 * over an isolated cookie-free [OkHttpClient] (`CookieJar.NO_COOKIES`, redirects disabled,
 * cleared interceptors) so NodeSeek session cookies and forwarding secrets never leak.
 */
class AlertForwarderImpl(
    private val store: AlertForwardingStore,
    baseHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val allowLoopbackTargets: Boolean = true,
    private val allowPrivateNetworkTargets: Boolean = true,
    private val dnsResolver: Dns = Dns.SYSTEM,
) : AlertForwarder {

    internal val httpClient: OkHttpClient = createIsolatedHttpClient(
        baseHttpClient = baseHttpClient,
        allowLoopbackTargets = allowLoopbackTargets,
        allowPrivateNetworkTargets = allowPrivateNetworkTargets,
        dnsResolver = dnsResolver,
    )

    override val configFlow: StateFlow<AlertForwardingConfig>
        get() = store.configFlow

    override suspend fun updateConfig(transform: (AlertForwardingConfig) -> AlertForwardingConfig) {
        store.updateConfig(transform)
    }

    override suspend fun forward(event: ForwardableAlertEvent): List<ChannelDeliveryResult> {
        val config = runCatching { store.ensureLoaded() }.getOrElse { store.configFlow.value }
        if (!config.enabled || event.category !in config.enabledEvents) {
            return emptyList()
        }
        return dispatchToEnabledChannels(config = config, event = event)
    }

    override suspend fun sendTestAlert(): List<ChannelDeliveryResult> {
        val config = runCatching { store.ensureLoaded() }.getOrElse { store.configFlow.value }
        val testEvent = ForwardableAlertEvent.ProbeRecovered(
            panelName = "Anode 测试告警",
            serverName = "Demo-VPS",
            probeKey = "anode-test-probe",
            flagEmoji = "🚀",
            occurredAtEpochMillis = clockMillis(),
        )
        return dispatchToEnabledChannels(config = config, event = testEvent)
    }

    private suspend fun dispatchToEnabledChannels(
        config: AlertForwardingConfig,
        event: ForwardableAlertEvent,
    ): List<ChannelDeliveryResult> = coroutineScope {
        val jobs = buildList {
            if (config.telegram.enabled) {
                add(
                    async {
                        val spec = TelegramBotFormatter.buildRequest(config.telegram, event)
                        if (spec == null) {
                            ChannelDeliveryResult(
                                channelName = CHANNEL_TELEGRAM,
                                success = false,
                                errorMessage = "Telegram Bot Token 或 Chat ID 无效",
                            )
                        } else {
                            executeJsonPost(CHANNEL_TELEGRAM, spec, validateTelegramOk = true)
                        }
                    },
                )
            }
            if (config.bark.enabled) {
                add(
                    async {
                        val spec = BarkFormatter.buildRequest(
                            config = config.bark,
                            event = event,
                            allowLoopback = allowLoopbackTargets,
                            allowPrivateNetwork = allowPrivateNetworkTargets,
                        )
                        if (spec == null) {
                            ChannelDeliveryResult(
                                channelName = CHANNEL_BARK,
                                success = false,
                                errorMessage = "Bark 服务器 URL 或 Device Key 无效",
                            )
                        } else {
                            executeJsonPost(CHANNEL_BARK, spec, validateTelegramOk = false)
                        }
                    },
                )
            }
            if (config.webhook.enabled) {
                add(
                    async {
                        val spec = WebhookFormatter.buildRequest(
                            config = config.webhook,
                            event = event,
                            allowLoopback = allowLoopbackTargets,
                            allowPrivateNetwork = allowPrivateNetworkTargets,
                        )
                        if (spec == null) {
                            ChannelDeliveryResult(
                                channelName = CHANNEL_WEBHOOK,
                                success = false,
                                errorMessage = "Webhook URL 必须是有效的 http/https 地址",
                            )
                        } else {
                            executeJsonPost(CHANNEL_WEBHOOK, spec, validateTelegramOk = false)
                        }
                    },
                )
            }
        }
        jobs.awaitAll()
    }

    private suspend fun executeJsonPost(
        channelName: String,
        spec: AlertHttpRequestSpec,
        validateTelegramOk: Boolean,
    ): ChannelDeliveryResult = withContext(ioDispatcher) {
        try {
            val builder = Request.Builder()
                .url(spec.url)
                .post(spec.jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            spec.headers.forEach { (k, v) ->
                builder.header(k, v)
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                val status = response.code
                val bodyStr = runCatching { response.body.string() }.getOrDefault("")
                if (!response.isSuccessful) {
                    return@withContext ChannelDeliveryResult(
                        channelName = channelName,
                        success = false,
                        httpStatus = status,
                        errorMessage = extractErrorDetail(bodyStr) ?: "HTTP $status",
                    )
                }
                if (validateTelegramOk && bodyStr.isNotBlank()) {
                    val obj = runCatching { Json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                    val okFlag = obj?.get("ok")?.jsonPrimitive?.booleanOrNull
                    if (okFlag == false) {
                        val desc = obj["description"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?: "Telegram API 返回失败"
                        return@withContext ChannelDeliveryResult(
                            channelName = channelName,
                            success = false,
                            httpStatus = status,
                            errorMessage = desc,
                        )
                    }
                }
                ChannelDeliveryResult(
                    channelName = channelName,
                    success = true,
                    httpStatus = status,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ChannelDeliveryResult(
                channelName = channelName,
                success = false,
                errorMessage = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName,
            )
        }
    }

    private fun extractErrorDetail(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val jsonDetail = runCatching {
            val obj = Json.parseToJsonElement(trimmed).jsonObject
            obj["description"]?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return jsonDetail?.takeIf { it.isNotBlank() } ?: trimmed.take(120)
    }

    companion object {
        const val CHANNEL_TELEGRAM = "Telegram Bot"
        const val CHANNEL_BARK = "Bark"
        const val CHANNEL_WEBHOOK = "Webhook"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun createIsolatedHttpClient(
            baseHttpClient: OkHttpClient,
            allowLoopbackTargets: Boolean = true,
            allowPrivateNetworkTargets: Boolean = true,
            dnsResolver: Dns = Dns.SYSTEM,
        ): OkHttpClient =
            baseHttpClient.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .apply {
                    interceptors().clear()
                    networkInterceptors().clear()
                    dns(
                        Dns { hostname ->
                            val resolved = dnsResolver.lookup(hostname)
                            if (
                                resolved.any { addr ->
                                    AlertUrlValidator.isIpAddressBlocked(
                                        address = addr,
                                        allowLoopback = allowLoopbackTargets,
                                        allowPrivateNetwork = allowPrivateNetworkTargets,
                                    )
                                }
                            ) {
                                throw UnknownHostException("Blocked non-public alert target host: $hostname")
                            }
                            resolved
                        },
                    )
                }
                .build()
    }
}
