package com.nodeseek.app.data.update

import com.nodeseek.app.BuildConfig
import com.nodeseek.app.domain.update.AppReleaseAsset
import com.nodeseek.app.domain.update.AppReleaseInfo
import com.nodeseek.app.domain.update.AppVersionComparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name")
    val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("html_url")
    val htmlUrl: String = "",
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val id: Long = 0L,
    val name: String = "",
    @SerialName("content_type")
    val contentType: String = "",
    val state: String = "",
    val size: Long = 0L,
    val digest: String? = null,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
)

sealed interface FetchReleaseResponse {
    data class Success(
        val release: AppReleaseInfo?,
        val etag: String?,
    ) : FetchReleaseResponse

    data object NotModified : FetchReleaseResponse
}

class GitHubReleaseException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Isolated GitHub Releases REST & asset download client.
 *
 * Security & transport guarantees:
 * - Strips all forum interceptors and sets [CookieJar.NO_COOKIES] so NodeSeek cookies / CSRF
 *   tokens never leave the forum origin.
 * - Enables HTTPS redirects (`github.com` -> `release-assets.githubusercontent.com`) while
 *   validating every hop in a network interceptor against trusted GitHub origins (or loopback
 *   HTTP for tests).
 * - Verifies APK magic header (`PK\x03\x04`), file size, and SHA-256 digest during download.
 */
class GitHubReleaseClient(
    baseHttpClient: OkHttpClient = OkHttpClient(),
    val apiBaseUrl: HttpUrl = DEFAULT_API_BASE_URL,
    val owner: String = BuildConfig.GITHUB_OWNER,
    val repo: String = BuildConfig.GITHUB_REPO,
    private val userAgent: String = "Anode/${BuildConfig.VERSION_NAME} (Android)",
    private val supportedAbis: List<String> = emptyList(),
    private val allowLoopbackHttp: Boolean = false,
) {
    companion object {
        val DEFAULT_API_BASE_URL: HttpUrl = "https://api.github.com".toHttpUrl()
        private const val GITHUB_API_ACCEPT = "application/vnd.github+json"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val PROGRESS_CALLBACK_BYTES_THRESHOLD = 64L * 1024L
        private const val PROGRESS_CALLBACK_INTERVAL_MS = 100L
        private const val DOWNLOAD_BUFFER_SIZE = 8192

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
        private val DIGEST_SHA256_REGEX = Regex("""(?i)^sha256:([a-f0-9]{64})$""")
        private val APK_MAGIC_BYTES = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        private val ABI_TOKEN_ALIASES: Map<String, Set<String>> = mapOf(
            "arm64-v8a" to setOf("arm64-v8a", "arm64", "aarch64"),
            "armeabi-v7a" to setOf("armeabi-v7a", "armeabi", "armv7", "armv7a", "arm32"),
            "x86_64" to setOf("x86_64", "x64", "amd64"),
            "x86" to setOf("x86", "i686", "i386"),
        )
        private val ALL_KNOWN_ARCH_TOKENS: Set<String> =
            ABI_TOKEN_ALIASES.values.flatten().toSet()

        private fun isLoopbackHost(host: String): Boolean {
            val normalized = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
            return normalized in LOOPBACK_HOSTS
        }

        internal fun isTrustedGitHubUrl(
            url: HttpUrl,
            allowLoopbackHttp: Boolean = false,
        ): Boolean {
            val host = url.host.trim().lowercase(Locale.ROOT)
            if (url.scheme.equals("https", ignoreCase = true)) {
                return host == "api.github.com" ||
                    host == "github.com" ||
                    host.endsWith(".githubusercontent.com")
            }
            return allowLoopbackHttp &&
                url.scheme.equals("http", ignoreCase = true) &&
                isLoopbackHost(host)
        }

        internal fun sanitizeApkFileName(rawName: String): String {
            val baseName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = baseName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trimStart('.')
            val withPrefix = if (cleaned.isEmpty()) "Anode-update.apk" else cleaned
            return if (withPrefix.endsWith(".apk", ignoreCase = true)) {
                withPrefix
            } else {
                "$withPrefix.apk"
            }
        }
    }

    init {
        require(isTrustedGitHubUrl(apiBaseUrl, allowLoopbackHttp)) {
            "GitHub API baseUrl must use trusted HTTPS GitHub origin, got: $apiBaseUrl"
        }
        require(owner.isNotBlank() && repo.isNotBlank()) {
            "GitHub owner and repo must not be blank"
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val trustedOriginNetworkInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (!isTrustedGitHubUrl(req.url, allowLoopbackHttp)) {
            throw GitHubReleaseException("安全校验失败：拒绝访问非 GitHub 源站 (${req.url.host})")
        }
        chain.proceed(req)
    }

    private val apiClient: OkHttpClient = baseHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(20L, TimeUnit.SECONDS)
        .callTimeout(30L, TimeUnit.SECONDS)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .addNetworkInterceptor(trustedOriginNetworkInterceptor)
        .build()

    private val downloadClient: OkHttpClient = baseHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .callTimeout(0L, TimeUnit.MILLISECONDS)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .addNetworkInterceptor(trustedOriginNetworkInterceptor)
        .build()

    suspend fun fetchLatestRelease(
        includePrerelease: Boolean,
        cachedEtag: String? = null,
    ): FetchReleaseResponse {
        if (!includePrerelease) {
            val latestUrl = buildRepoApiUrl("releases", "latest")
            val response = executeApiGet(url = latestUrl, etag = cachedEtag, allowNotFound = true)
            if (response.notModified) {
                return FetchReleaseResponse.NotModified
            }
            if (response.statusCode == 200) {
                val dto = decodeSingleRelease(response.body)
                if (!dto.draft && !dto.prerelease && dto.tagName.isNotBlank()) {
                    return FetchReleaseResponse.Success(
                        release = dto.toDomain(),
                        etag = response.etag,
                    )
                }
            }
            // Fallback when repository has no /releases/latest (e.g., 404 or only pre-releases)
            return fetchFromReleasesList(
                includePrerelease = false,
                cachedEtag = if (response.statusCode == 404) null else cachedEtag,
            )
        }
        return fetchFromReleasesList(
            includePrerelease = true,
            cachedEtag = cachedEtag,
        )
    }

    private suspend fun fetchFromReleasesList(
        includePrerelease: Boolean,
        cachedEtag: String?,
    ): FetchReleaseResponse {
        val listUrl = buildRepoApiUrl("releases", queryParams = listOf("per_page" to "10"))
        val response = executeApiGet(url = listUrl, etag = cachedEtag, allowNotFound = true)
        if (response.notModified) {
            return FetchReleaseResponse.NotModified
        }
        if (response.statusCode == 404) {
            return FetchReleaseResponse.Success(release = null, etag = response.etag)
        }
        val list = decodeReleaseList(response.body)
        val candidates = list
            .filter { !it.draft && it.tagName.isNotBlank() }
            .filter { includePrerelease || !it.prerelease }

        val best = candidates.maxWithOrNull { a, b ->
            val cmp = AppVersionComparator.compare(a.tagName, b.tagName)
            if (cmp != 0) cmp else (a.publishedAt.orEmpty()).compareTo(b.publishedAt.orEmpty())
        }
        return FetchReleaseResponse.Success(
            release = best?.toDomain(),
            etag = response.etag,
        )
    }

    suspend fun downloadApkAsset(
        asset: AppReleaseAsset,
        targetDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val downloadHttpUrl = asset.downloadUrl.trim().toHttpUrlOrNull()
            ?.takeIf { isTrustedGitHubUrl(it, allowLoopbackHttp) }
            ?: throw GitHubReleaseException("安装包下载地址不受信任或格式无效")

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw GitHubReleaseException("无法创建更新包缓存目录")
        }

        val safeFileName = sanitizeApkFileName(asset.name)
        val finalFile = File(targetDir, safeFileName)
        val partFile = File(targetDir, "$safeFileName.part")

        if (isExistingApkValid(finalFile, asset)) {
            cleanupOtherUpdateFiles(targetDir, keepFileName = safeFileName)
            val existingSize = finalFile.length()
            onProgress(existingSize, existingSize)
            return@withContext finalFile
        }

        cleanupOtherUpdateFiles(targetDir, keepFileName = null)

        val request = Request.Builder()
            .url(downloadHttpUrl)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
            .build()

        val call = downloadClient.newCall(request)
        try {
            val response = call.awaitResponse()
            response.use { resp ->
                if (!isTrustedGitHubUrl(resp.request.url, allowLoopbackHttp)) {
                    throw GitHubReleaseException("安全校验失败：下载重定向目标域名不受信任")
                }
                if (!resp.isSuccessful) {
                    throw GitHubReleaseException(
                        message = buildHttpErrorMessage(resp.code, resp.headers["X-RateLimit-Remaining"]),
                        httpStatus = resp.code,
                    )
                }
                val body = resp.body ?: throw GitHubReleaseException("下载响应体为空")
                val declaredTotal = when {
                    asset.sizeBytes > 0L -> asset.sizeBytes
                    body.contentLength() > 0L -> body.contentLength()
                    else -> -1L
                }

                val digest = MessageDigest.getInstance("SHA-256")
                val headerBytes = ByteArray(4)
                var headerCaptured = 0
                var downloaded = 0L
                var lastCallbackBytes = 0L
                var lastCallbackTime = System.currentTimeMillis()

                onProgress(0L, declaredTotal.coerceAtLeast(0L))

                body.byteStream().use { input ->
                    FileOutputStream(partFile, false).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue

                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)

                            if (headerCaptured < 4) {
                                val copyLen = minOf(4 - headerCaptured, read)
                                System.arraycopy(buffer, 0, headerBytes, headerCaptured, copyLen)
                                headerCaptured += copyLen
                            }

                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (downloaded - lastCallbackBytes >= PROGRESS_CALLBACK_BYTES_THRESHOLD ||
                                now - lastCallbackTime >= PROGRESS_CALLBACK_INTERVAL_MS
                            ) {
                                lastCallbackBytes = downloaded
                                lastCallbackTime = now
                                onProgress(downloaded, declaredTotal.coerceAtLeast(0L))
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (downloaded <= 4L) {
                    throw GitHubReleaseException("下载的安装包内容为空或损坏")
                }
                if (asset.sizeBytes > 0L && downloaded != asset.sizeBytes) {
                    throw GitHubReleaseException(
                        "安装包大小不匹配（预期 ${asset.sizeBytes} 字节，实际 $downloaded 字节）",
                    )
                }
                if (headerCaptured < 4 || !headerBytes.contentEquals(APK_MAGIC_BYTES)) {
                    throw GitHubReleaseException("下载文件不是有效的 APK 安装包")
                }

                val expectedSha = asset.sha256?.trim()?.lowercase(Locale.ROOT)
                if (!expectedSha.isNullOrEmpty()) {
                    val actualSha = digest.digest().toHexLower()
                    if (actualSha != expectedSha) {
                        throw GitHubReleaseException("安装包 SHA-256 校验失败，已自动清理可疑文件")
                    }
                }

                if (finalFile.exists() && !finalFile.delete()) {
                    throw GitHubReleaseException("无法覆盖旧的更新安装包")
                }
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(downloaded, downloaded)
                finalFile
            }
        } catch (c: CancellationException) {
            call.cancel()
            partFile.delete()
            throw c
        } catch (t: Throwable) {
            partFile.delete()
            if (t is GitHubReleaseException) throw t
            throw GitHubReleaseException(
                message = t.message?.takeIf { it.isNotBlank() } ?: "下载安装包失败，请检查网络",
                cause = t,
            )
        }
    }

    internal fun selectBestApkAsset(
        assets: List<GitHubAssetDto>,
        releaseBody: String?,
    ): AppReleaseAsset? {
        val candidates = assets.filter { asset ->
            val cleanName = asset.name.trim()
            val url = asset.browserDownloadUrl.trim().toHttpUrlOrNull()
            cleanName.endsWith(".apk", ignoreCase = true) &&
                (asset.state.isBlank() || asset.state.equals("uploaded", ignoreCase = true)) &&
                url != null &&
                isTrustedGitHubUrl(url, allowLoopbackHttp)
        }
        if (candidates.isEmpty()) return null

        val chosen = candidates.maxWithOrNull { a, b ->
            val scoreA = scoreApkAsset(a.name)
            val scoreB = scoreApkAsset(b.name)
            if (scoreA != scoreB) scoreA.compareTo(scoreB) else a.size.compareTo(b.size)
        } ?: return null

        val resolvedSha256 = extractSha256(chosen, releaseBody)
        return AppReleaseAsset(
            id = chosen.id,
            name = chosen.name.trim(),
            downloadUrl = chosen.browserDownloadUrl.trim(),
            sizeBytes = chosen.size.coerceAtLeast(0L),
            contentType = chosen.contentType,
            sha256 = resolvedSha256,
        )
    }

    internal fun extractSha256(
        asset: GitHubAssetDto,
        releaseBody: String?,
    ): String? {
        val digestRaw = asset.digest?.trim().orEmpty()
        DIGEST_SHA256_REGEX.matchEntire(digestRaw)?.let { match ->
            return match.groupValues[1].lowercase(Locale.ROOT)
        }

        val body = releaseBody?.takeIf { it.isNotBlank() } ?: return null
        val escapedName = Regex.escape(asset.name.trim())
        val prefixPattern = Regex("""(?im)\b([a-f0-9]{64})\b\s+\*?$escapedName(?:\s|`|$)""")
        prefixPattern.find(body)?.let { match ->
            return match.groupValues[1].lowercase(Locale.ROOT)
        }
        val suffixPattern = Regex("""(?im)$escapedName\s*[:=\-]\s*`?([a-f0-9]{64})`?""")
        suffixPattern.find(body)?.let { match ->
            return match.groupValues[1].lowercase(Locale.ROOT)
        }
        return null
    }

    private fun scoreApkAsset(fileName: String): Int {
        val lower = fileName.trim().lowercase(Locale.ROOT)
        val tokens = tokenizeName(lower)
        var score = 0

        if (supportedAbis.isNotEmpty()) {
            var abiScore: Int? = null
            for ((index, abi) in supportedAbis.withIndex()) {
                val aliases = ABI_TOKEN_ALIASES[abi.lowercase(Locale.ROOT)] ?: setOf(abi.lowercase(Locale.ROOT))
                if (hasAnyArchMatch(lower, tokens, aliases)) {
                    abiScore = if (index == 0) 1000 else (900 - index * 10)
                    break
                }
            }
            if (abiScore != null) {
                score += abiScore
            } else if ("universal" in tokens || lower.contains("universal")) {
                score += 750
            } else if (!hasAnyArchMatch(lower, tokens, ALL_KNOWN_ARCH_TOKENS)) {
                score += 700
            } else {
                score += 100
            }
        } else {
            score += when {
                "universal" in tokens || lower.contains("universal") -> 750
                !hasAnyArchMatch(lower, tokens, ALL_KNOWN_ARCH_TOKENS) -> 700
                else -> 500
            }
        }

        if ("release" in tokens) score += 50
        if ("debug" in tokens) score -= 200
        if ("unsigned" in tokens) score -= 150
        return score
    }

    private fun tokenizeName(lowerFileName: String): Set<String> {
        val withoutExt = lowerFileName.removeSuffix(".apk")
        return withoutExt.split('-', '_', '.', ' ').filter { it.isNotEmpty() }.toSet()
    }

    private fun hasAnyArchMatch(
        lowerFileName: String,
        tokens: Set<String>,
        aliases: Set<String>,
    ): Boolean {
        return aliases.any { alias ->
            if (alias.contains('-')) {
                lowerFileName.contains(alias)
            } else {
                alias in tokens
            }
        }
    }

    private fun GitHubReleaseDto.toDomain(): AppReleaseInfo {
        val cleanTag = tagName.trim()
        val normalizedVer = AppVersionComparator.normalizeVersionString(cleanTag)
            .ifBlank { cleanTag }
        val cleanTitle = name?.trim()?.takeIf { it.isNotEmpty() } ?: cleanTag
        return AppReleaseInfo(
            tagName = cleanTag,
            versionName = normalizedVer,
            title = cleanTitle,
            changelogMarkdown = body.orEmpty().trim(),
            publishedAtIso = publishedAt.orEmpty().trim(),
            isPrerelease = prerelease,
            htmlUrl = htmlUrl.trim().ifBlank { "https://github.com/$owner/$repo/releases/tag/$cleanTag" },
            apkAsset = selectBestApkAsset(assets, body),
        )
    }

    private fun buildRepoApiUrl(
        vararg segments: String,
        queryParams: List<Pair<String, String>> = emptyList(),
    ): HttpUrl {
        val builder = apiBaseUrl.newBuilder()
        val existing = apiBaseUrl.pathSegments
        if (existing.isNotEmpty() && existing.last().isEmpty()) {
            builder.removePathSegment(existing.size - 1)
        }
        builder.addPathSegment("repos")
        builder.addPathSegment(owner)
        builder.addPathSegment(repo)
        for (segment in segments) {
            if (segment.isNotEmpty()) {
                builder.addPathSegment(segment)
            }
        }
        for ((k, v) in queryParams) {
            builder.addQueryParameter(k, v)
        }
        return builder.build()
    }

    private data class RawApiResponse(
        val statusCode: Int,
        val body: String,
        val etag: String?,
        val notModified: Boolean = false,
    )

    private suspend fun executeApiGet(
        url: HttpUrl,
        etag: String?,
        allowNotFound: Boolean,
    ): RawApiResponse {
        if (!isTrustedGitHubUrl(url, allowLoopbackHttp)) {
            throw GitHubReleaseException("安全校验失败：非法的 GitHub API 地址")
        }
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", GITHUB_API_ACCEPT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", userAgent)
        if (!etag.isNullOrBlank()) {
            builder.header("If-None-Match", etag.trim())
        }

        val call = apiClient.newCall(builder.build())
        return try {
            val resp = call.awaitResponse()
            resp.use { response ->
                if (!isTrustedGitHubUrl(response.request.url, allowLoopbackHttp)) {
                    throw GitHubReleaseException("安全校验失败：GitHub API 重定向至非信任域名")
                }
                val code = response.code
                val respEtag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                if (code == 304) {
                    return RawApiResponse(
                        statusCode = 304,
                        body = "",
                        etag = respEtag ?: etag,
                        notModified = true,
                    )
                }
                if (code == 404 && allowNotFound) {
                    return RawApiResponse(
                        statusCode = 404,
                        body = "",
                        etag = respEtag,
                    )
                }
                val bodyText = withContext(Dispatchers.IO) {
                    response.body?.string().orEmpty()
                }
                if (code !in 200..299) {
                    throw GitHubReleaseException(
                        message = buildHttpErrorMessage(code, response.header("X-RateLimit-Remaining")),
                        httpStatus = code,
                    )
                }
                RawApiResponse(
                    statusCode = code,
                    body = bodyText,
                    etag = respEtag,
                )
            }
        } catch (c: CancellationException) {
            call.cancel()
            throw c
        } catch (g: GitHubReleaseException) {
            throw g
        } catch (io: IOException) {
            throw GitHubReleaseException(
                message = "无法连接 GitHub Releases，请检查网络或代理设置",
                cause = io,
            )
        }
    }

    private suspend fun decodeSingleRelease(rawJson: String): GitHubReleaseDto =
        withContext(Dispatchers.Default) {
            try {
                json.decodeFromString(GitHubReleaseDto.serializer(), rawJson)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw GitHubReleaseException("GitHub Release 数据解析失败", cause = t)
            }
        }

    private suspend fun decodeReleaseList(rawJson: String): List<GitHubReleaseDto> =
        withContext(Dispatchers.Default) {
            try {
                json.decodeFromString(ListSerializer(GitHubReleaseDto.serializer()), rawJson)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw GitHubReleaseException("GitHub Releases 列表解析失败", cause = t)
            }
        }

    private fun buildHttpErrorMessage(statusCode: Int, rateLimitRemaining: String?): String {
        val isRateLimited = statusCode == 429 ||
            (statusCode == 403 && rateLimitRemaining?.trim() == "0")
        return when {
            isRateLimited -> "GitHub 接口请求过于频繁，请稍后再试或前往 GitHub Releases 查看"
            statusCode == 404 -> "未找到对应的 GitHub Release 资源"
            statusCode in 500..599 -> "GitHub 服务器暂时不可用 ($statusCode)"
            else -> "请求 GitHub Release 失败 (HTTP $statusCode)"
        }
    }

    private fun isExistingApkValid(file: File, asset: AppReleaseAsset): Boolean {
        if (!file.isFile || file.length() <= 4L) return false
        if (asset.sizeBytes > 0L && file.length() != asset.sizeBytes) return false

        val header = ByteArray(4)
        val headerRead = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(0)
        if (headerRead != 4 || !header.contentEquals(APK_MAGIC_BYTES)) return false

        val expectedSha = asset.sha256?.trim()?.lowercase(Locale.ROOT)
        if (!expectedSha.isNullOrEmpty()) {
            val actualSha = computeFileSha256(file) ?: return false
            if (actualSha != expectedSha) return false
        }
        return true
    }

    private fun computeFileSha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHexLower()
    }.getOrNull()

    private fun cleanupOtherUpdateFiles(targetDir: File, keepFileName: String?) {
        targetDir.listFiles()?.forEach { child ->
            if (child.isFile && child.name != keepFileName) {
                child.delete()
            }
        }
    }

    private fun ByteArray.toHexLower(): String =
        joinToString(separator = "") { b -> "%02x".format(Locale.ROOT, b) }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    response.close()
                }
            }
        },
    )
}
