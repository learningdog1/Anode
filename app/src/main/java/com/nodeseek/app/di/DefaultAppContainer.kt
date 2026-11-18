package com.nodeseek.app.di

import android.content.Context
import android.os.Build
import com.nodeseek.app.BuildConfig
import com.nodeseek.app.data.local.DraftRepositoryImpl
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.local.NodePulseDatabase
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.mail.SeekMailApiClient
import com.nodeseek.app.data.mail.SeekMailDiskCache
import com.nodeseek.app.data.mail.SeekMailRepositoryImpl
import java.io.File
import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.network.WebViewSameOriginActionRunner
import com.nodeseek.app.data.radar.PulseRadarMonitor
import com.nodeseek.app.data.radar.RadarWorkScheduler
import com.nodeseek.app.data.repository.BookmarkAndHistoryRepositoryImpl
import com.nodeseek.app.data.repository.CheckInRepositoryImpl
import com.nodeseek.app.data.repository.ForumRepositoryImpl
import com.nodeseek.app.data.repository.InboxRepositoryImpl
import com.nodeseek.app.data.repository.NodeImageHostRepositoryImpl
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.UserRepositoryImpl
import com.nodeseek.app.data.update.AndroidApkUpdateInstaller
import com.nodeseek.app.data.update.AppUpdateManagerImpl
import com.nodeseek.app.data.update.GitHubReleaseClient
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.InboxRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.update.AppUpdateManager
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Production [AppContainer] wiring Room, DataStore, WebView cookie & User-Agent bridging,
 * [NodeSeekApiClient], Coil image [OkHttpClient], and all concrete domain repositories.
 */
class DefaultAppContainer(
    context: Context,
) : AppContainer {

    private val appContext: Context = context.applicationContext ?: context
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: NodePulseDatabase by lazy {
        NodePulseDatabase.getInstance(appContext)
    }

    val userAgentProvider: UserAgentProvider by lazy {
        UserAgentProvider().also { it.initFromContext(appContext) }
    }

    val cookieBridge: WebViewCookieBridge by lazy {
        WebViewCookieBridge()
    }

    val csrfTokenStore: CsrfTokenStore by lazy {
        CsrfTokenStore()
    }

    val apiHttpClient: OkHttpClient by lazy {
        NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            csrfTokenStore = csrfTokenStore,
        )
    }

    val coilOkHttpClient: OkHttpClient by lazy {
        CoilHttpClientFactory.create(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
        )
    }

    val sameOriginActionRunner: WebViewSameOriginActionRunner by lazy {
        WebViewSameOriginActionRunner(
            context = appContext,
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            csrfTokenStore = csrfTokenStore,
        )
    }

    val apiClient: NodeSeekApiClient by lazy {
        NodeSeekApiClient(
            httpClient = apiHttpClient,
            sameOriginTransport = sameOriginActionRunner,
            onConfigObserved = { config ->
                sessionRepositoryImpl.onConfigPayloadObserved(config)
            },
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            context = appContext,
            externalScope = applicationScope,
        )
    }

    override val draftRepository: DraftRepository by lazy {
        DraftRepositoryImpl(draftDao = database.draftDao())
    }

    private val sessionRepositoryImpl: SessionRepositoryImpl by lazy {
        SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        ).also { sessionRepo ->
            sessionRepo.unreadCountListener = { unreadCount ->
                inboxRepositoryImpl.onPiggybackedUnreadCount(unreadCount)
            }
            sessionRepo.embeddedCategoriesListener = { categories, uid ->
                forumRepositoryImpl.onEmbeddedCategoriesObserved(categories, uid)
            }
            sessionRepo.onVerifiedCallback = {
                sameOriginActionRunner.markVerifiedByUser()
            }
        }
    }

    private val inboxRepositoryImpl: InboxRepositoryImpl by lazy {
        InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepositoryImpl,
            watermarkStore = radarPreferencesStore,
        )
    }

    private val bookmarkAndHistoryRepositoryImpl: BookmarkAndHistoryRepositoryImpl by lazy {
        BookmarkAndHistoryRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepositoryImpl,
            bookmarkDao = database.bookmarkDao(),
            readHistoryDao = database.readHistoryDao(),
        )
    }

    val feedDiskCache: FeedDiskCache by lazy {
        FeedDiskCache(
            cacheDir = File(appContext.filesDir, "feed_cache_v1"),
            ioScope = applicationScope,
        )
    }

    private val forumRepositoryImpl: ForumRepositoryImpl by lazy {
        ForumRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepositoryImpl,
            settingsRepository = settingsRepository,
            categoryDao = database.categoryDao(),
            readHistoryDao = database.readHistoryDao(),
            bookmarkDao = database.bookmarkDao(),
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepositoryImpl,
            backgroundScope = applicationScope,
            feedDiskCache = feedDiskCache,
        )
    }

    override val sessionRepository: SessionRepository
        get() = sessionRepositoryImpl

    override val inboxRepository: InboxRepository
        get() = inboxRepositoryImpl

    override val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository
        get() = bookmarkAndHistoryRepositoryImpl

    override val forumRepository: ForumRepository
        get() = forumRepositoryImpl

    override val checkInRepository: CheckInRepository by lazy {
        CheckInRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepositoryImpl,
        )
    }

    override val userRepository: UserRepository by lazy {
        UserRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepositoryImpl,
        )
    }

    override val imageHostRepository: ImageHostRepository by lazy {
        NodeImageHostRepositoryImpl(
            apiClientProvider = { apiClient },
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepositoryImpl,
        )
    }

    val seekMailDiskCache: SeekMailDiskCache by lazy {
        SeekMailDiskCache(
            cacheDir = File(appContext.filesDir, "seek_mail_cache_v1"),
            ioScope = applicationScope,
        )
    }

    val seekMailApiClient: SeekMailApiClient by lazy {
        SeekMailApiClient(
            baseHttpClient = apiHttpClient,
        )
    }

    override val seekMailRepository: SeekMailRepository by lazy {
        SeekMailRepositoryImpl(
            apiClient = seekMailApiClient,
            settingsRepository = settingsRepository,
            diskCache = seekMailDiskCache,
            externalScope = applicationScope,
        )
    }

    private val radarPreferencesStore: RadarPreferencesStore by lazy {
        RadarPreferencesStore(appContext)
    }

    override val notificationDispatcher: PulseNotificationDispatcher by lazy {
        PulseNotificationDispatcher(appContext)
    }

    private val radarRepositoryImpl: RadarRepositoryImpl by lazy {
        RadarRepositoryImpl(
            radarDao = database.radarDao(),
            preferencesStore = radarPreferencesStore,
        )
    }

    override val radarRepository: RadarRepository
        get() = radarRepositoryImpl

    override val radarMonitorController: RadarMonitorController by lazy {
        PulseRadarMonitor(
            appContext = appContext,
            scope = applicationScope,
            apiClient = apiClient,
            inboxRepository = inboxRepositoryImpl,
            sessionRepository = sessionRepositoryImpl,
            settingsRepository = settingsRepository,
            radarRepository = radarRepositoryImpl,
            notificationDispatcher = notificationDispatcher,
            watermarkStore = radarPreferencesStore,
        )
    }

    val radarWorkScheduler: RadarWorkScheduler by lazy {
        RadarWorkScheduler(appContext)
    }

    fun startBackgroundRadarScheduling() {
        radarWorkScheduler.observePollingSettings(
            scope = applicationScope,
            pollingSettingsFlow = radarRepository.pollingSettingsFlow,
        )
    }

    val gitHubReleaseClient: GitHubReleaseClient by lazy {
        GitHubReleaseClient(
            baseHttpClient = apiHttpClient,
            owner = BuildConfig.GITHUB_OWNER,
            repo = BuildConfig.GITHUB_REPO,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    override val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManagerImpl(
            releaseClient = gitHubReleaseClient,
            settingsRepository = settingsRepository,
            installer = AndroidApkUpdateInstaller(appContext),
            cacheFile = File(appContext.filesDir, AppUpdateManagerImpl.CACHE_FILE_NAME),
            apkDirectory = File(appContext.cacheDir, AppUpdateManagerImpl.APK_CACHE_DIR_NAME),
            externalScope = applicationScope,
        )
    }
}
