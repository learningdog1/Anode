package com.nodeseek.app.navigation

import kotlinx.serialization.Serializable
import java.util.UUID

data class ComposerPrefillPayload(
    val title: String,
    val markdown: String,
)

/**
 * Process-scoped, bounded in-memory handoff store for large topic composer prefills (e.g. multi-tab
 * SSH ANSI benchmark outputs up to 180KB+) so `navigation-compose` route URIs and `Bundle` arguments
 * stay tiny and never risk `TransactionTooLargeException`.
 */
object ComposerPrefillStore {
    private const val MAX_ENTRIES = 8

    private val lock = Any()
    private val entries = LinkedHashMap<String, ComposerPrefillPayload>(MAX_ENTRIES + 1)

    fun put(
        title: String,
        markdown: String,
    ): String {
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            entries[token] = ComposerPrefillPayload(
                title = title,
                markdown = markdown,
            )
            while (entries.size > MAX_ENTRIES) {
                val oldestKey = entries.entries.firstOrNull()?.key ?: break
                entries.remove(oldestKey)
            }
        }
        return token
    }

    fun get(token: String): ComposerPrefillPayload? {
        if (token.isBlank()) return null
        return synchronized(lock) { entries[token] }
    }

    fun remove(token: String) {
        if (token.isBlank()) return
        synchronized(lock) {
            entries.remove(token)
        }
    }

    internal fun clearForTests() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

sealed interface AppDestination

@Serializable
data object ExploreRoute : AppDestination

@Serializable
data object PulseHubRoute : AppDestination

@Serializable
data class TopicDetailRoute(
    val topicId: Long,
    val initialFloor: Int = 0,
    val titleHint: String = "",
) : AppDestination

@Serializable
data class ComposerRoute(
    val editTopicId: Long? = null,
    val initialCategorySlug: String = "daily",
    val initialTitle: String = "",
    val initialMarkdown: String = "",
    val prefillId: String = "",
) : AppDestination

@Serializable
data class InboxRoute(
    val initialTab: Int = 0,
) : AppDestination

@Serializable
data class DirectMessageRoute(
    val peerUid: Long,
    val peerName: String,
) : AppDestination

@Serializable
data class UserProfileRoute(
    val uid: Long,
    val usernameHint: String = "",
) : AppDestination

@Serializable
data object CheckInBoardRoute : AppDestination

@Serializable
data object SettingsRoute : AppDestination

@Serializable
data class PulseRadarRoute(
    val initialTab: Int = 0,
) : AppDestination

@Serializable
data class SeekMailRoute(
    val initialMailboxId: Long = 0L,
    val initialMailId: Long = 0L,
) : AppDestination

@Serializable
data object SearchRoute : AppDestination

@Serializable
data class HubCollectionRoute(
    val initialTab: Int = 0,
) : AppDestination

@Serializable
data class RestockMonitorRoute(
    val initialUrl: String = "",
    val initialTitle: String = "",
    val initialTab: Int = 0,
) : AppDestination

@Serializable
data object ActivityRoute : AppDestination

@Serializable
data object OverseasIntelRoute : AppDestination

@Serializable
data class OverseasIntelDetailRoute(
    val offerId: String,
    val initialTitle: String = "",
    val initialSourceUrl: String = "",
) : AppDestination

@Serializable
data class MyServersRoute(
    val initialTab: Int = 0,
    val importName: String = "",
    val importPrice: String = "",
    val importUrl: String = "",
    val highlightAssetId: String = "",
) : AppDestination

@Serializable
data class MobileNetBenchRoute(
    val initialTarget: String = "",
) : AppDestination

@Serializable
data object LiteSshRoute : AppDestination
