package com.nodeseek.app.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nodeseek.app.data.local.BookmarkDao
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.CategoryDao
import com.nodeseek.app.data.local.CategoryEntity
import com.nodeseek.app.data.local.RadarWatermarkStore
import com.nodeseek.app.data.local.RadarWatermarks
import com.nodeseek.app.data.local.ReadHistoryDao
import com.nodeseek.app.data.local.ReadHistoryEntity
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.SoftwareSettingsSecretCipher
import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.SameOriginHttpResponse
import com.nodeseek.app.data.network.SameOriginTransport
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.CookieHostScope
import com.nodeseek.app.domain.model.StoredCookieRecord
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@OptIn(ExperimentalCoroutinesApi::class)
class MultiAccountManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cookieBridge: WebViewCookieBridge
    private lateinit var csrfTokenStore: CsrfTokenStore
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var checkInRepository: CheckInRepositoryImpl
    private lateinit var inboxRepository: InboxRepositoryImpl
    private lateinit var bookmarkAndHistoryRepository: BookmarkAndHistoryRepositoryImpl
    private lateinit var forumRepository: ForumRepositoryImpl
    private lateinit var vault: FileAccountCookieVault
    private lateinit var managerScope: CoroutineScope
    private lateinit var manager: MultiAccountManagerImpl

    private val recordedPaths = CopyOnWriteArrayList<String>()
    private val expiredSessions = CopyOnWriteArrayList<String>()
    private val challengeSessions = CopyOnWriteArrayList<String>()
    private val disconnectSessions = CopyOnWriteArrayList<String>()
    private val serverErrorSessions = CopyOnWriteArrayList<String>()
    private val slowFeedGate = AtomicBoolean(false)
    private val slowFeedEntered = CountDownLatch(1)
    private val slowFeedRelease = CountDownLatch(1)
    private val fakeClock = AtomicLong(1_710_000_000_000L)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                recordedPaths.add(path)
                val cookieHeader = request.getHeader("Cookie").orEmpty()
                val sessionToken = extractCookieValue(cookieHeader, "session")

                return when {
                    path.startsWith("/api/account/signIn") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Set-Cookie", "session=sess_bob; Path=/; Secure")
                            .setBody("""{"success":true,"message":"ok"}""")
                    }
                    path.startsWith("/api/account/signOut") -> {
                        MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                    }
                    path.startsWith("/api/attendance/board") -> {
                        val uid = if (sessionToken == "sess_bob") 2002L else 1001L
                        val name = if (uid == 2002L) "Bob" else "Alice"
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "hasCheckedInToday": true,
                              "order": 3,
                              "total": 100,
                              "record": { "memberId": $uid, "memberName": "$name", "gain": 5, "createdAt": "2026-11-26T08:00:00Z" },
                              "list": []
                            }
                            """.trimIndent(),
                        )
                    }
                    path.startsWith("/api/notification/unread-count") -> {
                        val count = if (sessionToken == "sess_bob") 9 else 2
                        MockResponse().setResponseCode(200).setBody("""{"success":true,"unreadCount":{"all":$count}}""")
                    }
                    path.startsWith("/api/homepage") -> {
                        val body = if (sessionToken == "sess_bob") {
                            """{"success":true,"data":[{"category":"carpool","showInIndex":true},{"category":"daily","showInIndex":true},{"category":"trade","showInIndex":false}]}"""
                        } else {
                            """{"success":true,"data":[{"category":"tech","showInIndex":true},{"category":"trade","showInIndex":true},{"category":"daily","showInIndex":false}]}"""
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    path.startsWith("/?sortBy=") -> {
                        if (slowFeedGate.get()) {
                            slowFeedEntered.countDown()
                            slowFeedRelease.await(5, TimeUnit.SECONDS)
                        }
                        buildHomeHtmlResponse(sessionToken)
                    }
                    path == "/" -> {
                        if (sessionToken != null && sessionToken in disconnectSessions) {
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        } else if (sessionToken != null && sessionToken in serverErrorSessions) {
                            MockResponse().setResponseCode(500).setBody("Internal Server Error")
                        } else if (sessionToken != null && sessionToken in challengeSessions) {
                            MockResponse()
                                .setResponseCode(403)
                                .addHeader("cf-mitigated", "challenge")
                                .setBody("<html><title>Just a moment...</title></html>")
                        } else {
                            buildHomeHtmlResponse(sessionToken)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.useHttps(tlsFixture.sslContext.socketFactory, false)
        server.start()

        val baseUrl = "https://www.nodeseek.com:${server.port}/".toHttpUrl()
        cookieBridge = WebViewCookieBridge(forceInMemory = true)
        csrfTokenStore = CsrfTokenStore()
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("AnodeMultiAccountTest/1.0"),
            csrfTokenStore = csrfTokenStore,
        ).newBuilder()
            .proxy(Proxy.NO_PROXY)
            .sslSocketFactory(tlsFixture.sslContext.socketFactory, tlsFixture.trustManager)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (hostname.equals("www.nodeseek.com", ignoreCase = true) ||
                            hostname.equals("nodeseek.com", ignoreCase = true)
                        ) {
                            listOf(InetAddress.getByName("127.0.0.1"))
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                },
            )
            .build()

        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        inboxRepository = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            watermarkStore = InMemoryWatermarkStore(),
        )
        sessionRepository.unreadCountListener = inboxRepository::onPiggybackedUnreadCount

        val bookmarkDao = InMemoryBookmarkDao()
        val readHistoryDao = InMemoryReadHistoryDao()
        val categoryDao = InMemoryCategoryDao()
        bookmarkAndHistoryRepository = BookmarkAndHistoryRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            bookmarkDao = bookmarkDao,
            readHistoryDao = readHistoryDao,
        )
        forumRepository = ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            settingsRepository = FakeSettingsRepository(),
            categoryDao = categoryDao,
            readHistoryDao = readHistoryDao,
            bookmarkDao = bookmarkDao,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
        )
        checkInRepository = CheckInRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
        )

        val fakeTransport = object : SameOriginTransport {
            override fun canAttemptWithExistingClearance(): Boolean = false
            override fun markVerifiedByUser() = Unit
            override fun clearSessionContext() {
                csrfTokenStore.clear()
            }
            override suspend fun fetchHtml(url: String): SameOriginHttpResponse =
                SameOriginHttpResponse(statusCode = 200, body = "")
            override suspend fun executeApiRequest(
                method: String,
                url: String,
                refererUrl: String,
                headers: Map<String, String>,
                body: String?,
            ): SameOriginHttpResponse = SameOriginHttpResponse(statusCode = 200, body = "{}")
        }

        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            sameOriginTransport = fakeTransport,
            onConfigObserved = sessionRepository::onConfigPayloadObserved,
            sessionGenerationProvider = { sessionRepository.currentSessionGeneration() },
            onConfigObservedWithGeneration = { config, gen ->
                sessionRepository.onConfigPayloadObserved(config, expectedGeneration = gen)
            },
        )

        vault = FileAccountCookieVault(
            vaultFile = File(tempFolder.root, FileAccountCookieVault.VAULT_FILE_NAME),
            cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey()),
            clock = { fakeClock.getAndAdd(50L) },
        )

        managerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        manager = MultiAccountManagerImpl(
            vault = vault,
            cookieStore = cookieBridge,
            sessionRepository = sessionRepository,
            apiClientProvider = { apiClient },
            checkInRepository = checkInRepository,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            forumRepository = forumRepository,
            externalScope = managerScope,
            clock = { fakeClock.get() },
        )
    }

    @After
    fun tearDown() {
        slowFeedRelease.countDown()
        managerScope.cancel()
        runCatching { server.shutdown() }
    }

    private fun buildHomeHtmlResponse(sessionToken: String?): MockResponse {
        val userJson = when {
            sessionToken == null || sessionToken in expiredSessions -> "null"
            sessionToken == "sess_alice" ->
                """{"member_id":1001,"member_name":"Alice","coin":450,"stardust":10,"unViewedCount":{"all":2}}"""
            sessionToken == "sess_bob" ->
                """{"member_id":2002,"member_name":"Bob","coin":920,"stardust":30,"unViewedCount":{"all":9}}"""
            else -> "null"
        }
        val payloadJson = """{"enableCustomedStyle":1,"user":$userJson,"allCategory":[{"key":"tech","cn_text":"技术"}]}"""
        val b64 = Base64.getEncoder().encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val body = """
            <html><body>
              <div id="nsk-body"></div>
              <script id="temp-script" type="application/json">$b64</script>
            </body></html>
        """.trimIndent()
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun extractCookieValue(cookieHeader: String, name: String): String? =
        cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun seedActiveAlice() = runBlocking {
        val baseUrl = "https://www.nodeseek.com:${server.port}/".toHttpUrl()
        val future = System.currentTimeMillis() + 3_600_000L
        cookieBridge.saveFromResponse(
            baseUrl,
            listOf(
                Cookie.Builder()
                    .name("session")
                    .value("sess_alice")
                    .domain("nodeseek.com")
                    .path("/")
                    .secure()
                    .expiresAt(future)
                    .build(),
            ),
        )
        sessionRepository.refreshSession().getOrThrow()
        manager.initializeAndSyncActiveAccount()
    }

    private suspend fun seedBobSlotInVault() {
        val bobSession = AccountSession(uid = 2002L, username = "Bob", drumsticks = 920, stardust = 30)
        vault.saveOrUpdateSlot(
            session = bobSession,
            cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
            status = AccountSlotStatus.READY,
        )
    }

    @Test
    fun `happy path switch A to B swaps cookies, clears caches, preserves A slot, and updates active account`() = runTest {
        seedActiveAlice()
        // Populate Alice caches + CSRF tokens
        csrfTokenStore.seedCsrfToken("alice_csrf")
        csrfTokenStore.seedSecurityToken("alice_sec.tok")
        checkInRepository.getCheckInBoard(page = 1, forceRefresh = true)
        assertNotNull(checkInRepository.checkInStatus.value)
        bookmarkAndHistoryRepository.cacheTopicSummaries(
            listOf(
                TopicSummary(
                    id = 555L,
                    title = "Alice cached topic",
                    categorySlug = "inside",
                    categoryName = "内版",
                    authorName = "author",
                ),
            ),
        )
        assertNotNull(bookmarkAndHistoryRepository.getCachedTopicSummary(555L))

        // Seed Bob in vault as READY (Alice remains ACTIVE)
        val bobSession = AccountSession(uid = 2002L, username = "Bob", drumsticks = 920, stardust = 30)
        vault.saveOrUpdateSlot(
            session = bobSession,
            cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
            status = AccountSlotStatus.READY,
        )

        val result = manager.switchAccount(2002L)
        assertTrue(result is com.nodeseek.app.domain.model.AccountSwitchResult.Switched)
        assertEquals(2002L, sessionRepository.sessionState.value?.uid)
        assertEquals("Bob", sessionRepository.sessionState.value?.username)

        // CSRF tokens & in-memory caches must be cleared
        assertNull(csrfTokenStore.csrfToken)
        assertNull(csrfTokenStore.securityToken)
        assertNull(bookmarkAndHistoryRepository.getCachedTopicSummary(555L))
        assertNull(checkInRepository.checkInStatus.value)

        // Vault retains Alice as READY and Bob as ACTIVE
        assertEquals(AccountSlotStatus.READY, vault.getSlot(1001L)?.status)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(2002L)?.status)

        // Subsequent HTTP request uses Bob's cookie on the wire
        val wwwUrl = "https://www.nodeseek.com/".toHttpUrl()
        assertEquals("sess_bob", cookieBridge.loadForRequest(wwwUrl).first { it.name == "session" }.value)

        // Never called /api/account/signOut
        assertFalse(recordedPaths.any { it.contains("signOut") })
    }

    @Test
    fun `expired target B rolls back seamlessly to A and marks B EXPIRED`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()
        // Re-save Alice as ACTIVE
        vault.saveOrUpdateSlot(
            sessionRepository.sessionState.value!!,
            listOf(StoredCookieRecord("session", "sess_alice", CookieHostScope.SHARED_DOT_DOMAIN)),
            AccountSlotStatus.ACTIVE,
        )

        expiredSessions.add("sess_bob")

        val result = manager.switchAccount(2002L)
        assertTrue(result is com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack)
        val rolledBack = result as com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack
        assertEquals(2002L, rolledBack.expiredUid)
        assertEquals(1001L, rolledBack.restoredSession?.uid)

        // Active session & cookies rolled back to Alice
        assertEquals(1001L, sessionRepository.sessionState.value?.uid)
        val wwwUrl = "https://www.nodeseek.com/".toHttpUrl()
        assertEquals("sess_alice", cookieBridge.loadForRequest(wwwUrl).first { it.name == "session" }.value)
        assertEquals(AccountSlotStatus.EXPIRED, vault.getSlot(2002L)?.status)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(1001L)?.status)
    }

    @Test
    fun `cloudflare challenge on target B keeps B active and sets challengeRequired`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()
        challengeSessions.add("sess_bob")

        val result = manager.switchAccount(2002L)
        assertTrue(result is com.nodeseek.app.domain.model.AccountSwitchResult.ChallengeRequired)
        assertEquals(2002L, sessionRepository.sessionState.value?.uid)
        assertTrue(sessionRepository.challengeRequired.value)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(2002L)?.status)
    }

    @Test
    fun `offline IOException keeps B active using snapshot without marking B expired`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()
        server.shutdown()

        val result = manager.switchAccount(2002L)
        assertTrue("Expected OfflineUsingSnapshot, got $result", result is com.nodeseek.app.domain.model.AccountSwitchResult.OfflineUsingSnapshot)
        assertEquals(2002L, sessionRepository.sessionState.value?.uid)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(2002L)?.status)
    }

    @Test
    fun `beginAddAccount clears local cookies without calling signOut, cancel restores A, and login saves A and B`() = runTest {
        seedActiveAlice()
        val wwwUrl = "https://www.nodeseek.com/".toHttpUrl()

        // 1. Begin add account -> enters guest locally without signOut API
        val beginResult = manager.beginAddAccount()
        assertTrue(beginResult.isSuccess)
        assertNull(sessionRepository.sessionState.value)
        assertFalse(cookieBridge.hasAnyAuthCookie())
        assertTrue(manager.pendingAddAccountFlow.value)
        assertFalse(recordedPaths.any { it.contains("signOut") })

        // 2. Cancel without logging in -> restores Alice
        val restored = manager.cancelAddAccountIfUnauthenticated()
        assertEquals(1001L, restored?.uid)
        assertEquals(1001L, sessionRepository.sessionState.value?.uid)
        assertEquals("sess_alice", cookieBridge.loadForRequest(wwwUrl).first { it.name == "session" }.value)
        assertFalse(manager.pendingAddAccountFlow.value)

        // 3. Begin add account again and complete native sign-in as Bob
        manager.beginAddAccount()
        val signInResult = sessionRepository.signIn("Bob", "secret", "turnstile_tok")
        assertEquals(SignInStepResult.Authenticated, signInResult.getOrThrow())

        // Both Alice and Bob are preserved in vault, Bob is ACTIVE, signOut never called
        assertEquals(2002L, sessionRepository.sessionState.value?.uid)
        assertNotNull(vault.getSlot(1001L))
        assertNotNull(vault.getSlot(2002L))
        assertEquals(AccountSlotStatus.READY, vault.getSlot(1001L)?.status)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(2002L)?.status)
        assertFalse(manager.pendingAddAccountFlow.value)
        assertFalse(recordedPaths.any { it.contains("signOut") })
    }

    @Test
    fun `stale pre-switch HTML response from A does not overwrite B sessionState`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()

        // Start a slow HTML feed fetch while Alice (gen 0) is active
        slowFeedGate.set(true)
        val staleFeedDeferred = async(Dispatchers.IO) {
            apiClient.fetchFeedPage()
        }
        assertTrue(slowFeedEntered.await(3, TimeUnit.SECONDS))

        // Switch to Bob (bumps sessionGeneration) while Alice's feed HTML is still in-flight
        slowFeedGate.set(false)
        val switchResult = manager.switchAccount(2002L)
        assertTrue(switchResult is com.nodeseek.app.domain.model.AccountSwitchResult.Switched)
        assertEquals(2002L, sessionRepository.sessionState.value?.uid)

        // Release Alice's slow pre-switch response
        slowFeedRelease.countDown()
        staleFeedDeferred.await()

        // Session MUST remain Bob (2002L), never reverted to Alice (1001L)
        assertEquals(2002L, sessionRepository.sessionState.value?.uid)
        assertEquals("Bob", sessionRepository.sessionState.value?.username)
    }

    @Test
    fun `onActiveAccountSignedOut removes only signed-out account while preserving other saved slots`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()

        val activeUid = sessionRepository.sessionState.value?.uid
        assertEquals(1001L, activeUid)
        sessionRepository.signOut().getOrThrow()
        manager.onActiveAccountSignedOut(activeUid)

        assertNull(sessionRepository.sessionState.value)
        assertNull(vault.getSlot(1001L))
        assertNotNull(vault.getSlot(2002L))
    }

    @Test
    fun `corrupted encrypted cookies in vault are treated as expired slot without crashing`() = runTest {
        seedActiveAlice()
        // Save Bob with a tampered ciphertext envelope directly in vault file
        val bobSession = AccountSession(uid = 2002L, username = "Bob")
        vault.saveOrUpdateSlot(
            session = bobSession,
            cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
            status = AccountSlotStatus.READY,
        )
        val vaultFile = File(tempFolder.root, FileAccountCookieVault.VAULT_FILE_NAME)
        val raw = vaultFile.readText(Charsets.UTF_8)
        // Tamper the ciphertext payload inside v1:sw:...
        val tampered = raw.replace(Regex("v1:sw:[^\"]+"), "v1:sw:AAAA:BBBB")
        vaultFile.writeText(tampered, Charsets.UTF_8)

        val freshVault = FileAccountCookieVault(
            vaultFile = vaultFile,
            cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey()),
        )
        freshVault.ensureLoaded()
        val freshManager = MultiAccountManagerImpl(
            vault = freshVault,
            cookieStore = cookieBridge,
            sessionRepository = sessionRepository,
            apiClientProvider = { apiClient },
            checkInRepository = checkInRepository,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            forumRepository = forumRepository,
            externalScope = managerScope,
        )

        val result = freshManager.switchAccount(2002L)
        assertTrue(result is com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack)
        assertEquals(AccountSlotStatus.EXPIRED, freshVault.getSlot(2002L)?.status)
        assertEquals(1001L, sessionRepository.sessionState.value?.uid)
    }

    @Test
    fun `HTTP 500 on target B rolls back to A, clears pending add state, and keeps A active`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()
        serverErrorSessions.add("sess_bob")

        val result = manager.switchAccount(2002L)
        assertTrue("Expected Failure, got $result", result is com.nodeseek.app.domain.model.AccountSwitchResult.Failure)
        assertEquals(1001L, sessionRepository.sessionState.value?.uid)
        assertEquals("Alice", sessionRepository.sessionState.value?.username)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(1001L)?.status)
        assertEquals(AccountSlotStatus.READY, vault.getSlot(2002L)?.status)
        val wwwUrl = "https://www.nodeseek.com/".toHttpUrl()
        assertEquals("sess_alice", cookieBridge.loadForRequest(wwwUrl).firstOrNull { it.name == "session" }?.value)
        assertFalse(manager.pendingAddAccountFlow.value)
    }

    @Test
    fun `expired target B when A has missing or empty rollback cookies transitions to guest and marks A EXPIRED`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()
        val aliceSession = sessionRepository.sessionState.value!!

        // Clear live cookies and remove Alice's vault cookies (empty cookie list) while Alice is still in sessionState
        cookieBridge.clearAllCookies()
        vault.saveOrUpdateSlot(aliceSession, emptyList(), AccountSlotStatus.ACTIVE)
        expiredSessions.add("sess_bob")

        val result = manager.switchAccount(2002L)
        assertTrue(
            "Expected TargetExpiredRolledBack, got $result",
            result is com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack,
        )
        val rolledBack = result as com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack
        assertEquals(2002L, rolledBack.expiredUid)
        assertNull(rolledBack.restoredSession)
        assertNull(sessionRepository.sessionState.value)
        assertFalse(cookieBridge.hasAnyAuthCookie())
        assertEquals(AccountSlotStatus.EXPIRED, vault.getSlot(2002L)?.status)
        assertEquals(AccountSlotStatus.EXPIRED, vault.getSlot(1001L)?.status)
    }

    @Test
    fun `fresh manager switch to expired B with corrupted A vault ciphertext does not flip A back to ACTIVE after IO settles`() = runTest {
        seedActiveAlice()
        val aliceSession = sessionRepository.sessionState.value!!
        val sharedCipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        val probeVaultFile = File(tempFolder.root, "probe_2c_ii_vault.json")

        val seedVault = FileAccountCookieVault(vaultFile = probeVaultFile, cipher = sharedCipher)
        seedVault.saveOrUpdateSlot(
            session = aliceSession,
            cookies = listOf(StoredCookieRecord("session", "sess_alice", CookieHostScope.SHARED_DOT_DOMAIN)),
            status = AccountSlotStatus.ACTIVE,
        )
        // Tamper only Alice's encrypted ciphertext while keeping Bob's cookies valid
        val tamperedRaw = probeVaultFile.readText(Charsets.UTF_8)
            .replace(Regex("v1:sw:[^\"]+"), "v1:sw:CORRUPT:DATA")
        probeVaultFile.writeText(tamperedRaw, Charsets.UTF_8)

        val bobVaultWriter = FileAccountCookieVault(vaultFile = probeVaultFile, cipher = sharedCipher)
        bobVaultWriter.ensureLoaded()
        bobVaultWriter.saveOrUpdateSlot(
            session = AccountSession(uid = 2002L, username = "Bob", drumsticks = 920, stardust = 30),
            cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
            status = AccountSlotStatus.READY,
        )

        cookieBridge.clearAllCookies()
        expiredSessions.add("sess_bob")

        val freshVault = FileAccountCookieVault(vaultFile = probeVaultFile, cipher = sharedCipher)
        freshVault.ensureLoaded()
        assertEquals(1001L, sessionRepository.sessionState.value?.uid)
        assertEquals(AccountSlotStatus.ACTIVE, freshVault.getSlot(1001L)?.status)
        assertNull(freshVault.decryptCookies(1001L))
        assertNotNull(freshVault.decryptCookies(2002L))

        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Warm Dispatchers.IO so sessionObserverJob starts immediately during switchAccount
            withContext(Dispatchers.IO) { Unit }
            val freshManager = MultiAccountManagerImpl(
                vault = freshVault,
                cookieStore = cookieBridge,
                sessionRepository = sessionRepository,
                apiClientProvider = { apiClient },
                checkInRepository = checkInRepository,
                bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
                forumRepository = forumRepository,
                externalScope = ioScope,
                clock = { fakeClock.get() },
            )

            val result = freshManager.switchAccount(2002L)
            assertTrue(
                "Expected TargetExpiredRolledBack, got $result",
                result is com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack,
            )
            assertNull(
                (result as com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack).restoredSession,
            )

            // Allow Dispatchers.IO coroutines (sessionObserverJob) to settle in real wall-clock time
            withContext(Dispatchers.IO) {
                delay(100)
            }

            assertNull(sessionRepository.sessionState.value)
            assertEquals(AccountSlotStatus.EXPIRED, freshVault.getSlot(1001L)?.status)
            assertEquals(AccountSlotStatus.EXPIRED, freshVault.getSlot(2002L)?.status)
        } finally {
            ioScope.cancel()
        }
    }

    @Test
    fun `switchAccount after beginAddAccount hitting HTTP 500 rolls back to pendingRollbackUid Alice`() = runTest {
        seedActiveAlice()
        seedBobSlotInVault()
        serverErrorSessions.add("sess_bob")

        val beginResult = manager.beginAddAccount()
        assertTrue(beginResult.isSuccess)
        assertTrue(manager.pendingAddAccountFlow.value)
        assertNull(sessionRepository.sessionState.value)

        val result = manager.switchAccount(2002L)
        assertTrue("Expected Failure, got $result", result is com.nodeseek.app.domain.model.AccountSwitchResult.Failure)
        assertEquals(1001L, sessionRepository.sessionState.value?.uid)
        assertEquals("Alice", sessionRepository.sessionState.value?.username)
        val wwwUrl = "https://www.nodeseek.com/".toHttpUrl()
        assertEquals("sess_alice", cookieBridge.loadForRequest(wwwUrl).firstOrNull { it.name == "session" }?.value)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(1001L)?.status)
        assertEquals(AccountSlotStatus.READY, vault.getSlot(2002L)?.status)
        assertFalse(manager.pendingAddAccountFlow.value)
    }

    @Test
    fun `switchAccount after beginAddAccount with empty target B cookies rolls back to pendingRollbackUid Alice`() = runTest {
        seedActiveAlice()
        vault.saveOrUpdateSlot(
            session = AccountSession(uid = 2002L, username = "Bob", drumsticks = 920, stardust = 30),
            cookies = emptyList(),
            status = AccountSlotStatus.READY,
        )

        val beginResult = manager.beginAddAccount()
        assertTrue(beginResult.isSuccess)
        assertTrue(manager.pendingAddAccountFlow.value)
        assertNull(sessionRepository.sessionState.value)

        val result = manager.switchAccount(2002L)
        assertTrue(
            "Expected TargetExpiredRolledBack, got $result",
            result is com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack,
        )
        val rolledBack = result as com.nodeseek.app.domain.model.AccountSwitchResult.TargetExpiredRolledBack
        assertEquals(2002L, rolledBack.expiredUid)
        assertEquals(1001L, rolledBack.restoredSession?.uid)
        assertEquals(1001L, sessionRepository.sessionState.value?.uid)
        val wwwUrl = "https://www.nodeseek.com/".toHttpUrl()
        assertEquals("sess_alice", cookieBridge.loadForRequest(wwwUrl).firstOrNull { it.name == "session" }?.value)
        assertEquals(AccountSlotStatus.ACTIVE, vault.getSlot(1001L)?.status)
        assertEquals(AccountSlotStatus.EXPIRED, vault.getSlot(2002L)?.status)
        assertFalse(manager.pendingAddAccountFlow.value)
    }

    @Test
    fun `switchAccount isolates ForumRepository homepageCategoryOrder per accountUid without manual clear`() = runBlocking {
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forumBgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { File(tempFolder.root, "multi_account_settings.preferences_pb") },
            )
            val realSettingsRepo = SettingsRepositoryImpl(
                dataStore = dataStore,
                externalScope = settingsScope,
                secretCipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey()),
            )
            sessionRepository.activeUidChangedListener = realSettingsRepo::onActiveAccountChanged
            realSettingsRepo.onActiveAccountChanged(sessionRepository.sessionState.value?.uid ?: 0L)

            val perUidCategories = MutableStateFlow<List<CategoryEntity>>(emptyList())
            val realCategoryDao = object : CategoryDao {
                override fun observeCategories(accountUid: Long): Flow<List<CategoryEntity>> = flowOf(emptyList())
                override suspend fun getCategories(accountUid: Long): List<CategoryEntity> =
                    perUidCategories.value.filter { it.accountUid == accountUid }.sortedBy { it.sortOrder }
                override suspend fun getLastSyncedAtMillis(accountUid: Long): Long? = null
                override suspend fun upsertAll(entities: List<CategoryEntity>) {
                    perUidCategories.value = perUidCategories.value + entities
                }
                override suspend fun clearForAccount(accountUid: Long) {
                    perUidCategories.value = perUidCategories.value.filterNot { it.accountUid == accountUid }
                }
            }
            val realForumRepo = ForumRepositoryImpl(
                apiClientProvider = { apiClient },
                sessionRepository = sessionRepository,
                settingsRepository = realSettingsRepo,
                categoryDao = realCategoryDao,
                readHistoryDao = InMemoryReadHistoryDao(),
                bookmarkDao = InMemoryBookmarkDao(),
                bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
                backgroundScope = forumBgScope,
            )
            val realManager = MultiAccountManagerImpl(
                vault = vault,
                cookieStore = cookieBridge,
                sessionRepository = sessionRepository,
                apiClientProvider = { apiClient },
                checkInRepository = checkInRepository,
                bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
                forumRepository = realForumRepo,
                externalScope = managerScope,
                clock = { fakeClock.get() },
                settingsRepository = realSettingsRepo,
            )

            seedActiveAlice()
            seedBobSlotInVault()
            realManager.initializeAndSyncActiveAccount()

            // Alice syncs & saves custom category order
            realForumRepo.syncHomepageCategories().getOrThrow()
            realForumRepo.saveHomepageCategories(
                orderedCategories = listOf(
                    Category(slug = "trade", name = "交易", isCustomizable = true, visibleOnHome = true),
                    Category(slug = "tech", name = "技术", isCustomizable = true, visibleOnHome = true),
                    Category(slug = "daily", name = "日常", isCustomizable = true, visibleOnHome = false),
                ),
                syncToServer = false,
            ).getOrThrow()
            assertEquals(listOf("trade", "tech", "daily"), realSettingsRepo.settings.value.homepageCategoryOrder)
            assertEquals(listOf("trade", "tech", "daily"), realSettingsRepo.getHomepageCategoryOrder(1001L))

            // Switch A -> B WITHOUT manual setHomepageCategoryOrder(emptyList())
            val switchToBob = realManager.switchAccount(2002L)
            assertTrue(switchToBob is com.nodeseek.app.domain.model.AccountSwitchResult.Switched)
            assertEquals(emptyList<String>(), realSettingsRepo.settings.value.homepageCategoryOrder)

            val bobSynced = realForumRepo.syncHomepageCategories().getOrThrow()
            assertEquals(listOf("", "carpool", "daily", "trade"), bobSynced.take(4).map { it.slug })
            assertEquals(listOf("carpool", "daily", "trade"), realSettingsRepo.settings.value.homepageCategoryOrder.take(3))
            // Alice's persisted order remains untouched
            assertEquals(listOf("trade", "tech", "daily"), realSettingsRepo.getHomepageCategoryOrder(1001L))

            // Switch B -> A restores Alice's exact order without re-syncing
            val switchToAlice = realManager.switchAccount(1001L)
            assertTrue(switchToAlice is com.nodeseek.app.domain.model.AccountSwitchResult.Switched)
            assertEquals(listOf("trade", "tech", "daily"), realSettingsRepo.settings.value.homepageCategoryOrder)
        } finally {
            sessionRepository.activeUidChangedListener = null
            forumBgScope.cancel()
            settingsScope.cancel()
            dsScope.cancel()
        }
    }

    private class InMemoryWatermarkStore : RadarWatermarkStore {
        private var current = RadarWatermarks()
        override suspend fun getWatermarks(): RadarWatermarks = current
        override suspend fun updateWatermarks(transform: (RadarWatermarks) -> RadarWatermarks): RadarWatermarks {
            current = transform(current)
            return current
        }
    }

    private class InMemoryBookmarkDao : BookmarkDao {
        private val items = MutableStateFlow<List<BookmarkEntity>>(emptyList())
        override fun observeBookmarks(accountUid: Long): Flow<List<BookmarkEntity>> = flowOf(items.value.filter { it.accountUid == accountUid })
        override fun observeBookmarkedTopicIds(accountUid: Long): Flow<List<Long>> = flowOf(items.value.filter { it.accountUid == accountUid }.map { it.topicId })
        override suspend fun getBookmarkedTopicIds(accountUid: Long): List<Long> = items.value.filter { it.accountUid == accountUid }.map { it.topicId }
        override suspend fun getBookmark(accountUid: Long, topicId: Long): BookmarkEntity? = items.value.firstOrNull { it.accountUid == accountUid && it.topicId == topicId }
        override suspend fun getAllBookmarks(): List<BookmarkEntity> = items.value
        override suspend fun upsert(entity: BookmarkEntity) {
            items.value = items.value.filterNot { it.accountUid == entity.accountUid && it.topicId == entity.topicId } + entity
        }
        override suspend fun upsertAll(entities: List<BookmarkEntity>) { entities.forEach { upsert(it) } }
        override suspend fun updateOfflineSnapshot(accountUid: Long, topicId: Long, offlineDetailJson: String?) = Unit
        override suspend fun deleteBookmark(accountUid: Long, topicId: Long) {
            items.value = items.value.filterNot { it.accountUid == accountUid && it.topicId == topicId }
        }
        override suspend fun clearForAccount(accountUid: Long) {
            items.value = items.value.filterNot { it.accountUid == accountUid }
        }
        override suspend fun clearAll() { items.value = emptyList() }
        override suspend fun replaceAllBookmarks(entities: List<BookmarkEntity>) { items.value = entities }
    }

    private class InMemoryReadHistoryDao : ReadHistoryDao {
        private val items = MutableStateFlow<List<ReadHistoryEntity>>(emptyList())
        override fun observeHistory(accountUid: Long, limit: Int): Flow<List<ReadHistoryEntity>> = flowOf(emptyList())
        override fun observeByTopicIds(accountUid: Long, topicIds: Collection<Long>): Flow<List<ReadHistoryEntity>> = flowOf(emptyList())
        override suspend fun getByTopicId(accountUid: Long, topicId: Long): ReadHistoryEntity? = items.value.firstOrNull { it.accountUid == accountUid && it.topicId == topicId }
        override suspend fun getByTopicIds(accountUid: Long, topicIds: Collection<Long>): List<ReadHistoryEntity> = emptyList()
        override suspend fun upsert(entity: ReadHistoryEntity) { items.value = items.value + entity }
        override suspend fun deleteByTopicId(accountUid: Long, topicId: Long) { items.value = items.value.filterNot { it.accountUid == accountUid && it.topicId == topicId } }
        override suspend fun clearForAccount(accountUid: Long) { items.value = items.value.filterNot { it.accountUid == accountUid } }
        override suspend fun clearAll() { items.value = emptyList() }
    }

    private class InMemoryCategoryDao : CategoryDao {
        override fun observeCategories(accountUid: Long): Flow<List<CategoryEntity>> = flowOf(emptyList())
        override suspend fun getCategories(accountUid: Long): List<CategoryEntity> = emptyList()
        override suspend fun getLastSyncedAtMillis(accountUid: Long): Long? = null
        override suspend fun upsertAll(entities: List<CategoryEntity>) = Unit
        override suspend fun clearForAccount(accountUid: Long) = Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings())
        override val recentSearchQueries: StateFlow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setPurePitchAmoled(enabled: Boolean) = Unit
        override suspend fun setUseDynamicColor(enabled: Boolean) = Unit
        override suspend fun setFontScale(scale: Float) = Unit
        override suspend fun setHomeCardScale(scale: Float) = Unit
        override suspend fun setPreferRandomCheckIn(preferRandom: Boolean) = Unit
        override suspend fun setPinnedCategorySlugs(slugs: Set<String>) = Unit
        override suspend fun setHomepageCategoryOrder(slugs: List<String>) = Unit
        override suspend fun togglePinnedCategory(slug: String) = Unit
        override suspend fun setIncludeBlockedTopics(include: Boolean) = Unit
        override suspend fun setPreferRssFallback(preferRss: Boolean) = Unit
        override suspend fun setNodeImageApiKey(apiKey: String) = Unit
        override suspend fun setSeekMailApiKey(apiKey: String) = Unit
        override suspend fun setAutoCheckUpdates(enabled: Boolean) = Unit
        override suspend fun setIncludePrereleaseUpdates(enabled: Boolean) = Unit
        override suspend fun setIgnoredUpdateTag(tag: String) = Unit
        override suspend fun setAwayDigestEnabled(enabled: Boolean) = Unit
        override suspend fun setAwayDigestTriggerMode(mode: AwayDigestTriggerMode) = Unit
        override suspend fun setAwayDigestThresholdMinutes(minutes: Int) = Unit
        override suspend fun addRecentSearchQuery(query: String) = Unit
        override suspend fun removeRecentSearchQuery(query: String) = Unit
        override suspend fun clearRecentSearchQueries() = Unit
        override suspend fun exportSettingsForBackup(includeApiKeys: Boolean): AppSettingsBackupDto = AppSettingsBackupDto()
        override suspend fun restoreSettingsFromBackup(backup: AppSettingsBackupDto, merge: Boolean) = Unit
    }

    companion object {
        private lateinit var tlsTempDir: File
        private lateinit var tlsFixture: TestTlsFixture

        private data class TestTlsFixture(
            val sslContext: SSLContext,
            val trustManager: X509TrustManager,
        )

        @JvmStatic
        @BeforeClass
        fun setUpTls() {
            tlsTempDir = Files.createTempDirectory("multi_account_tls_test").toFile()
            val keystoreFile = File(tlsTempDir, "nodeseek_multi_account_test.p12")
            val passwordChars = "changeit".toCharArray()
            val keytoolBin = File(System.getProperty("java.home"), "bin/keytool")
                .takeIf { it.isFile && it.canExecute() }
                ?.absolutePath
                ?: "keytool"
            val proc = ProcessBuilder(
                keytoolBin,
                "-genkeypair",
                "-alias",
                "nodeseektest",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-sigalg",
                "SHA256withECDSA",
                "-validity",
                "7",
                "-dname",
                "CN=www.nodeseek.com",
                "-ext",
                "SAN=DNS:www.nodeseek.com,DNS:nodeseek.com,DNS:localhost,IP:127.0.0.1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystoreFile.absolutePath,
                "-storepass",
                String(passwordChars),
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "keytool failed generating test cert: $out" }

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                keystoreFile.inputStream().use { load(it, passwordChars) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, passwordChars)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
            }
            tlsFixture = TestTlsFixture(sslContext = sslContext, trustManager = trustManager)
        }

        @JvmStatic
        @AfterClass
        fun tearDownTls() {
            if (::tlsTempDir.isInitialized) {
                tlsTempDir.deleteRecursively()
            }
        }
    }
}
