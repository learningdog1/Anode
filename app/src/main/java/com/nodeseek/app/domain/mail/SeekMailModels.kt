package com.nodeseek.app.domain.mail

import kotlinx.serialization.Serializable

@Serializable
data class SeekMailbox(
    val id: Long,
    val address: String,
    val alias: String? = null,
    val domainId: Long = 0L,
    val ownerId: Long = 0L,
    val isReserved: Boolean = false,
    val doNotDisturb: Boolean = false,
    val archiveCount: Int = 0,
    val archiveLimit: Int = 0,
    val clientAccessAdmin: Boolean = false,
    val clientAccessUser: Boolean = false,
    val sendDisabled: Boolean = false,
    val forwardTo: String = "",
    val forwardKeepCopy: Boolean = false,
    val forwardDeleteDays: Int = 0,
    val status: Int = 1,
    val sortOrder: Int = 0,
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val createdAt: String = "",
) {
    val domain: String
        get() = address.substringAfter('@', "")

    val localPart: String
        get() = address.substringBefore('@', address)
}

@Serializable
data class SeekMailTag(
    val id: Long,
    val name: String,
    val color: String = "",
    val isSystem: Boolean = false,
    val createdAt: String = "",
)

@Serializable
data class SeekMailAuthTelemetry(
    val spf: String = "",
    val dkim: String = "",
    val dmarc: String = "",
    val fromMismatch: Boolean = false,
    val authTotalScore: Int = 0,
    val finalScore: Int = 0,
    val decision: String = "",
    val aiUpgrade: Boolean = false,
)

@Serializable
data class SeekMailSummary(
    val id: Long,
    val mailboxId: Long,
    val fromAddress: String,
    val fromName: String,
    val toAddress: String,
    val subject: String,
    val snippet: String,
    val verificationCode: String? = null,
    val messageId: String = "",
    val threadId: String = "",
    val isRead: Boolean = false,
    val isFlagged: Boolean = false,
    val hasAttachments: Boolean = false,
    val isPinned: Boolean = false,
    val importance: String = "normal",
    val status: String = "active",
    val receivedAt: String = "",
    val createdAt: String = "",
    val sizeBytes: Long = 0L,
    val spamScore: Int = 0,
    val authScore: Int = 0,
    val tags: List<SeekMailTag> = emptyList(),
)

@Serializable
data class SeekMailDetail(
    val summary: SeekMailSummary,
    val bodyText: String = "",
    val bodyHtml: String = "",
    val sanitizedBodyHtml: String = "",
    val hasRemoteImages: Boolean = false,
    val authTelemetry: SeekMailAuthTelemetry? = null,
    val rawScoreDetails: String = "",
)

@Serializable
data class SeekMailAttachment(
    val id: String,
    val mailId: Long,
    val fileName: String,
    val contentType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
)

@Serializable
data class SeekMailContact(
    val id: Long,
    val name: String,
    val email: String,
    val source: String = "",
    val lastContactAt: String = "",
    val createdAt: String = "",
)

@Serializable
data class SeekMailPage(
    val items: List<SeekMailSummary>,
    val limit: Int,
    val offset: Int,
    val total: Int,
    val hasMore: Boolean,
)

@Serializable
data class SeekMailUnreadPage(
    val items: List<SeekMailSummary>,
    val limit: Int,
    val hasMore: Boolean,
    val nextCursor: String = "",
)

@Serializable
data class SeekMailRateLimit(
    val limit: Int,
    val remaining: Int,
    val resetEpochSeconds: Long,
)

@Serializable
data class SeekMailConnectionStatus(
    val mailboxes: List<SeekMailbox>,
    val unreadSampleCount: Int = 0,
    val hasMoreUnread: Boolean = false,
    val rateLimit: SeekMailRateLimit? = null,
)

@Serializable
enum class SeekMailErrorKind {
    MISSING_API_KEY,
    INVALID_API_KEY_FORMAT,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    ADDRESS_UNAVAILABLE,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    UNKNOWN,
}

class SeekMailException(
    val kind: SeekMailErrorKind,
    override val message: String,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
