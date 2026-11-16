@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.domain.mail.VerificationCodeExtractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val SeekMailJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
internal data class MailboxDto(
    val id: Long,
    val address: String = "",
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
)

@Serializable
internal data class TagDto(
    val id: Long,
    val userId: Long = 0L,
    val orgId: Long = 0L,
    val name: String = "",
    val color: String = "",
    val isSystem: Boolean = false,
    val createdAt: String = "",
)

@Serializable
internal data class MailDto(
    val id: Long,
    val mailboxId: Long = 0L,
    val orgId: Long = 0L,
    val originalOwnerId: Long = 0L,
    val fromAddress: String = "",
    val fromName: String = "",
    val toAddress: String = "",
    val subject: String = "",
    val bodyText: String = "",
    val bodyHtml: String = "",
    val verificationCode: String? = null,
    val messageId: String = "",
    val threadId: String = "",
    val isRead: Boolean = false,
    val isFlagged: Boolean = false,
    val hasAttachments: Boolean = false,
    val isPinned: Boolean = false,
    val importance: String = "normal",
    val status: String = "active",
    val pendingAt: String? = null,
    val receivedAt: String = "",
    val createdAt: String = "",
    val size: Long = 0L,
    val spamScore: Int = 0,
    val authScore: Int = 0,
    val ruleScore: Int = 0,
    val scoreDetails: String = "",
    val tags: List<TagDto>? = null,
)

@Serializable
internal data class ContactDto(
    val id: Long,
    val userId: Long = 0L,
    val orgId: Long = 0L,
    val name: String = "",
    val email: String = "",
    val source: String = "",
    val lastContactAt: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
internal data class AttachmentDto(
    val id: JsonElement? = null,
    val attachmentId: JsonElement? = null,
    val mailId: Long? = null,
    val filename: String? = null,
    val fileName: String? = null,
    val name: String? = null,
    val contentType: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val sizeBytes: Long? = null,
) {
    fun resolveId(): String = extractJsonId(id) ?: extractJsonId(attachmentId) ?: ""

    fun resolveFileName(): String =
        listOf(filename, fileName, name)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?: "attachment"

    fun resolveContentType(): String =
        listOf(contentType, mimeType)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?: "application/octet-stream"

    fun resolveSizeBytes(): Long = (size ?: sizeBytes ?: 0L).coerceAtLeast(0L)

    private fun extractJsonId(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        return (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}

@Serializable
internal data class MailboxesResponseDto(
    val data: List<MailboxDto>? = null,
)

@Serializable
internal data class MailboxResponseDto(
    val data: MailboxDto? = null,
)

@Serializable
internal data class CheckMailboxAvailableDataDto(
    val available: Boolean = false,
)

@Serializable
internal data class CheckMailboxAvailableResponseDto(
    val data: CheckMailboxAvailableDataDto? = null,
)

@Serializable
internal data class CreateMailboxRequestDto(
    val address: String,
)

@Serializable
internal data class MailPaginationDto(
    val limit: Int = 20,
    val offset: Int = 0,
    val total: Int = 0,
)

@Serializable
internal data class MailListResponseDto(
    val data: List<MailDto>? = null,
    val pagination: MailPaginationDto? = null,
)

@Serializable
internal data class UnreadMailPageMetaDto(
    val hasMore: Boolean = false,
    val limit: Int = 50,
    val nextCursor: String = "",
)

@Serializable
internal data class UnreadMailListResponseDto(
    val data: List<MailDto>? = null,
    val page: UnreadMailPageMetaDto? = null,
)

@Serializable
internal data class MailDetailDataDto(
    val mail: MailDto? = null,
    val tags: List<TagDto>? = null,
)

@Serializable
internal data class MailDetailResponseDto(
    val data: MailDetailDataDto? = null,
)

@Serializable
internal data class AttachmentsResponseDto(
    val data: List<AttachmentDto>? = null,
)

@Serializable
internal data class TagsResponseDto(
    val data: List<TagDto>? = null,
)

@Serializable
internal data class ContactsResponseDto(
    val data: List<ContactDto>? = null,
)

@Serializable
internal data class SeekMailErrorEnvelopeDto(
    val error: JsonElement? = null,
    val code: String? = null,
    val message: String? = null,
)

internal data class ParsedSeekMailError(
    val code: String? = null,
    val message: String? = null,
)

internal object SeekMailErrorParser {
    fun parse(rawBody: String, json: Json = SeekMailJson): ParsedSeekMailError {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ParsedSeekMailError()

        val envelope = runCatching {
            json.decodeFromString(SeekMailErrorEnvelopeDto.serializer(), trimmed)
        }.getOrNull() ?: return ParsedSeekMailError()

        var code: String? = envelope.code?.trim()?.takeIf { it.isNotEmpty() }
        var message: String? = envelope.message?.trim()?.takeIf { it.isNotEmpty() }

        when (val err = envelope.error) {
            is JsonObject -> {
                val objCode = (err["code"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val objMsg = (err["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                if (objCode != null) code = objCode
                if (objMsg != null) message = objMsg
            }
            is JsonPrimitive -> {
                if (err !is JsonNull) {
                    val strMsg = err.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    if (strMsg != null) message = strMsg
                }
            }
            else -> Unit
        }

        return ParsedSeekMailError(code = code, message = message)
    }
}

private val SNIPPET_WHITESPACE_REGEX = Regex("""\s+""")
private const val MAX_SNIPPET_CHARS = 160

internal fun MailboxDto.toDomain(): SeekMailbox = SeekMailbox(
    id = id,
    address = address,
    alias = alias,
    domainId = domainId,
    ownerId = ownerId,
    isReserved = isReserved,
    doNotDisturb = doNotDisturb,
    archiveCount = archiveCount,
    archiveLimit = archiveLimit,
    clientAccessAdmin = clientAccessAdmin,
    clientAccessUser = clientAccessUser,
    sendDisabled = sendDisabled,
    forwardTo = forwardTo,
    forwardKeepCopy = forwardKeepCopy,
    forwardDeleteDays = forwardDeleteDays,
    status = status,
    sortOrder = sortOrder,
    isPinned = isPinned,
    isLocked = isLocked,
    createdAt = createdAt,
)

internal fun TagDto.toDomain(): SeekMailTag = SeekMailTag(
    id = id,
    name = name,
    color = color,
    isSystem = isSystem,
    createdAt = createdAt,
)

internal fun ContactDto.toDomain(): SeekMailContact = SeekMailContact(
    id = id,
    name = name,
    email = email,
    source = source,
    lastContactAt = lastContactAt,
    createdAt = createdAt,
)

internal fun AttachmentDto.toDomain(fallbackMailId: Long): SeekMailAttachment = SeekMailAttachment(
    id = resolveId(),
    mailId = mailId ?: fallbackMailId,
    fileName = resolveFileName(),
    contentType = resolveContentType(),
    sizeBytes = resolveSizeBytes(),
)

internal fun MailDto.toDomainSummary(
    overrideTags: List<SeekMailTag>? = null,
    fallbackBodyText: String? = null,
    overrideVerificationCode: String? = null,
): SeekMailSummary {
    val effectiveText = when {
        bodyText.isNotBlank() -> bodyText
        !fallbackBodyText.isNullOrBlank() -> fallbackBodyText
        bodyHtml.isNotBlank() -> SeekMailHtmlSanitizer.extractPlainTextFallback(bodyHtml)
        else -> ""
    }
    val snippet = effectiveText
        .replace(SNIPPET_WHITESPACE_REGEX, " ")
        .trim()
        .let { clean ->
            if (clean.length > MAX_SNIPPET_CHARS) {
                clean.take(MAX_SNIPPET_CHARS - 1).trimEnd() + "…"
            } else {
                clean
            }
        }
    val resolvedCode = overrideVerificationCode ?: VerificationCodeExtractor.resolveCode(
        serverCode = verificationCode,
        subject = subject,
        bodyText = effectiveText,
    )
    return SeekMailSummary(
        id = id,
        mailboxId = mailboxId,
        fromAddress = fromAddress,
        fromName = fromName,
        toAddress = toAddress,
        subject = subject,
        snippet = snippet,
        verificationCode = resolvedCode,
        messageId = messageId,
        threadId = threadId,
        isRead = isRead,
        isFlagged = isFlagged,
        hasAttachments = hasAttachments,
        isPinned = isPinned,
        importance = importance,
        status = status,
        receivedAt = receivedAt,
        createdAt = createdAt,
        sizeBytes = size,
        spamScore = spamScore,
        authScore = authScore,
        tags = overrideTags ?: (tags ?: emptyList()).map { it.toDomain() },
    )
}

internal fun MailDetailDataDto.toDomainDetail(): SeekMailDetail? {
    val dto = mail ?: return null
    val mergedTags = (dto.tags.orEmpty() + tags.orEmpty())
        .distinctBy { it.id }
        .map { it.toDomain() }
    val sanitized = SeekMailHtmlSanitizer.sanitize(dto.bodyHtml)
    val resolvedBodyText = if (dto.bodyText.isNotBlank()) {
        dto.bodyText
    } else {
        SeekMailHtmlSanitizer.extractPlainTextFallback(dto.bodyHtml)
    }
    val resolvedCode = VerificationCodeExtractor.resolveCode(
        serverCode = dto.verificationCode,
        subject = dto.subject,
        bodyText = resolvedBodyText,
    )
    val summary = dto.toDomainSummary(
        overrideTags = mergedTags,
        fallbackBodyText = resolvedBodyText,
        overrideVerificationCode = resolvedCode,
    )
    return SeekMailDetail(
        summary = summary,
        bodyText = resolvedBodyText,
        bodyHtml = dto.bodyHtml,
        sanitizedBodyHtml = sanitized.bodyFragmentHtml,
        hasRemoteImages = sanitized.hasRemoteImages,
        authTelemetry = SeekMailHtmlSanitizer.parseScoreDetails(dto.scoreDetails),
        rawScoreDetails = dto.scoreDetails,
    )
}
