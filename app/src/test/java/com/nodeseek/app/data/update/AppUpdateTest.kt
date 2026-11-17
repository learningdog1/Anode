package com.nodeseek.app.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.domain.update.AppReleaseAsset
import com.nodeseek.app.domain.update.AppVersionComparator
import com.nodeseek.app.domain.update.UpdateCheckResult
import com.nodeseek.app.domain.update.UpdateDownloadPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

class AppUpdateTest {

    private lateinit var server: MockWebServer
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempRoot = Files.createTempDirectory("anode_update_test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempRoot.deleteRecursively()
    }

    // ── 1. AppVersionComparator Tests ──────────────────────────────────────────────────────

    @Test
    fun `AppVersionComparator compares standard and prefixed versions accurately`() {
        assertTrue(AppVersionComparator.isNewer("v1.0.1", "1.0.0"))
        assertTrue(AppVersionComparator.isNewer("v1.10.0", "v1.2.0"))
        assertTrue(AppVersionComparator.isNewer("Anode-v2.0.0", "v1.9.9"))
        assertEquals(0, AppVersionComparator.compare("v1.2", "1.2.0"))
        assertFalse(AppVersionComparator.isNewer("v1.0.0", "1.0.0"))
        assertFalse(AppVersionComparator.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `AppVersionComparator enforces SemVer prerelease ordering`() {
        val ordered = listOf(
            "v1.0.0",
            "v1.1.0-alpha.1",
            "v1.1.0-beta.1",
            "v1.1.0-beta.2",
            "v1.1.0-rc.1",
            "v1.1.0",
        )
        for (i in 0 until ordered.lastIndex) {
            assertTrue(
                "Expected ${ordered[i + 1]} > ${ordered[i]}",
                AppVersionComparator.isNewer(ordered[i + 1], ordered[i]),
            )
            assertFalse(
                "Expected ${ordered[i]} <= ${ordered[i + 1]}",
                AppVersionComparator.isNewer(ordered[i], ordered[i + 1]),
            )
        }
    }

    // ── 2. GitHubReleaseClient Tests ───────────────────────────────────────────────────────

    @Test
    fun `GitHubReleaseClient strips foreign cookies and headers and parses release with digest`() = runTest {
        val pollutedClient = OkHttpClient.Builder()
            .cookieJar(
                object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                        Cookie.Builder()
                            .name("nodeseek_session")
                            .value("secret_cookie")
                            .domain(url.host)
                            .path("/")
                            .build(),
                    )
                },
            )
            .addInterceptor(
                Interceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("X-NodeSeek-Leak", "leaked")
                            .header("Cookie", "session=123")
                            .build(),
                    )
                },
            )
            .build()

        val expectedSha = "3643b8dd4259a9fd3153f61cca5689b8803feb61174325e1f8377fccfccbe831"
        val jsonBody = """
            {
              "tag_name": "v1.1.0",
              "name": "Anode v1.1.0",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-20T08:00:00Z",
              "html_url": "https://github.com/learningdog1/Anode/releases/tag/v1.1.0",
              "body": "## 更新内容\n- 新增应用内更新功能",
              "assets": [
                {
                  "id": 1001,
                  "name": "Anode-v1.1.0-release.apk.sha256",
                  "content_type": "text/plain",
                  "state": "uploaded",
                  "size": 92,
                  "browser_download_url": "${server.url("/download/Anode-v1.1.0-release.apk.sha256")}"
                },
                {
                  "id": 1002,
                  "name": "Anode-v1.1.0-release.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "state": "uploaded",
                  "size": 15053084,
                  "digest": "sha256:$expectedSha",
                  "browser_download_url": "${server.url("/download/Anode-v1.1.0-release.apk")}"
                }
              ]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag-v110\"")
                .setBody(jsonBody),
        )

        val client = GitHubReleaseClient(
            baseHttpClient = pollutedClient,
            apiBaseUrl = server.url("/"),
            owner = "learningdog1",
            repo = "Anode",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        val result = client.fetchLatestRelease(includePrerelease = false, cachedEtag = null)
        assertTrue(result is FetchReleaseResponse.Success)
        val success = result as FetchReleaseResponse.Success
        assertEquals("\"etag-v110\"", success.etag)
        val release = success.release
        assertNotNull(release)
        assertEquals("v1.1.0", release!!.tagName)
        assertEquals("1.1.0", release.versionName)
        assertEquals("Anode-v1.1.0-release.apk", release.apkAsset?.name)
        assertEquals(expectedSha, release.apkAsset?.sha256)

        val req = server.takeRequest()
        assertEquals("/repos/learningdog1/Anode/releases/latest", req.path)
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertNull(req.getHeader("Cookie"))
        assertNull(req.getHeader("X-NodeSeek-Leak"))
    }

    @Test
    fun `GitHubReleaseClient extracts fallback sha256 from release body and supports 304 Not Modified`() = runTest {
        val bodySha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val jsonBody = """
            {
              "tag_name": "v1.2.0",
              "name": "Anode v1.2.0",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-22T09:00:00Z",
              "html_url": "https://github.com/learningdog1/Anode/releases/tag/v1.2.0",
              "body": "### SHA-256\n```text\n$bodySha  Anode-v1.2.0-release.apk\n```",
              "assets": [
                {
                  "id": 2001,
                  "name": "Anode-v1.2.0-release.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "state": "uploaded",
                  "size": 1024,
                  "browser_download_url": "${server.url("/download/Anode-v1.2.0-release.apk")}"
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"etag-120\"").setBody(jsonBody))
        server.enqueue(MockResponse().setResponseCode(304))

        val client = GitHubReleaseClient(
            apiBaseUrl = server.url("/"),
            owner = "learningdog1",
            repo = "Anode",
        )

        val first = client.fetchLatestRelease(includePrerelease = false, cachedEtag = null) as FetchReleaseResponse.Success
        assertEquals(bodySha, first.release?.apkAsset?.sha256)

        val second = client.fetchLatestRelease(includePrerelease = false, cachedEtag = "\"etag-120\"")
        assertTrue(second is FetchReleaseResponse.NotModified)

        server.takeRequest()
        val req2 = server.takeRequest()
        assertEquals("\"etag-120\"", req2.getHeader("If-None-Match"))
    }

    @Test
    fun `GitHubReleaseClient selects ABI-preferred apk and supports prerelease channel`() = runTest {
        val releasesJson = """
            [
              {
                "tag_name": "v1.3.0-draft",
                "draft": true,
                "prerelease": false,
                "assets": []
              },
              {
                "tag_name": "v1.2.0-rc.1",
                "name": "Anode v1.2.0 RC1",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-11-25T10:00:00Z",
                "html_url": "https://github.com/learningdog1/Anode/releases/tag/v1.2.0-rc.1",
                "assets": [
                  {
                    "id": 1,
                    "name": "Anode-v1.2.0-rc.1-x86_64-release.apk",
                    "state": "uploaded",
                    "size": 500,
                    "browser_download_url": "${server.url("/dl/x86_64.apk")}"
                  },
                  {
                    "id": 2,
                    "name": "Anode-v1.2.0-rc.1-universal-release.apk",
                    "state": "uploaded",
                    "size": 800,
                    "browser_download_url": "${server.url("/dl/universal.apk")}"
                  },
                  {
                    "id": 3,
                    "name": "Anode-v1.2.0-rc.1-arm64-v8a-release.apk",
                    "state": "uploaded",
                    "size": 600,
                    "browser_download_url": "${server.url("/dl/arm64.apk")}"
                  },
                  {
                    "id": 4,
                    "name": "Anode-v1.2.0-rc.1-arm64-v8a-debug.apk",
                    "state": "uploaded",
                    "size": 650,
                    "browser_download_url": "${server.url("/dl/arm64-debug.apk")}"
                  }
                ]
              },
              {
                "tag_name": "v1.1.0",
                "name": "Anode v1.1.0",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-20T10:00:00Z",
                "html_url": "https://github.com/learningdog1/Anode/releases/tag/v1.1.0",
                "assets": []
              }
            ]
        """.trimIndent()

        // When /releases/latest returns 404, stable mode falls back to /releases?per_page=10 and excludes prerelease
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody(releasesJson))
        // Prerelease mode picks v1.2.0-rc.1 and arm64-v8a release APK
        server.enqueue(MockResponse().setResponseCode(200).setBody(releasesJson))

        val client = GitHubReleaseClient(
            apiBaseUrl = server.url("/"),
            owner = "learningdog1",
            repo = "Anode",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        val stable = client.fetchLatestRelease(includePrerelease = false) as FetchReleaseResponse.Success
        assertEquals("v1.1.0", stable.release?.tagName)

        val pre = client.fetchLatestRelease(includePrerelease = true) as FetchReleaseResponse.Success
        assertEquals("v1.2.0-rc.1", pre.release?.tagName)
        assertTrue(pre.release!!.isPrerelease)
        assertEquals("Anode-v1.2.0-rc.1-arm64-v8a-release.apk", pre.release?.apkAsset?.name)
    }

    @Test
    fun `GitHubReleaseClient follows 302 redirect, verifies SHA-256, and rejects tampered payloads`() = runTest {
        val fakeApkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(8192) { (it % 127).toByte() }
        val expectedSha = sha256Hex(fakeApkBytes)
        val downloadDir = File(tempRoot, "apks_test")

        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/cdn/Anode-v1.1.0-release.apk").toString()),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(fakeApkBytes)),
        )

        val client = GitHubReleaseClient(
            apiBaseUrl = server.url("/"),
            owner = "learningdog1",
            repo = "Anode",
        )

        val asset = AppReleaseAsset(
            id = 1L,
            name = "Anode-v1.1.0-release.apk",
            downloadUrl = server.url("/releases/download/v1.1.0/Anode-v1.1.0-release.apk").toString(),
            sizeBytes = fakeApkBytes.size.toLong(),
            sha256 = expectedSha,
        )

        val progressSnapshots = mutableListOf<Pair<Long, Long>>()
        val downloadedFile = client.downloadApkAsset(asset, downloadDir) { downloaded, total ->
            progressSnapshots.add(downloaded to total)
        }

        assertTrue(downloadedFile.isFile)
        assertEquals(fakeApkBytes.size.toLong(), downloadedFile.length())
        assertTrue(progressSnapshots.isNotEmpty())
        assertEquals(fakeApkBytes.size.toLong(), progressSnapshots.last().first)

        // Now test tampered SHA-256 rejection and verify .part cleanup
        val badAsset = AppReleaseAsset(
            id = 2L,
            name = "Anode-v1.1.1-release.apk",
            downloadUrl = server.url("/releases/download/v1.1.1/Anode-v1.1.1-release.apk").toString(),
            sizeBytes = fakeApkBytes.size.toLong(),
            sha256 = "0".repeat(64),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(fakeApkBytes)),
        )

        try {
            client.downloadApkAsset(badAsset, downloadDir) { _, _ -> }
            fail("Expected GitHubReleaseException on SHA-256 mismatch")
        } catch (e: GitHubReleaseException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertFalse(File(downloadDir, "Anode-v1.1.1-release.apk").exists())
        assertFalse(File(downloadDir, "Anode-v1.1.1-release.apk.part").exists())
    }

    // ── 3. SettingsRepository & AppUpdateManagerImpl State Machine Tests ───────────────────

    @Test
    fun `SettingsRepositoryImpl persists update preferences across DataStore round-trip`() = runBlocking {
        val dsDir = Files.createTempDirectory("settings_update_ds").toFile()
        val dsFile = File(dsDir, "nodepulse_update_settings.preferences_pb")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { dsFile }
            val writer = SettingsRepositoryImpl(dataStore = dataStore, externalScope = repoScope1)
            writer.setAutoCheckUpdates(false)
            writer.setIncludePrereleaseUpdates(true)
            writer.setIgnoredUpdateTag(" v1.2.0 ")

            val written = withTimeout(5_000L) {
                writer.settings.first { !it.autoCheckUpdates && it.includePrereleaseUpdates }
            }
            assertFalse(written.autoCheckUpdates)
            assertTrue(written.includePrereleaseUpdates)
            assertEquals("v1.2.0", written.ignoredUpdateTag)

            val reader = SettingsRepositoryImpl(dataStore = dataStore, externalScope = repoScope2)
            val reloaded = withTimeout(5_000L) {
                reader.settings.first { it.ignoredUpdateTag == "v1.2.0" }
            }
            assertFalse(reloaded.autoCheckUpdates)
            assertTrue(reloaded.includePrereleaseUpdates)
            assertEquals("v1.2.0", reloaded.ignoredUpdateTag)
        } finally {
            repoScope1.cancel()
            repoScope2.cancel()
            dataStoreScope.cancel()
            dsDir.deleteRecursively()
        }
    }

    @Test
    fun `AppUpdateManagerImpl respects ignored tag on auto check and resumes install after permission`() = runBlocking {
        val fakeApkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(2048) { 0x11 }
        val expectedSha = sha256Hex(fakeApkBytes)
        val releaseJson = """
            {
              "tag_name": "v1.5.0",
              "name": "Anode v1.5.0",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-12-01T12:00:00Z",
              "html_url": "https://github.com/learningdog1/Anode/releases/tag/v1.5.0",
              "body": "Bug fixes",
              "assets": [
                {
                  "id": 5001,
                  "name": "Anode-v1.5.0-release.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "state": "uploaded",
                  "size": ${fakeApkBytes.size},
                  "digest": "sha256:$expectedSha",
                  "browser_download_url": "${server.url("/dl/Anode-v1.5.0-release.apk")}"
                }
              ]
            }
        """.trimIndent()

        val settingsRepo = FakeSettingsRepository().apply {
            setIgnoredUpdateTag("v1.5.0")
        }
        val fakeInstaller = FakeApkInstallerGateway(canInstall = false)
        val cacheFile = File(tempRoot, "update_cache.json")
        val apkDir = File(tempRoot, "apk_cache").apply { mkdirs() }
        // Pre-seed stale v1.0.0 APK & .part file to verify launch cleanup removes outdated artifacts
        val stalePart = File(apkDir, "Anode-v1.0.0-release.apk.part").apply { writeText("partial") }
        val staleApk = File(apkDir, "Anode-v1.0.0-release.apk").apply { writeBytes(fakeApkBytes) }

        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val client = GitHubReleaseClient(
                apiBaseUrl = server.url("/"),
                owner = "learningdog1",
                repo = "Anode",
            )
            var nowMillis = 1_000_000L
            val manager = AppUpdateManagerImpl(
                releaseClient = client,
                settingsRepository = settingsRepo,
                installer = fakeInstaller,
                cacheFile = cacheFile,
                apkDirectory = apkDir,
                externalScope = managerScope,
                currentVersionName = "1.0.0",
                currentVersionCode = 1,
                nowMillisProvider = { nowMillis },
            )

            // 1. Auto-check finds ignored v1.5.0 -> hasUpdate=true, showUpdatePrompt=false
            server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"etag-150\"").setBody(releaseJson))
            val autoOutcome = manager.checkForUpdates(manual = false)
            assertTrue(autoOutcome is UpdateCheckResult.UpdateAvailable)
            assertTrue(manager.state.value.hasUpdate)
            assertFalse(manager.state.value.showUpdatePrompt)

            // 2. Manual check overrides ignored suppression -> showUpdatePrompt=true
            nowMillis += 20L * 60L * 1000L
            server.enqueue(MockResponse().setResponseCode(304))
            val manualOutcome = manager.checkForUpdates(manual = true)
            assertTrue(manualOutcome is UpdateCheckResult.UpdateAvailable)
            assertTrue(manager.state.value.showUpdatePrompt)

            // 3. Download APK while install permission is disabled -> ReadyToInstall(requiresInstallPermission=true)
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(fakeApkBytes)))
            manager.startDownloadAndInstall()

            val readyState = withTimeout(5_000L) {
                manager.state.first {
                    it.downloadPhase is UpdateDownloadPhase.ReadyToInstall ||
                        it.downloadPhase is UpdateDownloadPhase.Failed
                }
            }
            assertTrue(
                "Unexpected phase: ${readyState.downloadPhase}",
                readyState.downloadPhase is UpdateDownloadPhase.ReadyToInstall,
            )
            val readyPhase = readyState.downloadPhase as UpdateDownloadPhase.ReadyToInstall
            assertTrue(readyPhase.requiresInstallPermission)
            assertEquals(0, fakeInstaller.launchedFiles.size)

            // 4. User opens unknown sources settings, grants permission, and returns to app (onAppResumed)
            assertTrue(manager.openUnknownSourcesSettings())
            assertEquals(1, fakeInstaller.settingsOpenedCount)
            fakeInstaller.canInstall = true
            manager.onAppResumed()

            val resumedPhase = manager.state.value.downloadPhase as UpdateDownloadPhase.ReadyToInstall
            assertFalse(resumedPhase.requiresInstallPermission)
            assertEquals(1, fakeInstaller.launchedFiles.size)
            assertEquals("Anode-v1.5.0-release.apk", fakeInstaller.launchedFiles.single().name)

            // Verify stale launch files were cleaned up
            assertFalse(stalePart.exists())
            assertFalse(staleApk.exists())
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `checkForUpdatesOnLaunch skips network call when autoCheckUpdates is disabled`() = runBlocking {
        val settingsRepo = FakeSettingsRepository().apply {
            setAutoCheckUpdates(false)
        }
        val fakeInstaller = FakeApkInstallerGateway(canInstall = true)
        val cacheFile = File(tempRoot, "update_cache_off.json")
        val apkDir = File(tempRoot, "apk_cache_off").apply { mkdirs() }
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val client = GitHubReleaseClient(
                apiBaseUrl = server.url("/"),
                owner = "learningdog1",
                repo = "Anode",
            )
            val manager = AppUpdateManagerImpl(
                releaseClient = client,
                settingsRepository = settingsRepo,
                installer = fakeInstaller,
                cacheFile = cacheFile,
                apkDirectory = apkDir,
                externalScope = managerScope,
                currentVersionName = "1.0.0",
                currentVersionCode = 1,
            )

            val initialCount = server.requestCount
            manager.checkForUpdatesOnLaunch()
            kotlinx.coroutines.delay(250L)
            assertEquals(initialCount, server.requestCount)
            assertFalse(manager.state.value.hasUpdate)
            assertFalse(manager.state.value.showUpdatePrompt)
        } finally {
            managerScope.cancel()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private class FakeApkInstallerGateway(
        var canInstall: Boolean,
    ) : ApkInstallerGateway {
        val launchedFiles = mutableListOf<File>()
        var settingsOpenedCount = 0

        override fun canRequestPackageInstalls(): Boolean = canInstall

        override fun verifyArchivePackageName(apkFile: File): Boolean = apkFile.isFile && apkFile.length() > 0L

        override fun getArchiveVersionName(apkFile: File): String? = null

        override fun launchInstall(apkFile: File): Boolean {
            launchedFiles.add(apkFile)
            return true
        }

        override fun openUnknownSourcesSettings(): Boolean {
            settingsOpenedCount += 1
            return true
        }
    }
}
