package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.DirectMessage
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InboxRepository {
    val unreadTotalCount: StateFlow<Int>
    val replyNotifications: StateFlow<List<NotificationItem>>
    val mentionNotifications: StateFlow<List<NotificationItem>>
    val messageThreads: StateFlow<List<DirectMessageSummary>>
    val inAppPopupEvents: Flow<InAppMessagePopup>
    val unreadIncreaseEvents: Flow<Int>

    suspend fun refreshUnreadCount(): Result<Int>
    suspend fun syncForegroundInbox(forceListSync: Boolean = false): Result<Unit>
    suspend fun getNotifications(kind: NotificationKind): Result<List<NotificationItem>>
    suspend fun markNotificationsRead(kind: NotificationKind, ids: List<Long>? = null): Result<Unit>

    suspend fun getMessageThreads(): Result<List<DirectMessageSummary>>
    suspend fun getConversation(peerUid: Long): Result<List<DirectMessage>>
    suspend fun markThreadRead(peerUid: Long): Result<Unit>
    suspend fun markMessagesRead(messageIds: List<Long>? = null): Result<Unit>
    suspend fun sendDirectMessage(
        receiverUid: Long,
        content: String,
        isMarkdown: Boolean = false,
    ): Result<Unit>
}
