package com.nodeseek.app.di

import androidx.compose.runtime.staticCompositionLocalOf
import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.fleet.JvmTlsCertificateInspector
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.activity.ActivityMonitorController
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.LuckyDrawRepository
import com.nodeseek.app.domain.activity.NoOpActivityMonitorController
import com.nodeseek.app.domain.activity.NoOpLuckyDrawRepository
import com.nodeseek.app.domain.alerts.AlertForwarder
import com.nodeseek.app.domain.alerts.NoOpAlertForwarder
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.baseline.BenchmarkBaselineRepository
import com.nodeseek.app.domain.baseline.NoOpBenchmarkBaselineRepository
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.TlsCertificateInspector
import com.nodeseek.app.domain.mail.NoOpSeekMailOtpWatcher
import com.nodeseek.app.domain.mail.NoOpVpsMailAutoIngestService
import com.nodeseek.app.domain.mail.SeekMailOtpWatcher
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.mail.VpsMailAutoIngestService
import com.nodeseek.app.domain.overseas.NoOpOverseasPromoMonitorController
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorController
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.digest.AwayDigestRepository
import com.nodeseek.app.domain.digest.NoOpAwayDigestRepository
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.CommunityToolsRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.InboxRepository
import com.nodeseek.app.domain.repository.MultiAccountManager
import com.nodeseek.app.domain.repository.NoOpCommunityToolsRepository
import com.nodeseek.app.domain.repository.NoOpMultiAccountManager
import com.nodeseek.app.domain.repository.NoOpPollAndStardustRepository
import com.nodeseek.app.domain.repository.PollAndStardustRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.trust.NoOpOfficialTradeRepository
import com.nodeseek.app.domain.trust.OfficialTradeRepository
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.update.AppUpdateManager
import com.nodeseek.app.domain.valuation.NoOpTradeMarketReferenceRepository
import com.nodeseek.app.domain.valuation.TradeMarketReferenceRepository
import com.nodeseek.app.domain.widget.NoOpWidgetSnapshotRepository
import com.nodeseek.app.domain.widget.WidgetSnapshotRepository
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import okhttp3.CookieJar
import okhttp3.OkHttpClient

interface AppContainer {
    val sessionRepository: SessionRepository
    val forumRepository: ForumRepository
    val awayDigestRepository: AwayDigestRepository
        get() = NoOpAwayDigestRepository
    val checkInRepository: CheckInRepository
    val inboxRepository: InboxRepository
    val userRepository: UserRepository
    val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository
    val settingsRepository: SettingsRepository
    val draftRepository: DraftRepository
    val imageHostRepository: ImageHostRepository
    val seekMailRepository: SeekMailRepository
    val seekMailOtpWatcher: SeekMailOtpWatcher
        get() = NoOpSeekMailOtpWatcher
    val vpsMailAutoIngestService: VpsMailAutoIngestService
        get() = NoOpVpsMailAutoIngestService
    val radarRepository: RadarRepository
    val radarMonitorController: RadarMonitorController
    val contentFilterRepository: ContentFilterRepository
    val notificationDispatcher: PulseNotificationDispatcher
    val appUpdateManager: AppUpdateManager
    val backupManager: BackupManager
    val exchangeRateRepository: ExchangeRateRepository
    val restockRepository: RestockRepository
    val restockMonitorController: RestockMonitorController
    val restockNotificationHelper: RestockNotificationHelper
    val flareSolverrClient: FlareSolverrClient
    val activityRepository: ActivityRepository
    val activityMonitorController: ActivityMonitorController
        get() = NoOpActivityMonitorController
    val sellerTrustRepository: SellerTrustRepository
    val officialTradeRepository: OfficialTradeRepository
        get() = NoOpOfficialTradeRepository
    val fleetRepository: FleetRepository
    val fleetWorkScheduler: FleetWorkScheduler
    val netBenchHistoryStore: NetBenchHistoryStore
    val sshVaultRepository: SshVaultRepository
    val sshSessionManager: com.nodeseek.app.ui.ssh.LiteSshSessionManager
    val overseasIntelRepository: OverseasIntelRepository
    val overseasPromoMonitorController: OverseasPromoMonitorController
        get() = NoOpOverseasPromoMonitorController
    val pollAndStardustRepository: PollAndStardustRepository
        get() = NoOpPollAndStardustRepository
    val luckyDrawRepository: LuckyDrawRepository
        get() = NoOpLuckyDrawRepository
    val communityToolsRepository: CommunityToolsRepository
        get() = NoOpCommunityToolsRepository
    val benchmarkBaselineRepository: BenchmarkBaselineRepository
        get() = NoOpBenchmarkBaselineRepository
    val domainRdapClient: DomainRdapClient
        get() = DomainRdapClient.sharedDefault
    val tlsCertificateInspector: TlsCertificateInspector
        get() = DefaultTlsInspectorHolder.instance
    val tradeMarketReferenceRepository: TradeMarketReferenceRepository
        get() = NoOpTradeMarketReferenceRepository
    val alertForwarder: AlertForwarder
        get() = NoOpAlertForwarder
    val widgetSnapshotRepository: WidgetSnapshotRepository
        get() = NoOpWidgetSnapshotRepository
    val coilOkHttpClient: OkHttpClient
        get() = DefaultCoilOkHttpHolder.instance
    val multiAccountManager: MultiAccountManager
        get() = NoOpMultiAccountManager
}

private object DefaultCoilOkHttpHolder {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }
}

private object DefaultTlsInspectorHolder {
    val instance: TlsCertificateInspector by lazy { JvmTlsCertificateInspector() }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer was not provided in the Compose hierarchy")
}
