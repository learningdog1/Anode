package com.nodeseek.app.restock

import com.nodeseek.app.data.restock.RestockRepositoryImpl
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.restock.StockAnalysisResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestockRepositoryAndTransitionTest {

    private lateinit var tempDir: File
    private lateinit var storeFile: File
    private var fakeClock: Long = 1_760_000_000_000L

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("restock_repo_test").toFile()
        storeFile = File(tempDir, RestockRepositoryImpl.STORE_FILE_NAME)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createRepo(): RestockRepositoryImpl = RestockRepositoryImpl(
        storeFile = storeFile,
        clockMillis = { fakeClock },
    )

    private fun analysis(
        status: RestockStockStatus,
        signal: String = status.displayName,
        panel: MerchantPanelType = MerchantPanelType.WHMCS,
        price: String = "$10.98/年",
        error: String? = null,
    ): StockAnalysisResult = StockAnalysisResult(
        rawUrl = "https://my.racknerd.com/cart.php?a=add&pid=801",
        resolvedOrderUrl = "https://my.racknerd.com/cart.php?a=add&pid=801",
        finalUrl = "https://my.racknerd.com/cart.php?a=add&pid=801",
        httpStatusCode = if (status == RestockStockStatus.CHECK_ERROR) 503 else 200,
        panelType = panel,
        stockStatus = status,
        detectedTitle = "RackNerd 2G",
        detectedPriceHint = price,
        matchedSignal = signal,
        errorMessage = error,
        checkedAtEpochMillis = fakeClock,
    )

    @Test
    fun `deduplicates restock waves while IN_STOCK and re-arms after OUT_OF_STOCK`() = runTest {
        val repo = createRepo()
        val item = repo.addOrUpdateWatch(
            RestockWatchItem(
                id = "watch-1",
                title = "RackNerd BF 2G",
                rawUrl = "https://www.nodeseek.com/jump?to=https%3A%2F%2Fmy.racknerd.com%2Fcart.php%3Fa%3Dadd%26pid%3D801",
                resolvedOrderUrl = "",
                stockStatus = RestockStockStatus.UNKNOWN,
                sourceTopicId = 54321L,
            ),
        )
        assertEquals("https://my.racknerd.com/cart.php?a=add&pid=801", item.resolvedOrderUrl)
        assertEquals(MerchantPanelType.WHMCS, item.panelType)

        // 1. UNKNOWN -> OUT_OF_STOCK: arms the wave, no event
        fakeClock += 10_000L
        assertNull(repo.recordCheckResult("watch-1", analysis(RestockStockStatus.OUT_OF_STOCK, "命中缺货标识：Out of Stock")))
        assertEquals(0, repo.getEventHistorySnapshot().size)
        assertTrue(repo.getWatchItemsSnapshot().single().alertWaveArmed)

        // 2. OUT_OF_STOCK -> IN_STOCK: triggers wave #1!
        fakeClock += 60_000L
        val wave1 = repo.recordCheckResult("watch-1", analysis(RestockStockStatus.IN_STOCK, "WHMCS 检测到可配置下单表单"))
        assertNotNull(wave1)
        assertEquals(RestockStockStatus.OUT_OF_STOCK, wave1!!.previousStatus)
        assertEquals(RestockStockStatus.IN_STOCK, wave1.detectedStatus)
        assertEquals(54321L, wave1.sourceTopicId)
        assertEquals(1, repo.getEventHistorySnapshot().size)
        assertFalse(repo.getWatchItemsSnapshot().single().alertWaveArmed)

        // 3. IN_STOCK -> IN_STOCK (repeated polls): deduped! No second event
        fakeClock += 60_000L
        assertNull(repo.recordCheckResult("watch-1", analysis(RestockStockStatus.IN_STOCK)))
        fakeClock += 60_000L
        assertNull(repo.recordCheckResult("watch-1", analysis(RestockStockStatus.IN_STOCK)))
        assertEquals(1, repo.getEventHistorySnapshot().size)

        // 4. Transient network error while IN_STOCK (IN_STOCK -> CHECK_ERROR -> IN_STOCK): STILL deduped!
        fakeClock += 60_000L
        assertNull(repo.recordCheckResult("watch-1", analysis(RestockStockStatus.CHECK_ERROR, error = "503")))
        assertEquals(1, repo.getWatchItemsSnapshot().single().consecutiveErrors)
        fakeClock += 60_000L
        assertNull(repo.recordCheckResult("watch-1", analysis(RestockStockStatus.IN_STOCK)))
        assertEquals(0, repo.getWatchItemsSnapshot().single().consecutiveErrors)
        assertEquals(1, repo.getEventHistorySnapshot().size)

        // 5. Goes OUT_OF_STOCK again -> re-arms!
        fakeClock += 60_000L
        assertNull(repo.recordCheckResult("watch-1", analysis(RestockStockStatus.OUT_OF_STOCK)))
        assertTrue(repo.getWatchItemsSnapshot().single().alertWaveArmed)

        // 6. Transient error while OUT_OF_STOCK -> stays armed!
        fakeClock += 60_000L
        assertNull(repo.recordCheckResult("watch-1", analysis(RestockStockStatus.CHECK_ERROR, error = "Timeout")))
        assertTrue(repo.getWatchItemsSnapshot().single().alertWaveArmed)

        // 7. Recovers directly into IN_STOCK -> triggers wave #2!
        fakeClock += 60_000L
        val wave2 = repo.recordCheckResult("watch-1", analysis(RestockStockStatus.IN_STOCK))
        assertNotNull(wave2)
        assertEquals(2, repo.getEventHistorySnapshot().size)
        assertEquals(fakeClock, repo.getWatchItemsSnapshot().single().lastRestockedEpochMillis)
    }

    @Test
    fun `first check on an already IN_STOCK UNKNOWN item does not fire false restock alert`() = runTest {
        val repo = createRepo()
        repo.addOrUpdateWatch(
            RestockWatchItem(
                id = "already-in-stock",
                title = "Always Available VPS",
                rawUrl = "https://example.com/cart.php?a=add&pid=1",
                resolvedOrderUrl = "https://example.com/cart.php?a=add&pid=1",
                stockStatus = RestockStockStatus.UNKNOWN,
            ),
        )

        val event = repo.recordCheckResult("already-in-stock", analysis(RestockStockStatus.IN_STOCK))
        assertNull(event)
        assertEquals(RestockStockStatus.IN_STOCK, repo.getWatchItemsSnapshot().single().stockStatus)
        assertEquals(0, repo.getEventHistorySnapshot().size)
    }

    @Test
    fun `persists watch items, events, and settings cleanly across repository restarts`() = runTest {
        val repo1 = createRepo()
        repo1.updateSettings {
            it.copy(
                defaultPollIntervalMinutes = 5,
                backgroundIntervalMinutes = 30,
                wifiOnlyInBackground = true,
                urgentNotificationsEnabled = true,
            )
        }
        repo1.addOrUpdateWatch(
            RestockWatchItem(
                id = "persist-1",
                title = "DMIT Pro Wee",
                rawUrl = "https://www.dmit.io/cart.php?a=add&pid=183",
                resolvedOrderUrl = "https://www.dmit.io/cart.php?a=add&pid=183",
                panelType = MerchantPanelType.WHMCS,
                pollIntervalMinutes = 3,
                stockStatus = RestockStockStatus.OUT_OF_STOCK,
            ),
        )
        fakeClock += 30_000L
        val created = repo1.recordCheckResult("persist-1", analysis(RestockStockStatus.IN_STOCK, price = "$39.90/年"))
        assertNotNull(created)

        // Reload a fresh repository instance pointing at the same JSON file
        val repo2 = createRepo()
        val loadedItems = repo2.getWatchItemsSnapshot()
        val loadedEvents = repo2.getEventHistorySnapshot()
        val loadedSettings = repo2.getSettingsSnapshot()

        assertEquals(1, loadedItems.size)
        assertEquals("DMIT Pro Wee", loadedItems.single().title)
        assertEquals("$39.90/年", loadedItems.single().priceHint)
        assertEquals(RestockStockStatus.IN_STOCK, loadedItems.single().stockStatus)
        assertEquals(3, loadedItems.single().pollIntervalMinutes)
        assertFalse(loadedItems.single().alertWaveArmed)

        assertEquals(1, loadedEvents.size)
        assertEquals(created!!.id, loadedEvents.single().id)
        assertFalse(loadedEvents.single().isRead)

        assertEquals(5, loadedSettings.defaultPollIntervalMinutes)
        assertEquals(30, loadedSettings.backgroundIntervalMinutes)
        assertTrue(loadedSettings.wifiOnlyInBackground)

        // Verify read/delete/clear operations persist too
        repo2.markAllEventsRead()
        assertTrue(createRepo().getEventHistorySnapshot().single().isRead)

        repo2.updateWatchPollInterval("persist-1", 999)
        assertEquals(RestockWatchItem.MAX_POLL_INTERVAL_MINUTES, createRepo().getWatchItemsSnapshot().single().pollIntervalMinutes)

        repo2.clearEventHistory()
        assertTrue(createRepo().getEventHistorySnapshot().isEmpty())
    }

    @Test
    fun `recovers gracefully when store JSON file is corrupted`() = runTest {
        storeFile.writeText("{corrupted-json!!!")
        val repo = createRepo()
        assertTrue(repo.getWatchItemsSnapshot().isEmpty())
        assertTrue(repo.getEventHistorySnapshot().isEmpty())
    }

    @Test
    fun `updating watch URL resets stock status and disarms alert wave to prevent false restock`() = runTest {
        val repo = createRepo()
        val added = repo.addOrUpdateWatch(
            RestockWatchItem(
                id = "url-change-1",
                title = "Old Plan",
                rawUrl = "https://merchant.example/cart.php?a=add&pid=1",
                resolvedOrderUrl = "https://merchant.example/cart.php?a=add&pid=1",
            ),
        )
        // Arm wave via OUT_OF_STOCK check
        repo.recordCheckResult(added.id, analysis(RestockStockStatus.OUT_OF_STOCK))
        val armed = repo.getWatchItemsSnapshot().single()
        assertEquals(RestockStockStatus.OUT_OF_STOCK, armed.stockStatus)
        assertTrue(armed.alertWaveArmed)

        // Change URL to a different already-in-stock product -> must reset status & disarm wave
        repo.addOrUpdateWatch(
            armed.copy(
                rawUrl = "https://merchant.example/cart.php?a=add&pid=2",
                resolvedOrderUrl = "https://merchant.example/cart.php?a=add&pid=2",
            ),
        )
        val afterEdit = repo.getWatchItemsSnapshot().single()
        assertEquals(RestockStockStatus.UNKNOWN, afterEdit.stockStatus)
        assertFalse(afterEdit.alertWaveArmed)

        // First IN_STOCK check on new URL must NOT trigger a false restock event
        val falseEvent = repo.recordCheckResult(afterEdit.id, analysis(RestockStockStatus.IN_STOCK))
        assertEquals(null, falseEvent)
    }
}
