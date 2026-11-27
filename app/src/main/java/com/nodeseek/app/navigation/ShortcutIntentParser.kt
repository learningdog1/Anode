package com.nodeseek.app.navigation

import android.content.Intent
import com.nodeseek.app.data.fleet.FleetAlertNotifier

sealed interface ShortcutCommand {
    data object PerformCheckIn : ShortcutCommand
    data object CopyLatestOtp : ShortcutCommand
    data class Navigate(val destination: AppDestination) : ShortcutCommand
}

/**
 * Pure-Kotlin parser mapping App Shortcut and Glance AppWidget intent actions to
 * [ShortcutCommand] values and clearing consumed actions to avoid replay on configuration changes.
 */
object ShortcutIntentParser {
    const val ACTION_SHORTCUT_CHECK_IN = "com.nodeseek.app.action.SHORTCUT_CHECK_IN"
    const val ACTION_SHORTCUT_OPEN_SSH = "com.nodeseek.app.action.SHORTCUT_OPEN_SSH"
    const val ACTION_SHORTCUT_COPY_OTP = "com.nodeseek.app.action.SHORTCUT_COPY_OTP"

    const val ACTION_WIDGET_OPEN_CHECKIN_BOARD = "com.nodeseek.app.action.WIDGET_OPEN_CHECKIN_BOARD"
    const val ACTION_WIDGET_OPEN_RADAR = "com.nodeseek.app.action.WIDGET_OPEN_RADAR"
    const val ACTION_WIDGET_OPEN_SEEK_MAIL = "com.nodeseek.app.action.WIDGET_OPEN_SEEK_MAIL"

    fun parseAction(
        action: String?,
        fleetTab: String? = null,
        assetId: String? = null,
    ): ShortcutCommand? {
        return when (action?.trim()) {
            ACTION_SHORTCUT_CHECK_IN -> ShortcutCommand.PerformCheckIn
            ACTION_SHORTCUT_COPY_OTP -> ShortcutCommand.CopyLatestOtp
            ACTION_SHORTCUT_OPEN_SSH -> ShortcutCommand.Navigate(LiteSshRoute)
            ACTION_WIDGET_OPEN_CHECKIN_BOARD -> ShortcutCommand.Navigate(CheckInBoardRoute)
            ACTION_WIDGET_OPEN_RADAR -> ShortcutCommand.Navigate(PulseRadarRoute(initialTab = 0))
            ACTION_WIDGET_OPEN_SEEK_MAIL -> ShortcutCommand.Navigate(
                SeekMailRoute(initialMailboxId = 0L, initialMailId = 0L),
            )
            FleetAlertNotifier.ACTION_OPEN_FLEET -> {
                val cleanAssetId = assetId?.trim().orEmpty()
                if (fleetTab == FleetAlertNotifier.EXTRA_TAB_DOMAINS) {
                    ShortcutCommand.Navigate(
                        DomainAssetsRoute(
                            highlightAssetId = cleanAssetId,
                        ),
                    )
                } else {
                    val initialTab = if (
                        fleetTab == FleetAlertNotifier.EXTRA_TAB_ASSETS || cleanAssetId.isNotEmpty()
                    ) {
                        1
                    } else {
                        0
                    }
                    ShortcutCommand.Navigate(
                        MyServersRoute(
                            initialTab = initialTab,
                            highlightAssetId = cleanAssetId,
                        ),
                    )
                }
            }
            else -> null
        }
    }

    fun isKnownAction(action: String?): Boolean = parseAction(action) != null

    fun consumeCommand(intent: Intent?): ShortcutCommand? {
        if (intent == null) return null
        val command = parseAction(
            action = intent.action,
            fleetTab = intent.getStringExtra(FleetAlertNotifier.EXTRA_FLEET_TAB),
            assetId = intent.getStringExtra(FleetAlertNotifier.EXTRA_ASSET_ID),
        ) ?: return null

        intent.action = null
        intent.removeExtra(FleetAlertNotifier.EXTRA_FLEET_TAB)
        intent.removeExtra(FleetAlertNotifier.EXTRA_ASSET_ID)
        return command
    }
}
