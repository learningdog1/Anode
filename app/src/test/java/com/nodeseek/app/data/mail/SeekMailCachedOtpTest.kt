package com.nodeseek.app.data.mail

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SeekMailCachedOtpTest {

    private lateinit var cacheDir: File
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var repoScope: CoroutineScope
    private lateinit var diskCache: SeekMailDiskCache
    private lateinit var repository: SeekMailRepositoryImpl

    private val keyA = "sk_live_" + "a".repeat(32)
    private val keyB = "sk_live_" + "b".repeat(32)

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("seek_mail_cached_otp_test").toFile()
        settingsRepository = FakeSettingsRepository()
        repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        diskCache = SeekMailDiskCache(cacheDir = cacheDir, ioScope = repoScope)
        repository = SeekMailRepositoryImpl(
            apiClient = SeekMailApiClient(baseUrl = "https://example.invalid/openapi/v1".toHttpUrl()),
            settingsRepository = settingsRepository,
            diskCache = diskCache,
            externalScope = repoScope,
        )
    }

    @After
    fun tearDown() {
        repoScope.cancel()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `getCachedLatestOtp returns null without API key or cached pages`() = runBlocking {
        assertNull(repository.getCachedLatestOtp())
        settingsRepository.setSeekMailApiKey(keyA)
        assertNull(repository.getCachedLatestOtp())
    }

    @Test
    fun `getCachedLatestOtp extracts latest non-deleted OTP from disk cache and falls back via VerificationCodeExtractor`() = runBlocking {
        settingsRepository.setSeekMailApiKey(keyA)

        val olderWithServerCode = SeekMailSummary(
            id = 101L,
            mailboxId = 1L,
            fromAddress = "noreply@older.com",
            fromName = "OlderService",
            toAddress = "box@seek.li",
            subject = "Older code",
            snippet = "Your code is 111222",
            verificationCode = "111222",
            receivedAt = "2026-11-26T07:00:00Z",
        )
        val newerNoOtp = SeekMailSummary(
            id = 102L,
            mailboxId = 1L,
            fromAddress = "news@promo.com",
            fromName = "Newsletter",
            toAddress = "box@seek.li",
            subject = "Weekly digest",
            snippet = "Nothing to verify here",
            verificationCode = null,
            receivedAt = "2026-11-26T09:00:00Z",
        )
        val newestOtpViaExtractor = SeekMailSummary(
            id = 103L,
            mailboxId = 1L,
            fromAddress = "security@github.com",
            fromName = "GitHub",
            toAddress = "box@seek.li",
            subject = "GitHub verification",
            snippet = "Your verification code is 739104. It expires in 10 minutes.",
            verificationCode = null,
            receivedAt = "2026-11-26T08:30:00Z",
        )

        diskCache.saveDefaultMailsPage(
            apiKey = keyA,
            mailboxId = null,
            page = SeekMailPage(
                items = listOf(olderWithServerCode, newerNoOtp, newestOtpViaExtractor),
                limit = 20,
                offset = 0,
                total = 3,
                hasMore = false,
            ),
        )

        val latest = repository.getCachedLatestOtp()
        assertNotNull(latest)
        assertEquals(103L, latest!!.mailId)
        assertEquals("739104", latest.code)
        assertEquals("GitHub", latest.sender)

        // Delete the newest OTP mail locally -> falls back to 101L
        repository.deleteMailLocally(103L)
        val afterDelete = repository.getCachedLatestOtp()
        assertNotNull(afterDelete)
        assertEquals(101L, afterDelete!!.mailId)
        assertEquals("111222", afterDelete.code)
    }

    @Test
    fun `getCachedLatestOtp scans per-mailbox cached pages when mb_0 is empty and isolates keys`() = runBlocking {
        settingsRepository.setSeekMailApiKey(keyA)

        // No mb_0 page cached, only mailbox 42
        diskCache.saveDefaultMailsPage(
            apiKey = keyA,
            mailboxId = 42L,
            page = SeekMailPage(
                items = listOf(
                    SeekMailSummary(
                        id = 201L,
                        mailboxId = 42L,
                        fromAddress = "auth@cloudflare.com",
                        fromName = "Cloudflare",
                        toAddress = "cf@seek.li",
                        subject = "Login verification code",
                        snippet = "您的验证码为 482-910，请勿泄露",
                        verificationCode = null,
                        receivedAt = "2026-11-26T08:40:00Z",
                    ),
                ),
                limit = 20,
                offset = 0,
                total = 1,
                hasMore = false,
            ),
        )

        val fromMailboxPage = repository.getCachedLatestOtp()
        assertNotNull(fromMailboxPage)
        assertEquals(201L, fromMailboxPage!!.mailId)
        assertEquals("482910", fromMailboxPage.code)

        // Switch to keyB -> must not leak keyA's cached OTP
        settingsRepository.setSeekMailApiKey(keyB)
        assertNull(repository.getCachedLatestOtp())
    }

    @Test
    fun `getCachedLatestOtp returns newer per-mailbox mb_1 OTP instead of older mb_0 OTP`() = runBlocking {
        settingsRepository.setSeekMailApiKey(keyA)

        diskCache.saveDefaultMailsPage(
            apiKey = keyA,
            mailboxId = null,
            page = SeekMailPage(
                items = listOf(
                    SeekMailSummary(
                        id = 5L,
                        mailboxId = 1L,
                        fromAddress = "old@example.com",
                        fromName = "OldService",
                        toAddress = "me@seek.li",
                        subject = "Old OTP",
                        snippet = "Code: 111000",
                        verificationCode = "111000",
                        receivedAt = "2026-11-24T00:00:00Z",
                    ),
                ),
                limit = 20,
                offset = 0,
                total = 1,
                hasMore = false,
            ),
            fetchedAtEpochMillis = 1_000L,
        )

        diskCache.saveDefaultMailsPage(
            apiKey = keyA,
            mailboxId = 1L,
            page = SeekMailPage(
                items = listOf(
                    SeekMailSummary(
                        id = 11L,
                        mailboxId = 1L,
                        fromAddress = "new@example.com",
                        fromName = "NewService",
                        toAddress = "me@seek.li",
                        subject = "验证码：808123",
                        snippet = "请使用验证码 808123",
                        verificationCode = null,
                        receivedAt = "2026-11-26T08:00:00Z",
                    ),
                ),
                limit = 20,
                offset = 0,
                total = 1,
                hasMore = false,
            ),
            fetchedAtEpochMillis = 2_000L,
        )

        val latest = repository.getCachedLatestOtp()
        assertNotNull(latest)
        assertEquals(11L, latest!!.mailId)
        assertEquals("808123", latest.code)
        assertEquals("NewService", latest.sender)
    }

    @Test
    fun `getCachedLatestOtp awaits delayed SettingsRepository hydration on cold start`() = runBlocking {
        val backingSettings = FakeSettingsRepository()
        val delayedSettings = object : SettingsRepository by backingSettings {
            override suspend fun awaitLoadedSettings(): AppSettings {
                delay(35L)
                backingSettings.setSeekMailApiKey(keyA)
                return backingSettings.settings.value
            }
        }
        val coldRepository = SeekMailRepositoryImpl(
            apiClient = SeekMailApiClient(baseUrl = "https://example.invalid/openapi/v1".toHttpUrl()),
            settingsRepository = delayedSettings,
            diskCache = diskCache,
            externalScope = repoScope,
        )

        diskCache.saveDefaultMailsPage(
            apiKey = keyA,
            mailboxId = null,
            page = SeekMailPage(
                items = listOf(
                    SeekMailSummary(
                        id = 301L,
                        mailboxId = 1L,
                        fromAddress = "cold@example.com",
                        fromName = "ColdAuth",
                        toAddress = "me@seek.li",
                        subject = "Code 556677",
                        snippet = "Your verification code is 556677",
                        verificationCode = "556677",
                        receivedAt = "2026-11-26T09:15:00Z",
                    ),
                ),
                limit = 20,
                offset = 0,
                total = 1,
                hasMore = false,
            ),
        )

        assertEquals("", delayedSettings.settings.value.seekMailApiKey)
        val latest = coldRepository.getCachedLatestOtp()
        assertNotNull(latest)
        assertEquals(301L, latest!!.mailId)
        assertEquals("556677", latest.code)
    }
}
