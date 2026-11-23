@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nodeseek.app.data.network

import com.nodeseek.app.data.parser.CategoryDto
import com.nodeseek.app.data.parser.FlexibleIntSerializer
import com.nodeseek.app.data.parser.FlexibleLongSerializer
import com.nodeseek.app.data.parser.FlexibleNullableIntSerializer
import com.nodeseek.app.data.parser.FlexibleNullableLongSerializer
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.WebsiteBoolSerializer
import com.nodeseek.app.data.parser.WebsiteFlagSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class BasicApiResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val message: String? = null,
)

@Serializable
data class SignInRequestDto(
    val username: String,
    val password: String,
)

@Serializable
data class TwoFactorSignInRequestDto(
    @SerialName("otp_code")
    val otpCode: String,
    @SerialName("otp_session")
    val otpSession: String,
)

@Serializable
data class SignInDataDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    @JsonNames("need2FA", "need_2fa", "need2fa", "require2FA", "twoFactor")
    val need2FA: Boolean? = null,
    @JsonNames("otpSession", "otp_session")
    val otpSession: String? = null,
)

@Serializable
data class SignInResponseDto(
    @Serializable(with = WebsiteBoolSerializer::class)
    val success: Boolean = false,
    @Serializable(with = WebsiteFlagSerializer::class)
    @JsonNames("need2FA", "need_2fa", "need2fa", "require2FA", "twoFactor")
    val need2FA: Boolean? = null,
    @JsonNames("otpSession", "otp_session")
    val otpSession: String? = null,
    val data: SignInDataDto? = null,
    val message: String? = null,
) {
    val resolvedNeed2FA: Boolean?
        get() = need2FA ?: data?.need2FA

    val resolvedOtpSession: String?
        get() = otpSession?.takeIf { it.isNotBlank() } ?: data?.otpSession?.takeIf { it.isNotBlank() }

    val isOtpExpired: Boolean
        get() = message?.contains("OTP_EXPIRED_OR_NOT_EXIST", ignoreCase = true) == true
}

@Serializable
data class CategoryListResponseDto(
    @Serializable(with = WebsiteBoolSerializer::class)
    val success: Boolean = false,
    val data: List<CategoryDto> = emptyList(),
    val message: String? = null,
)

@Serializable(with = AttendanceRecordSerializer::class)
data class AttendanceRecordDto(
    val id: Long? = null,
    val memberId: Long? = null,
    val dayId: Long? = null,
    val gain: Int? = null,
    val createdAt: String? = null,
)

object AttendanceRecordSerializer : KSerializer<AttendanceRecordDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AttendanceRecordDto")

    override fun deserialize(decoder: Decoder): AttendanceRecordDto {
        val jsonDecoder = decoder as? JsonDecoder ?: return AttendanceRecordDto()
        val element = jsonDecoder.decodeJsonElement()
        val obj = when (element) {
            is JsonNull -> return AttendanceRecordDto()
            is JsonPrimitive -> return AttendanceRecordDto()
            is JsonArray -> element.firstOrNull() as? JsonObject ?: return AttendanceRecordDto()
            is JsonObject -> element
        }
        return AttendanceRecordDto(
            id = obj.flexibleLong("id"),
            memberId = obj.flexibleLong("member_id") ?: obj.flexibleLong("user_id") ?: obj.flexibleLong("uid"),
            dayId = obj.flexibleLong("day_id") ?: obj.flexibleLong("dayId"),
            gain = obj.flexibleInt("gain") ?: obj.flexibleInt("reward"),
            createdAt = (obj["created_at"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (obj["createdAt"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    override fun serialize(encoder: Encoder, value: AttendanceRecordDto) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
        if (jsonEncoder != null) {
            val map = buildMap {
                value.id?.let { put("id", JsonPrimitive(it)) }
                value.memberId?.let { put("member_id", JsonPrimitive(it)) }
                value.dayId?.let { put("day_id", JsonPrimitive(it)) }
                value.gain?.let { put("gain", JsonPrimitive(it)) }
                value.createdAt?.let { put("created_at", JsonPrimitive(it)) }
            }
            jsonEncoder.encodeJsonElement(JsonObject(map))
        }
    }

    private fun JsonObject.flexibleInt(key: String): Int? {
        val prim = this[key] as? JsonPrimitive ?: return null
        if (!prim.isString) {
            return prim.intOrNull ?: prim.doubleOrNull?.toInt()
        }
        val cleaned = prim.content.replace(",", "").trim()
        return cleaned.toIntOrNull()
    }

    private fun JsonObject.flexibleLong(key: String): Long? {
        val prim = this[key] as? JsonPrimitive ?: return null
        if (!prim.isString) {
            return prim.longOrNull ?: prim.doubleOrNull?.toLong()
        }
        val cleaned = prim.content.replace(",", "").trim()
        return cleaned.toLongOrNull()
    }
}

@Serializable
data class AttendanceMemberDto(
    @SerialName("member_id")
    @Serializable(with = FlexibleLongSerializer::class)
    val memberId: Long,
    @SerialName("member_name")
    val memberName: String = "",
    @Serializable(with = FlexibleIntSerializer::class)
    val gain: Int = 0,
    @SerialName("day_id")
    @Serializable(with = FlexibleNullableLongSerializer::class)
    val dayId: Long? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
)

@Serializable
data class AttendanceBoardResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val record: AttendanceRecordDto? = null,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val order: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val total: Int = 0,
    val list: List<AttendanceMemberDto> = emptyList(),
    val message: String? = null,
) {
    val hasCheckedInToday: Boolean
        get() {
            val rec = record ?: return false
            if (rec.gain == null) return false
            val boardDayId = list.firstOrNull { it.dayId != null }?.dayId
            if (rec.dayId != null && boardDayId != null && rec.dayId != boardDayId) {
                return false
            }
            if (rec.dayId == null && rec.createdAt != null) {
                val recDate = parseBeijingLocalDate(rec.createdAt)
                val boardDate = list.firstNotNullOfOrNull { it.createdAt?.let(::parseBeijingLocalDate) }
                if (recDate != null && boardDate != null && recDate != boardDate) {
                    return false
                }
            }
            return true
        }

    companion object {
        internal val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private val SQL_DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        internal fun parseBeijingLocalDate(raw: String?): LocalDate? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { return Instant.parse(trimmed).atZone(BEIJING_ZONE).toLocalDate() }
            runCatching { return OffsetDateTime.parse(trimmed).atZoneSameInstant(BEIJING_ZONE).toLocalDate() }
            runCatching {
                val clean = trimmed.substringBefore('.')
                return LocalDateTime.parse(clean, SQL_DATE_TIME_FORMATTER).toLocalDate()
            }
            runCatching {
                if (trimmed.length >= 10) {
                    return LocalDate.parse(trimmed.substring(0, 10))
                }
            }
            return null
        }
    }
}

@Serializable
data class AttendanceActionResponseDto(
    @Serializable(with = WebsiteBoolSerializer::class)
    val success: Boolean = false,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val gain: Int? = null,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val current: Int? = null,
    val message: String? = null,
)

@Serializable
data class FreeLikeQuotaResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val maxFreeLike: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val freeLikeUsed: Int = 0,
    val message: String? = null,
) {
    val drumstickCoinCost: Int
        get() = if (freeLikeUsed < maxFreeLike) 0 else 1
}

@Serializable
data class DailyProgressResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val postBonusCount: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val maxPostBonusCount: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val commentBonusCount: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val maxCommentBonusCount: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val freeLikeUsed: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val maxFreeLike: Int = 0,
    val message: String? = null,
)

@Serializable
data class CommentReactionRequestDto(
    val commentId: Long,
    val action: String = "add",
)

@Serializable
data class ReactionActionResponseDto(
    @Serializable(with = WebsiteBoolSerializer::class)
    val success: Boolean = false,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val current: Int? = null,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val coin: Int? = null,
    val message: String? = null,
)

@Serializable
data class CollectionActionRequestDto(
    val postId: Long,
    val action: String, // "add" or "remove"
)

@Serializable
data class UserContentRowDto(
    @SerialName("post_id")
    @JsonNames("post_id", "postId", "pid", "id")
    @Serializable(with = FlexibleLongSerializer::class)
    val postId: Long,
    @JsonNames("title", "post_title", "subject")
    val title: String = "",
    @SerialName("floor_id")
    @JsonNames("floor_id", "floorId", "floor")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val floorId: Int? = null,
    @JsonNames("text", "content", "comment", "body", "excerpt")
    val text: String? = null,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val rank: Int? = null,
    @SerialName("category")
    @JsonNames("category", "category_slug", "categorySlug")
    val categorySlug: String? = null,
    @SerialName("category_title")
    @JsonNames("category_title", "categoryTitle", "category_name")
    val categoryTitle: String? = null,
    @SerialName("member_name")
    @JsonNames("member_name", "author", "username", "user_name")
    val authorName: String? = null,
    @SerialName("comments")
    @JsonNames("comments", "comment_count", "nComment", "reply_count")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val commentsCount: Int? = null,
    @SerialName("views")
    @JsonNames("views", "view_count", "click", "nView")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val viewsCount: Int? = null,
    @SerialName("created_at")
    @JsonNames("created_at", "createdAt", "created_at_str", "time", "date")
    val createdAt: String? = null,
) {
    val plainTextExcerpt: String?
        get() = text?.let { NodeSeekHtmlParser.cleanCommentHtmlToExcerpt(it) }
}

@Serializable
data class CollectionListResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    @JsonNames("collections", "collectionList", "list")
    val collections: List<UserContentRowDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class UserDiscussionsEnvelopeDto(
    @JsonNames("discussions", "postList", "list")
    val discussions: List<UserContentRowDto> = emptyList(),
    @JsonNames("totalPage", "total_page", "totalPages", "pages")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val totalPage: Int? = null,
    @JsonNames("hasNext", "has_next", "hasMore", "has_more")
    @Serializable(with = WebsiteFlagSerializer::class)
    val hasNext: Boolean? = null,
)

@Serializable
data class UserDiscussionsResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    @JsonNames("discussions", "postList", "list")
    val discussions: List<UserContentRowDto> = emptyList(),
    val data: UserDiscussionsEnvelopeDto? = null,
    @JsonNames("totalPage", "total_page", "totalPages", "pages")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val totalPage: Int? = null,
    @JsonNames("hasNext", "has_next", "hasMore", "has_more")
    @Serializable(with = WebsiteFlagSerializer::class)
    val hasNext: Boolean? = null,
    val message: String? = null,
) {
    val resolvedDiscussions: List<UserContentRowDto>
        get() = discussions.ifEmpty { data?.discussions.orEmpty() }

    fun hasMorePages(currentPage: Int, defaultPageSize: Int): Boolean {
        val explicitHasNext = hasNext ?: data?.hasNext
        if (explicitHasNext != null) return explicitHasNext
        val resolvedTotalPages = totalPage ?: data?.totalPage
        if (resolvedTotalPages != null) return currentPage < resolvedTotalPages
        return resolvedDiscussions.size >= defaultPageSize
    }
}

@Serializable
data class UserCommentsEnvelopeDto(
    @JsonNames("comments", "commentList", "list")
    val comments: List<UserContentRowDto> = emptyList(),
    @JsonNames("totalPage", "total_page", "totalPages", "pages")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val totalPage: Int? = null,
    @JsonNames("hasNext", "has_next", "hasMore", "has_more")
    @Serializable(with = WebsiteFlagSerializer::class)
    val hasNext: Boolean? = null,
)

@Serializable
data class UserCommentsResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    @JsonNames("comments", "commentList", "list")
    val comments: List<UserContentRowDto> = emptyList(),
    val data: UserCommentsEnvelopeDto? = null,
    @JsonNames("totalPage", "total_page", "totalPages", "pages")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val totalPage: Int? = null,
    @JsonNames("hasNext", "has_next", "hasMore", "has_more")
    @Serializable(with = WebsiteFlagSerializer::class)
    val hasNext: Boolean? = null,
    val message: String? = null,
) {
    val resolvedComments: List<UserContentRowDto>
        get() = comments.ifEmpty { data?.comments.orEmpty() }

    fun hasMorePages(currentPage: Int, defaultPageSize: Int): Boolean {
        val explicitHasNext = hasNext ?: data?.hasNext
        if (explicitHasNext != null) return explicitHasNext
        val resolvedTotalPages = totalPage ?: data?.totalPage
        if (resolvedTotalPages != null) return currentPage < resolvedTotalPages
        return resolvedComments.size >= defaultPageSize
    }
}

@Serializable
data class NewDiscussionRequestDto(
    val mode: String = "new-discussion",
    val title: String,
    val content: String,
    val category: String,
    val rank: Int = 0,
)

@Serializable
data class EditDiscussionRequestDto(
    val mode: String = "edit-discussion",
    val postId: Long,
    val title: String,
    val content: String,
    val rank: Int = 0,
)

@Serializable
data class NewCommentRequestDto(
    val mode: String = "new-comment",
    val postId: Long,
    val content: String,
)

@Serializable
data class EditCommentRequestDto(
    val mode: String = "edit-comment",
    val commentId: Long,
    val content: String,
)

@Serializable
data class PinCommentRequestDto(
    val commentId: Long,
    val action: String, // "add" or "remove"
)

@Serializable(with = UnreadNotificationCountResponseSerializer::class)
data class UnreadNotificationCountResponseDto(
    val success: Boolean? = null,
    val unreadCount: UnreadCountObjectDto = UnreadCountObjectDto(),
    val message: String? = null,
) {
    @Serializable
    data class UnreadCountObjectDto(
        @JsonNames("total", "count")
        @Serializable(with = LenientIntZeroSerializer::class)
        val all: Int = 0,
        @JsonNames("replys", "replies", "replyCount", "reply_count")
        @Serializable(with = LenientIntZeroSerializer::class)
        val reply: Int = 0,
        @JsonNames("atMe", "at_me", "mention", "mentions", "atCount", "at_count")
        @Serializable(with = LenientIntZeroSerializer::class)
        val at: Int = 0,
        @JsonNames("messages", "pm", "dm", "messageCount", "message_count", "msg", "msgCount")
        @Serializable(with = LenientIntZeroSerializer::class)
        val message: Int = 0,
        @JsonNames("sys", "systemCount", "system_count")
        @Serializable(with = LenientIntZeroSerializer::class)
        val system: Int = 0,
    ) {
        fun effectiveAll(): Int =
            maxOf(all, reply + at + message + system).coerceAtLeast(0)
    }
}

private object UnreadNotificationCountResponseSerializer : KSerializer<UnreadNotificationCountResponseDto> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("UnreadNotificationCountResponseDto")

    override fun deserialize(decoder: Decoder): UnreadNotificationCountResponseDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return UnreadNotificationCountResponseDto()
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: return UnreadNotificationCountResponseDto()

        val success = (obj["success"] as? JsonPrimitive)?.let { prim ->
            runCatching { WebsiteFlagSerializer.parsePrimitive(prim) }.getOrNull()
        }
        val rawMessagePrim = obj["message"] as? JsonPrimitive
        val serverMessage = rawMessagePrim?.contentOrNull?.takeIf {
            it.isNotBlank() && it.toIntOrNull() == null
        }

        val nestedElement = obj["unreadCount"] ?: obj["unViewedCount"] ?: obj["data"]
        val parsedNested = when (nestedElement) {
            is JsonObject -> parseCountObject(nestedElement)
            is JsonPrimitive -> UnreadNotificationCountResponseDto.UnreadCountObjectDto(
                all = parseLenientIntOrNull(nestedElement) ?: 0,
            )
            else -> null
        }
        val parsedTopLevel = parseCountObject(obj)
        val resolved = if (parsedNested != null) {
            UnreadNotificationCountResponseDto.UnreadCountObjectDto(
                all = maxOf(parsedNested.all, parsedTopLevel.all),
                reply = maxOf(parsedNested.reply, parsedTopLevel.reply),
                at = maxOf(parsedNested.at, parsedTopLevel.at),
                message = maxOf(parsedNested.message, parsedTopLevel.message),
                system = maxOf(parsedNested.system, parsedTopLevel.system),
            )
        } else {
            parsedTopLevel
        }

        return UnreadNotificationCountResponseDto(
            success = success,
            unreadCount = resolved,
            message = serverMessage,
        )
    }

    override fun serialize(encoder: Encoder, value: UnreadNotificationCountResponseDto) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder ?: return
        val countsObj = JsonObject(
            mapOf(
                "all" to JsonPrimitive(value.unreadCount.all),
                "reply" to JsonPrimitive(value.unreadCount.reply),
                "at" to JsonPrimitive(value.unreadCount.at),
                "message" to JsonPrimitive(value.unreadCount.message),
                "system" to JsonPrimitive(value.unreadCount.system),
            ),
        )
        val map = buildMap {
            value.success?.let { put("success", JsonPrimitive(it)) }
            put("unreadCount", countsObj)
            value.message?.let { put("message", JsonPrimitive(it)) }
        }
        jsonEncoder.encodeJsonElement(JsonObject(map))
    }

    private fun parseCountObject(obj: JsonObject): UnreadNotificationCountResponseDto.UnreadCountObjectDto {
        return UnreadNotificationCountResponseDto.UnreadCountObjectDto(
            all = firstLenientInt(obj, "all", "total", "count"),
            reply = firstLenientInt(obj, "reply", "replys", "replies", "replyCount", "reply_count"),
            at = firstLenientInt(obj, "at", "atMe", "at_me", "mention", "mentions", "atCount", "at_count"),
            message = firstLenientInt(obj, "message", "messages", "pm", "dm", "messageCount", "message_count", "msg", "msgCount"),
            system = firstLenientInt(obj, "system", "sys", "systemCount", "system_count"),
        )
    }

    private fun firstLenientInt(obj: JsonObject, vararg keys: String): Int {
        for (key in keys) {
            val elem = obj[key] as? JsonPrimitive ?: continue
            parseLenientIntOrNull(elem)?.let { return it }
        }
        return 0
    }

    private fun parseLenientIntOrNull(element: JsonPrimitive): Int? {
        if (!element.isString) {
            return element.booleanOrNull?.let { if (it) 1 else 0 }
                ?: element.intOrNull
                ?: element.doubleOrNull?.toInt()
        }
        return element.content.replace(",", "").trim().toIntOrNull()
    }
}

@Serializable
data class NoticeRowDto(
    @Serializable(with = LenientLongZeroSerializer::class)
    val id: Long = 0L,
    @Serializable(with = LenientBoolFalseSerializer::class)
    val viewed: Boolean = false,
    @SerialName("post_id")
    @JsonNames("postId")
    @Serializable(with = LenientLongZeroSerializer::class)
    val postId: Long = 0L,
    @Serializable(with = LenientStringEmptySerializer::class)
    val title: String = "",
    @SerialName("floor_id")
    @JsonNames("floorId", "floor")
    @Serializable(with = LenientIntZeroSerializer::class)
    val floorId: Int = 0,
    @SerialName("commenter_id")
    @JsonNames("commenterId")
    @Serializable(with = LenientLongZeroSerializer::class)
    val commenterId: Long = 0L,
    @SerialName("commenter_name")
    @JsonNames("commenterName")
    @Serializable(with = LenientStringEmptySerializer::class)
    val commenterName: String = "",
    @SerialName("created_at")
    @JsonNames("createdAt")
    @Serializable(with = LenientStringEmptySerializer::class)
    val createdAt: String = "",
)

@Serializable
data class NoticeListResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val data: List<NoticeRowDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class DirectMessageRowDto(
    @JsonNames("msg_id", "message_id", "mid", "pm_id", "notice_id")
    @Serializable(with = LenientLongZeroSerializer::class)
    val id: Long = 0L,
    @SerialName("sender_id")
    @JsonNames("senderId", "from_id", "fromId", "from")
    @Serializable(with = LenientLongZeroSerializer::class)
    val senderId: Long = 0L,
    @SerialName("sender_name")
    @JsonNames("senderName", "from_name", "fromName")
    @Serializable(with = LenientStringEmptySerializer::class)
    val senderName: String = "",
    @SerialName("receiver_id")
    @JsonNames("receiverId", "to_id", "toId", "to")
    @Serializable(with = LenientLongZeroSerializer::class)
    val receiverId: Long = 0L,
    @SerialName("receiver_name")
    @JsonNames("receiverName", "to_name", "toName")
    @Serializable(with = LenientStringEmptySerializer::class)
    val receiverName: String = "",
    @JsonNames("message", "msg", "text", "body", "notice")
    @Serializable(with = LenientStringEmptySerializer::class)
    val content: String = "",
    @SerialName("is_markdown")
    @JsonNames("isMarkdown", "markdown")
    @Serializable(with = LenientBoolFalseSerializer::class)
    val isMarkdown: Boolean = false,
    @SerialName("created_at")
    @JsonNames("createdAt", "time", "created", "created_at_str")
    @Serializable(with = LenientStringEmptySerializer::class)
    val createdAt: String = "",
    @JsonNames("is_viewed", "isViewed", "read", "is_read")
    @Serializable(with = LenientBoolFalseSerializer::class)
    val viewed: Boolean = false,
    @SerialName("is_system")
    @JsonNames("isSystem", "sys")
    @Serializable(with = LenientBoolFalseSerializer::class)
    val isSystemFlag: Boolean = false,
    @Serializable(with = LenientBoolFalseSerializer::class)
    val system: Boolean = false,
    @JsonNames("msg_type", "msgType", "notice_type", "noticeType", "kind", "category")
    @Serializable(with = LenientStringEmptySerializer::class)
    val type: String = "",
    @JsonNames("subject")
    @Serializable(with = LenientStringEmptySerializer::class)
    val title: String = "",
    @JsonNames("url", "target_url", "targetUrl")
    @Serializable(with = LenientStringEmptySerializer::class)
    val link: String = "",
    @SerialName("post_id")
    @JsonNames("postId")
    @Serializable(with = LenientLongZeroSerializer::class)
    val postId: Long = 0L,
    @SerialName("floor_id")
    @JsonNames("floorId", "floor")
    @Serializable(with = LenientIntZeroSerializer::class)
    val floorId: Int = 0,
) {
    fun isSystemMessage(viewerUid: Long): Boolean {
        if (isSystemFlag || system) return true
        val normalizedType = type.trim().lowercase()
        if (normalizedType.isNotEmpty() && normalizedType !in NORMAL_DM_TYPES) {
            return true
        }
        if (senderId <= 0L) return true
        if (viewerUid > 0L && senderId != viewerUid && receiverId <= 0L) return true
        if (senderName.trim().lowercase() in SYSTEM_SENDER_NAMES) return true
        return false
    }

    fun resolvedPeerUid(viewerUid: Long): Long =
        if (viewerUid > 0L && senderId == viewerUid) receiverId else senderId

    fun belongsToConversation(viewerUid: Long, peerUid: Long): Boolean {
        if ((senderId == viewerUid && receiverId == peerUid) ||
            (senderId == peerUid && receiverId == viewerUid)
        ) {
            return true
        }
        if (peerUid <= 0L && isSystemMessage(viewerUid) && resolvedPeerUid(viewerUid) == peerUid) {
            return receiverId == viewerUid || receiverId <= 0L
        }
        return false
    }

    fun inferNoticeCategory(): String {
        val typeText = type.trim().lowercase()
        val combined = "$typeText $title $content".lowercase()
        return when {
            "鸡腿" in combined || "coin" in typeText || "drumstick" in combined -> "鸡腿"
            "星辰" in combined || "stardust" in combined -> "星辰"
            "点赞" in combined || "赞了" in combined || "upvote" in combined || "like" in typeText -> "点赞"
            else -> ""
        }
    }

    fun effectiveContent(): String {
        val rawTitle = title.trim()
        val rawBody = content.trim()
        val base = when {
            rawBody.isNotEmpty() && rawTitle.isNotEmpty() && !rawBody.contains(rawTitle) -> {
                if (isMarkdown) "**$rawTitle**\n\n$rawBody" else "$rawTitle\n$rawBody"
            }
            rawBody.isNotEmpty() -> rawBody
            else -> rawTitle
        }
        val rawLink = link.trim()
        val normalizedLink = when {
            rawLink.isEmpty() -> ""
            rawLink.startsWith("/") -> NodeSeekUrlNormalizer.normalizeNodeSeekUrl(rawLink)
            else -> rawLink
        }
        return when {
            normalizedLink.isNotEmpty() && !base.contains(rawLink) && !base.contains(normalizedLink) -> {
                val suffix = "[查看详情]($normalizedLink)"
                if (base.isEmpty()) suffix else "$base\n$suffix"
            }
            postId > 0L && !base.contains("/post-$postId") -> {
                val fragment = if (floorId > 0) "#$floorId" else ""
                val url = "${NodeSeekUrlNormalizer.ORIGIN}/post-$postId-1$fragment"
                val suffix = "[查看相关帖子]($url)"
                if (base.isEmpty()) suffix else "$base\n$suffix"
            }
            else -> base
        }
    }
}

private val NORMAL_DM_TYPES = setOf("message", "dm", "pm", "chat", "private", "direct")
private val SYSTEM_SENDER_NAMES = setOf(
    "system",
    "sys",
    "系统",
    "系统消息",
    "系统通知",
    "官方通知",
    "通知中心",
)

data class GroupedMessageThreadDto(
    val peerUid: Long,
    val peerName: String,
    val latestMessageId: Long?,
    val preview: String,
    val isMarkdown: Boolean,
    val createdAt: String,
    val viewed: Boolean,
    val isSystem: Boolean = false,
    val unreadCount: Int = 0,
)

@Serializable
data class MessageListResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val msgArray: List<DirectMessageRowDto> = emptyList(),
    val message: String? = null,
    @JsonNames("systemArray", "system_array", "sys_array", "systemMessages", "system_messages")
    val sysArray: List<DirectMessageRowDto> = emptyList(),
    val data: List<DirectMessageRowDto> = emptyList(),
) {
    fun allMessages(): List<DirectMessageRowDto> {
        val markedSystem = sysArray.map { row ->
            if (row.isSystemFlag || row.system) row else row.copy(isSystemFlag = true)
        }
        val combined = when {
            msgArray.isNotEmpty() || markedSystem.isNotEmpty() -> msgArray + markedSystem
            else -> data
        }
        if (combined.size <= 1) return combined
        val seenIds = HashSet<Long>(combined.size)
        return combined.filter { row ->
            row.id <= 0L || seenIds.add(row.id)
        }
    }

    /**
     * Groups flat `msgArray` (plus any explicit `sysArray` rows) by peer (`AccountScripts.read` `kind === 'messages'`),
     * keeping the latest message per peer, aggregating unread state/counts across all rows, and sorting newest threads first.
     */
    fun groupThreadsForViewer(viewerUid: Long): List<GroupedMessageThreadDto> {
        val groupedRows = LinkedHashMap<Long, MutableList<DirectMessageRowDto>>()
        for (row in allMessages()) {
            val peerId = row.resolvedPeerUid(viewerUid)
            groupedRows.getOrPut(peerId) { mutableListOf() }.add(row)
        }
        val threads = groupedRows.entries.map { (peerId, rows) ->
            val latest = rows.maxWith(
                compareBy<DirectMessageRowDto> { parseMessageTimestampMillis(it.createdAt) ?: Long.MIN_VALUE }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )
            val isSystemThread = peerId <= 0L || rows.any { it.isSystemMessage(viewerUid) }
            val explicitPeerName = rows.asSequence()
                .map { row ->
                    if (viewerUid > 0L && row.senderId == viewerUid) {
                        row.receiverName.trim()
                    } else {
                        row.senderName.trim()
                    }
                }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            val resolvedPeerName = when {
                explicitPeerName.isNotEmpty() -> explicitPeerName
                isSystemThread -> "系统通知"
                else -> ""
            }
            val unreadCount = rows.count { row ->
                (viewerUid <= 0L || row.senderId != viewerUid) && !row.viewed
            }
            val effectiveContent = latest.effectiveContent()
            val hasMarkdownLink = effectiveContent != latest.content.trim() &&
                effectiveContent.contains("](")
            GroupedMessageThreadDto(
                peerUid = peerId,
                peerName = resolvedPeerName,
                latestMessageId = latest.id,
                preview = effectiveContent,
                isMarkdown = latest.isMarkdown || hasMarkdownLink,
                createdAt = latest.createdAt,
                viewed = unreadCount == 0,
                isSystem = isSystemThread,
                unreadCount = unreadCount,
            )
        }
        return threads.sortedWith(
            compareByDescending<GroupedMessageThreadDto> { parseMessageTimestampMillis(it.createdAt) ?: Long.MIN_VALUE }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.latestMessageId ?: 0L },
        )
    }

    companion object {
        private val SQL_MESSAGE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        internal fun parseMessageTimestampMillis(raw: String?): Long? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { return Instant.parse(trimmed).toEpochMilli() }
            runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            runCatching {
                val clean = trimmed.substringBefore('.')
                return LocalDateTime.parse(clean, SQL_MESSAGE_TIME_FORMATTER)
                    .atZone(AttendanceBoardResponseDto.BEIJING_ZONE)
                    .toInstant()
                    .toEpochMilli()
            }
            return null
        }
    }
}

@Serializable
data class ConversationPeerDto(
    @SerialName("member_id")
    @JsonNames("memberId", "uid", "id")
    @Serializable(with = LenientLongZeroSerializer::class)
    val memberId: Long = 0L,
    @SerialName("member_name")
    @JsonNames("memberName", "name", "username")
    @Serializable(with = LenientStringEmptySerializer::class)
    val memberName: String = "",
)

@Serializable
data class ConversationResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val talkTo: ConversationPeerDto? = null,
    val msgArray: List<DirectMessageRowDto> = emptyList(),
    val message: String? = null,
    @JsonNames("systemArray", "system_array", "sys_array")
    val sysArray: List<DirectMessageRowDto> = emptyList(),
    val data: List<DirectMessageRowDto> = emptyList(),
) {
    fun allMessages(): List<DirectMessageRowDto> {
        val markedSystem = sysArray.map { row ->
            if (row.isSystemFlag || row.system) row else row.copy(isSystemFlag = true)
        }
        return when {
            msgArray.isNotEmpty() || markedSystem.isNotEmpty() -> msgArray + markedSystem
            else -> data
        }
    }
}

private object LenientLongZeroSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientLongZero", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return parseLongElement(jsonDecoder.decodeJsonElement())
    }

    private fun parseLongElement(element: kotlinx.serialization.json.JsonElement): Long = when (element) {
        is JsonNull -> 0L
        is JsonPrimitive -> {
            if (!element.isString) {
                element.booleanOrNull?.let { if (it) 1L else 0L }
                    ?: element.longOrNull
                    ?: element.doubleOrNull?.toLong()
                    ?: 0L
            } else {
                element.content.replace(",", "").trim().toLongOrNull() ?: 0L
            }
        }
        is JsonObject -> {
            val nested = element["member_id"] ?: element["uid"] ?: element["id"]
            if (nested != null) parseLongElement(nested) else 0L
        }
        else -> 0L
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

private object LenientIntZeroSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientIntZero", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> 0
            is JsonPrimitive -> {
                if (!element.isString) {
                    element.booleanOrNull?.let { if (it) 1 else 0 }
                        ?: element.intOrNull
                        ?: element.doubleOrNull?.toInt()
                        ?: 0
                } else {
                    element.content.replace(",", "").trim().toIntOrNull() ?: 0
                }
            }
            else -> 0
        }
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

private object LenientStringEmptySerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientStringEmpty", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return parseStringElement(jsonDecoder.decodeJsonElement())
    }

    private fun parseStringElement(element: kotlinx.serialization.json.JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonObject -> {
            val nested = element["member_name"]
                ?: element["username"]
                ?: element["name"]
                ?: element["text"]
                ?: element["content"]
            if (nested != null) parseStringElement(nested) else ""
        }
        else -> ""
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

private object LenientBoolFalseSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolFalse", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> false
            is JsonPrimitive -> {
                if (!element.isString) {
                    element.booleanOrNull
                        ?: element.longOrNull?.let { it != 0L }
                        ?: false
                } else {
                    when (element.content.trim().lowercase()) {
                        "true", "1", "yes" -> true
                        else -> false
                    }
                }
            }
            else -> false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

@Serializable
data class SendDirectMessageRequestDto(
    val receiverUid: Long,
    val content: String,
    val markdown: Boolean = false,
)

@Serializable
data class UserProfileDetailDto(
    @SerialName("member_id")
    @JsonNames("member_id", "uid", "user_id", "id")
    @Serializable(with = FlexibleNullableLongSerializer::class)
    val memberId: Long? = null,
    @SerialName("member_name")
    @JsonNames("member_name", "username", "name")
    val memberName: String = "",
    @JsonNames("avatar", "avatarUrl", "avatar_url")
    val avatar: String? = null,
    @JsonNames("rank", "level")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val rank: Int? = null,
    @JsonNames("bio", "introduction", "signature_text")
    val bio: String? = null,
    @JsonNames("readme", "readMe", "read_me")
    val readme: String? = null,
    @SerialName("signature_markdown")
    @JsonNames("signature_markdown", "signatureMarkdown")
    val signatureMarkdown: String? = null,
    val signature: String? = null,
    @SerialName("created_at")
    @JsonNames("created_at", "createdAt", "registered_at", "created_at_str")
    val createdAt: String? = null,
    @JsonNames("coin", "chicken", "chickenCount", "chicken_count")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val coin: Int? = null,
    @JsonNames("stardust", "star", "stars", "starCount", "star_count")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val stardust: Int? = null,
    @JsonNames("nPost", "post_count", "postCount", "topicCount", "discussion_count")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val nPost: Int? = null,
    @JsonNames("nComment", "comment_count", "commentCount")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val nComment: Int? = null,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val follows: Int? = null,
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val fans: Int? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val followed: Boolean? = null,
)

@Serializable
data class UserProfileResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val detail: UserProfileDetailDto? = null,
    val data: UserProfileDetailDto? = null,
    val user: UserProfileDetailDto? = null,
    val account: UserProfileDetailDto? = null,
    val result: UserProfileDetailDto? = null,
    val message: String? = null,
) {
    val resolvedDetail: UserProfileDetailDto?
        get() = detail ?: data ?: user ?: account ?: result
}

@Serializable
data class UpdateIntroductionRequestDto(
    val bio: String,
    val signature: String,
    val readme: String,
)

@Serializable
data class MemberBriefDto(
    @SerialName("member_id")
    @Serializable(with = FlexibleLongSerializer::class)
    val memberId: Long,
    @SerialName("member_name")
    val memberName: String = "",
    val avatar: String? = null,
)

@Serializable
data class MemberListResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val memberList: List<MemberBriefDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class FollowActionRequestDto(
    @SerialName("followed_member_id")
    val followedMemberId: Long,
)

@Serializable
data class BlockedMemberDto(
    @SerialName("block_member_id")
    @Serializable(with = FlexibleLongSerializer::class)
    val blockMemberId: Long,
    @SerialName("block_member_name")
    val blockMemberName: String = "",
)

@Serializable
data class BlockListResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val data: List<BlockedMemberDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class BlockByNameRequestDto(
    @SerialName("block_member_name")
    val blockMemberName: String,
)

@Serializable
data class UnblockByIdRequestDto(
    @SerialName("block_member_id")
    val blockMemberId: Long,
)

@Serializable
data class HomepageCategoryPrefDto(
    val category: String,
    @Serializable(with = WebsiteBoolSerializer::class)
    val showInIndex: Boolean = true,
)

@Serializable
data class HomepageCategoryResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val data: List<HomepageCategoryPrefDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class SaveHomepageRequestDto(
    val data: List<HomepageCategoryPrefDto>,
)

@Serializable
data class OtpStatusResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    @Serializable(with = WebsiteBoolSerializer::class)
    val enabled: Boolean = false,
    val message: String? = null,
)

@Serializable
data class OtpActionRequestDto(
    val action: String, // "create" or "remove"
    val password: String,
)

@Serializable
data class OtpActionResponseDto(
    @Serializable(with = WebsiteBoolSerializer::class)
    val success: Boolean = false,
    val secret: String? = null,
    val otpauth: String? = null,
    val message: String? = null,
)

@Serializable
data class SignInHistoryEntryDto(
    val ip: String = "",
    val time: String = "",
)

@Serializable
data class SignInHistoryResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val historyIp: List<SignInHistoryEntryDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class ChangePasswordRequestDto(
    val oldPassword: String,
    val password: String,
)

@Serializable(with = CreditLedgerTupleSerializer::class)
data class CreditLedgerTupleDto(
    val change: Int,
    val balance: Int,
    val reason: String,
    val createdAt: String,
)

/**
 * Deserializes NodeSeek's 4-element tuple array `[change, balance, reason, createdAt]`
 * returned by `GET /api/account/credit/page-{page}`.
 */
object CreditLedgerTupleSerializer : KSerializer<CreditLedgerTupleDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CreditLedgerTupleDto")

    override fun deserialize(decoder: Decoder): CreditLedgerTupleDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("CreditLedgerTupleSerializer requires JsonDecoder")
        val arr = jsonDecoder.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("Expected 4-element credit ledger tuple array")
        if (arr.size < 4) {
            throw SerializationException("Expected at least 4 elements in credit tuple, got ${arr.size}")
        }
        val change = (arr[0] as? JsonPrimitive)?.let {
            it.intOrNull ?: it.content.toIntOrNull()
        } ?: 0
        val balance = (arr[1] as? JsonPrimitive)?.let {
            it.intOrNull ?: it.content.toIntOrNull()
        } ?: 0
        val reason = (arr[2] as? JsonPrimitive)?.content.orEmpty()
        val createdAt = (arr[3] as? JsonPrimitive)?.content.orEmpty()
        return CreditLedgerTupleDto(
            change = change,
            balance = balance,
            reason = reason,
            createdAt = createdAt,
        )
    }

    override fun serialize(encoder: Encoder, value: CreditLedgerTupleDto) {
        throw UnsupportedOperationException("CreditLedgerTupleDto is read-only")
    }
}

@Serializable
data class CreditLedgerResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val data: List<CreditLedgerTupleDto> = emptyList(),
    @Serializable(with = FlexibleIntSerializer::class)
    val total: Int = 0,
    val message: String? = null,
)

@Serializable
data class StardustRecordDto(
    @Serializable(with = FlexibleLongSerializer::class)
    val id: Long,
    @Serializable(with = FlexibleIntSerializer::class)
    val diff: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val result: Int = 0,
    val type: String = "",
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("peer_id")
    @Serializable(with = FlexibleNullableLongSerializer::class)
    val peerId: Long? = null,
)

@Serializable
data class StardustListResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val records: List<StardustRecordDto> = emptyList(),
    @SerialName("exist_more")
    @Serializable(with = WebsiteBoolSerializer::class)
    val existMore: Boolean = false,
    @Serializable(with = FlexibleNullableLongSerializer::class)
    val cursor: Long? = null,
    val message: String? = null,
)

@Serializable
data class StardustPrepareRequestDto(
    @SerialName("receiver_id")
    val receiverId: Long,
    val origin: String = NodeSeekUrlNormalizer.ORIGIN,
)

@Serializable
data class StardustPrepareResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    @SerialName("receiver_name")
    val receiverName: String = "",
    val message: String? = null,
)

@Serializable
data class StardustSendRequestDto(
    @SerialName("member_id")
    val memberId: Long,
    val diff: Int,
    @SerialName("ref_id")
    val refId: Long = 0L,
)

@Serializable
data class VoteOptionDto(
    @SerialName("vote_item_id")
    @Serializable(with = FlexibleLongSerializer::class)
    val voteItemId: Long,
    val text: String = "",
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val count: Int? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val voted: Boolean? = null,
    val voters: List<Long>? = null,
)

@Serializable
data class VoteDetailDto(
    @Serializable(with = FlexibleLongSerializer::class)
    val id: Long,
    val title: String = "",
    @Serializable(with = FlexibleLongSerializer::class)
    val uid: Long = 0L,
    @Serializable(with = WebsiteBoolSerializer::class)
    val isPublic: Boolean = false,
    @Serializable(with = WebsiteBoolSerializer::class)
    val locked: Boolean = false,
    @Serializable(with = WebsiteBoolSerializer::class)
    val multiple: Boolean = false,
    val items: List<VoteOptionDto> = emptyList(),
)

@Serializable
data class VoteResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val vote: VoteDetailDto? = null,
    val message: String? = null,
)

@Serializable
data class CreateVoteRequestDto(
    val title: String,
    val multiple: Boolean,
    val isPublic: Boolean,
    val items: List<String>,
)

@Serializable
data class CastVoteRequestDto(
    val ids: List<Long>,
)

@Serializable
data class LockVoteRequestDto(
    val locked: Boolean = true,
)

@Serializable
data class NodeImageLinksDto(
    val direct: String? = null,
    val markdown: String? = null,
    val html: String? = null,
    val raw: String? = null,
)

@Serializable
data class NodeImageUploadResponseDto(
    @Serializable(with = WebsiteFlagSerializer::class)
    val success: Boolean? = null,
    val url: String? = null,
    val links: NodeImageLinksDto? = null,
    val imageId: String? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val resolvedDirectUrl: String?
        get() = links?.direct?.trim()?.takeIf { it.isNotBlank() }
            ?: url?.trim()?.takeIf { it.isNotBlank() }
            ?: links?.raw?.trim()?.takeIf { it.isNotBlank() }
            ?: links?.markdown?.let(::extractUrlFromNodeImageMarkdown)
            ?: links?.html?.let(::extractUrlFromNodeImageHtml)
}

private val NODE_IMAGE_MD_URL_REGEX = Regex("""!\[[^\]]*\]\(([^)\s]+)""")
private val NODE_IMAGE_HTML_URL_REGEX = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

private fun extractUrlFromNodeImageMarkdown(raw: String): String? =
    NODE_IMAGE_MD_URL_REGEX.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

private fun extractUrlFromNodeImageHtml(raw: String): String? =
    NODE_IMAGE_HTML_URL_REGEX.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
