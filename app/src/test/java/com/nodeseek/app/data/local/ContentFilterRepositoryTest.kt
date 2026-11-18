package com.nodeseek.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.radar.KeywordMatchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContentFilterRepositoryTest {

    @Test
    fun `repository persists rules, toggles, muted boards, and survives DataStore reload`() = runBlocking {
        val tempDir = Files.createTempDirectory("content_filter_repo_test").toFile()
        val dsFile = File(tempDir, "content_filter.preferences_pb")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) { dsFile }
            val writer = ContentFilterRepositoryImpl(dataStore = dataStore, externalScope = writerScope)

            assertTrue(writer.config.value.isEnabled)
            assertTrue(writer.config.value.rules.isEmpty())

            val rule1 = ContentBlockRule(
                id = "r1",
                pattern = "  求购  ",
                matchMode = KeywordMatchMode.CONTAINS,
                categorySlug = " Trade ",
                note = " 屏蔽求购 ",
                createdAtEpochMillis = 1000L,
            )
            val rule2 = ContentBlockRule(
                id = "r2",
                pattern = """\baff\b""",
                matchMode = KeywordMatchMode.REGEX,
                categorySlug = "",
                createdAtEpochMillis = 2000L,
            )

            writer.upsertRule(rule1)
            writer.upsertRule(rule2)
            writer.toggleMutedCategory("Promotion")
            writer.toggleMutedCategory("") // ignored: cannot mute "all"
            writer.setRuleEnabled("r1", false)
            writer.setEnabled(false)

            val current = writer.config.value
            assertFalse(current.isEnabled)
            assertEquals(setOf("promotion"), current.mutedCategorySlugs)
            assertEquals(2, current.rules.size)
            assertEquals("求购", current.rules[0].pattern)
            assertEquals("trade", current.rules[0].categorySlug)
            assertEquals("屏蔽求购", current.rules[0].note)
            assertFalse(current.rules[0].isEnabled)

            // Reload into a brand-new repository instance backed by same DataStore file
            val reader = ContentFilterRepositoryImpl(dataStore = dataStore, externalScope = readerScope)
            val reloaded = withTimeout(5_000L) {
                reader.config.first { it.rules.size == 2 }
            }
            assertFalse(reloaded.isEnabled)
            assertEquals(setOf("promotion"), reloaded.mutedCategorySlugs)
            assertEquals("r2", reloaded.rules[1].id)

            // Update existing rule by id and remove another
            reader.upsertRule(rule1.copy(pattern = "高价收", isEnabled = true))
            reader.removeRule("r2")
            reader.toggleMutedCategory("promotion") // toggles off

            assertEquals(1, reader.config.value.rules.size)
            assertEquals("高价收", reader.config.value.rules[0].pattern)
            assertTrue(reader.config.value.rules[0].isEnabled)
            assertTrue(reader.config.value.mutedCategorySlugs.isEmpty())
        } finally {
            writerScope.cancel()
            readerScope.cancel()
            dsScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `replaceConfig and mergeConfig handle deduplication, id collisions, and setMutedCategories`() = runBlocking {
        val tempDir = Files.createTempDirectory("content_filter_merge_test").toFile()
        val dsFile = File(tempDir, "content_filter.preferences_pb")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) { dsFile }
            val repo = ContentFilterRepositoryImpl(dataStore = dataStore, externalScope = repoScope)

            val initial = ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("meaningless"),
                rules = listOf(
                    ContentBlockRule(
                        id = "rule_1",
                        pattern = "求购",
                        matchMode = KeywordMatchMode.CONTAINS,
                        categorySlug = "",
                        createdAtEpochMillis = 100L,
                    ),
                ),
            )
            repo.replaceConfig(initial)

            // Merge incoming backup containing:
            // - duplicate of "求购" (different case / id) -> should dedupe
            // - distinct rule sharing same id "rule_1" -> should get new unique id without clobbering
            // - new muted board "promotion" and blank "" -> blank filtered out
            val incoming = ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("Promotion", "  ", "meaningless"),
                rules = listOf(
                    ContentBlockRule(
                        id = "other_id",
                        pattern = "求购",
                        matchMode = KeywordMatchMode.CONTAINS,
                        categorySlug = "",
                        createdAtEpochMillis = 200L,
                    ),
                    ContentBlockRule(
                        id = "rule_1",
                        pattern = """\baff\b""",
                        matchMode = KeywordMatchMode.REGEX,
                        categorySlug = "",
                        createdAtEpochMillis = 300L,
                    ),
                ),
            )
            repo.mergeConfig(incoming)

            val merged = repo.config.value
            assertEquals(setOf("meaningless", "promotion"), merged.mutedCategorySlugs)
            assertEquals(2, merged.rules.size)
            assertEquals("求购", merged.rules[0].pattern)
            assertEquals("""\baff\b""", merged.rules[1].pattern)
            assertTrue(merged.rules[1].id != "rule_1")

            // Merging the same incoming payload again is idempotent
            repo.mergeConfig(incoming)
            assertEquals(2, repo.config.value.rules.size)

            repo.setMutedCategories(setOf("  ", "EXPOSE", "carpool"))
            assertEquals(setOf("expose", "carpool"), repo.config.value.mutedCategorySlugs)
        } finally {
            repoScope.cancel()
            dsScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt JSON in DataStore recovers to default config and serialization round-trips`() = runBlocking {
        val tempDir = Files.createTempDirectory("content_filter_corrupt_test").toFile()
        val dsFile = File(tempDir, "content_filter.preferences_pb")
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) { dsFile }
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("content_filter_config_json")] = "{not_valid_json"
            }

            val repo = ContentFilterRepositoryImpl(dataStore = dataStore, externalScope = repoScope)
            val fallback = repo.config.value
            assertTrue(fallback.isEnabled)
            assertTrue(fallback.rules.isEmpty())
            assertTrue(fallback.mutedCategorySlugs.isEmpty())

            // Verify kotlinx.serialization round-trip of models including KeywordMatchMode
            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
            val sample = ContentFilterConfig(
                isEnabled = false,
                mutedCategorySlugs = setOf("promotion"),
                rules = listOf(
                    ContentBlockRule(
                        id = "id_1",
                        pattern = "test",
                        matchMode = KeywordMatchMode.REGEX,
                        categorySlug = "trade",
                        isEnabled = true,
                        note = "note",
                        createdAtEpochMillis = 42L,
                    ),
                ),
            )
            val encoded = json.encodeToString(sample)
            val decoded = json.decodeFromString<ContentFilterConfig>(encoded)
            assertEquals(sample, decoded)
        } finally {
            repoScope.cancel()
            dsScope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
