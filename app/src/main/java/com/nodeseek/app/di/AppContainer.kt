package com.nodeseek.app.di

import androidx.compose.runtime.staticCompositionLocalOf
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
    val notificationDispatcher: PulseNotificationDispatcher
    val appUpdateManager: AppUpdateManager
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer was not provided in the Compose hierarchy")
}
