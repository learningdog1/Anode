package com.nodeseek.app.widget

import com.nodeseek.app.navigation.ShortcutIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WidgetManifestAndXmlContractTest {

    private val appDir: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        when {
            File(cwd, "src/main/AndroidManifest.xml").isFile -> cwd
            File(cwd, "app/src/main/AndroidManifest.xml").isFile -> File(cwd, "app")
            else -> error("Unable to locate app module directory from ${cwd.absolutePath}")
        }
    }

    private fun parseXml(relativePath: String): Document {
        val file = File(appDir, relativePath)
        assertTrue("Missing XML file: ${file.absolutePath}", file.isFile)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    private fun Element.androidAttr(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
            .ifEmpty { getAttribute("android:$name") }

    private fun elementsByTag(doc: Document, tag: String): List<Element> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    @Test
    fun `AndroidManifest declares shortcuts meta-data and both exported Glance widget receivers`() {
        val manifest = parseXml("src/main/AndroidManifest.xml")
        val activities = elementsByTag(manifest, "activity")
        val mainActivity = activities.firstOrNull { it.androidAttr("name") == ".MainActivity" }
        assertNotNull("MainActivity must be declared in AndroidManifest.xml", mainActivity)
        assertEquals("singleTop", mainActivity!!.androidAttr("launchMode"))

        val activityMeta = mainActivity.getElementsByTagName("meta-data")
        val shortcutsMeta = (0 until activityMeta.length)
            .mapNotNull { activityMeta.item(it) as? Element }
            .firstOrNull { it.androidAttr("name") == "android.app.shortcuts" }
        assertNotNull("MainActivity must declare android.app.shortcuts meta-data", shortcutsMeta)
        assertEquals("@xml/shortcuts", shortcutsMeta!!.androidAttr("resource"))

        val receivers = elementsByTag(manifest, "receiver")
        val serverReceiver = receivers.firstOrNull {
            it.androidAttr("name") == ".widget.ServerStatusGlanceWidgetReceiver"
        }
        val dailyReceiver = receivers.firstOrNull {
            it.androidAttr("name") == ".widget.DailyPulseGlanceWidgetReceiver"
        }
        assertNotNull("ServerStatusGlanceWidgetReceiver missing from manifest", serverReceiver)
        assertNotNull("DailyPulseGlanceWidgetReceiver missing from manifest", dailyReceiver)

        listOf(
            serverReceiver!! to "@xml/widget_server_status_info",
            dailyReceiver!! to "@xml/widget_daily_pulse_info",
        ).forEach { (receiver, expectedProviderResource) ->
            assertEquals("true", receiver.androidAttr("exported"))
            val actions = (0 until receiver.getElementsByTagName("action").length)
                .mapNotNull { receiver.getElementsByTagName("action").item(it) as? Element }
                .map { it.androidAttr("name") }
            assertTrue(
                "Receiver ${receiver.androidAttr("name")} must listen for APPWIDGET_UPDATE",
                "android.appwidget.action.APPWIDGET_UPDATE" in actions,
            )
            val providerMeta = (0 until receiver.getElementsByTagName("meta-data").length)
                .mapNotNull { receiver.getElementsByTagName("meta-data").item(it) as? Element }
                .firstOrNull { it.androidAttr("name") == "android.appwidget.provider" }
            assertNotNull(providerMeta)
            assertEquals(expectedProviderResource, providerMeta!!.androidAttr("resource"))
        }
    }

    @Test
    fun `shortcuts xml declares check-in, lite SSH, and copy OTP shortcuts targeting MainActivity`() {
        val shortcutsDoc = parseXml("src/main/res/xml/shortcuts.xml")
        val shortcuts = elementsByTag(shortcutsDoc, "shortcut")
        assertEquals(3, shortcuts.size)

        val byId = shortcuts.associateBy { it.androidAttr("shortcutId") }
        assertTrue(byId.containsKey("shortcut_check_in"))
        assertTrue(byId.containsKey("shortcut_lite_ssh"))
        assertTrue(byId.containsKey("shortcut_copy_otp"))

        val expectedActions = mapOf(
            "shortcut_check_in" to ShortcutIntentParser.ACTION_SHORTCUT_CHECK_IN,
            "shortcut_lite_ssh" to ShortcutIntentParser.ACTION_SHORTCUT_OPEN_SSH,
            "shortcut_copy_otp" to ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP,
        )
        expectedActions.forEach { (id, expectedAction) ->
            val shortcut = byId.getValue(id)
            assertEquals("true", shortcut.androidAttr("enabled"))
            val intentEl = shortcut.getElementsByTagName("intent").item(0) as? Element
            assertNotNull("Shortcut $id must have an <intent>", intentEl)
            assertEquals(expectedAction, intentEl!!.androidAttr("action"))
            assertEquals("com.nodeseek.app", intentEl.androidAttr("targetPackage"))
            assertEquals("com.nodeseek.app.MainActivity", intentEl.androidAttr("targetClass"))
        }
    }

    @Test
    fun `widget appwidget-provider XMLs, strings, and ProGuard keep rules are properly configured`() {
        listOf(
            "src/main/res/xml/widget_server_status_info.xml",
            "src/main/res/xml/widget_daily_pulse_info.xml",
        ).forEach { path ->
            val doc = parseXml(path)
            val provider = doc.documentElement
            assertEquals("appwidget-provider", provider.tagName)
            assertTrue(provider.androidAttr("minWidth").endsWith("dp"))
            assertTrue(provider.androidAttr("minHeight").endsWith("dp"))
            assertEquals("home_screen", provider.androidAttr("widgetCategory"))
            assertTrue((provider.androidAttr("updatePeriodMillis").toLongOrNull() ?: 0L) >= 1_800_000L)
        }

        val stringsDoc = parseXml("src/main/res/values/strings.xml")
        val stringNames = elementsByTag(stringsDoc, "string").map { it.getAttribute("name") }.toSet()
        listOf(
            "shortcut_check_in_short",
            "shortcut_check_in_long",
            "shortcut_lite_ssh_short",
            "shortcut_lite_ssh_long",
            "shortcut_copy_otp_short",
            "shortcut_copy_otp_long",
            "widget_server_status_name",
            "widget_server_status_description",
            "widget_daily_pulse_name",
            "widget_daily_pulse_description",
        ).forEach { key ->
            assertTrue("Missing string resource: $key", key in stringNames)
        }

        val proguardFile = File(appDir, "proguard-rules.pro")
        assertTrue(proguardFile.isFile)
        val proguardText = proguardFile.readText()
        assertTrue(
            "proguard-rules.pro must keep androidx.glance.appwidget.action.ActionCallback",
            proguardText.contains("androidx.glance.appwidget.action.ActionCallback"),
        )
    }
}
