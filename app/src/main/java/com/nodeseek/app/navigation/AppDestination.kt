package com.nodeseek.app.navigation

import kotlinx.serialization.Serializable

sealed interface AppDestination

@Serializable
data object ExploreRoute : AppDestination

@Serializable
data object BoardsRoute : AppDestination

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
data class DealsHubRoute(
    val initialTab: Int = 0,
) : AppDestination

@Serializable
data object OverseasIntelRoute : AppDestination

@Serializable
data class MyServersRoute(
    val initialTab: Int = 0,
    val importName: String = "",
    val importPrice: String = "",
    val importUrl: String = "",
) : AppDestination

@Serializable
data class MobileNetBenchRoute(
    val initialTarget: String = "",
) : AppDestination

@Serializable
data object LiteSshRoute : AppDestination
