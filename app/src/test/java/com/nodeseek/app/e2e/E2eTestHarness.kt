package com.nodeseek.app.e2e

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.activity.DrandApiClient
import com.nodeseek.app.data.activity.LuckyDrawRepositoryImpl
import com.nodeseek.app.data.alerts.AlertForwarderImpl
import com.nodeseek.app.data.alerts.AlertForwardingStore
import com.nodeseek.app.data.backup.BackupCryptoV2
import com.nodeseek.app.data.backup.BackupManagerImpl
import com.nodeseek.app.data.backup.FullBackupV2Stores
import com.nodeseek.app.data.baseline.BenchmarkBaselineStore
import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.FleetAlertSink
import com.nodeseek.app.data.fleet.FleetMonitorRunner
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.JvmTlsCertificateInspector
import com.nodeseek.app.data.fleet.JvmTlsSocketOps
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.local.BlockedUsersCache
import com.nodeseek.app.data.local.BookmarkDao
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.CategoryDao
import com.nodeseek.app.data.local.CategoryEntity
import com.nodeseek.app.data.local.DraftDao
import com.nodeseek.app.data.local.DraftEntity
import com.nodeseek.app.data.local.ContentFilterRepositoryImpl
import com.nodeseek.app.data.local.DraftRepositoryImpl
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.local.RadarWatermarkStore
import com.nodeseek.app.data.local.RadarWatermarks
import com.nodeseek.app.data.local.ReadHistoryDao
import com.nodeseek.app.data.local.ReadHistoryEntity
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.SettingsSecretCipher
import com.nodeseek.app.data.local.SettingsSecretCipherFactory
import com.nodeseek.app.data.local.SoftwareSettingsSecretCipher
import com.nodeseek.app.data.mail.SeekMailApiClient
import com.nodeseek.app.data.mail.SeekMailDiskCache
import com.nodeseek.app.data.mail.SeekMailRepositoryImpl
import com.nodeseek.app.data.mail.VpsMailAutoIngestServiceImpl
import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.IpIntelligenceEndpoints
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.PingExecutionEvent
import com.nodeseek.app.data.netbench.PingProcessExecutor
import com.nodeseek.app.data.netbench.PingProcessRequest
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.data.netbench.ReverseDnsLookup
import com.nodeseek.app.data.netbench.SystemDefaultNetworkBridge
import com.nodeseek.app.data.netbench.TracerouteKernelOps
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.netbench.TracerouteRunner
import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.data.network.SameOriginHttpResponse
import com.nodeseek.app.data.network.SameOriginTransport
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.parser.CategoryDto
import com.nodeseek.app.data.parser.ConfigPayloadDto
import com.nodeseek.app.data.parser.ConfigUserDto
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.radar.PulseRadarMonitor
import com.nodeseek.app.data.repository.BookmarkAndHistoryRepositoryImpl
import com.nodeseek.app.data.repository.CheckInRepositoryImpl
import com.nodeseek.app.data.repository.CommunityToolsRepositoryImpl
import com.nodeseek.app.data.repository.FileAccountCookieVault
import com.nodeseek.app.data.repository.ForumRepositoryImpl
import com.nodeseek.app.data.repository.InboxRepositoryImpl
import com.nodeseek.app.data.repository.MultiAccountManagerImpl
import com.nodeseek.app.data.repository.NodeImageHostRepositoryImpl
import com.nodeseek.app.data.repository.PollAndStardustRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.UserRepositoryImpl
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.data.valuation.TradeMarketReferenceRepositoryImpl
import com.nodeseek.app.data.widget.WidgetSnapshotRepositoryImpl
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.activity.NoOpActivityMonitorController
import com.nodeseek.app.domain.alerts.AlertForwarder
import com.nodeseek.app.domain.baseline.BenchmarkBaselineRepository
import com.nodeseek.app.domain.digest.NoOpAwayDigestRepository
import com.nodeseek.app.domain.fleet.FleetAlertTransition
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.TlsCertificateInspector
import com.nodeseek.app.domain.fleet.TlsPeerChain
import com.nodeseek.app.domain.fleet.TlsSocketOps
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.DefaultSeekMailOtpWatcher
import com.nodeseek.app.domain.mail.OtpWatchNotificationSink
import com.nodeseek.app.domain.mail.OtpWatchState
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.overseas.NoOpOverseasPromoMonitorController
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarMonitorSyncSupport
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuleCanonicalizer
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.AccountCookieVault
import com.nodeseek.app.domain.repository.MultiAccountManager
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshSftpClient
import com.nodeseek.app.domain.ssh.SshSftpEntry
import com.nodeseek.app.domain.ssh.SshSftpErrorCode
import com.nodeseek.app.domain.ssh.SshSftpException
import com.nodeseek.app.domain.ssh.SshSftpTextContent
import com.nodeseek.app.domain.ssh.SshSftpTransport
import com.nodeseek.app.domain.ssh.SshSftpWriteResult
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.domain.trust.NoOpOfficialTradeRepository
import com.nodeseek.app.domain.valuation.TradeMarketReferenceRepository
import com.nodeseek.app.e2e.fixtures.E2eBitmapAndTlsFixtures
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.composer.TopicComposerViewModel
import com.nodeseek.app.ui.hub.PulseHubViewModel
import com.nodeseek.app.ui.mail.SeekMailViewModel
import com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator
import com.nodeseek.app.ui.netbench.MobileNetBenchViewModel
import com.nodeseek.app.ui.netbench.NetBenchCardImageGenerator
import com.nodeseek.app.ui.netbench.NetBenchSessionController
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.NoOpSshForegroundServiceController
import com.nodeseek.app.ui.topic.TopicDetailViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy as NetProxy
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * JUnit [TestWatcher] setting and resetting `Dispatchers.Main` for pure-JVM E2E ViewModel tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class E2eMainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Deterministic mutable clock supporting Asia/Shanghai calendar date calculations.
 */
class E2eClock(
    initialEpochMillis: Long = DEFAULT_INITIAL_EPOCH_MILLIS,
) {
    private val epochMillis = AtomicLong(initialEpochMillis)

    fun nowMillis(): Long = epochMillis.get()

    fun nowInstant(): Instant = Instant.ofEpochMilli(nowMillis())

    fun setEpochMillis(newEpochMillis: Long) {
        epochMillis.set(newEpochMillis)
    }

    fun advanceByMillis(deltaMillis: Long): Long = epochMillis.addAndGet(deltaMillis)

    fun todayShanghai(): LocalDate =
        Instant.ofEpochMilli(nowMillis()).atZone(SHANGHAI_ZONE).toLocalDate()

    companion object {
        const val DEFAULT_INITIAL_EPOCH_MILLIS: Long = 1_764_000_000_000L // 2025-11-24T16:00:00Z / 2025-11-25 00:00:00 +08:00
        val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/**
 * Thread-safe route-based [MockWebServer] [Dispatcher] supporting method+path exact routes,
 * prefix routes, one-shot FIFO queued responses, request recording, and an automatic
 * `Cookie` header leak guard for third-party hosts.
 */
class RouteMockDispatcher(
    val forbidCookieHeader: Boolean = false,
) : Dispatcher() {

    private data class RouteKey(
        val method: String?,
        val path: String,
    )

    private data class PrefixRoute(
        val method: String?,
        val pathPrefix: String,
        val handler: (RecordedRequest) -> MockResponse,
    )

    private val lock = Any()
    private val exactRoutes = LinkedHashMap<RouteKey, (RecordedRequest) -> MockResponse>()
    private val prefixRoutes = mutableListOf<PrefixRoute>()
    private val queuedResponses = LinkedHashMap<RouteKey, ArrayDeque<MockResponse>>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private val cookieLeakViolations = CopyOnWriteArrayList<RecordedRequest>()

    fun register(
        method: String? = null,
        path: String,
        handler: (RecordedRequest) -> MockResponse,
    ): RouteMockDispatcher {
        val normalizedMethod = method?.uppercase()
        synchronized(lock) {
            exactRoutes[RouteKey(normalizedMethod, path)] = handler
        }
        return this
    }

    fun registerPrefix(
        method: String? = null,
        pathPrefix: String,
        handler: (RecordedRequest) -> MockResponse,
    ): RouteMockDispatcher {
        val normalizedMethod = method?.uppercase()
        synchronized(lock) {
            prefixRoutes.removeAll { it.method == normalizedMethod && it.pathPrefix == pathPrefix }
            prefixRoutes.add(PrefixRoute(normalizedMethod, pathPrefix, handler))
            prefixRoutes.sortWith(
                compareByDescending<PrefixRoute> { it.pathPrefix.length }
                    .thenByDescending { it.method != null },
            )
        }
        return this
    }

    fun enqueueNext(path: String, response: MockResponse): RouteMockDispatcher =
        enqueueNext(method = null, path = path, response = response)

    fun enqueueNext(
        method: String?,
        path: String,
        response: MockResponse,
    ): RouteMockDispatcher {
        val key = RouteKey(method?.uppercase(), path)
        synchronized(lock) {
            val queue = queuedResponses.getOrPut(key) { ArrayDeque() }
            queue.addLast(response)
        }
        return this
    }

    fun recordedRequests(): List<RecordedRequest> = recorded.toList()

    fun clearRecordedRequests() {
        recorded.clear()
        cookieLeakViolations.clear()
    }

    fun cookieViolations(): List<RecordedRequest> = cookieLeakViolations.toList()

    override fun dispatch(request: RecordedRequest): MockResponse {
        recorded.add(request)
        if (forbidCookieHeader && request.getHeader("Cookie") != null) {
            cookieLeakViolations.add(request)
            return MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"COOKIE_LEAK_GUARD_VIOLATION","path":"${request.path.orEmpty()}"}""")
        }

        val pathOnly = request.requestUrl?.encodedPath
            ?: request.path?.substringBefore('?')
            ?: "/"
        val methodUpper = request.method?.uppercase()

        val queued = synchronized(lock) {
            val specificKey = RouteKey(methodUpper, pathOnly)
            val wildcardKey = RouteKey(null, pathOnly)
            val specificQueue = queuedResponses[specificKey]
            val wildcardQueue = queuedResponses[wildcardKey]
            when {
                specificQueue != null && specificQueue.isNotEmpty() -> specificQueue.removeFirst()
                wildcardQueue != null && wildcardQueue.isNotEmpty() -> wildcardQueue.removeFirst()
                else -> null
            }
        }
        if (queued != null) return queued

        val handler = synchronized(lock) {
            exactRoutes[RouteKey(methodUpper, pathOnly)]
                ?: exactRoutes[RouteKey(null, pathOnly)]
                ?: prefixRoutes.firstOrNull { route ->
                    (route.method == null || route.method == methodUpper) &&
                        pathOnly.startsWith(route.pathPrefix)
                }?.handler
        }

        return handler?.invoke(request)
            ?: MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":false,"message":"No mock route registered for $methodUpper $pathOnly"}""")
    }
}

/**
 * Deterministic resource fixture loader and `temp-script` HTML wrapper for `app/src/test/resources/e2e/`.
 */
object E2eFixtures {
    fun loadText(relativePath: String): String =
        loadBytes(relativePath).toString(Charsets.UTF_8)

    fun loadBytes(relativePath: String): ByteArray {
        val clean = relativePath.trimStart('/')
        val resourcePath = if (clean.startsWith("e2e/")) clean else "e2e/$clean"
        val stream = E2eFixtures::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Missing E2E fixture resource: $resourcePath")
        return stream.use { it.readBytes() }
    }

    fun wrapTempScriptHtml(
        configJson: String,
        bodyHtml: String = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"></div></div></div>""",
        title: String = "NodeSeek",
    ): String {
        val encoded = Base64.getEncoder().encodeToString(configJson.toByteArray(Charsets.UTF_8))
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <title>$title</title>
              <script id="temp-script" type="application/json">$encoded</script>
            </head>
            <body>
              $bodyHtml
            </body>
            </html>
        """.trimIndent()
    }

    fun minimalPngBytes(): ByteArray = E2eBitmapAndTlsFixtures.minimalPngBytes()

    fun loadMtrSection(sectionName: String): String {
        val full = loadText("netbench/mtr_sample_outputs.txt")
        val marker = "# === $sectionName ==="
        val start = full.indexOf(marker)
        require(start >= 0) { "Missing MTR fixture section: $sectionName" }
        val afterHeader = full.indexOf('\n', start).let { if (it < 0) full.length else it + 1 }
        val nextSection = full.indexOf("\n# === ", afterHeader)
        return (if (nextSection < 0) full.substring(afterHeader) else full.substring(afterHeader, nextSection)).trim()
    }
}

/**
 * In-memory Room [DraftDao] implementation matching `DraftDao` queries and ordering semantics.
 */
class E2eDraftDao : DraftDao {
    private val writeMutex = Mutex()
    private val state = MutableStateFlow<List<DraftEntity>>(emptyList())

    override suspend fun getDraft(accountUid: Long, draftKey: String): DraftEntity? =
        state.value.firstOrNull { it.accountUid == accountUid && it.draftKey == draftKey }

    override fun observeDraft(accountUid: Long, draftKey: String): Flow<DraftEntity?> =
        state.map { rows -> rows.firstOrNull { it.accountUid == accountUid && it.draftKey == draftKey } }

    override fun observeDrafts(accountUid: Long): Flow<List<DraftEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid }
                .sortedByDescending { it.updatedAtMillis }
        }

    override suspend fun upsert(entity: DraftEntity) {
        upsertAll(listOf(entity))
    }

    override suspend fun deleteDraft(accountUid: Long, draftKey: String) {
        writeMutex.withLock {
            state.update { rows ->
                rows.filterNot { it.accountUid == accountUid && it.draftKey == draftKey }
            }
        }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        writeMutex.withLock {
            state.update { rows ->
                rows.filterNot { it.accountUid == accountUid }
            }
        }
    }

    override suspend fun getAllDrafts(): List<DraftEntity> =
        state.value.sortedByDescending { it.updatedAtMillis }

    override suspend fun upsertAll(entities: List<DraftEntity>) {
        writeMutex.withLock {
            state.update { rows ->
                val updated = rows.toMutableList()
                for (entity in entities) {
                    updated.removeAll { it.accountUid == entity.accountUid && it.draftKey == entity.draftKey }
                    updated.add(entity)
                }
                updated.toList()
            }
        }
    }

    override suspend fun clearAll() {
        writeMutex.withLock {
            state.update { emptyList() }
        }
    }
}

/**
 * Thread-safe in-memory Room [CategoryDao] with atomic `replaceForAccount`.
 */
class E2eCategoryDao : CategoryDao {
    private val writeMutex = Mutex()
    private val state = MutableStateFlow<List<CategoryEntity>>(emptyList())

    override fun observeCategories(accountUid: Long): Flow<List<CategoryEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid }
                .sortedBy { it.sortOrder }
        }

    override suspend fun getCategories(accountUid: Long): List<CategoryEntity> =
        state.value.filter { it.accountUid == accountUid }
            .sortedBy { it.sortOrder }

    override suspend fun getLastSyncedAtMillis(accountUid: Long): Long? =
        getCategories(accountUid).maxOfOrNull { it.syncedAtEpochMillis }

    override suspend fun upsertAll(entities: List<CategoryEntity>) {
        if (entities.isEmpty()) return
        writeMutex.withLock {
            state.update { rows ->
                val updated = rows.toMutableList()
                for (entity in entities) {
                    updated.removeAll { it.accountUid == entity.accountUid && it.slug == entity.slug }
                    updated.add(entity)
                }
                updated.toList()
            }
        }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        writeMutex.withLock {
            state.update { rows -> rows.filterNot { it.accountUid == accountUid } }
        }
    }

    override suspend fun replaceForAccount(accountUid: Long, entities: List<CategoryEntity>) {
        writeMutex.withLock {
            state.update { rows ->
                val preservedOtherAccounts = rows.filterNot { it.accountUid == accountUid }
                val dedupedForAccount = LinkedHashMap<String, CategoryEntity>()
                for (entity in entities) {
                    dedupedForAccount[entity.slug] = entity
                }
                preservedOtherAccounts + dedupedForAccount.values
            }
        }
    }
}

/**
 * Thread-safe in-memory Room [BookmarkDao] with atomic `updateOfflineSnapshot` and `replaceAllBookmarks`.
 */
class E2eBookmarkDao : BookmarkDao {
    private val writeMutex = Mutex()
    private val state = MutableStateFlow<List<BookmarkEntity>>(emptyList())

    override fun observeBookmarks(accountUid: Long): Flow<List<BookmarkEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid }
                .sortedByDescending { it.savedAtEpochMillis }
        }

    override fun observeBookmarkedTopicIds(accountUid: Long): Flow<List<Long>> =
        observeBookmarks(accountUid).map { rows -> rows.map { it.topicId } }

    override suspend fun getBookmarkedTopicIds(accountUid: Long): List<Long> =
        state.value
            .filter { it.accountUid == accountUid }
            .sortedByDescending { it.savedAtEpochMillis }
            .map { it.topicId }

    override suspend fun getBookmark(accountUid: Long, topicId: Long): BookmarkEntity? =
        state.value.firstOrNull { it.accountUid == accountUid && it.topicId == topicId }

    override suspend fun upsert(entity: BookmarkEntity) {
        upsertAll(listOf(entity))
    }

    override suspend fun upsertAll(entities: List<BookmarkEntity>) {
        if (entities.isEmpty()) return
        writeMutex.withLock {
            state.update { rows ->
                val updated = rows.toMutableList()
                for (entity in entities) {
                    updated.removeAll { it.accountUid == entity.accountUid && it.topicId == entity.topicId }
                    updated.add(entity)
                }
                updated.toList()
            }
        }
    }

    override suspend fun updateOfflineSnapshot(
        accountUid: Long,
        topicId: Long,
        offlineDetailJson: String?,
    ) {
        writeMutex.withLock {
            state.update { rows ->
                rows.map { row ->
                    if (row.accountUid == accountUid && row.topicId == topicId) {
                        row.copy(offlineDetailJson = offlineDetailJson)
                    } else {
                        row
                    }
                }
            }
        }
    }

    override suspend fun deleteBookmark(accountUid: Long, topicId: Long) {
        writeMutex.withLock {
            state.update { rows ->
                rows.filterNot { it.accountUid == accountUid && it.topicId == topicId }
            }
        }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        writeMutex.withLock {
            state.update { rows -> rows.filterNot { it.accountUid == accountUid } }
        }
    }

    override suspend fun getAllBookmarks(): List<BookmarkEntity> =
        state.value.sortedByDescending { it.savedAtEpochMillis }

    override suspend fun clearAll() {
        writeMutex.withLock {
            state.update { emptyList() }
        }
    }

    override suspend fun replaceAllBookmarks(entities: List<BookmarkEntity>) {
        writeMutex.withLock {
            state.update {
                val deduped = LinkedHashMap<Pair<Long, Long>, BookmarkEntity>()
                for (entity in entities) {
                    deduped[entity.accountUid to entity.topicId] = entity
                }
                deduped.values.toList()
            }
        }
    }
}

/**
 * Thread-safe in-memory Room [ReadHistoryDao].
 */
class E2eReadHistoryDao : ReadHistoryDao {
    private val writeMutex = Mutex()
    private val state = MutableStateFlow<List<ReadHistoryEntity>>(emptyList())

    override fun observeHistory(accountUid: Long, limit: Int): Flow<List<ReadHistoryEntity>> =
        state.map { rows ->
            rows.filter { it.accountUid == accountUid }
                .sortedByDescending { it.visitedAtEpochMillis }
                .take(limit)
        }

    override fun observeByTopicIds(
        accountUid: Long,
        topicIds: Collection<Long>,
    ): Flow<List<ReadHistoryEntity>> {
        val targetIds = topicIds.toSet()
        return state.map { rows ->
            rows.filter { it.accountUid == accountUid && it.topicId in targetIds }
        }
    }

    override suspend fun getByTopicIds(
        accountUid: Long,
        topicIds: Collection<Long>,
    ): List<ReadHistoryEntity> {
        val targetIds = topicIds.toSet()
        return state.value.filter { it.accountUid == accountUid && it.topicId in targetIds }
    }

    override suspend fun getByTopicId(accountUid: Long, topicId: Long): ReadHistoryEntity? =
        state.value.firstOrNull { it.accountUid == accountUid && it.topicId == topicId }

    override suspend fun upsert(entity: ReadHistoryEntity) {
        writeMutex.withLock {
            state.update { rows ->
                rows.filterNot { it.accountUid == entity.accountUid && it.topicId == entity.topicId } + entity
            }
        }
    }

    override suspend fun deleteByTopicId(accountUid: Long, topicId: Long) {
        writeMutex.withLock {
            state.update { rows ->
                rows.filterNot { it.accountUid == accountUid && it.topicId == topicId }
            }
        }
    }

    override suspend fun clearForAccount(accountUid: Long) {
        writeMutex.withLock {
            state.update { rows -> rows.filterNot { it.accountUid == accountUid } }
        }
    }

    override suspend fun clearAll() {
        writeMutex.withLock {
            state.update { emptyList() }
        }
    }
}

/**
 * Recording [SameOriginTransport] double for testing high-risk action / WebView fallback paths
 * without instantiating Android WebView.
 */
class RecordingSameOriginTransport(
    var canAttempt: Boolean = false,
    var userAgentValue: String? = E2eTestHarness.DEFAULT_TEST_USER_AGENT,
    var csrfTokenProvider: () -> String? = { null },
    var onClearSessionContext: () -> Unit = {},
    var htmlHandler: suspend (url: String) -> SameOriginHttpResponse = {
        SameOriginHttpResponse(statusCode = 200, body = "")
    },
    var apiHandler: suspend (
        method: String,
        url: String,
        refererUrl: String,
        headers: Map<String, String>,
        body: String?,
    ) -> SameOriginHttpResponse = { _, _, _, _, _ ->
        SameOriginHttpResponse(statusCode = 200, body = """{"success":true}""")
    },
) : SameOriginTransport {

    data class ApiInvocation(
        val method: String,
        val url: String,
        val refererUrl: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    val fetchHtmlCalls = CopyOnWriteArrayList<String>()
    val apiCalls = CopyOnWriteArrayList<ApiInvocation>()
    var verifiedByUserCount: Int = 0
        private set
    var clearSessionContextCount: Int = 0
        private set

    override fun canAttemptWithExistingClearance(): Boolean = canAttempt

    override fun markVerifiedByUser() {
        verifiedByUserCount += 1
        canAttempt = true
    }

    override fun clearSessionContext() {
        clearSessionContextCount += 1
        canAttempt = false
        onClearSessionContext()
    }

    override fun getUserAgent(): String? = userAgentValue

    override fun getCsrfToken(): String? = csrfTokenProvider()

    override suspend fun fetchHtml(url: String): SameOriginHttpResponse {
        fetchHtmlCalls.add(url)
        return htmlHandler(url)
    }

    override suspend fun executeApiRequest(
        method: String,
        url: String,
        refererUrl: String,
        headers: Map<String, String>,
        body: String?,
    ): SameOriginHttpResponse {
        apiCalls.add(ApiInvocation(method, url, refererUrl, headers, body))
        return apiHandler(method, url, refererUrl, headers, body)
    }
}

/**
 * Isolated opaque-box E2E test harness providing dual [MockWebServer]s (`forumServer` +
 * cookie-guarded `externalServer`), deterministic clock, temporary persistence directory,
 * real repositories, and a compile-safe [AppContainer] Proxy builder.
 */
class E2eTestHarness(
    initialEpochMillis: Long = E2eClock.DEFAULT_INITIAL_EPOCH_MILLIS,
    val testUserAgent: String = DEFAULT_TEST_USER_AGENT,
    val useHttpsNodeSeekOrigin: Boolean = false,
) : Closeable {

    val clock = E2eClock(initialEpochMillis)
    val tempDir: File = Files.createTempDirectory("anode_e2e_").toFile()

    val forumDispatcher = RouteMockDispatcher(forbidCookieHeader = false)
    val externalDispatcher = RouteMockDispatcher(forbidCookieHeader = true)

    val forumServer: MockWebServer = MockWebServer().apply {
        dispatcher = forumDispatcher
        if (useHttpsNodeSeekOrigin) {
            useHttps(nodeSeekHttpsTlsFixture.sslContext.socketFactory, false)
        }
        start()
    }
    val externalServer: MockWebServer = MockWebServer().apply {
        dispatcher = externalDispatcher
        start()
    }

    val forumBaseOrigin: HttpUrl = if (useHttpsNodeSeekOrigin) {
        "https://${NodeSeekUrlNormalizer.CANONICAL_HOST}:${forumServer.port}/".toHttpUrl()
    } else {
        forumServer.url("/")
    }

    val forumRssUrl: HttpUrl = if (useHttpsNodeSeekOrigin) {
        "https://${NodeSeekUrlNormalizer.CANONICAL_HOST}:${forumServer.port}/rss.xml".toHttpUrl()
    } else {
        forumServer.url("/rss.xml")
    }

    private val nodeSeekDns: Dns = Dns { hostname ->
        if (hostname.equals(NodeSeekUrlNormalizer.CANONICAL_HOST, ignoreCase = true) ||
            hostname.equals(NodeSeekUrlNormalizer.BARE_HOST, ignoreCase = true)
        ) {
            listOf(InetAddress.getByName("127.0.0.1"))
        } else {
            Dns.SYSTEM.lookup(hostname)
        }
    }

    val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModelStore = ViewModelStore()
    private val viewModelCounter = AtomicLong(0L)
    private val trackedViewModels = CopyOnWriteArrayList<ViewModel>()

    fun <T : ViewModel> manage(viewModel: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = viewModel as VM
        }
        val key = "e2e_vm_${viewModelCounter.incrementAndGet()}"
        val managed = ViewModelProvider(viewModelStore, factory)[key, viewModel::class.java]
        trackedViewModels.add(managed)
        return managed
    }

    val cookieBridge = WebViewCookieBridge(forceInMemory = true)
    val userAgentProvider = UserAgentProvider(testUserAgent)
    val csrfTokenStore = CsrfTokenStore()
    val sameOriginTransport = RecordingSameOriginTransport(
        userAgentValue = testUserAgent,
        csrfTokenProvider = { csrfTokenStore.csrfToken },
        onClearSessionContext = { csrfTokenStore.clear() },
    )

    val forumOkHttp: OkHttpClient = NodeSeekApiClient.createDefaultHttpClient(
        cookieBridge = cookieBridge,
        userAgentProvider = userAgentProvider,
        csrfTokenStore = csrfTokenStore,
        additionalTargetHost = forumBaseOrigin.host,
    ).let { client ->
        if (useHttpsNodeSeekOrigin) {
            client.newBuilder()
                .proxy(NetProxy.NO_PROXY)
                .sslSocketFactory(
                    nodeSeekHttpsTlsFixture.sslContext.socketFactory,
                    nodeSeekHttpsTlsFixture.trustManager,
                )
                .dns(nodeSeekDns)
                .build()
        } else {
            client
        }
    }

    val coilOkHttpClient: OkHttpClient = CoilHttpClientFactory.create(
        cookieBridge = cookieBridge,
        userAgentProvider = userAgentProvider,
    ).let { client ->
        if (useHttpsNodeSeekOrigin) {
            client.newBuilder()
                .proxy(NetProxy.NO_PROXY)
                .sslSocketFactory(
                    nodeSeekHttpsTlsFixture.sslContext.socketFactory,
                    nodeSeekHttpsTlsFixture.trustManager,
                )
                .dns(nodeSeekDns)
                .build()
        } else {
            client
        }
    }

    fun createPort443RoutingForumOkHttp(): OkHttpClient {
        require(useHttpsNodeSeekOrigin) { "useHttpsNodeSeekOrigin must be true" }
        return forumOkHttp.newBuilder()
            .socketFactory(PortRedirectSocketFactory(targetPort = forumServer.port))
            .build()
    }

    val externalCookieFreeHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val blockedUsersCache: BlockedUsersCache = E2eInMemoryBlockedUsersCache()

    val sessionRepository: SessionRepositoryImpl = SessionRepositoryImpl(
        apiClientProvider = { apiClient },
        cookieBridge = cookieBridge,
    ).also { sessionRepo ->
        sessionRepo.onSignedOutCallback = {
            backgroundScope.launch {
                runCatching { blockedUsersCache.clear() }
            }
        }
    }

    val apiClient: NodeSeekApiClient = NodeSeekApiClient(
        httpClient = forumOkHttp,
        baseOrigin = forumBaseOrigin,
        rssUrl = forumRssUrl,
        nodeImageOrigin = externalServer.url("/"),
        sameOriginTransport = sameOriginTransport,
        onConfigObserved = { config ->
            sessionRepository.onConfigPayloadObserved(config)
        },
        sessionGenerationProvider = { sessionRepository.currentSessionGeneration() },
        onConfigObservedWithGeneration = { config, expectedGeneration ->
            sessionRepository.onConfigPayloadObserved(
                config = config,
                expectedGeneration = expectedGeneration,
            )
        },
    )

    val settingsDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = dataStoreScope,
        produceFile = { File(tempDir, "nodepulse_settings.preferences_pb") },
    )

    val settingsRepository = SettingsRepositoryImpl(
        dataStore = settingsDataStore,
        externalScope = dataStoreScope,
        secretCipher = SettingsSecretCipherFactory.createDefault(),
    ).also { settingsRepo ->
        sessionRepository.activeUidChangedListener = { uid ->
            settingsRepo.onActiveAccountChanged(uid)
        }
        settingsRepo.onActiveAccountChanged(sessionRepository.sessionState.value?.uid ?: 0L)
    }

    val categoryDao = E2eCategoryDao()
    val readHistoryDao = E2eReadHistoryDao()
    val bookmarkDao = E2eBookmarkDao()
    val draftDao = E2eDraftDao()
    val draftRepository = DraftRepositoryImpl(draftDao = draftDao)

    val contentFilterDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = dataStoreScope,
        produceFile = { File(tempDir, "nodepulse_content_filter_prefs.preferences_pb") },
    )

    val contentFilterRepository = ContentFilterRepositoryImpl(
        dataStore = contentFilterDataStore,
        externalScope = dataStoreScope,
    )

    val feedDiskCache = FeedDiskCache(
        cacheDir = File(tempDir, "feed_cache_v1"),
        ioScope = backgroundScope,
    )

    val bookmarkAndHistoryRepository = BookmarkAndHistoryRepositoryImpl(
        apiClientProvider = { apiClient },
        sessionRepository = sessionRepository,
        bookmarkDao = bookmarkDao,
        readHistoryDao = readHistoryDao,
    )

    val forumRepository = ForumRepositoryImpl(
        apiClientProvider = { apiClient },
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        categoryDao = categoryDao,
        readHistoryDao = readHistoryDao,
        bookmarkDao = bookmarkDao,
        bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
        backgroundScope = backgroundScope,
        feedDiskCache = feedDiskCache,
        clockMillis = clock::nowMillis,
    ).also { forumRepo ->
        sessionRepository.embeddedCategoriesListener = { categories, uid ->
            forumRepo.onEmbeddedCategoriesObserved(categories, uid)
        }
    }

    val checkInRepository = CheckInRepositoryImpl(
        apiClientProvider = { apiClient },
        sessionRepository = sessionRepository,
        clockMillis = clock::nowMillis,
    )

    val userRepository = UserRepositoryImpl(
        apiClientProvider = { apiClient },
        sessionRepository = sessionRepository,
        blockedUsersCache = blockedUsersCache,
        knownTopicsResolver = { topicIds -> forumRepository.resolveKnownTopics(topicIds) },
    )

    val radarWatermarkStore: RadarWatermarkStore = E2eInMemoryWatermarkStore()

    val inboxRepository = InboxRepositoryImpl(
        apiClientProvider = { apiClient },
        sessionRepository = sessionRepository,
        clockMillis = clock::nowMillis,
        watermarkStore = radarWatermarkStore,
    ).also { inboxRepo ->
        sessionRepository.unreadCountListener = inboxRepo::onPiggybackedUnreadCount
    }

    val accountCookieVaultCipher: SettingsSecretCipher =
        SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
    val accountCookieVaultFile: File = File(tempDir, FileAccountCookieVault.VAULT_FILE_NAME)
    val accountCookieVault: AccountCookieVault = FileAccountCookieVault(
        vaultFile = accountCookieVaultFile,
        cipher = accountCookieVaultCipher,
        clock = clock::nowMillis,
    )

    val multiAccountManager: MultiAccountManager = MultiAccountManagerImpl(
        vault = accountCookieVault,
        cookieStore = cookieBridge,
        sessionRepository = sessionRepository,
        apiClientProvider = { apiClient },
        checkInRepository = checkInRepository,
        bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
        forumRepository = forumRepository,
        externalScope = backgroundScope,
        clock = clock::nowMillis,
        settingsRepository = settingsRepository,
    )

    val imageHostRepository = NodeImageHostRepositoryImpl(
        apiClientProvider = { apiClient },
        settingsRepository = settingsRepository,
        sessionRepository = sessionRepository,
    )

    val fleetCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
    val fleetStoreFile = File(tempDir, "fleet_store.json")
    val fleetRepository = FleetRepositoryImpl(
        storeFile = fleetStoreFile,
        tokenCipher = fleetCipher,
        nowMillisProvider = clock::nowMillis,
    )

    val sshCipher = InMemoryTestCipher()
    val sshVaultFile = File(tempDir, "lite_ssh_vault.json")
    val sshVaultRepository = SshServerVault(
        vaultFile = sshVaultFile,
        cipher = sshCipher,
        ioDispatcher = Dispatchers.IO,
        clock = clock::nowMillis,
    )

    val vpsMailIngestStateFile = File(tempDir, "vps_mail_ingest_state.json")
    val vpsMailAutoIngestService = VpsMailAutoIngestServiceImpl(
        fleetRepository = fleetRepository,
        sshVaultRepository = sshVaultRepository,
        stateFile = vpsMailIngestStateFile,
        todayProvider = clock::todayShanghai,
        nowMillisProvider = clock::nowMillis,
    )

    val netBenchHistoryFile = File(tempDir, "netbench_history.json")
    val netBenchHistoryStore = NetBenchHistoryStore(
        historyFile = netBenchHistoryFile,
    )

    val communityToolsRepository = CommunityToolsRepositoryImpl(
        apiClientProvider = { apiClient },
        sessionRepository = sessionRepository,
    )

    val pollAndStardustRepository = PollAndStardustRepositoryImpl(
        apiClientProvider = { apiClient },
        sessionRepository = sessionRepository,
    )

    val drandApiClient = DrandApiClient(
        baseHttpClient = forumOkHttp,
        baseUrl = externalServer.url("/"),
    )

    val luckyDrawRepository = LuckyDrawRepositoryImpl(
        apiClientProvider = { apiClient },
        drandApiClient = drandApiClient,
        sessionRepository = sessionRepository,
    )

    val benchmarkBaselineFile = File(tempDir, "benchmark_baselines.json")
    val benchmarkBaselineStore = BenchmarkBaselineStore(
        storeFile = benchmarkBaselineFile,
        deferInitialLoad = false,
    )

    val exchangeRateCacheFile = File(tempDir, "valuation_fx_cache.json")
    val exchangeRateRepository = ExchangeRateRepository(
        cacheFile = exchangeRateCacheFile,
        httpClient = externalCookieFreeHttpClient,
        endpointUrl = externalServer.url("/fx/latest?base=CNY").toString(),
        nowMillisProvider = clock::nowMillis,
    )

    val radarRepository: RadarRepository = E2eInMemoryRadarRepository()

    val tradeMarketReferenceRepository: TradeMarketReferenceRepository = TradeMarketReferenceRepositoryImpl(
        forumRepository = forumRepository,
        bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
        clockMillis = clock::nowMillis,
    )

    val domainRdapClient = DomainRdapClient(
        baseHttpClient = forumOkHttp,
        primaryEndpointTemplate = externalServer.url("/rdap/domain/{domain}").toString(),
        ianaBootstrapUrl = externalServer.url("/iana/rdap/dns.json").toString(),
        clockMillis = clock::nowMillis,
    )

    @Volatile
    var tlsSocketOpsOverride: TlsSocketOps? = null
    private val defaultTlsSocketOps = JvmTlsSocketOps()
    val tlsCertificateInspector: TlsCertificateInspector = JvmTlsCertificateInspector(
        socketOps = object : TlsSocketOps {
            override suspend fun fetchPeerCertificates(
                host: String,
                port: Int,
                timeoutMillis: Int,
            ): TlsPeerChain = (tlsSocketOpsOverride ?: defaultTlsSocketOps)
                .fetchPeerCertificates(host, port, timeoutMillis)
        },
        nowMillisProvider = clock::nowMillis,
    )

    val defaultSshSessionManager: LiteSshSessionManager by lazy {
        createSshSessionManager()
    }

    // ── M4 Bindings: R12 Widgets, R14 Seek Mail OTP Watcher, R15 Alert Forwarding ──
    val seekMailDiskCache = SeekMailDiskCache(
        cacheDir = File(tempDir, "seek_mail_cache_v1"),
        ioScope = backgroundScope,
    )

    val seekMailApiClient = SeekMailApiClient(
        baseHttpClient = forumOkHttp,
        baseUrl = externalServer.url("/openapi/v1"),
    )

    val seekMailRepository = SeekMailRepositoryImpl(
        apiClient = seekMailApiClient,
        settingsRepository = settingsRepository,
        diskCache = seekMailDiskCache,
        externalScope = backgroundScope,
        vpsMailAutoIngestService = vpsMailAutoIngestService,
    )

    val otpWatchNotificationSink = E2eOtpWatchNotificationSink()
    private val trackedOtpWatchers = CopyOnWriteArrayList<DefaultSeekMailOtpWatcher>()

    fun createSeekMailOtpWatcher(
        scope: CoroutineScope = backgroundScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        notificationSink: OtpWatchNotificationSink = otpWatchNotificationSink,
        clockMillis: () -> Long = clock::nowMillis,
        delayFn: suspend (Long) -> Unit = { ms ->
            clock.advanceByMillis(ms)
            kotlinx.coroutines.yield()
        },
    ): DefaultSeekMailOtpWatcher = DefaultSeekMailOtpWatcher(
        seekMailRepository = seekMailRepository,
        settingsRepository = settingsRepository,
        notificationSink = notificationSink,
        scope = scope,
        ioDispatcher = ioDispatcher,
        clockMillis = clockMillis,
        delayFn = delayFn,
    ).also { watcher ->
        trackedOtpWatchers.add(watcher)
    }

    val defaultSeekMailOtpWatcher: DefaultSeekMailOtpWatcher by lazy {
        createSeekMailOtpWatcher()
    }

    val alertForwardingCipher: SettingsSecretCipher =
        SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
    val alertForwardingStoreFile = File(tempDir, AlertForwardingStore.STORE_FILE_NAME)
    val alertForwardingStore = AlertForwardingStore(
        storeFile = alertForwardingStoreFile,
        secretCipher = alertForwardingCipher,
        ioDispatcher = Dispatchers.IO,
    )

    val alertForwarder = AlertForwarderImpl(
        store = alertForwardingStore,
        baseHttpClient = forumOkHttp,
        ioDispatcher = Dispatchers.IO,
        clockMillis = clock::nowMillis,
    )

    fun createTelegramRoutingAlertForwarder(
        store: AlertForwardingStore = alertForwardingStore,
    ): Pair<MockWebServer, AlertForwarderImpl> {
        val tlsFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
            commonName = "api.telegram.org",
            sanDnsNames = listOf("api.telegram.org", "localhost"),
            sanIps = listOf("127.0.0.1"),
            validityDays = 7,
        )
        val telegramServer = MockWebServer().apply {
            dispatcher = externalDispatcher
            useHttps(tlsFixture.sslContext.socketFactory, false)
            start()
        }
        track(AutoCloseable { telegramServer.shutdown() })
        val routingBaseClient = forumOkHttp.newBuilder()
            .proxy(java.net.Proxy.NO_PROXY)
            .dns { hostname ->
                if (hostname.equals("api.telegram.org", ignoreCase = true)) {
                    listOf(InetAddress.getByName("127.0.0.1"))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
            .socketFactory(PortRedirectSocketFactory(targetPort = telegramServer.port))
            .sslSocketFactory(tlsFixture.sslContext.socketFactory, tlsFixture.trustManager)
            .build()
        val forwarder = AlertForwarderImpl(
            store = store,
            baseHttpClient = routingBaseClient,
            ioDispatcher = Dispatchers.IO,
            clockMillis = clock::nowMillis,
        )
        return telegramServer to forwarder
    }

    val widgetSnapshotRepository = WidgetSnapshotRepositoryImpl(
        fleetRepository = fleetRepository,
        sessionRepository = sessionRepository,
        checkInRepository = checkInRepository,
        settingsRepository = settingsRepository,
        radarRepository = radarRepository,
        seekMailRepository = seekMailRepository,
        todayProvider = clock::todayShanghai,
    )

    private val trackedCloseables = CopyOnWriteArrayList<AutoCloseable>()

    fun <T : AutoCloseable> track(closeable: T): T {
        trackedCloseables.add(closeable)
        return closeable
    }

    fun buildIpIntelligenceEndpoints(): IpIntelligenceEndpoints {
        val origin = externalServer.url("/").toString().trimEnd('/')
        return IpIntelligenceEndpoints(
            ipWhoIsTargetTemplate = "$origin/ipwhois/{ip}",
            ipInfoTargetTemplate = "$origin/ipinfo/{ip}",
            ipApiTargetTemplate = "$origin/ipapi/{ip}",
            ripeStatWhoisTemplate = "$origin/ripestat/{ip}",
            rdapTemplates = listOf("$origin/rdap-ip/{ip}"),
            clientSelfUrls = listOf(
                "ipwho.is" to "$origin/self/ipwhois",
                "ipinfo.io" to "$origin/self/ipinfo",
            ),
            clientDirectUrls = listOf(
                "ipwho.is" to "$origin/direct/ipwhois",
                "ipinfo.io" to "$origin/direct/ipinfo",
            ),
        )
    }

    fun createIpIntelligenceClient(
        networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
        endpoints: IpIntelligenceEndpoints = buildIpIntelligenceEndpoints(),
    ): IpIntelligenceClient = IpIntelligenceClient(
        httpClient = externalCookieFreeHttpClient,
        endpoints = endpoints,
        networkBridge = networkBridge,
    )

    fun registerNetBenchGeoIpRoutes(
        clientEgressIp: String = "114.240.88.123",
    ) {
        val root = TempScriptParser.json.parseToJsonElement(
            E2eFixtures.loadText("netbench/geoip_and_rdap_hops.json"),
        ).jsonObject
        val ipWhoIsMap = root.getValue("ipWhoIs").jsonObject
        val ripeStatBody = root.getValue("ripeStatWhois").toString()
        val rdapIpBody = root.getValue("rdapIp").toString()

        externalDispatcher.registerPrefix("GET", "/ipwhois/") { req ->
            val rawIp = req.requestUrl?.encodedPath?.removePrefix("/ipwhois/").orEmpty()
            val decodedIp = java.net.URLDecoder.decode(rawIp, "UTF-8")
            val entry = ipWhoIsMap[decodedIp]
            if (entry != null) {
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(entry.toString())
            } else {
                MockResponse().setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":false,"ip":"$decodedIp"}""")
            }
        }
        externalDispatcher.registerPrefix("GET", "/ripestat/") {
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(ripeStatBody)
        }
        externalDispatcher.registerPrefix("GET", "/rdap-ip/") {
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(rdapIpBody)
        }
        val selfBody = ipWhoIsMap[clientEgressIp]?.toString()
            ?: """{"success":true,"ip":"$clientEgressIp","country_code":"CN","country":"China","region":"Beijing","city":"Beijing","connection":{"asn":4134,"org":"China Telecom","isp":"China Telecom"}}"""
        externalDispatcher.register("GET", "/direct/ipwhois") {
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(selfBody)
        }
        externalDispatcher.register("GET", "/self/ipwhois") {
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(selfBody)
        }
    }

    fun createSshSessionManager(
        scope: CoroutineScope = backgroundScope,
        transport: SshTransport = E2eSshTransport(),
        sftpTransport: SshSftpTransport? = null,
        fleetRepo: FleetRepository? = null,
        baselineRepo: BenchmarkBaselineRepository? = benchmarkBaselineStore,
    ): LiteSshSessionManager = LiteSshSessionManager(
        vaultRepository = sshVaultRepository,
        executor = LiteSshExecutor(
            transport = transport,
            clock = clock::nowMillis,
        ),
        externalScope = scope,
        foregroundServiceController = NoOpSshForegroundServiceController,
        clock = clock::nowMillis,
        enableElapsedTicker = false,
        fleetRepository = fleetRepo,
        baseHttpClient = externalCookieFreeHttpClient,
        sftpTransportOverride = sftpTransport,
        benchmarkBaselineRepository = baselineRepo,
    )

    fun createNetBenchController(
        scope: CoroutineScope = backgroundScope,
        networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
        targetResolver: NetBenchTargetResolver = NetBenchTargetResolver(networkBridge = networkBridge),
        ipIntelligenceEndpoints: IpIntelligenceEndpoints = buildIpIntelligenceEndpoints(),
        ipIntelligenceClient: IpIntelligenceClient = createIpIntelligenceClient(
            networkBridge = networkBridge,
            endpoints = ipIntelligenceEndpoints,
        ),
        tracerouteKernelOps: TracerouteKernelOps = E2eTracerouteKernelOps(),
        pingExecutor: PingProcessExecutor = E2ePingProcessExecutor(),
        reverseDnsLookup: ReverseDnsLookup = ReverseDnsLookup { null },
        tracerouteRunner: TracerouteRunner = TracerouteRunner(
            resolver = targetResolver,
            networkBridge = networkBridge,
            kernelOps = tracerouteKernelOps,
            pingExecutor = pingExecutor,
            ipIntelligenceClient = ipIntelligenceClient,
            reverseDnsLookup = reverseDnsLookup,
            clockMillis = clock::nowMillis,
        ),
        sshSessionManager: LiteSshSessionManager? = null,
        baselineRepo: BenchmarkBaselineRepository = benchmarkBaselineStore,
        initialTarget: String = "",
    ): NetBenchSessionController = NetBenchSessionController(
        scope = scope,
        historyStore = netBenchHistoryStore,
        networkBridge = networkBridge,
        targetResolver = targetResolver,
        ipIntelligenceClient = ipIntelligenceClient,
        imageHostRepository = imageHostRepository,
        settingsRepository = settingsRepository,
        initialTarget = initialTarget,
        sshVaultRepository = sshVaultRepository,
        tracerouteRunner = tracerouteRunner,
        sshSessionManager = sshSessionManager,
        benchmarkBaselineRepository = baselineRepo,
    )

    fun createNetBenchViewModel(
        networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
        targetResolver: NetBenchTargetResolver = NetBenchTargetResolver(networkBridge = networkBridge),
        ipIntelligenceEndpoints: IpIntelligenceEndpoints = buildIpIntelligenceEndpoints(),
        ipIntelligenceClient: IpIntelligenceClient = createIpIntelligenceClient(
            networkBridge = networkBridge,
            endpoints = ipIntelligenceEndpoints,
        ),
        tracerouteKernelOps: TracerouteKernelOps = E2eTracerouteKernelOps(),
        pingExecutor: PingProcessExecutor = E2ePingProcessExecutor(),
        reverseDnsLookup: ReverseDnsLookup = ReverseDnsLookup { null },
        tracerouteRunner: TracerouteRunner = TracerouteRunner(
            resolver = targetResolver,
            networkBridge = networkBridge,
            kernelOps = tracerouteKernelOps,
            pingExecutor = pingExecutor,
            ipIntelligenceClient = ipIntelligenceClient,
            reverseDnsLookup = reverseDnsLookup,
            clockMillis = clock::nowMillis,
        ),
        sshSessionManager: LiteSshSessionManager? = null,
        baselineRepo: BenchmarkBaselineRepository = benchmarkBaselineStore,
        initialTarget: String = "",
    ): MobileNetBenchViewModel = manage(
        MobileNetBenchViewModel(
            historyStore = netBenchHistoryStore,
            networkBridge = networkBridge,
            targetResolver = targetResolver,
            ipIntelligenceClient = ipIntelligenceClient,
            imageHostRepository = imageHostRepository,
            settingsRepository = settingsRepository,
            initialTarget = initialTarget,
            sshVaultRepository = sshVaultRepository,
            tracerouteRunner = tracerouteRunner,
            sshSessionManager = sshSessionManager,
            benchmarkBaselineRepository = baselineRepo,
        ),
    )

    fun createTopicDetailViewModel(
        topicId: Long,
        initialFloor: Int = 0,
        titleHint: String = "",
        container: AppContainer = buildAppContainer(),
    ): TopicDetailViewModel = manage(
        TopicDetailViewModel.factory(
            container = container,
            topicId = topicId,
            initialFloor = initialFloor,
            titleHint = titleHint,
        ).create(TopicDetailViewModel::class.java),
    )

    fun createTopicComposerViewModel(
        editTopicId: Long? = null,
        initialCategorySlug: String = "daily",
        initialTitle: String = "",
        initialMarkdown: String = "",
        prefillKey: String = "",
        container: AppContainer = buildAppContainer(),
    ): TopicComposerViewModel = manage(
        TopicComposerViewModel.factory(
            container = container,
            editTopicId = editTopicId,
            initialCategorySlug = initialCategorySlug,
            initialTitle = initialTitle,
            initialMarkdown = initialMarkdown,
            prefillKey = prefillKey,
        ).create(TopicComposerViewModel::class.java),
    )

    fun createSeekMailViewModel(
        initialMailboxId: Long = 0L,
        initialMailId: Long = 0L,
        container: AppContainer = buildAppContainer(),
    ): SeekMailViewModel = manage(
        SeekMailViewModel.factory(
            container = container,
            initialMailboxId = initialMailboxId,
            initialMailId = initialMailId,
        ).create(SeekMailViewModel::class.java),
    )

    fun testAppContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = tempDir
        override fun getNoBackupFilesDir(): File = tempDir
        override fun getCacheDir(): File = File(tempDir, "cache").apply { mkdirs() }
        override fun getSystemService(name: String): Any? = null
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            PackageManager.PERMISSION_DENIED
    }

    fun createPulseHubViewModel(
        container: AppContainer = buildAppContainer(
            overrides = mapOf("getInboxRepository" to inboxRepository),
        ),
    ): PulseHubViewModel = manage(
        PulseHubViewModel.factory(container = container).create(PulseHubViewModel::class.java),
    )

    fun createPulseRadarMonitor(
        appContext: Context = testAppContext(),
        watermarkStore: RadarWatermarkStore = radarWatermarkStore,
        inboxRepo: InboxRepositoryImpl = inboxRepository,
        notificationDispatcher: PulseNotificationDispatcher = object : PulseNotificationDispatcher(appContext) {
            override fun ensureChannels() = Unit
            override fun hasPostNotificationsPermission(): Boolean = true
            override fun postKeywordHitNotification(record: LocalAlertRecord) = Unit
            override fun postTrackedReplyNotification(record: LocalAlertRecord, newReplyCount: Int) = Unit
            override fun postForumInteractionNotification(
                itemId: Long,
                isMention: Boolean,
                senderName: String,
                topicId: Long,
                topicTitle: String,
                floorNumber: Int,
                excerpt: String,
            ) = Unit
            override fun postDirectMessageNotification(
                peerUid: Long,
                peerName: String,
                messageId: Long,
                previewText: String,
            ) = Unit
        },
        isUnmeteredNetwork: () -> Boolean = { true },
        currentMinuteOfDay: () -> Int = { 12 * 60 },
        forwarder: AlertForwarder = alertForwarder,
    ): PulseRadarMonitor = PulseRadarMonitor(
        appContext = appContext,
        scope = backgroundScope,
        apiClient = apiClient,
        inboxRepository = inboxRepo,
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        radarRepository = radarRepository,
        notificationDispatcher = notificationDispatcher,
        watermarkStore = watermarkStore,
        clockMillis = clock::nowMillis,
        isUnmeteredNetwork = isUnmeteredNetwork,
        currentMinuteOfDay = currentMinuteOfDay,
        alertForwarder = forwarder,
    )

    fun createFleetMonitorRunner(
        fleetRepo: FleetRepository = fleetRepository,
        alertSink: FleetAlertSink = E2eFleetAlertSink(),
        fxRatesProvider: suspend () -> FxRatesState? = { null },
        forwarder: AlertForwarder = alertForwarder,
    ): FleetMonitorRunner = FleetMonitorRunner(
        fleetRepository = fleetRepo,
        alertSink = alertSink,
        fxRatesProvider = fxRatesProvider,
        todayProvider = clock::todayShanghai,
        nowMillisProvider = clock::nowMillis,
        alertForwarder = forwarder,
    )

    fun createBackupManager(
        baselineRepo: BenchmarkBaselineRepository = benchmarkBaselineStore,
    ): BackupManagerImpl = BackupManagerImpl(
        settingsRepository = settingsRepository,
        radarRepository = radarRepository,
        bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
        draftRepository = draftRepository,
        userRepository = userRepository,
        sessionRepository = sessionRepository,
        blockedUsersCache = blockedUsersCache,
        clockMillis = clock::nowMillis,
        fullBackupStores = FullBackupV2Stores(
            fleetRepository = fleetRepository,
            sshVaultRepository = sshVaultRepository,
            netBenchHistoryStore = netBenchHistoryStore,
            exchangeRateRepository = exchangeRateRepository,
            benchmarkBaselineRepository = baselineRepo,
        ),
        backupCryptoV2 = BackupCryptoV2(),
    )

    fun seedAuthenticatedSession(
        uid: Long = 1001L,
        username: String = "tester",
        rank: Int = 2,
        coin: Int = 200,
        stardust: Int = 50,
        sessionCookie: String = "ns_e2e_sess",
        cfClearance: String? = null,
        configJsonOverride: String? = null,
    ) {
        val forumUrl = forumBaseOrigin
        val canonicalUrl = NodeSeekUrlNormalizer.ORIGIN.toHttpUrl()

        fun buildCookies(host: String, sharedDotDomain: Boolean = false): List<Cookie> = buildList {
            add(
                Cookie.Builder()
                    .name("session")
                    .value(sessionCookie)
                    .domain(host)
                    .path("/")
                    .apply { if (useHttpsNodeSeekOrigin || sharedDotDomain) secure() }
                    .build(),
            )
            if (!cfClearance.isNullOrBlank()) {
                add(
                    Cookie.Builder()
                        .name("cf_clearance")
                        .value(cfClearance)
                        .domain(host)
                        .path("/")
                        .apply { if (useHttpsNodeSeekOrigin || sharedDotDomain) secure() }
                        .build(),
                )
            }
        }

        if (useHttpsNodeSeekOrigin) {
            cookieBridge.saveFromResponse(
                forumUrl,
                buildCookies(NodeSeekUrlNormalizer.BARE_HOST, sharedDotDomain = true),
            )
        } else {
            cookieBridge.saveFromResponse(forumUrl, buildCookies(forumUrl.host))
            cookieBridge.saveFromResponse(canonicalUrl, buildCookies(canonicalUrl.host))
        }

        val config = if (configJsonOverride != null) {
            TempScriptParser.json.decodeFromString(ConfigPayloadDto.serializer(), configJsonOverride)
        } else {
            ConfigPayloadDto(
                user = ConfigUserDto(
                    memberId = uid,
                    memberName = username,
                    coin = coin,
                    stardust = stardust,
                    rank = rank,
                ),
                enableCustomedStyle = true,
                allCategory = listOf(
                    CategoryDto(key = "daily", cnText = "日常", customizable = true, showInIndex = true),
                    CategoryDto(key = "tech", cnText = "技术", customizable = true, showInIndex = true),
                    CategoryDto(key = "trade", cnText = "交易", customizable = true, showInIndex = true),
                    CategoryDto(key = "carpool", cnText = "拼车", customizable = true, showInIndex = true),
                ),
            )
        }
        sessionRepository.onConfigPayloadObserved(config)
    }

    fun buildAppContainer(overrides: Map<String, Any?> = emptyMap()): AppContainer {
        val defaultBindings: Map<String, Any?> = mapOf(
            "getSessionRepository" to sessionRepository,
            "getForumRepository" to forumRepository,
            "getAwayDigestRepository" to NoOpAwayDigestRepository,
            "getCheckInRepository" to checkInRepository,
            "getUserRepository" to userRepository,
            "getBookmarkAndHistoryRepository" to bookmarkAndHistoryRepository,
            "getSettingsRepository" to settingsRepository,
            "getDraftRepository" to draftRepository,
            "getContentFilterRepository" to contentFilterRepository,
            "getImageHostRepository" to imageHostRepository,
            "getVpsMailAutoIngestService" to vpsMailAutoIngestService,
            "getActivityMonitorController" to NoOpActivityMonitorController,
            "getOfficialTradeRepository" to NoOpOfficialTradeRepository,
            "getFleetRepository" to fleetRepository,
            "getNetBenchHistoryStore" to netBenchHistoryStore,
            "getSshVaultRepository" to sshVaultRepository,
            "getOverseasPromoMonitorController" to NoOpOverseasPromoMonitorController,
            "getCommunityToolsRepository" to communityToolsRepository,
            "getPollAndStardustRepository" to pollAndStardustRepository,
            "getLuckyDrawRepository" to luckyDrawRepository,
            "getBenchmarkBaselineRepository" to benchmarkBaselineStore,
            "getDomainRdapClient" to domainRdapClient,
            "getTlsCertificateInspector" to tlsCertificateInspector,
            "getExchangeRateRepository" to exchangeRateRepository,
            "getSshSessionManager" to defaultSshSessionManager,
            "getRadarRepository" to radarRepository,
            "getTradeMarketReferenceRepository" to tradeMarketReferenceRepository,
            "getSeekMailRepository" to seekMailRepository,
            "getSeekMailOtpWatcher" to defaultSeekMailOtpWatcher,
            "getAlertForwarder" to alertForwarder,
            "getWidgetSnapshotRepository" to widgetSnapshotRepository,
            "getMultiAccountManager" to multiAccountManager,
            "getCoilOkHttpClient" to coilOkHttpClient,
        )

        return Proxy.newProxyInstance(
            AppContainer::class.java.classLoader,
            arrayOf(AppContainer::class.java),
        ) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "toString" -> "E2eAppContainerProxy@${System.identityHashCode(proxy)}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException(
                        "Unexpected Object call on E2eAppContainerProxy: ${method.name}",
                    )
                }
            }

            val propName = if (method.name.startsWith("get") && method.name.length > 3) {
                method.name.removePrefix("get").replaceFirstChar { it.lowercase() }
            } else {
                method.name
            }
            when {
                overrides.containsKey(method.name) -> overrides[method.name]
                overrides.containsKey(propName) -> overrides[propName]
                defaultBindings.containsKey(method.name) -> defaultBindings[method.name]
                method.isDefault -> invokeDefaultMethod(proxy, method, args)
                else -> invokeKotlinDefaultImpls(proxy, method, args)
                    ?: throw UnsupportedOperationException(
                        "Unexpected AppContainer call in E2eTestHarness: ${method.name}",
                    )
            }
        } as AppContainer
    }

    private fun invokeDefaultMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val safeArgs: Array<Any?> = if (args == null) emptyArray() else Array(args.size) { args[it] }
        val invokeDefault = runCatching {
            InvocationHandler::class.java.getMethod(
                "invokeDefault",
                Any::class.java,
                Method::class.java,
                Array<Any>::class.java,
            )
        }.getOrNull()
        if (invokeDefault != null) {
            return try {
                invokeDefault.invoke(null, proxy, method, safeArgs)
            } catch (ex: InvocationTargetException) {
                throw ex.cause ?: ex
            }
        }
        return invokeKotlinDefaultImpls(proxy, method, args)
            ?: throw UnsupportedOperationException(
                "Unable to invoke default method on AppContainer: ${method.name}",
            )
    }

    private fun invokeKotlinDefaultImpls(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val declaringClass = method.declaringClass
        val defaultImplsClass = runCatching {
            Class.forName("${declaringClass.name}\$DefaultImpls", false, declaringClass.classLoader)
        }.getOrNull() ?: return null
        val paramTypes = arrayOf(declaringClass, *method.parameterTypes)
        val staticMethod = runCatching {
            defaultImplsClass.getMethod(method.name, *paramTypes)
        }.getOrNull() ?: return null
        val callArgs: Array<Any?> = Array((args?.size ?: 0) + 1) { index ->
            if (index == 0) proxy else args!![index - 1]
        }
        return try {
            staticMethod.invoke(null, *callArgs)
        } catch (ex: InvocationTargetException) {
            throw ex.cause ?: ex
        }
    }

    fun assertNoCookiesLeakedToExternalServer() {
        val violations = externalDispatcher.cookieViolations()
        if (violations.isNotEmpty()) {
            val summary = violations.joinToString { "${it.method} ${it.path} -> Cookie=${it.getHeader("Cookie")}" }
            throw AssertionError("Cookie header leaked to externalServer: $summary")
        }
        val leakedRequests = externalDispatcher.recordedRequests().filter { it.getHeader("Cookie") != null }
        if (leakedRequests.isNotEmpty()) {
            val summary = leakedRequests.joinToString { "${it.method} ${it.path} -> Cookie=${it.getHeader("Cookie")}" }
            throw AssertionError("Recorded externalServer request carried Cookie header: $summary")
        }
    }

    override fun close() {
        trackedOtpWatchers.forEach { runCatching { it.stopWatch() } }
        trackedOtpWatchers.clear()
        val vmJobs = trackedViewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        runCatching { viewModelStore.clear() }
        trackedCloseables.forEach { runCatching { it.close() } }
        trackedCloseables.clear()
        runCatching { forumServer.shutdown() }
        runCatching { externalServer.shutdown() }
        dataStoreScope.cancel()
        backgroundScope.cancel()
        val dataJob = dataStoreScope.coroutineContext[Job]
        val bgJob = backgroundScope.coroutineContext[Job]
        runCatching {
            runBlocking(Dispatchers.Default) {
                withTimeoutOrNull(2_000L) {
                    vmJobs.forEach { it.join() }
                    dataJob?.join()
                    bgJob?.join()
                }
            }
        }
        if (tempDir.exists()) {
            tempDir.walkBottomUp().forEach { it.setWritable(true) }
            tempDir.deleteRecursively()
        }
    }

    companion object {
        const val DEFAULT_TEST_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val nodeSeekHttpsTlsFixture: E2eBitmapAndTlsFixtures.TlsServerFixture by lazy {
            E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
                commonName = NodeSeekUrlNormalizer.CANONICAL_HOST,
                sanDnsNames = listOf(
                    NodeSeekUrlNormalizer.CANONICAL_HOST,
                    NodeSeekUrlNormalizer.BARE_HOST,
                    "localhost",
                ),
                sanIps = listOf("127.0.0.1"),
                validityDays = 30,
            )
        }
    }
}

/**
 * Public-boundary scriptable [SshTransport] double supporting JumpHost + TOFU verification
 * and deterministic command execution for R5/R6/R7 E2E tests.
 */
class E2eSshTransport(
    var hostKeyByServerOrHost: Map<String, HostKeyInfo> = emptyMap(),
    var commandHandler: suspend (request: SshConnectionRequest, command: String) -> List<SshStreamEvent> = { _, _ ->
        listOf(
            SshStreamEvent.Connected,
            SshStreamEvent.OutputChunk("ok\n"),
            SshStreamEvent.Completed(0),
        )
    },
    var failWith: Throwable? = null,
) : SshTransport {

    val recordedRequests = CopyOnWriteArrayList<Pair<SshConnectionRequest, String>>()

    override fun execute(
        request: SshConnectionRequest,
        command: String,
    ): Flow<SshStreamEvent> = flow {
        recordedRequests.add(request to command)
        failWith?.let { throw it }

        // Verify JumpHost chain TOFU before target
        for (hop in request.jumpHosts) {
            val actualKey = resolveHostKey(hop.serverId, hop.host, hop.port)
            val verification = HostKeyTrustPolicy.verify(
                expectedFingerprint = hop.expectedHostKeyFingerprint,
                actual = actualKey,
            )
            if (verification is HostKeyVerificationResult.KeyChangedWarning && !hop.allowHostKeyMismatchOverride) {
                throw SshHostKeyMismatchException(
                    expectedFingerprint = verification.expectedFingerprint,
                    actualHostKey = verification.actualHostKey,
                    serverId = hop.serverId,
                    serverAlias = hop.displayLabel,
                )
            }
            emit(SshStreamEvent.HostKeyObserved(verification = verification, serverId = hop.serverId))
        }

        // Verify target TOFU before authentication/command execution
        val targetKey = resolveHostKey(request.serverId, request.host, request.port)
        val targetVerification = HostKeyTrustPolicy.verify(
            expectedFingerprint = request.expectedHostKeyFingerprint,
            actual = targetKey,
        )
        if (targetVerification is HostKeyVerificationResult.KeyChangedWarning && !request.allowHostKeyMismatchOverride) {
            throw SshHostKeyMismatchException(
                expectedFingerprint = targetVerification.expectedFingerprint,
                actualHostKey = targetVerification.actualHostKey,
                serverId = request.serverId,
                serverAlias = request.alias.ifBlank { request.host },
            )
        }
        emit(SshStreamEvent.HostKeyObserved(verification = targetVerification, serverId = request.serverId))

        val events = commandHandler(request, command)
        for (ev in events) {
            emit(ev)
        }
    }

    private fun resolveHostKey(serverId: String?, host: String, port: Int): HostKeyInfo {
        val mapped = serverId?.let { hostKeyByServerOrHost[it] } ?: hostKeyByServerOrHost[host]
        if (mapped != null) return mapped
        val fp = HostKeyTrustPolicy.computeSha256Fingerprint("$host:$port".toByteArray())
        return HostKeyInfo(
            host = host,
            port = port,
            algorithm = "ssh-ed25519",
            fingerprintSha256 = fp,
        )
    }
}

/**
 * In-memory remote filesystem [SshSftpClient] double implementing the public [SshSftpClient] contract,
 * enforcing `.bak` creation before overwrite, binary/truncation preview guards, and close lifecycle.
 */
class E2eSftpClient(
    override val homeDirectory: String = "/root",
    initialFiles: Map<String, ByteArray> = emptyMap(),
    initialDirs: Set<String> = emptySet(),
    var failBackupWith: SshSftpErrorCode? = null,
    var failReadWith: SshSftpErrorCode? = null,
    var uploadChunkDelayMillis: Long = 0L,
) : SshSftpClient {

    private val lock = Any()
    private val connected = AtomicBoolean(true)
    val closeCount = AtomicInteger(0)
    val listDirCalls = CopyOnWriteArrayList<String>()
    val files = LinkedHashMap<String, ByteArray>()
    val directories = LinkedHashSet<String>()

    init {
        directories.add("/")
        addDirWithParents(homeDirectory)
        initialDirs.forEach { addDirWithParents(it) }
        initialFiles.forEach { (path, bytes) ->
            val norm = normalizePath(path)
            files[norm] = bytes.copyOf()
            addDirWithParents(parentOf(norm))
        }
    }

    override val isConnected: Boolean
        get() = connected.get()

    fun readRawFileBytes(remotePath: String): ByteArray? = synchronized(lock) {
        files[normalizePath(remotePath)]?.copyOf()
    }

    override suspend fun listDir(path: String): List<SshSftpEntry> {
        ensureConnected()
        val resolved = normalizeDir(path)
        listDirCalls.add(resolved)
        return synchronized(lock) {
            if (resolved !in directories) {
                if (resolved in files) {
                    throw SshSftpException(SshSftpErrorCode.NOT_DIRECTORY, "不是目录：$resolved")
                }
                throw SshSftpException(SshSftpErrorCode.NOT_FOUND, "目录不存在：$resolved")
            }
            val directDirs = directories
                .filter { it != resolved && parentOf(it) == resolved }
                .map { dirPath ->
                    SshSftpEntry(
                        name = dirPath.substringAfterLast('/'),
                        fullPath = dirPath,
                        isDirectory = true,
                        permissionsString = "drwxr-xr-x",
                        modifiedEpochSeconds = 1_764_000_000L,
                    )
                }
                .sortedBy { it.name.lowercase() }
            val directFiles = files
                .filterKeys { parentOf(it) == resolved }
                .map { (filePath, bytes) ->
                    SshSftpEntry(
                        name = filePath.substringAfterLast('/'),
                        fullPath = filePath,
                        isDirectory = false,
                        sizeBytes = bytes.size.toLong(),
                        permissionsString = "-rw-r--r--",
                        modifiedEpochSeconds = 1_764_000_000L,
                    )
                }
                .sortedBy { it.name.lowercase() }
            directDirs + directFiles
        }
    }

    override suspend fun readTextPreview(
        remotePath: String,
        maxBytes: Long,
        tailIfLarger: Boolean,
    ): SshSftpTextContent {
        ensureConnected()
        failReadWith?.let { throw SshSftpException(it, "读取失败: $it") }
        val resolved = normalizePath(remotePath)
        val raw = synchronized(lock) {
            if (resolved in directories) {
                throw SshSftpException(SshSftpErrorCode.IS_DIRECTORY, "目标是目录：$resolved")
            }
            files[resolved] ?: throw SshSftpException(SshSftpErrorCode.NOT_FOUND, "文件不存在：$resolved")
        }
        val totalBytes = raw.size.toLong()
        val isTruncated = totalBytes > maxBytes
        val slice = if (isTruncated) {
            val takeLen = maxBytes.toInt().coerceAtMost(raw.size)
            if (tailIfLarger) {
                raw.copyOfRange(raw.size - takeLen, raw.size)
            } else {
                raw.copyOfRange(0, takeLen)
            }
        } else {
            raw
        }
        val isBinary = slice.any { it == 0.toByte() }
        val text = if (isBinary) {
            "[Binary file: $totalBytes bytes]"
        } else {
            slice.toString(Charsets.UTF_8)
        }
        return SshSftpTextContent(
            remotePath = resolved,
            text = text,
            totalBytes = totalBytes,
            readBytes = slice.size.toLong(),
            isTruncatedFromHead = isTruncated && tailIfLarger,
            isBinaryDetected = isBinary,
            canEdit = !isTruncated && !isBinary && totalBytes <= SshSftpClient.MAX_EDITABLE_BYTES,
        )
    }

    override suspend fun writeWithBakBackup(
        remotePath: String,
        content: ByteArray,
        createBackupIfExists: Boolean,
    ): SshSftpWriteResult {
        ensureConnected()
        val resolved = normalizePath(remotePath)
        val backupPath = "$resolved.bak"
        return synchronized(lock) {
            val existing = files[resolved]
            var backupCreated = false
            if (existing != null && createBackupIfExists) {
                failBackupWith?.let { code ->
                    throw SshSftpException(code, "创建备份 $backupPath 失败 ($code)")
                }
                files[backupPath] = existing.copyOf()
                backupCreated = true
            }
            addDirWithParents(parentOf(resolved))
            files[resolved] = content.copyOf()
            SshSftpWriteResult(
                remotePath = resolved,
                backupPath = if (backupCreated) backupPath else null,
                bytesWritten = content.size.toLong(),
                backupCreated = backupCreated,
            )
        }
    }

    override suspend fun uploadStream(
        remotePath: String,
        inputStream: InputStream,
        overwrite: Boolean,
    ): Long {
        ensureConnected()
        val resolved = normalizePath(remotePath)
        val buffer = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            if (uploadChunkDelayMillis > 0L) {
                kotlinx.coroutines.delay(uploadChunkDelayMillis)
            }
            val read = inputStream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        val bytes = out.toByteArray()
        synchronized(lock) {
            if (!overwrite && resolved in files) {
                throw SshSftpException(SshSftpErrorCode.PERMISSION_DENIED, "文件已存在：$resolved")
            }
            addDirWithParents(parentOf(resolved))
            files[resolved] = bytes
        }
        return bytes.size.toLong()
    }

    override suspend fun mkdir(remotePath: String) {
        ensureConnected()
        val resolved = normalizePath(remotePath)
        synchronized(lock) {
            addDirWithParents(resolved)
        }
    }

    override suspend fun delete(remotePath: String, isDirectory: Boolean) {
        ensureConnected()
        val resolved = normalizePath(remotePath)
        synchronized(lock) {
            if (isDirectory) {
                if (resolved !in directories) {
                    throw SshSftpException(SshSftpErrorCode.NOT_FOUND, "目录不存在：$resolved")
                }
                val prefix = if (resolved == "/") "/" else "$resolved/"
                files.keys.removeAll { it.startsWith(prefix) }
                directories.removeAll { it == resolved || it.startsWith(prefix) }
            } else {
                if (files.remove(resolved) == null) {
                    throw SshSftpException(SshSftpErrorCode.NOT_FOUND, "文件不存在：$resolved")
                }
            }
        }
    }

    override fun close() {
        connected.set(false)
        closeCount.incrementAndGet()
    }

    private fun ensureConnected() {
        if (!connected.get()) {
            throw SshSftpException(SshSftpErrorCode.CONNECTION_LOST, "SFTP 连接已关闭")
        }
    }

    private fun normalizeDir(path: String): String {
        val trimmed = path.trim()
        return when {
            trimmed.isEmpty() || trimmed == "." -> normalizePath(homeDirectory)
            trimmed.startsWith("/") -> normalizePath(trimmed)
            else -> normalizePath("$homeDirectory/$trimmed")
        }
    }

    private fun normalizePath(path: String): String {
        val raw = if (path.startsWith("/")) path else "$homeDirectory/$path"
        val parts = ArrayList<String>()
        raw.split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    private fun parentOf(path: String): String {
        if (path == "/") return "/"
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }

    private fun addDirWithParents(dirPath: String) {
        var cur = normalizePath(dirPath)
        while (true) {
            directories.add(cur)
            if (cur == "/") break
            cur = parentOf(cur)
        }
    }
}

/**
 * Public-boundary [SshSftpTransport] enforcing JumpHost + TOFU verification before opening an [SshSftpClient].
 */
class E2eSftpTransport(
    var hostKeyByServerOrHost: Map<String, HostKeyInfo> = emptyMap(),
    var connectDelayMillis: Long = 0L,
    val connectDelayByServerId: MutableMap<String, Long> = ConcurrentHashMap(),
    var clientFactory: (SshConnectionRequest) -> E2eSftpClient = { E2eSftpClient() },
) : SshSftpTransport {

    val openedRequests = CopyOnWriteArrayList<SshConnectionRequest>()
    val openedClients = CopyOnWriteArrayList<E2eSftpClient>()

    override suspend fun openSftpSession(
        request: SshConnectionRequest,
        onHostKeyObserved: (HostKeyVerificationResult, String?) -> Unit,
    ): SshSftpClient {
        openedRequests.add(request)
        val effectiveDelayMillis = request.serverId?.let { connectDelayByServerId[it] } ?: connectDelayMillis
        if (effectiveDelayMillis > 0L) {
            kotlinx.coroutines.delay(effectiveDelayMillis)
        }

        for (hop in request.jumpHosts) {
            val hopKey = resolveHostKey(hop.serverId, hop.host, hop.port)
            val verification = HostKeyTrustPolicy.verify(hop.expectedHostKeyFingerprint, hopKey)
            if (verification is HostKeyVerificationResult.KeyChangedWarning && !hop.allowHostKeyMismatchOverride) {
                throw SshHostKeyMismatchException(
                    expectedFingerprint = verification.expectedFingerprint,
                    actualHostKey = verification.actualHostKey,
                    serverId = hop.serverId,
                    serverAlias = hop.displayLabel,
                )
            }
            onHostKeyObserved(verification, hop.serverId)
        }

        val targetKey = resolveHostKey(request.serverId, request.host, request.port)
        val verification = HostKeyTrustPolicy.verify(request.expectedHostKeyFingerprint, targetKey)
        if (verification is HostKeyVerificationResult.KeyChangedWarning && !request.allowHostKeyMismatchOverride) {
            throw SshHostKeyMismatchException(
                expectedFingerprint = verification.expectedFingerprint,
                actualHostKey = verification.actualHostKey,
                serverId = request.serverId,
                serverAlias = request.alias.ifBlank { request.host },
            )
        }
        onHostKeyObserved(verification, request.serverId)

        val client = clientFactory(request)
        openedClients.add(client)
        return client
    }

    private fun resolveHostKey(serverId: String?, host: String, port: Int): HostKeyInfo {
        val mapped = serverId?.let { hostKeyByServerOrHost[it] } ?: hostKeyByServerOrHost[host]
        if (mapped != null) return mapped
        val fp = HostKeyTrustPolicy.computeSha256Fingerprint("$host:$port".toByteArray())
        return HostKeyInfo(
            host = host,
            port = port,
            algorithm = "ssh-ed25519",
            fingerprintSha256 = fp,
        )
    }
}

/**
 * Public-boundary [TracerouteKernelOps] double simulating Stage-1 per-hop TTL probing
 * across multiple MTR rounds.
 */
class E2eTracerouteKernelOps(
    var supported: Boolean = true,
    var openSocketError: Throwable? = null,
    var hopResponder: (ttl: Int, sequence: Int, roundIndexByTtl: Int) -> TracerouteProbeReply = { ttl, seq, _ ->
        TracerouteProbeReply(
            kind = TracerouteProbeReply.Kind.TIMEOUT,
            responderIp = null,
            sequence = seq,
            rttMs = null,
        )
    },
) : TracerouteKernelOps {
    private val roundCountByTtl = LinkedHashMap<Int, Int>()
    private var lastTtl = 1

    override fun isSupported(): Boolean = supported

    override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?): FileDescriptor {
        openSocketError?.let { throw it }
        return FileDescriptor()
    }

    override fun sendEchoWithTtl(
        fd: FileDescriptor,
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        ttl: Int,
        identifier: Int,
        sequence: Int,
    ) {
        lastTtl = ttl
    }

    override fun awaitReplyOrError(
        fd: FileDescriptor,
        targetAddress: InetAddress,
        ipFamily: IpFamily,
        identifier: Int,
        expectedSequence: Int,
        timeoutMs: Int,
    ): TracerouteProbeReply {
        val count = synchronized(roundCountByTtl) {
            val prev = roundCountByTtl[lastTtl] ?: 0
            roundCountByTtl[lastTtl] = prev + 1
            prev
        }
        return hopResponder(lastTtl, expectedSequence, count)
    }

    override fun closeQuietly(fd: FileDescriptor) = Unit
}

/**
 * Public-boundary [NetBenchSocketChannel] double.
 */
class E2eSocketChannel(
    override val networkId: String = "eth0",
    override val transportLabel: String? = "Wi-Fi",
    override val isVpn: Boolean = false,
    override val isDirectPhysical: Boolean = !isVpn,
    override val bypassSystemHttpProxy: Boolean = !isVpn,
    override val supportsFileDescriptorBind: Boolean = !isVpn,
    private val socketError: Throwable? = null,
    private val dnsMap: Map<String, List<InetAddress>> = emptyMap(),
) : NetBenchSocketChannel {

    override val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = this@E2eSocketChannel.createSocket()
        override fun createSocket(host: String, port: Int): Socket = createSocket()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = createSocket()
    }

    override fun createSocket(): Socket {
        socketError?.let { throw it }
        return Socket()
    }

    override fun resolveAllByName(hostname: String): List<InetAddress> =
        dnsMap[hostname] ?: InetAddress.getAllByName(hostname).toList()
}

/**
 * Public-boundary [NetBenchNetworkBridge] double supporting VPN lockdown & direct physical topologies.
 */
class E2eNetBenchNetworkBridge(
    var bindingProvider: () -> ResolvedNetworkBinding = {
        val direct = E2eSocketChannel(
            networkId = "cell-direct-1",
            transportLabel = "蜂窝移动",
            isVpn = false,
        )
        val desc = NetworkDescriptor(
            token = direct as NetBenchSocketChannel,
            networkId = direct.networkId,
            isVpn = false,
            hasInternet = true,
            isValidated = true,
            isActiveDefault = true,
            transports = setOf(NetBenchTransportKind.CELLULAR),
            interfaceName = "rmnet_data0",
        )
        val plan = NetBenchNetworkSelector.selectNetworkPlan(listOf(desc))
        ResolvedNetworkBinding(
            plan = plan,
            directChannel = direct,
            proxyOrDefaultChannel = direct,
        )
    },
) : NetBenchNetworkBridge {
    override fun inspectCurrentNetworks(): ResolvedNetworkBinding = bindingProvider()

    companion object {
        fun vpnLockdownNoDirect(): E2eNetBenchNetworkBridge = E2eNetBenchNetworkBridge {
            val vpn = E2eSocketChannel(
                networkId = "vpn-tun0",
                transportLabel = "VPN 代理",
                isVpn = true,
            )
            val vpnDesc = NetworkDescriptor(
                token = vpn as NetBenchSocketChannel,
                networkId = vpn.networkId,
                isVpn = true,
                hasInternet = true,
                isValidated = true,
                isActiveDefault = true,
                transports = setOf(NetBenchTransportKind.VPN),
                interfaceName = "tun0",
            )
            val plan = NetBenchNetworkSelector.selectNetworkPlan(listOf(vpnDesc))
            ResolvedNetworkBinding(
                plan = plan,
                directChannel = null,
                proxyOrDefaultChannel = vpn,
            )
        }
    }
}

/**
 * Public-boundary [PingProcessExecutor] double for Stage-2 subprocess traceroute fallback tests.
 */
class E2ePingProcessExecutor(
    var handler: suspend (PingProcessRequest) -> List<PingExecutionEvent> = {
        listOf(PingExecutionEvent.Exited(1))
    },
) : PingProcessExecutor {
    override fun execute(request: PingProcessRequest): Flow<PingExecutionEvent> = flow {
        handler(request).forEach { emit(it) }
    }
}

private class E2eInMemoryWatermarkStore : RadarWatermarkStore {
    private val mutex = Mutex()
    private var current = RadarWatermarks()

    override suspend fun getWatermarks(): RadarWatermarks = mutex.withLock { current }

    override suspend fun updateWatermarks(transform: (RadarWatermarks) -> RadarWatermarks): RadarWatermarks =
        mutex.withLock {
            current = transform(current)
            current
        }
}

private class E2eInMemoryBlockedUsersCache : BlockedUsersCache {
    private val users = MutableStateFlow<List<FloorAuthor>>(emptyList())

    override suspend fun getBlockedUsers(): List<FloorAuthor> = users.value

    override suspend fun replaceAll(users: List<FloorAuthor>) {
        this.users.value = users
    }

    override suspend fun mergeAll(users: List<FloorAuthor>) {
        val map = LinkedHashMap<Long, FloorAuthor>()
        this.users.value.forEach { map[it.uid] = it }
        users.forEach { map[it.uid] = it }
        this.users.value = map.values.toList()
    }

    override suspend fun recordBlockedByName(username: String) = Unit

    override suspend fun removeByUid(uid: Long) {
        users.value = users.value.filterNot { it.uid == uid }
    }
}

/**
 * Public recording [OtpWatchNotificationSink] double for verifying Seek Mail OTP watch notification lifecycle.
 */
class E2eOtpWatchNotificationSink : OtpWatchNotificationSink {
    val ensureChannelCount = AtomicInteger(0)
    val cancelWatchingCount = AtomicInteger(0)
    val watchingUpdates = CopyOnWriteArrayList<OtpWatchState.Watching>()
    val foundNotifications = CopyOnWriteArrayList<OtpWatchState.Found>()
    val timedOutNotifications = CopyOnWriteArrayList<OtpWatchState.TimedOut>()

    override fun ensureChannel() {
        ensureChannelCount.incrementAndGet()
    }

    override fun postWatchingNotification(state: OtpWatchState.Watching) {
        watchingUpdates.add(state)
    }

    override fun postOtpFoundNotification(found: OtpWatchState.Found) {
        foundNotifications.add(found)
    }

    override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) {
        timedOutNotifications.add(timedOut)
    }

    override fun cancelWatchingNotification() {
        cancelWatchingCount.incrementAndGet()
    }
}

/**
 * Public recording [FleetAlertSink] double for verifying Fleet offline/recovery/renewal/traffic alerts.
 */
class E2eFleetAlertSink : FleetAlertSink {
    data class OfflineAlert(val panelName: String, val server: ProbeServerSnapshot)
    data class RecoveryAlert(val panelName: String, val server: ProbeServerSnapshot)
    data class RenewalReminder(val asset: VpsAssetRecord, val daysRemaining: Long, val renewalCny: BigDecimal)

    val ensureChannelsCount = AtomicInteger(0)
    val offlineAlerts = CopyOnWriteArrayList<OfflineAlert>()
    val recoveryAlerts = CopyOnWriteArrayList<RecoveryAlert>()
    val renewalReminders = CopyOnWriteArrayList<RenewalReminder>()
    val trafficAlerts = CopyOnWriteArrayList<FleetAlertTransition.TrafficThresholdReached>()

    override fun ensureChannels() {
        ensureChannelsCount.incrementAndGet()
    }

    override fun notifyServerOffline(panelName: String, server: ProbeServerSnapshot) {
        offlineAlerts.add(OfflineAlert(panelName, server))
    }

    override fun notifyServerRecovered(panelName: String, server: ProbeServerSnapshot) {
        recoveryAlerts.add(RecoveryAlert(panelName, server))
    }

    override fun notifyRenewalReminder(asset: VpsAssetRecord, daysRemaining: Long, renewalCny: BigDecimal) {
        renewalReminders.add(RenewalReminder(asset, daysRemaining, renewalCny))
    }

    override fun notifyTrafficWarning(alert: FleetAlertTransition.TrafficThresholdReached) {
        trafficAlerts.add(alert)
    }
}

private class PortRedirectSocketFactory(
    private val targetHost: String = "127.0.0.1",
    private val targetPort: Int,
    private val matchPort: Int = 443,
) : SocketFactory() {

    private fun rewrite(endpoint: SocketAddress): SocketAddress {
        val inet = endpoint as? InetSocketAddress ?: return endpoint
        return if (inet.port == matchPort) {
            InetSocketAddress(InetAddress.getByName(targetHost), targetPort)
        } else {
            endpoint
        }
    }

    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress) {
            super.connect(rewrite(endpoint))
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            super.connect(rewrite(endpoint), timeout)
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        if (port == matchPort) Socket(InetAddress.getByName(targetHost), targetPort) else Socket(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        if (port == matchPort) {
            Socket(InetAddress.getByName(targetHost), targetPort, localHost, localPort)
        } else {
            Socket(host, port, localHost, localPort)
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        if (port == matchPort) Socket(InetAddress.getByName(targetHost), targetPort) else Socket(host, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        if (port == matchPort) {
            Socket(InetAddress.getByName(targetHost), targetPort, localAddress, localPort)
        } else {
            Socket(address, port, localAddress, localPort)
        }
}

private class E2eInMemoryRadarRepository : RadarRepository, RadarMonitorSyncSupport {
    private val writeMutex = Mutex()
    private val ruleIdSeq = AtomicLong(0L)
    private val alertIdSeq = AtomicLong(0L)

    override val pollingSettingsFlow = MutableStateFlow(RadarPollingSettings())
    override val keywordRulesFlow = MutableStateFlow<List<KeywordWatchRule>>(emptyList())
    override val trackedTopicsFlow = MutableStateFlow<List<TrackedTopic>>(emptyList())
    override val alertRecordsFlow = MutableStateFlow<List<LocalAlertRecord>>(emptyList())
    override val unreadAlertCountFlow = MutableStateFlow(0)

    override fun observeTrackedTopic(topicId: Long): Flow<TrackedTopic?> =
        trackedTopicsFlow.map { list -> list.firstOrNull { it.topicId == topicId } }

    override suspend fun getPollingSettings(): RadarPollingSettings = pollingSettingsFlow.value

    override suspend fun updatePollingSettings(transform: (RadarPollingSettings) -> RadarPollingSettings) {
        writeMutex.withLock {
            pollingSettingsFlow.value = transform(pollingSettingsFlow.value)
        }
    }

    override suspend fun addKeywordRule(
        keyword: String,
        excludeWords: String,
        categorySlug: String,
        matchMode: KeywordMatchMode,
    ): Result<KeywordWatchRule> {
        val normalizedKeyword = RadarRuleCanonicalizer.validateAndNormalizeKeyword(keyword, matchMode)
            .getOrElse { return Result.failure(it) }
        val normalizedExclude = RadarRuleCanonicalizer.normalizeExcludeWords(excludeWords)
        val normalizedCategory = RadarRuleCanonicalizer.normalizeCategorySlug(categorySlug)
        val targetKey = RadarRuleCanonicalizer.dedupeKey(
            keyword = normalizedKeyword,
            excludeWords = normalizedExclude,
            categorySlug = normalizedCategory,
            matchMode = matchMode,
        )
        return writeMutex.withLock {
            val existing = keywordRulesFlow.value.firstOrNull {
                RadarRuleCanonicalizer.dedupeKey(it) == targetKey
            }
            if (existing != null) {
                return@withLock Result.success(existing)
            }
            val created = KeywordWatchRule(
                id = ruleIdSeq.incrementAndGet(),
                keyword = normalizedKeyword,
                excludeWords = normalizedExclude,
                categorySlug = normalizedCategory,
                matchMode = matchMode,
                isEnabled = true,
            )
            keywordRulesFlow.value = listOf(created) + keywordRulesFlow.value
            Result.success(created)
        }
    }

    override suspend fun toggleKeywordRule(ruleId: Long, enabled: Boolean) {
        writeMutex.withLock {
            keywordRulesFlow.update { rules ->
                rules.map { if (it.id == ruleId) it.copy(isEnabled = enabled) else it }
            }
        }
    }

    override suspend fun deleteKeywordRule(ruleId: Long) {
        writeMutex.withLock {
            keywordRulesFlow.update { rules -> rules.filterNot { it.id == ruleId } }
        }
    }

    override suspend fun trackTopic(
        topicId: Long,
        title: String,
        categorySlug: String,
        categoryName: String,
        authorName: String,
        currentMaxFloor: Int,
        totalPages: Int,
    ): TrackedTopic = writeMutex.withLock {
        val existing = trackedTopicsFlow.value.firstOrNull { it.topicId == topicId }
        val now = System.currentTimeMillis()
        val updated = if (existing != null) {
            existing.copy(
                title = title.ifBlank { existing.title },
                categorySlug = categorySlug.ifBlank { existing.categorySlug },
                categoryName = categoryName.ifBlank { existing.categoryName },
                authorName = authorName.ifBlank { existing.authorName },
                lastKnownFloor = maxOf(existing.lastKnownFloor, currentMaxFloor),
                lastKnownTotalPages = maxOf(existing.lastKnownTotalPages, totalPages),
                isActive = true,
                lastUpdatedEpochMillis = now,
            )
        } else {
            TrackedTopic(
                topicId = topicId,
                title = title,
                categorySlug = categorySlug,
                categoryName = categoryName,
                authorName = authorName,
                lastKnownFloor = currentMaxFloor,
                lastKnownTotalPages = totalPages.coerceAtLeast(1),
                isActive = true,
                createdAtEpochMillis = now,
                lastCheckedEpochMillis = 0L,
                lastUpdatedEpochMillis = now,
            )
        }
        trackedTopicsFlow.update { list ->
            listOf(updated) + list.filterNot { it.topicId == topicId }
        }
        updated
    }

    override suspend fun untrackTopic(topicId: Long) {
        writeMutex.withLock {
            trackedTopicsFlow.update { list -> list.filterNot { it.topicId == topicId } }
        }
    }

    override suspend fun toggleTrackTopic(
        topicId: Long,
        title: String,
        categorySlug: String,
        categoryName: String,
        authorName: String,
        currentMaxFloor: Int,
        totalPages: Int,
    ): Boolean {
        val exists = trackedTopicsFlow.value.any { it.topicId == topicId }
        if (exists) {
            untrackTopic(topicId)
            return false
        }
        trackTopic(
            topicId = topicId,
            title = title,
            categorySlug = categorySlug,
            categoryName = categoryName,
            authorName = authorName,
            currentMaxFloor = currentMaxFloor,
            totalPages = totalPages,
        )
        return true
    }

    override suspend fun onTopicFloorSeen(topicId: Long, maxSeenFloor: Int, totalPages: Int) {
        writeMutex.withLock {
            trackedTopicsFlow.update { list ->
                list.map { topic ->
                    if (topic.topicId == topicId) {
                        topic.copy(
                            lastKnownFloor = maxOf(topic.lastKnownFloor, maxSeenFloor),
                            lastKnownTotalPages = maxOf(topic.lastKnownTotalPages, totalPages),
                            unreadNewReplies = 0,
                        )
                    } else {
                        topic
                    }
                }
            }
        }
    }

    override suspend fun recordAlertIfAbsent(record: LocalAlertRecord): Boolean = writeMutex.withLock {
        if (alertRecordsFlow.value.any { it.dedupKey == record.dedupKey }) {
            return@withLock false
        }
        val persisted = if (record.id > 0L) record else record.copy(id = alertIdSeq.incrementAndGet())
        val next = (alertRecordsFlow.value + persisted)
            .sortedWith(
                compareByDescending<LocalAlertRecord> { it.createdAtEpochMillis }
                    .thenByDescending { it.id },
            )
        updateAlertsLocked(next)
        true
    }

    override suspend fun markAlertRead(alertId: Long, read: Boolean) {
        writeMutex.withLock {
            updateAlertsLocked(
                alertRecordsFlow.value.map { if (it.id == alertId) it.copy(isRead = read) else it },
            )
        }
    }

    override suspend fun markAlertReadByDedupKey(dedupKey: String) {
        writeMutex.withLock {
            updateAlertsLocked(
                alertRecordsFlow.value.map { if (it.dedupKey == dedupKey) it.copy(isRead = true) else it },
            )
        }
    }

    override suspend fun markAlertsReadForTopic(topicId: Long) {
        writeMutex.withLock {
            updateAlertsLocked(
                alertRecordsFlow.value.map { if (it.topicId == topicId) it.copy(isRead = true) else it },
            )
        }
    }

    override suspend fun markAllAlertsRead() {
        writeMutex.withLock {
            updateAlertsLocked(alertRecordsFlow.value.map { it.copy(isRead = true) })
        }
    }

    override suspend fun deleteAlert(alertId: Long) {
        writeMutex.withLock {
            updateAlertsLocked(alertRecordsFlow.value.filterNot { it.id == alertId })
        }
    }

    override suspend fun clearReadAlerts() {
        writeMutex.withLock {
            updateAlertsLocked(alertRecordsFlow.value.filterNot { it.isRead })
        }
    }

    override suspend fun clearAllAlerts() {
        writeMutex.withLock {
            updateAlertsLocked(emptyList())
        }
    }

    override suspend fun recordMonitorTopicProgress(
        topicId: Long,
        title: String?,
        categorySlug: String?,
        categoryName: String?,
        authorName: String?,
        newMaxFloor: Int,
        newTotalPages: Int,
        unreadDelta: Int,
        checkedAtEpochMillis: Long,
    ) {
        writeMutex.withLock {
            trackedTopicsFlow.update { list ->
                list.map { topic ->
                    if (topic.topicId == topicId) {
                        topic.copy(
                            title = title?.takeIf { it.isNotBlank() } ?: topic.title,
                            categorySlug = categorySlug?.takeIf { it.isNotBlank() } ?: topic.categorySlug,
                            categoryName = categoryName?.takeIf { it.isNotBlank() } ?: topic.categoryName,
                            authorName = authorName?.takeIf { it.isNotBlank() } ?: topic.authorName,
                            lastKnownFloor = maxOf(topic.lastKnownFloor, newMaxFloor),
                            lastKnownTotalPages = maxOf(topic.lastKnownTotalPages, newTotalPages),
                            unreadNewReplies = (topic.unreadNewReplies + unreadDelta).coerceAtLeast(0),
                            lastCheckedEpochMillis = checkedAtEpochMillis,
                            lastUpdatedEpochMillis = checkedAtEpochMillis,
                        )
                    } else {
                        topic
                    }
                }
            }
        }
    }

    override suspend fun markTopicCheckedWithoutNewReplies(
        topicId: Long,
        totalPages: Int?,
        checkedAtEpochMillis: Long,
    ) {
        writeMutex.withLock {
            trackedTopicsFlow.update { list ->
                list.map { topic ->
                    if (topic.topicId == topicId) {
                        topic.copy(
                            lastKnownTotalPages = totalPages?.let { maxOf(topic.lastKnownTotalPages, it) }
                                ?: topic.lastKnownTotalPages,
                            lastCheckedEpochMillis = checkedAtEpochMillis,
                        )
                    } else {
                        topic
                    }
                }
            }
        }
    }

    private fun updateAlertsLocked(records: List<LocalAlertRecord>) {
        alertRecordsFlow.value = records
        unreadAlertCountFlow.value = records.count { !it.isRead }
    }
}
