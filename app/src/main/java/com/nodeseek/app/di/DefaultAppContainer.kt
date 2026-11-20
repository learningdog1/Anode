package com.nodeseek.app.di

import android.content.Context
import android.os.Build
import com.nodeseek.app.BuildConfig
import com.nodeseek.app.data.backup.BackupManagerImpl
import com.nodeseek.app.data.backup.ContentFilterRepositoryBackupBridge
import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.fleet.FleetMonitorWorker
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.local.BlockedUsersCache
import com.nodeseek.app.data.local.ContentFilterRepositoryImpl
import com.nodeseek.app.data.local.DataStoreBlockedUsersCache
import com.nodeseek.app.data.local.DraftRepositoryImpl
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.local.NodePulseDatabase
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.mail.SeekMailApiClient
import com.nodeseek.app.data.mail.SeekMailDiskCache
import com.nodeseek.app.data.mail.SeekMailRepositoryImpl
import java.io.File
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.network.CsrfTokenStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.network.WebViewSameOriginActionRunner
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
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
import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.RestockCheckWorker
import com.nodeseek.app.data.restock.RestockMonitorControllerImpl
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.restock.RestockRepositoryImpl
import com.nodeseek.app.data.restock.RestockWorkScheduler
import com.nodeseek.app.data.restock.StockPageAnalyzer
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.data.update.AndroidApkUpdateInstaller
import com.nodeseek.app.data.update.AppUpdateManagerImpl
import com.nodeseek.app.data.update.GitHubReleaseClient
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.EmptyActivityRepository
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
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
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.trust.SellerTrustRepository
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

    override val contentFilterRepository: ContentFilterRepository by lazy {
        ContentFilterRepositoryImpl(
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
                forumRepositoryImpl.clearRssFallbackCache()
            }
            sessionRepo.onChallengeClearedOrAuthenticated = {
                forumRepositoryImpl.clearRssFallbackCache()
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

    private val blockedUsersCache: BlockedUsersCache by lazy {
        DataStoreBlockedUsersCache(appContext)
    }

    override val userRepository: UserRepository by lazy {
        UserRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepositoryImpl,
            blockedUsersCache = blockedUsersCache,
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

    override val backupManager: BackupManager by lazy {
        BackupManagerImpl(
            settingsRepository = settingsRepository,
            radarRepository = radarRepository,
            bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
            draftRepository = draftRepository,
            userRepository = userRepository,
            sessionRepository = sessionRepository,
            blockedUsersCache = blockedUsersCache,
            contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(contentFilterRepository),
            onRadarSettingsRestored = { restored -> radarWorkScheduler.sync(restored) },
        )
    }

    override val exchangeRateRepository: ExchangeRateRepository by lazy {
        ExchangeRateRepository.forContext(appContext)
    }

    override val restockNotificationHelper: RestockNotificationHelper by lazy {
        RestockNotificationHelper(appContext)
    }

    override val restockRepository: RestockRepository by lazy {
        RestockRepositoryImpl(appContext)
    }

    override val flareSolverrClient: FlareSolverrClient by lazy {
        FlareSolverrClient()
    }

    val stockPageAnalyzer: StockPageAnalyzer by lazy {
        StockPageAnalyzer(
            flareSolverrClient = flareSolverrClient,
            settingsProvider = { restockRepository.getSettingsSnapshot() },
        )
    }

    override val restockMonitorController: RestockMonitorController by lazy {
        RestockMonitorControllerImpl(
            appContext = appContext,
            repository = restockRepository,
            analyzer = stockPageAnalyzer,
            notificationHelper = restockNotificationHelper,
            scope = applicationScope,
        )
    }

    val restockWorkScheduler: RestockWorkScheduler by lazy {
        RestockWorkScheduler(appContext)
    }

    override val overseasIntelRepository: OverseasIntelRepository by lazy {
        OverseasIntelRepositoryImpl(
            context = appContext,
        )
    }

    override val activityRepository: ActivityRepository by lazy {
        // TODO(ForumWatch): 接入真实 ForumWatch ActivityRepository 实现
        EmptyActivityRepository()
    }

    override val sellerTrustRepository: SellerTrustRepository by lazy {
        SellerTrustRepositoryImpl.forContext(
            context = appContext,
            userRepository = userRepository,
            forumRepository = forumRepository,
            baseHttpClient = apiHttpClient,
            userAgentProvider = userAgentProvider,
            sameOriginTransport = sameOriginActionRunner,
            onConfigObserved = { config ->
                sessionRepositoryImpl.onConfigPayloadObserved(config)
            },
        )
    }

    override val fleetRepository: FleetRepository by lazy {
        FleetRepositoryImpl.forContext(appContext)
    }

    val fleetAlertNotifier: FleetAlertNotifier by lazy {
        FleetAlertNotifier(appContext)
    }

    override val fleetWorkScheduler: FleetWorkScheduler by lazy {
        FleetWorkScheduler(appContext)
    }

    override val netBenchHistoryStore: NetBenchHistoryStore by lazy {
        NetBenchHistoryStore.forContext(appContext)
    }

    override val sshVaultRepository: SshVaultRepository by lazy {
        SshServerVault.getDefault(appContext)
    }

    fun startBackgroundFeatureSchedulers() {
        restockNotificationHelper.ensureChannel()
        fleetAlertNotifier.ensureChannels()
        RestockCheckWorker.registerControllerProvider { restockMonitorController }
        FleetMonitorWorker.externalRepositoryFactory = { fleetRepository }
        restockWorkScheduler.observe(
            scope = applicationScope,
            settingsFlow = restockRepository.settingsFlow,
            watchItemsFlow = restockRepository.watchItemsFlow,
        )
        fleetWorkScheduler.observeRepository(
            scope = applicationScope,
            repository = fleetRepository,
        )
    }
}
