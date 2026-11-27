package com.nodeseek.app.navigation

import com.nodeseek.app.data.fleet.FleetAlertNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutIntentParserTest {

    @Test
    fun `parseAction maps all 3 static App Shortcuts correctly`() {
        assertEquals(
            ShortcutCommand.PerformCheckIn,
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_SHORTCUT_CHECK_IN),
        )
        assertEquals(
            ShortcutCommand.Navigate(LiteSshRoute),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_SHORTCUT_OPEN_SSH),
        )
        assertEquals(
            ShortcutCommand.CopyLatestOtp,
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP),
        )
    }

    @Test
    fun `parseAction maps widget navigation actions and FleetAlertNotifier routes`() {
        assertEquals(
            ShortcutCommand.Navigate(CheckInBoardRoute),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_WIDGET_OPEN_CHECKIN_BOARD),
        )
        assertEquals(
            ShortcutCommand.Navigate(PulseRadarRoute(initialTab = 0)),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_WIDGET_OPEN_RADAR),
        )
        assertEquals(
            ShortcutCommand.Navigate(SeekMailRoute(initialMailboxId = 0L, initialMailId = 0L)),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_WIDGET_OPEN_SEEK_MAIL),
        )
        assertEquals(
            ShortcutCommand.Navigate(MyServersRoute(initialTab = 0, highlightAssetId = "")),
            ShortcutIntentParser.parseAction(
                action = FleetAlertNotifier.ACTION_OPEN_FLEET,
                fleetTab = FleetAlertNotifier.EXTRA_TAB_PROBES,
            ),
        )
        assertEquals(
            ShortcutCommand.Navigate(MyServersRoute(initialTab = 1, highlightAssetId = "asset-7")),
            ShortcutIntentParser.parseAction(
                action = FleetAlertNotifier.ACTION_OPEN_FLEET,
                fleetTab = FleetAlertNotifier.EXTRA_TAB_ASSETS,
                assetId = "  asset-7 ",
            ),
        )
        assertEquals(
            ShortcutCommand.Navigate(DomainAssetsRoute(highlightAssetId = "dom-9")),
            ShortcutIntentParser.parseAction(
                action = FleetAlertNotifier.ACTION_OPEN_FLEET,
                fleetTab = FleetAlertNotifier.EXTRA_TAB_DOMAINS,
                assetId = "  dom-9 ",
            ),
        )
        assertEquals(
            ShortcutCommand.Navigate(DomainAssetsRoute(highlightAssetId = "")),
            ShortcutIntentParser.parseAction(
                action = FleetAlertNotifier.ACTION_OPEN_FLEET,
                fleetTab = FleetAlertNotifier.EXTRA_TAB_DOMAINS,
            ),
        )
    }

    @Test
    fun `parseAction and isKnownAction reject null, blank, and unrelated actions`() {
        assertNull(ShortcutIntentParser.parseAction(null))
        assertNull(ShortcutIntentParser.parseAction(""))
        assertNull(ShortcutIntentParser.parseAction("android.intent.action.MAIN"))
        assertNull(ShortcutIntentParser.parseAction("com.nodeseek.app.action.UNKNOWN"))

        assertFalse(ShortcutIntentParser.isKnownAction(null))
        assertFalse(ShortcutIntentParser.isKnownAction("android.intent.action.VIEW"))
        assertTrue(ShortcutIntentParser.isKnownAction(ShortcutIntentParser.ACTION_SHORTCUT_CHECK_IN))
        assertTrue(ShortcutIntentParser.isKnownAction(ShortcutIntentParser.ACTION_SHORTCUT_OPEN_SSH))
        assertTrue(ShortcutIntentParser.isKnownAction(ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP))
    }
}
