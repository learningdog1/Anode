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
