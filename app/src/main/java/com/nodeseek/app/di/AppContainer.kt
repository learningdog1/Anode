package com.nodeseek.app.di

import androidx.compose.runtime.staticCompositionLocalOf
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.deals.DealsRepository
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

interface AppContainer {
    val sessionRepository: SessionRepository
    val forumRepository: ForumRepository
    val checkInRepository: CheckInRepository
    val inboxRepository: InboxRepository
    val userRepository: UserRepository
    val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository
    val settingsRepository: SettingsRepository
    val draftRepository: DraftRepository
    val imageHostRepository: ImageHostRepository
    val seekMailRepository: SeekMailRepository
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
    val dealsRepository: DealsRepository
    val sellerTrustRepository: SellerTrustRepository
    val fleetRepository: FleetRepository
    val fleetWorkScheduler: FleetWorkScheduler
    val netBenchHistoryStore: NetBenchHistoryStore
    val sshVaultRepository: SshVaultRepository
    val overseasIntelRepository: OverseasIntelRepository
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer was not provided in the Compose hierarchy")
}
