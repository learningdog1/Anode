package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailUnreadPage
import com.nodeseek.app.domain.mail.SeekMailbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Isolated OpenAPI client for Seek Mail (`https://seek.li/openapi/v1`).
 *
 * Security & isolation guarantees:
 * - Strips all application/network interceptors and sets [CookieJar.NO_COOKIES] so NodeSeek forum
 *   cookies/CSRF headers and SafeLine `sl-session` cookies are never shared or leaked.
 * - Disables automatic redirects (`followRedirects(false)`, `followSslRedirects(false)`) and
 *   validates strict origin equality (`scheme`, `host`, `port`) before attaching the Bearer token.
 * - Parses polymorphic Go/OpenAPI error payloads (`{"error": {"code","message"}}` and `{"error": "..."}`)
 *   into dedicated [SeekMailException] instances without triggering NodeSeek Cloudflare challenges.
 */
class SeekMailApiClient(
    baseHttpClient: OkHttpClient = OkHttpClient(),
    val baseUrl: HttpUrl = DEFAULT_BASE_URL,
) {
    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://seek.li/openapi/v1".toHttpUrl()
        const val MAX_ATTACHMENT_BYTES: Long = 100L * 1024L * 1024L // 100 MB safety cap
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

        private fun isLoopbackHost(host: String): Boolean {
            val normalized = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
            return normalized in LOOPBACK_HOSTS
        }

        internal fun isAllowedOrigin(url: HttpUrl): Boolean =
            url.scheme.equals("https", ignoreCase = true) ||
                (url.scheme.equals("http", ignoreCase = true) && isLoopbackHost(url.host))
    }

    init {
        require(isAllowedOrigin(baseUrl)) {
            "Seek Mail baseUrl must use HTTPS or loopback HTTP, got: $baseUrl"
        }
    }

    private val json: Json = SeekMailJson

    private val httpClient: OkHttpClient = baseHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .build()

    private val _latestRateLimit = MutableStateFlow<SeekMailRateLimit?>(null)
    val latestRateLimit: StateFlow<SeekMailRateLimit?> = _latestRateLimit.asStateFlow()

    suspend fun getMailboxes(apiKey: String): List<SeekMailbox> {
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = buildEndpointUrl("mailboxes"),
            serializer = MailboxesResponseDto.serializer(),
        )
        return (dto.data ?: emptyList()).map { it.toDomain() }
    }

    suspend fun checkMailboxAvailable(apiKey: String, address: String): Boolean {
        val cleanAddress = address.trim()
        if (cleanAddress.isEmpty()) {
            throw SeekMailException(
                kind = SeekMailErrorKind.UNKNOWN,
                message = "请输入有效的邮箱地址",
            )
        }
        val dto = executeJsonPost(
            apiKey = apiKey,
            url = buildEndpointUrl(
                "mailboxes",
                "check",
                queryParams = listOf("address" to cleanAddress),
            ),
            body = EMPTY_BODY,
            serializer = CheckMailboxAvailableResponseDto.serializer(),
        )
        return dto.data?.available
            ?: throw SeekMailException(
                kind = SeekMailErrorKind.SERVER,
                message = "Seek 邮箱未返回可用的地址检查结果",
            )
    }

    suspend fun createMailbox(apiKey: String, address: String): SeekMailbox {
        val cleanAddress = address.trim()
        if (cleanAddress.isEmpty()) {
            throw SeekMailException(
                kind = SeekMailErrorKind.UNKNOWN,
                message = "请输入要创建的邮箱地址",
            )
        }
        val payload = json.encodeToString(
            CreateMailboxRequestDto.serializer(),
            CreateMailboxRequestDto(address = cleanAddress),
        )
        val dto = executeJsonPost(
            apiKey = apiKey,
            url = buildEndpointUrl("mailboxes"),
            body = payload.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE),
            serializer = MailboxResponseDto.serializer(),
        )
        return dto.data?.toDomain()
            ?: throw SeekMailException(
                kind = SeekMailErrorKind.SERVER,
                message = "创建邮箱失败：服务端返回空数据",
            )
    }

    suspend fun getMails(
        apiKey: String,
        mailboxId: Long? = null,
        search: String? = null,
        tagId: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): SeekMailPage {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val params = buildList {
            mailboxId?.takeIf { it > 0L }?.let { add("mailbox_id" to it.toString()) }
            search?.trim()?.takeIf { it.isNotEmpty() }?.let { add("search" to it) }
            tagId?.takeIf { it > 0L }?.let { add("tag_id" to it.toString()) }
            add("limit" to safeLimit.toString())
            add("offset" to safeOffset.toString())
        }
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = buildEndpointUrl("mails", queryParams = params),
            serializer = MailListResponseDto.serializer(),
        )
        return withContext(Dispatchers.Default) {
            val items = (dto.data ?: emptyList()).map { it.toDomainSummary() }
            val pageLimit = dto.pagination?.limit ?: safeLimit
            val pageOffset = dto.pagination?.offset ?: safeOffset
            val total = dto.pagination?.total ?: items.size
            val hasMore = dto.pagination != null &&
                items.isNotEmpty() &&
                (pageOffset + items.size) < total
            SeekMailPage(
                items = items,
                limit = pageLimit,
                offset = pageOffset,
                total = total,
                hasMore = hasMore,
            )
        }
    }

    suspend fun getUnreadMails(
        apiKey: String,
        mailboxId: Long? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): SeekMailUnreadPage {
        val safeLimit = limit.coerceIn(1, 100)
        val params = buildList {
            mailboxId?.takeIf { it > 0L }?.let { add("mailbox_id" to it.toString()) }
            add("limit" to safeLimit.toString())
            cursor?.trim()?.takeIf { it.isNotEmpty() }?.let { add("cursor" to it) }
        }
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = buildEndpointUrl("mails", "unread", queryParams = params),
            serializer = UnreadMailListResponseDto.serializer(),
        )
        return withContext(Dispatchers.Default) {
            val items = (dto.data ?: emptyList()).map { it.toDomainSummary() }
            SeekMailUnreadPage(
                items = items,
                limit = dto.page?.limit ?: safeLimit,
                hasMore = dto.page?.hasMore ?: false,
                nextCursor = dto.page?.nextCursor.orEmpty(),
            )
        }
    }

    suspend fun getMailDetail(apiKey: String, mailId: Long): SeekMailDetail {
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = buildEndpointUrl("mails", mailId.toString()),
            serializer = MailDetailResponseDto.serializer(),
        )
        return withContext(Dispatchers.Default) {
            dto.data?.toDomainDetail()
                ?: throw SeekMailException(
                    kind = SeekMailErrorKind.NOT_FOUND,
                    message = "邮件不存在或已被删除",
                )
        }
    }

    suspend fun getAttachments(apiKey: String, mailId: Long): List<SeekMailAttachment> {
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = buildEndpointUrl("mails", mailId.toString(), "attachments"),
            serializer = AttachmentsResponseDto.serializer(),
        )
        return (dto.data ?: emptyList()).map { it.toDomain(fallbackMailId = mailId) }
    }

    suspend fun downloadAttachmentBytes(
        apiKey: String,
        mailId: Long,
        attachmentId: String,
        maxBytes: Long = MAX_ATTACHMENT_BYTES,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        downloadAttachmentToStream(
            apiKey = apiKey,
            mailId = mailId,
            attachmentId = attachmentId,
            outputStream = out,
            maxBytes = maxBytes,
        )
        return out.toByteArray()
    }

    suspend fun downloadAttachmentToStream(
        apiKey: String,
        mailId: Long,
        attachmentId: String,
        outputStream: OutputStream,
        maxBytes: Long = MAX_ATTACHMENT_BYTES,
    ): Long {
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty() || cleanId == "." || cleanId == "..") {
            throw SeekMailException(
                kind = SeekMailErrorKind.NOT_FOUND,
                message = "附件标识无效",
            )
        }
        val request = buildAuthorizedRequest(
            apiKey = apiKey,
            url = buildEndpointUrl("mails", mailId.toString(), "attachments", cleanId),
            accept = "*/*",
        ).get().build()
        return executeStreamCall(
            request = request,
            outputStream = outputStream,
            maxBytes = maxBytes,
        )
    }

    suspend fun getTags(apiKey: String): List<SeekMailTag> {
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = buildEndpointUrl("tags"),
            serializer = TagsResponseDto.serializer(),
        )
        return (dto.data ?: emptyList()).map { it.toDomain() }
    }

    suspend fun getContacts(apiKey: String, limit: Int = 50): List<SeekMailContact> {
        val safeLimit = limit.coerceIn(1, 200)
        val dto = executeJsonGet(
            apiKey = apiKey,
            url = buildEndpointUrl(
                "contacts",
                queryParams = listOf("limit" to safeLimit.toString()),
            ),
            serializer = ContactsResponseDto.serializer(),
        )
        return (dto.data ?: emptyList()).map { it.toDomain() }
    }

    private suspend fun <T> executeJsonGet(
        apiKey: String,
        url: HttpUrl,
        serializer: KSerializer<T>,
    ): T {
        val request = buildAuthorizedRequest(
            apiKey = apiKey,
            url = url,
            accept = "application/json",
        ).get().build()
        val rawBody = executeStringCall(request)
        return decodeJson(serializer = serializer, rawBody = rawBody)
    }

    private suspend fun <T> executeJsonPost(
        apiKey: String,
        url: HttpUrl,
        body: RequestBody,
        serializer: KSerializer<T>,
    ): T {
        val request = buildAuthorizedRequest(
            apiKey = apiKey,
            url = url,
            accept = "application/json",
        ).post(body).build()
        val rawBody = executeStringCall(request)
        return decodeJson(serializer = serializer, rawBody = rawBody)
    }

    private suspend fun <T> decodeJson(
        serializer: KSerializer<T>,
        rawBody: String,
    ): T {
        return try {
            withContext(Dispatchers.Default) {
                json.decodeFromString(serializer, rawBody)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (s: SerializationException) {
            throw SeekMailException(
                kind = SeekMailErrorKind.SERVER,
                message = "Seek 邮箱响应数据解析失败",
                cause = s,
            )
        } catch (e: IllegalArgumentException) {
            throw SeekMailException(
                kind = SeekMailErrorKind.SERVER,
                message = "Seek 邮箱响应数据格式异常",
                cause = e,
            )
        }
    }

    private fun buildEndpointUrl(
        vararg segments: String,
        queryParams: List<Pair<String, String?>> = emptyList(),
    ): HttpUrl {
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
        for ((key, value) in queryParams) {
            if (value != null) {
                builder.addQueryParameter(key, value)
            }
        }
        return builder.build()
    }

    private fun buildAuthorizedRequest(
        apiKey: String,
        url: HttpUrl,
        accept: String,
    ): Request.Builder {
        val trimmedKey = requireValidApiKey(apiKey)
        verifySameOriginBeforeAuth(url)
        return Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("Cache-Control", "no-cache")
            .header("Authorization", "Bearer $trimmedKey")
    }

    private fun requireValidApiKey(apiKey: String): String {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            throw SeekMailException(
                kind = SeekMailErrorKind.MISSING_API_KEY,
                message = "请先配置 Seek 邮箱 API Key",
            )
        }
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            throw SeekMailException(
                kind = SeekMailErrorKind.INVALID_API_KEY_FORMAT,
                message = "Seek 邮箱 API Key 格式无效，不能包含空白或控制字符",
            )
        }
        return trimmed
    }

    private fun verifySameOriginBeforeAuth(url: HttpUrl) {
        val sameOrigin = isAllowedOrigin(url) &&
            url.scheme.equals(baseUrl.scheme, ignoreCase = true) &&
            url.host.equals(baseUrl.host, ignoreCase = true) &&
            url.port == baseUrl.port
        if (!sameOrigin) {
            throw SeekMailException(
                kind = SeekMailErrorKind.NETWORK,
                message = "安全校验失败：禁止向非预期源站发送 Seek 邮箱凭证",
            )
        }
    }

    private suspend fun executeStringCall(request: Request): String {
        return try {
            httpClient.newCall(request).awaitString { status, headers, body ->
                updateRateLimitFromHeaders(headers)
                validateHttpStatus(statusCode = status, rawBody = body)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (s: SeekMailException) {
            throw s
        } catch (io: IOException) {
            throw SeekMailException(
                kind = SeekMailErrorKind.NETWORK,
                message = "网络连接失败，请检查网络后重试",
                cause = io,
            )
        }
    }

    private suspend fun executeStreamCall(
        request: Request,
        outputStream: OutputStream,
        maxBytes: Long,
    ): Long {
        return try {
            httpClient.newCall(request).awaitStreamTo(
                outputStream = outputStream,
                maxBytes = maxBytes,
            ) { status, headers, errorBody ->
                updateRateLimitFromHeaders(headers)
                validateHttpStatus(statusCode = status, rawBody = errorBody)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (s: SeekMailException) {
            throw s
        } catch (io: IOException) {
            throw SeekMailException(
                kind = SeekMailErrorKind.NETWORK,
                message = "下载附件失败，请检查网络后重试",
                cause = io,
            )
        }
    }

    private fun updateRateLimitFromHeaders(headers: Headers) {
        val limit = headers["X-Ratelimit-Limit"]?.trim()?.toIntOrNull()
        val remaining = headers["X-Ratelimit-Remaining"]?.trim()?.toIntOrNull()
        val reset = headers["X-Ratelimit-Reset"]?.trim()?.toLongOrNull()
        if (limit != null && remaining != null) {
            _latestRateLimit.value = SeekMailRateLimit(
                limit = limit,
                remaining = remaining,
                resetEpochSeconds = reset ?: 0L,
            )
        }
    }

    private fun validateHttpStatus(statusCode: Int, rawBody: String) {
        if (statusCode in 200..299) return

        if (statusCode in 300..399) {
            throw SeekMailException(
                kind = SeekMailErrorKind.NETWORK,
                message = "安全校验失败：Seek 邮箱接口禁止重定向 ($statusCode)",
                httpStatus = statusCode,
            )
        }

        val parsedError = SeekMailErrorParser.parse(rawBody, json)
        val serverMessage = parsedError.message
        val errorCode = parsedError.code

        val kind = when (statusCode) {
            401 -> SeekMailErrorKind.UNAUTHORIZED
            403 -> SeekMailErrorKind.FORBIDDEN
            404 -> SeekMailErrorKind.NOT_FOUND
            429 -> SeekMailErrorKind.RATE_LIMITED
            400 -> if (errorCode.equals("EMAIL_ADDRESS_UNAVAILABLE", ignoreCase = true)) {
                SeekMailErrorKind.ADDRESS_UNAVAILABLE
            } else {
                SeekMailErrorKind.SERVER
            }
            in 500..599 -> SeekMailErrorKind.SERVER
            else -> SeekMailErrorKind.SERVER
        }

        val fallbackMessage = when (kind) {
            SeekMailErrorKind.UNAUTHORIZED -> "API Key 无效或已过期，请检查配置"
            SeekMailErrorKind.FORBIDDEN -> "当前 API Key 权限不足 ($statusCode)"
            SeekMailErrorKind.NOT_FOUND -> "请求的 Seek 邮箱资源不存在 ($statusCode)"
            SeekMailErrorKind.RATE_LIMITED -> "Seek 邮箱请求过于频繁，请稍后再试"
            SeekMailErrorKind.ADDRESS_UNAVAILABLE -> "该邮箱地址已被占用或不可用"
            SeekMailErrorKind.SERVER -> if (statusCode in 500..599) {
                "Seek 邮箱服务器暂时不可用 ($statusCode)"
            } else {
                "请求参数有误 ($statusCode)"
            }
            else -> "Seek 邮箱请求失败 ($statusCode)"
        }

        throw SeekMailException(
            kind = kind,
            message = serverMessage?.takeIf { it.isNotBlank() } ?: fallbackMessage,
            httpStatus = statusCode,
            errorCode = errorCode,
        )
    }
}

private suspend fun Call.awaitString(
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

private suspend fun Call.awaitStreamTo(
    outputStream: OutputStream,
    maxBytes: Long,
    validator: (statusCode: Int, headers: Headers, errorBody: String) -> Unit,
): Long = suspendCancellableCoroutine { cont ->
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
                        if (resp.code in 200..299) {
                            validator(resp.code, resp.headers, "")
                            val body = resp.body
                            val declaredLength = body?.contentLength() ?: -1L
                            if (declaredLength > maxBytes) {
                                throw SeekMailException(
                                    kind = SeekMailErrorKind.SERVER,
                                    message = "附件体积超过上限（最大 ${maxBytes / (1024L * 1024L)} MB）",
                                )
                            }
                            var totalWritten = 0L
                            if (body != null) {
                                val buffer = ByteArray(8192)
                                body.byteStream().use { input ->
                                    while (true) {
                                        if (cont.isCancelled) {
                                            throw CancellationException("Attachment download cancelled")
                                        }
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        totalWritten += read
                                        if (totalWritten > maxBytes) {
                                            throw SeekMailException(
                                                kind = SeekMailErrorKind.SERVER,
                                                message = "附件体积超过上限（最大 ${maxBytes / (1024L * 1024L)} MB）",
                                            )
                                        }
                                        outputStream.write(buffer, 0, read)
                                    }
                                }
                                outputStream.flush()
                            }
                            if (!cont.isCompleted) {
                                cont.resume(totalWritten)
                            }
                        } else {
                            val errorText = resp.body?.string().orEmpty()
                            validator(resp.code, resp.headers, errorText)
                            if (!cont.isCompleted) {
                                cont.resume(0L)
                            }
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
