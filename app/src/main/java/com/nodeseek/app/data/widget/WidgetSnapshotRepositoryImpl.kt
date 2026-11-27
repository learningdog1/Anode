package com.nodeseek.app.data.widget

import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.mail.LatestSeekMailOtp
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.mail.VerificationCodeExtractor
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.domain.widget.DailyPulseWidgetSnapshot
import com.nodeseek.app.domain.widget.ServerStatusWidgetSnapshot
import com.nodeseek.app.domain.widget.WidgetSnapshotAggregator
import com.nodeseek.app.domain.widget.WidgetSnapshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.LocalDate

/**
 * Concrete [WidgetSnapshotRepository] ensuring persisted Fleet state is loaded on cold starts
 * and combining Account, Check-In, Radar, and Seek Mail offline/fresh OTP data.
 */
class WidgetSnapshotRepositoryImpl(
    private val fleetRepository: FleetRepository,
    private val sessionRepository: SessionRepository,
    private val checkInRepository: CheckInRepository,
    private val settingsRepository: SettingsRepository,
    private val radarRepository: RadarRepository,
    private val seekMailRepository: SeekMailRepository,
    private val todayProvider: () -> LocalDate = {
        VpsValuationEngine.todayInShanghai(Clock.system(VALUATION_ZONE_ID))
    },
) : WidgetSnapshotRepository {

    override suspend fun loadServerStatusSnapshot(): ServerStatusWidgetSnapshot {
        fleetRepository.loadPersistedState()
        return WidgetSnapshotAggregator.buildServerStatus(
            assets = fleetRepository.assetsFlow.value,
            snapshots = fleetRepository.snapshotsFlow.value,
            lastSyncEpochMillis = fleetRepository.lastSyncEpochMillisFlow.value,
            today = todayProvider(),
        )
    }

    override suspend fun loadDailyPulseSnapshot(): DailyPulseWidgetSnapshot {
        val session = sessionRepository.sessionState.value
        val settings = settingsRepository.awaitLoadedSettings()
        val checkInStatus = checkInRepository.checkInStatus.value

        val radarSettings = try {
            radarRepository.pollingSettingsFlow.first()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            RadarPollingSettings()
        }

        val unreadCount = try {
            radarRepository.unreadAlertCountFlow.first()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            0
        }

        val latestAlert = try {
            val records = radarRepository.alertRecordsFlow.first()
            records.firstOrNull { !it.isRead } ?: records.firstOrNull()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }

        val latestOtp = try {
            if (settings.seekMailApiKey.trim().isEmpty()) {
                null
            } else {
                seekMailRepository.getCachedLatestOtp()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }

        return WidgetSnapshotAggregator.buildDailyPulse(
            session = session,
            settings = settings,
            checkInStatus = checkInStatus,
            radarPollingSettings = radarSettings,
            unreadAlertCount = unreadCount,
            latestAlert = latestAlert,
            latestOtp = latestOtp,
        )
    }

    override suspend fun fetchOrCachedLatestOtp(timeoutMs: Long): LatestSeekMailOtp? {
        val apiKey = settingsRepository.awaitLoadedSettings().seekMailApiKey.trim()
        if (apiKey.isEmpty()) return null

        val freshPage = try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                seekMailRepository.getMails(
                    mailboxId = null,
                    search = null,
                    tagId = null,
                    limit = 20,
                    offset = 0,
                    forceRefresh = true,
                ).getOrNull()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }

        if (freshPage != null) {
            val archiveStore = seekMailRepository.localArchiveStore.value
            val freshOtp = freshPage.items
                .asSequence()
                .filter { it.id > 0L && !archiveStore.isDeleted(it.id) }
                .sortedWith(
                    compareByDescending<com.nodeseek.app.domain.mail.SeekMailSummary> {
                        it.receivedAt.ifBlank { it.createdAt }
                    }.thenByDescending { it.id },
                )
                .firstNotNullOfOrNull { summary ->
                    val code = summary.verificationCode?.trim()?.takeIf { it.isNotEmpty() }
                        ?: VerificationCodeExtractor.resolveCode(
                            serverCode = null,
                            subject = summary.subject,
                            bodyText = summary.snippet,
                        )
                    if (code.isNullOrBlank()) {
                        null
                    } else {
                        LatestSeekMailOtp(
                            mailId = summary.id,
                            code = code,
                            sender = summary.fromName.ifBlank { summary.fromAddress },
                            subject = summary.subject,
                            receivedAt = summary.receivedAt.ifBlank { summary.createdAt },
                        )
                    }
                }
            if (freshOtp != null) {
                return freshOtp
            }
        }

        return try {
            seekMailRepository.getCachedLatestOtp()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
    }
}
