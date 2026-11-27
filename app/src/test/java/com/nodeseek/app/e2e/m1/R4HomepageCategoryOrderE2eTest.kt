package com.nodeseek.app.e2e.m1

import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.SettingsSecretCipherFactory
import com.nodeseek.app.data.parser.CategoryDto
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.repository.ForumRepositoryImpl
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.explore.ExploreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class R4HomepageCategoryOrderE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T1-R4-1 cold load merges GET api homepage visibility and order with embedded allCategory`() = runTest {
        E2eTestHarness().use { harness ->
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val feedWithoutAllCategoryHtml = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1}""",
            )
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            val uid101Homepage = homepageComposite.getValue("uid101").toString()

            harness.forumDispatcher.enqueueNext("GET", "/", MockResponse().setResponseCode(200).setBody(homeHtml))
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(feedWithoutAllCategoryHtml)
            }
            harness.forumDispatcher.register("GET", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody(uid101Homepage)
            }

            assertTrue(harness.sessionRepository.refreshSession().isSuccess)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.categoryDao.observeCategories(101L).first { it.isNotEmpty() }
                }
            }

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                exploreVm.uiState.collect {}
            }

            val syncedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        val slugs = state.orderedBoardTabs.map { it.slug }
                        slugs.size >= 4 &&
                            slugs[0] == "" &&
                            slugs[1] == "tech" &&
                            slugs[2] == "trade" &&
                            slugs[3] == "review" &&
                            "daily" !in slugs
                    }
                }
            }
            assertEquals("", syncedState.orderedBoardTabs.first().slug)
            assertFalse(syncedState.orderedBoardTabs.any { it.slug == "daily" })
            assertTrue(syncedState.categories.any { it.slug == "daily" && !it.visibleOnHome })
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R4-2 saveCategoryPreferences updates tabs, DataStore, and posts exact order to api homepage`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("GET", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody(homepageComposite.getValue("uid101").toString())
            }
            harness.forumDispatcher.register("POST", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.categoryDao.observeCategories(101L).first { it.isNotEmpty() }
                }
            }
            harness.forumRepository.syncHomepageCategories().getOrThrow()

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                exploreVm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        !state.isSyncingCategories &&
                            !state.sliceFor("").isInitialLoading &&
                            state.orderedBoardTabs.any { it.slug == "trade" } &&
                            state.orderedBoardTabs.none { it.slug == "daily" }
                    }
                }
            }

            val reordered = listOf(
                Category(slug = "review", name = "测评", visibleOnHome = true),
                Category(slug = "trade", name = "交易", visibleOnHome = true),
                Category(slug = "tech", name = "技术", visibleOnHome = true),
                Category(slug = "daily", name = "日常", visibleOnHome = false),
            )
            exploreVm.saveCategoryPreferences(reordered)

            val updatedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        val slugs = state.orderedBoardTabs.map { it.slug }
                        slugs.take(4) == listOf("", "review", "trade", "tech") &&
                            state.snackbarMessage == "版块顺序已同步至账号"
                    }
                }
            }
            val settledOrder = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first {
                        it.homepageCategoryOrder == listOf("review", "trade", "tech", "daily")
                    }.homepageCategoryOrder
                }
            }
            assertEquals(listOf("", "review", "trade", "tech"), updatedState.orderedBoardTabs.take(4).map { it.slug })
            assertEquals(listOf("review", "trade", "tech", "daily"), settledOrder)

            val postReq = harness.forumDispatcher.recordedRequests()
                .first { it.method == "POST" && it.path == "/api/homepage" }
            val payloadArray = TempScriptParser.json.parseToJsonElement(postReq.body.readUtf8())
                .jsonObject.getValue("data").jsonArray
            val sentOrder = payloadArray.map {
                it.jsonObject.getValue("category").jsonPrimitive.content to
                    it.jsonObject.getValue("showInIndex").jsonPrimitive.content.toBoolean()
            }
            assertEquals(
                listOf("review" to true, "trade" to true, "tech" to true, "daily" to false),
                sentOrder.take(4),
            )
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R4-3 hiding active category removes it from strip, resets active tab, keeps in reorder list`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("GET", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody(homepageComposite.getValue("uid101").toString())
            }
            harness.forumDispatcher.register("POST", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.categoryDao.observeCategories(101L).first { it.isNotEmpty() }
                }
            }
            harness.forumRepository.syncHomepageCategories().getOrThrow()

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                exploreVm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        !state.isSyncingCategories &&
                            !state.sliceFor("").isInitialLoading &&
                            state.orderedBoardTabs.any { t -> t.slug == "trade" } &&
                            state.orderedBoardTabs.none { t -> t.slug == "daily" }
                    }
                }
            }

            exploreVm.selectCategory("trade")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first {
                        it.selectedCategorySlug == "trade" && !it.sliceFor("trade").isInitialLoading
                    }
                }
            }

            val updatedOrder = listOf(
                Category(slug = "tech", name = "技术", visibleOnHome = true),
                Category(slug = "trade", name = "交易", visibleOnHome = false),
                Category(slug = "review", name = "测评", visibleOnHome = true),
            )
            exploreVm.saveCategoryPreferences(updatedOrder)

            val afterHide = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        state.selectedCategorySlug == "" &&
                            state.orderedBoardTabs.none { tab -> tab.slug == "trade" } &&
                            state.homepageCategoryOrder == listOf("tech", "trade", "review") &&
                            state.categories.any { cat -> cat.slug == "trade" && !cat.visibleOnHome } &&
                            state.snackbarMessage == "版块顺序已同步至账号"
                    }
                }
            }
            val settledOrder = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first {
                        it.homepageCategoryOrder == listOf("tech", "trade", "review")
                    }.homepageCategoryOrder
                }
            }
            assertEquals("", afterHide.selectedCategorySlug)
            assertFalse(afterHide.orderedBoardTabs.any { it.slug == "trade" })
            assertEquals(listOf("tech", "trade", "review"), afterHide.homepageCategoryOrder)
            assertEquals(listOf("tech", "trade", "review"), settledOrder)
            val hiddenTrade = afterHide.categories.firstOrNull { it.slug == "trade" }
            assertNotNull(hiddenTrade)
            assertFalse(hiddenTrade!!.visibleOnHome)
            assertTrue(hiddenTrade.isCustomizable)
            assertFalse(hiddenTrade.isAdminOnly)
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R4-4 saved homepage order persists across cold reload of Room and DataStore`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("GET", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody(homepageComposite.getValue("uid101").toString())
            }
            harness.forumDispatcher.register("POST", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            harness.forumRepository.saveHomepageCategories(
                orderedCategories = listOf(
                    Category(slug = "review", name = "测评", visibleOnHome = true),
                    Category(slug = "trade", name = "交易", visibleOnHome = true),
                    Category(slug = "tech", name = "技术", visibleOnHome = true),
                    Category(slug = "daily", name = "日常", visibleOnHome = false),
                ),
                syncToServer = true,
            ).getOrThrow()

            // Clear request log and verify cold reload restores order without calling POST /api/homepage
            harness.forumDispatcher.clearRecordedRequests()

            val reloadedSettingsRepo = SettingsRepositoryImpl(
                dataStore = harness.settingsDataStore,
                externalScope = harness.dataStoreScope,
                secretCipher = SettingsSecretCipherFactory.createDefault(),
            )
            val restoredOrder = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    reloadedSettingsRepo.settings.first {
                        it.homepageCategoryOrder == listOf("review", "trade", "tech", "daily")
                    }.homepageCategoryOrder
                }
            }
            assertEquals(listOf("review", "trade", "tech", "daily"), restoredOrder)

            val reloadedForumRepo = ForumRepositoryImpl(
                apiClientProvider = { harness.apiClient },
                sessionRepository = harness.sessionRepository,
                settingsRepository = reloadedSettingsRepo,
                categoryDao = harness.categoryDao,
                readHistoryDao = harness.readHistoryDao,
                bookmarkDao = harness.bookmarkDao,
                bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                backgroundScope = harness.backgroundScope,
                feedDiskCache = harness.feedDiskCache,
                clockMillis = harness.clock::nowMillis,
            )
            val reloadedExploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = reloadedForumRepo,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = reloadedSettingsRepo,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                reloadedExploreVm.uiState.collect {}
            }

            val restoredState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    reloadedExploreVm.uiState.first { state ->
                        state.orderedBoardTabs.take(4).map { it.slug } == listOf("", "review", "trade", "tech") &&
                            state.orderedBoardTabs.none { it.slug == "daily" }
                    }
                }
            }
            assertFalse(restoredState.orderedBoardTabs.any { it.slug == "daily" })
            assertEquals(
                0,
                harness.forumDispatcher.recordedRequests().count { it.method == "POST" && it.path == "/api/homepage" },
            )
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R4-5 resetCategoryPreferences restores canonical order and syncs to api homepage`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("GET", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody(homepageComposite.getValue("uid101").toString())
            }
            harness.forumDispatcher.register("POST", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.categoryDao.observeCategories(101L).first { it.isNotEmpty() }
                }
            }
            harness.forumRepository.syncHomepageCategories().getOrThrow()

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                exploreVm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        !state.isSyncingCategories &&
                            !state.sliceFor("").isInitialLoading &&
                            state.orderedBoardTabs.any { t -> t.slug == "tech" } &&
                            state.orderedBoardTabs.none { t -> t.slug == "daily" }
                    }
                }
            }

            // Custom order with daily hidden
            exploreVm.saveCategoryPreferences(
                listOf(
                    Category(slug = "trade", name = "交易", visibleOnHome = true),
                    Category(slug = "daily", name = "日常", visibleOnHome = false),
                ),
            )
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        state.snackbarMessage == "版块顺序已同步至账号" &&
                            state.orderedBoardTabs.take(2).map { it.slug } == listOf("", "trade") &&
                            state.orderedBoardTabs.none { it.slug == "daily" }
                    }
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first {
                        it.homepageCategoryOrder == listOf("trade", "daily")
                    }
                }
            }

            val expectedPrefix = listOf("", "tech", "review", "info", "trade", "daily", "dev")
            exploreVm.resetCategoryPreferences()
            val resetState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        state.snackbarMessage == "已恢复默认版块顺序" &&
                            state.orderedBoardTabs.take(expectedPrefix.size).map { it.slug } == expectedPrefix &&
                            state.categories.filter { it.isCustomizable }.all { it.visibleOnHome }
                    }
                }
            }
            val settledResetSettings = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.homepageCategoryOrder.isEmpty() }
                }
            }
            assertTrue(settledResetSettings.homepageCategoryOrder.isEmpty())
            assertEquals(expectedPrefix, resetState.orderedBoardTabs.take(expectedPrefix.size).map { it.slug })
            assertTrue(
                resetState.categories.filter { it.isCustomizable }.all { it.visibleOnHome },
            )
            assertTrue(
                harness.forumDispatcher.recordedRequests().count { it.method == "POST" && it.path == "/api/homepage" } >= 2,
            )
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R4-1 synthetic All category remains pinned at index 0 and excluded from homepage payload`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L)
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("GET", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody(homepageComposite.getValue("uid101").toString())
            }
            harness.forumDispatcher.register("POST", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.categoryDao.observeCategories(101L).first { it.isNotEmpty() }
                }
            }
            harness.forumRepository.syncHomepageCategories().getOrThrow()

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                exploreVm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        !state.isSyncingCategories &&
                            !state.sliceFor("").isInitialLoading &&
                            state.orderedBoardTabs.any { it.slug == "tech" } &&
                            state.orderedBoardTabs.none { it.slug == "daily" }
                    }
                }
            }

            // Deliberately pass synthetic "" in the middle and marked invisible
            exploreVm.saveCategoryPreferences(
                listOf(
                    Category(slug = "trade", name = "交易", visibleOnHome = true),
                    Category(slug = "", name = "全部", isCustomizable = false, visibleOnHome = false),
                    Category(slug = "tech", name = "技术", visibleOnHome = true),
                ),
            )

            val state = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first {
                        it.snackbarMessage == "版块顺序已同步至账号" &&
                            it.orderedBoardTabs.take(3).map { tab -> tab.slug } == listOf("", "trade", "tech")
                    }
                }
            }
            val settledOrder = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first {
                        it.homepageCategoryOrder == listOf("trade", "tech")
                    }.homepageCategoryOrder
                }
            }
            val firstTab = state.orderedBoardTabs.first()
            assertEquals("", firstTab.slug)
            assertFalse(firstTab.isCustomizable)
            assertTrue(firstTab.visibleOnHome)
            assertEquals(listOf("trade", "tech"), settledOrder)

            val postReq = harness.forumDispatcher.recordedRequests()
                .last { it.method == "POST" && it.path == "/api/homepage" }
            val sentCategories = TempScriptParser.json.parseToJsonElement(postReq.body.readUtf8())
                .jsonObject.getValue("data").jsonArray
                .map { it.jsonObject.getValue("category").jsonPrimitive.content }
            assertFalse("" in sentCategories)
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R4-2 remote saveHomepageCategories and syncHomepageCategories failures preserve local state`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L)
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("GET", "/api/homepage") {
                MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"server error"}""")
            }
            harness.forumDispatcher.register("POST", "/api/homepage") {
                MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"server error"}""")
            }

            // Direct repository save fails remote sync but persists local Room + DataStore order
            val customCategories = listOf(
                Category(slug = "carpool", name = "拼车", visibleOnHome = true),
                Category(slug = "trade", name = "交易", visibleOnHome = true),
                Category(slug = "daily", name = "日常", visibleOnHome = false),
            )
            val directSave = harness.forumRepository.saveHomepageCategories(customCategories, syncToServer = true)
            assertTrue(directSave.isFailure)
            assertEquals(
                listOf("carpool", "trade", "daily"),
                harness.settingsRepository.settings.value.homepageCategoryOrder,
            )
            assertEquals(
                listOf("", "carpool", "trade", "daily"),
                harness.categoryDao.getCategories(101L).take(4).map { it.slug },
            )

            // ViewModel save surfaces offline-first snackbar while keeping local tabs
            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                exploreVm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first {
                        it.orderedBoardTabs.isNotEmpty() && !it.sliceFor("").isInitialLoading
                    }
                }
            }

            exploreVm.saveCategoryPreferences(
                listOf(
                    Category(slug = "review", name = "测评", visibleOnHome = true),
                    Category(slug = "carpool", name = "拼车", visibleOnHome = true),
                ),
            )
            val failedSyncState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first {
                        it.snackbarMessage?.contains("云端同步失败") == true &&
                            it.orderedBoardTabs.take(3).map { tab -> tab.slug } == listOf("", "review", "carpool")
                    }
                }
            }
            val settledOrder = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first {
                        it.homepageCategoryOrder == listOf("review", "carpool")
                    }.homepageCategoryOrder
                }
            }
            assertEquals(listOf("", "review", "carpool"), failedSyncState.orderedBoardTabs.take(3).map { it.slug })
            assertEquals(listOf("review", "carpool"), settledOrder)

            // Failing syncHomepageCategories returns Result.failure without wiping Room categories
            val syncFail = harness.forumRepository.syncHomepageCategories()
            assertTrue(syncFail.isFailure)
            assertTrue(harness.categoryDao.getCategories(101L).isNotEmpty())
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R4-3 adminOnly categories are excluded from tabs, reorder sheet, and homepage sync payload`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L)
            val emptyFeedHtml = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101"},"enableCustomedStyle":1}""",
            )
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(emptyFeedHtml)
            }
            harness.forumDispatcher.register("GET", "/api/content/list-categories") {
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "data": [
                        {"key": "tech", "cn_text": "技术", "customizable": 1, "showInIndex": 1, "adminOnly": 0},
                        {"key": "trade", "cn_text": "交易", "customizable": 1, "showInIndex": 1, "adminOnly": 0},
                        {"key": "admin_panel", "cn_text": "管理", "customizable": 1, "showInIndex": 1, "adminOnly": 1}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            harness.forumDispatcher.register("POST", "/api/homepage") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val refreshed = harness.forumRepository.refreshCategories(force = true).getOrThrow()
            assertTrue(refreshed.any { it.slug == "admin_panel" && it.isAdminOnly })

            harness.settingsRepository.setHomepageCategoryOrder(listOf("admin_panel", "trade", "tech"))

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                exploreVm.uiState.collect {}
            }

            val initialState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        !state.sliceFor("").isInitialLoading &&
                            state.homepageCategoryOrder == listOf("admin_panel", "trade", "tech") &&
                            state.categories.any { it.slug == "admin_panel" && it.isAdminOnly } &&
                            state.orderedBoardTabs.map { it.slug } == listOf("", "trade", "tech")
                    }
                }
            }
            assertFalse(initialState.orderedBoardTabs.any { it.slug == "admin_panel" })
            assertEquals(listOf("", "trade", "tech"), initialState.orderedBoardTabs.map { it.slug })
            assertFalse(
                initialState.categories
                    .filter { it.slug.isNotBlank() && !it.isAdminOnly }
                    .any { it.slug == "admin_panel" },
            )

            exploreVm.saveCategoryPreferences(refreshed)
            val afterSaveState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        !state.isSavingCategories &&
                            state.snackbarMessage == "版块顺序已同步至账号" &&
                            "admin_panel" !in state.homepageCategoryOrder
                    }
                }
            }
            assertFalse(afterSaveState.orderedBoardTabs.any { it.slug == "admin_panel" })
            assertFalse("admin_panel" in afterSaveState.homepageCategoryOrder)

            exploreVm.resetCategoryPreferences()
            val afterResetState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        !state.isSavingCategories &&
                            state.snackbarMessage == "已恢复默认版块顺序" &&
                            state.homepageCategoryOrder.isEmpty()
                    }
                }
            }
            assertFalse(afterResetState.orderedBoardTabs.any { it.slug == "admin_panel" })

            val postRequests = harness.forumDispatcher.recordedRequests()
                .filter { it.method == "POST" && it.path == "/api/homepage" }
            assertEquals(2, postRequests.size)
            postRequests.forEach { postReq ->
                val sentCategories = TempScriptParser.json.parseToJsonElement(postReq.body.readUtf8())
                    .jsonObject.getValue("data").jsonArray
                    .map { it.jsonObject.getValue("category").jsonPrimitive.content }
                assertFalse("admin_panel" in sentCategories)
                assertTrue("trade" in sentCategories && "tech" in sentCategories)
            }
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R4-4 category order and Room rows are isolated between uid 101 and uid 202`() = runTest {
        E2eTestHarness().use { harness ->
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            harness.forumDispatcher.enqueueNext(
                method = "GET",
                path = "/api/homepage",
                response = MockResponse().setResponseCode(200).setBody(homepageComposite.getValue("uid101").toString()),
            )
            harness.forumDispatcher.enqueueNext(
                method = "GET",
                path = "/api/homepage",
                response = MockResponse().setResponseCode(200).setBody(homepageComposite.getValue("uid202").toString()),
            )

            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            harness.forumRepository.syncHomepageCategories().getOrThrow()

            harness.seedAuthenticatedSession(uid = 202L, username = "bob202")
            harness.forumRepository.syncHomepageCategories().getOrThrow()

            val cats101 = harness.categoryDao.getCategories(101L)
            val cats202 = harness.categoryDao.getCategories(202L)
            assertTrue(cats101.isNotEmpty())
            assertTrue(cats202.isNotEmpty())
            assertEquals(listOf("", "tech", "trade", "review"), cats101.take(4).map { it.slug })
            assertEquals(false, cats101.first { it.slug == "daily" }.visibleOnHome)
            assertEquals(true, cats101.first { it.slug == "trade" }.visibleOnHome)

            assertEquals(listOf("", "carpool", "info", "daily"), cats202.take(4).map { it.slug })
            assertEquals(false, cats202.first { it.slug == "trade" }.visibleOnHome)
            assertEquals(true, cats202.first { it.slug == "daily" }.visibleOnHome)
            assertEquals(
                listOf("tech", "trade", "review", "daily"),
                harness.settingsRepository.getHomepageCategoryOrder(101L).take(4),
            )
            assertEquals(
                listOf("carpool", "info", "daily", "trade"),
                harness.settingsRepository.getHomepageCategoryOrder(202L).take(4),
            )
        }
    }

    @Test
    fun `E2E-T2-R4-5 concurrent refreshCategories and onEmbeddedCategoriesObserved preserve custom sortOrder`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            harness.forumDispatcher.register("GET", "/api/content/list-categories") {
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "data": [
                        {"key": "daily", "cn_text": "日常", "customizable": 1, "showInIndex": 1, "adminOnly": 0},
                        {"key": "tech", "cn_text": "技术", "customizable": 1, "showInIndex": 1, "adminOnly": 0},
                        {"key": "review", "cn_text": "测评", "customizable": 1, "showInIndex": 1, "adminOnly": 0},
                        {"key": "trade", "cn_text": "交易", "customizable": 1, "showInIndex": 1, "adminOnly": 0}
                      ]
                    }
                    """.trimIndent(),
                )
            }

            // Seed custom order & hidden daily
            harness.forumRepository.saveHomepageCategories(
                orderedCategories = listOf(
                    Category(slug = "trade", name = "交易", visibleOnHome = true),
                    Category(slug = "review", name = "测评", visibleOnHome = true),
                    Category(slug = "tech", name = "技术", visibleOnHome = true),
                    Category(slug = "daily", name = "日常", visibleOnHome = false),
                ),
                syncToServer = false,
            ).getOrThrow()

            val embeddedDtos = listOf(
                CategoryDto(key = "daily", cnText = "日常", customizable = true, showInIndex = true),
                CategoryDto(key = "tech", cnText = "技术", customizable = true, showInIndex = true),
                CategoryDto(key = "review", cnText = "测评", customizable = true, showInIndex = true),
                CategoryDto(key = "trade", cnText = "交易", customizable = true, showInIndex = true),
            )

            withContext(Dispatchers.Default) {
                (1..6).map { idx ->
                    async {
                        if (idx % 2 == 0) {
                            harness.forumRepository.refreshCategories(force = true)
                        } else {
                            harness.forumRepository.onEmbeddedCategoriesObserved(embeddedDtos, accountUid = 101L)
                        }
                    }
                }.awaitAll()
            }

            // Allow backgroundScope embedded listener coroutines to settle
            val finalCats = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    var rows = harness.categoryDao.getCategories(101L)
                    while (rows.isEmpty()) {
                        kotlinx.coroutines.delay(15L)
                        rows = harness.categoryDao.getCategories(101L)
                    }
                    rows
                }
            }
            assertEquals(listOf("", "trade", "review", "tech"), finalCats.take(4).map { it.slug })
            val dailyRow = finalCats.firstOrNull { it.slug == "daily" }
            assertNotNull(dailyRow)
            assertFalse(dailyRow!!.visibleOnHome)
        }
    }
}
