@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nodeseek.app.data.parser

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Base64
import kotlin.math.sqrt

/**
 * Deserializes NodeSeek's mixed boolean / SQL `0`/`1` flag fields.
 *
 * - `true` / `1` / `"1"` / `"true"` -> `true`
 * - `false` / `0` / `"0"` / `"false"` -> `false`
 * - `null` / `""` -> `null` (preserves unknown state when field is omitted)
 * - Other numbers (e.g. `2`, `-1`) -> throws [SerializationException]
 */
object WebsiteFlagSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WebsiteFlag", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeBoolean()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> parsePrimitive(element)
            else -> throw SerializationException("Expected boolean or 0/1 flag, got: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeBoolean(value)
        }
    }

    internal fun parsePrimitive(primitive: JsonPrimitive): Boolean? {
        if (!primitive.isString) {
            primitive.booleanOrNull?.let { return it }
            primitive.intOrNull?.let { intVal ->
                return when (intVal) {
                    1 -> true
                    0 -> false
                    else -> throw SerializationException("Invalid WebsiteFlag integer: $intVal")
                }
            }
        }
        return when (val text = primitive.content.trim().lowercase()) {
            "" -> null
            "true", "1" -> true
            "false", "0" -> false
            else -> throw SerializationException("Invalid WebsiteFlag value: '${primitive.content}'")
        }
    }
}

/**
 * Non-null variant of [WebsiteFlagSerializer] defaulting `null` to `false`.
 */
object WebsiteBoolSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WebsiteBool", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean =
        WebsiteFlagSerializer.deserialize(decoder) ?: false

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

/**
 * Deserializes counts that NodeSeek may emit as integers (`240`) or formatted strings (`"1,240"`).
 */
object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int =
        FlexibleNullableIntSerializer.deserialize(decoder) ?: 0

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

object FlexibleNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleNullableInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> {
                if (!element.isString) {
                    element.intOrNull ?: element.doubleOrNull?.toInt()
                } else {
                    val cleaned = element.content.replace(",", "").trim()
                    if (cleaned.isEmpty()) null else cleaned.toIntOrNull()
                        ?: throw SerializationException("Invalid integer value: '${element.content}'")
                }
            }
            else -> throw SerializationException("Expected integer or numeric string, got: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long =
        FlexibleNullableLongSerializer.deserialize(decoder) ?: 0L

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

object FlexibleNullableLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleNullableLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> {
                if (!element.isString) {
                    element.longOrNull ?: element.doubleOrNull?.toLong()
                } else {
                    val cleaned = element.content.replace(",", "").trim()
                    if (cleaned.isEmpty()) null else cleaned.toLongOrNull()
                        ?: throw SerializationException("Invalid long value: '${element.content}'")
                }
            }
            else -> throw SerializationException("Expected long or numeric string, got: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotEmpty() }
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

@Serializable
data class RoleBadgeDto(
    val name: String,
    @SerialName("display_text")
    val displayText: String = name,
)

/**
 * Handles both `["dev"]` (in `__config__.user.roles`) and
 * `[{"name": "admin", "display_text": "管理"}]` (in `postData.comments[].poster.roles`).
 */
object RoleBadgeListSerializer : KSerializer<List<RoleBadgeDto>> {
    private val delegate = ListSerializer(RoleBadgeDto.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<RoleBadgeDto> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> {
                    val name = item.contentOrNull?.trim().orEmpty()
                    if (name.isEmpty()) null else RoleBadgeDto(name = name, displayText = name)
                }
                is JsonObject -> {
                    val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    val label = (item["display_text"] as? JsonPrimitive)?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: name
                    if (name.isEmpty()) null else RoleBadgeDto(name = name, displayText = label)
                }
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<RoleBadgeDto>) {
        delegate.serialize(encoder, value)
    }
}

@Serializable
data class ConfigPayloadDto(
    val user: ConfigUserDto? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val enableCustomedStyle: Boolean? = null,
    val allCategory: List<CategoryDto>? = null,
    val rotateTopics: List<RotateTopicDto>? = null,
    val postData: PostDataDto? = null,
) {
    val canCustomizeStyle: Boolean
        get() {
            val u = user ?: return false
            return enableCustomedStyle == true &&
                (u.rank >= 2 || u.roles.any { it.name.equals("dev", ignoreCase = true) })
        }
}

@Serializable
data class ConfigUserDto(
    @SerialName("member_id")
    @Serializable(with = FlexibleLongSerializer::class)
    val memberId: Long,
    @SerialName("member_name")
    val memberName: String,
    @Serializable(with = FlexibleIntSerializer::class)
    val coin: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val stardust: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val nPost: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val nComment: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val rank: Int = 0,
    @Serializable(with = RoleBadgeListSerializer::class)
    val roles: List<RoleBadgeDto> = emptyList(),
    @Serializable(with = FlexibleIntSerializer::class)
    val collectionCount: Int = 0,
    @SerialName("telegram_id")
    @Serializable(with = FlexibleStringSerializer::class)
    val telegramId: String? = null,
    val unViewedCount: UnreadCountsDto? = null,
) {
    /** Official NodeSeek display level Lv0..Lv6: coins >= 100 * level^2 */
    val displayLevel: Int
        get() = sqrt((coin.coerceAtLeast(0) / 100.0)).toInt().coerceIn(0, 6)

    val isTelegramBound: Boolean
        get() = !telegramId.isNullOrBlank()
}

@Serializable
data class UnreadCountsDto(
    @JsonNames("total", "count")
    @Serializable(with = FlexibleIntSerializer::class)
    val all: Int = 0,
    @JsonNames("replys", "replies", "replyCount", "reply_count")
    @Serializable(with = FlexibleIntSerializer::class)
    val reply: Int = 0,
    @JsonNames("atMe", "at_me", "mention", "mentions", "atCount", "at_count")
    @Serializable(with = FlexibleIntSerializer::class)
    val at: Int = 0,
    @JsonNames("messages", "pm", "dm", "messageCount", "message_count", "msg", "msgCount")
    @Serializable(with = FlexibleIntSerializer::class)
    val message: Int = 0,
    @JsonNames("sys", "systemCount", "system_count")
    @Serializable(with = FlexibleIntSerializer::class)
    val system: Int = 0,
) {
    val effectiveAll: Int
        get() = maxOf(all, reply + at + message + system).coerceAtLeast(0)
}

@Serializable
data class CategoryDto(
    val key: String,
    @SerialName("cn_text")
    val cnText: String,
    @Serializable(with = WebsiteFlagSerializer::class)
    val customizable: Boolean? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val showInIndex: Boolean? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val adminOnly: Boolean? = null,
)

@Serializable
data class TopicAuthorDto(
    @JsonNames("uid", "member_id", "user_id", "id")
    @Serializable(with = FlexibleLongSerializer::class)
    val uid: Long = 0L,
    @JsonNames("name", "member_name", "username")
    val name: String = "",
    val avatar: String? = null,
) {
    val resolvedUid: Long
        get() = uid.takeIf { it > 0L }
            ?: extractUidFromPath(avatar)
            ?: 0L
}

@Serializable
data class RotateTopicDto(
    @Serializable(with = FlexibleLongSerializer::class)
    val postId: Long,
    val titleText: String = "",
    val category: String = "",
    val categoryWord: String? = null,
    val op: TopicAuthorDto? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val views: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val comments: Int = 0,
    @JsonNames("likeCount", "likes", "drumsticks")
    @Serializable(with = FlexibleIntSerializer::class)
    val likeCount: Int = 0,
    val lastCommentDate: String = "",
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val rank: Int? = null,
    @Serializable(with = WebsiteBoolSerializer::class)
    val blocked: Boolean = false,
)

@Serializable
data class PostDataDto(
    @Serializable(with = FlexibleLongSerializer::class)
    val postId: Long,
    @Serializable(with = FlexibleIntSerializer::class)
    val postPage: Int = 1,
    @Serializable(with = FlexibleIntSerializer::class)
    val postPageCount: Int = 1,
    val title: String = "",
    val category: String = "",
    val categoryWord: String = "",
    val categoryLink: String? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val views: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val collectionCount: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val rank: Int = 0,
    @Serializable(with = WebsiteFlagSerializer::class)
    val locked: Boolean? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val collected: Boolean? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val award: Boolean? = null,
    val op: TopicAuthorDto? = null,
    val comments: List<PostCommentDto> = emptyList(),
)

@Serializable
data class PostCommentDto(
    @Serializable(with = FlexibleLongSerializer::class)
    val commentId: Long,
    @Serializable(with = FlexibleIntSerializer::class)
    val floorIndex: Int,
    val poster: CommentPosterDto,
    val time: CommentTimeDto? = null,
    val markdown: String = "",
    val signature: String? = null,
    @SerialName("signature_markdown")
    @JsonNames("signature_markdown", "signatureMarkdown")
    val signatureMarkdown: String? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    val likeCount: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val dislikeCount: Int = 0,
    @Serializable(with = FlexibleIntSerializer::class)
    val upvoteCount: Int = 0,
    @Serializable(with = WebsiteFlagSerializer::class)
    val liked: Boolean? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val disliked: Boolean? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val upvoted: Boolean? = null,
    // Exact NodeSeek JSON spelling ("pined" with a single 'n')
    @SerialName("pined")
    @Serializable(with = WebsiteFlagSerializer::class)
    val pined: Boolean? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val hot: Boolean? = null,
    @Serializable(with = WebsiteBoolSerializer::class)
    val blocked: Boolean = false,
)

@Serializable
data class CommentPosterDto(
    @JsonNames("uid", "member_id", "user_id", "id")
    @Serializable(with = FlexibleLongSerializer::class)
    val uid: Long = 0L,
    @JsonNames("name", "member_name", "username")
    val name: String = "",
    val info: String? = null,
    val avatar: String? = null,
    val profile: String? = null,
    val signature: String? = null,
    @SerialName("signature_markdown")
    @JsonNames("signature_markdown", "signatureMarkdown")
    val signatureMarkdown: String? = null,
    @Serializable(with = WebsiteFlagSerializer::class)
    val isOp: Boolean? = null,
    @Serializable(with = RoleBadgeListSerializer::class)
    val roles: List<RoleBadgeDto> = emptyList(),
) {
    val resolvedUid: Long
        get() = uid.takeIf { it > 0L }
            ?: extractUidFromPath(profile)
            ?: extractUidFromPath(avatar)
            ?: 0L
}

private val POSTER_UID_PATH_REGEX = Regex("""/(?:space|avatar)/(\d+)""")

private fun extractUidFromPath(rawPath: String?): Long? {
    if (rawPath.isNullOrBlank()) return null
    return POSTER_UID_PATH_REGEX.find(rawPath)?.groupValues?.getOrNull(1)?.toLongOrNull()
}

@Serializable
data class CommentTimeDto(
    val createdDate: String = "",
    val createdDateFormated: String? = null,
    val editedDate: String? = null,
    val editedDateFormated: String? = null,
    val createdDateRel: String? = null,
    val editedDateRel: String? = null,
)

/**
 * Extracts and decodes `<script id="temp-script" type="application/json">` Base64 UTF-8 JSON
 * into [ConfigPayloadDto].
 */
object TempScriptParser {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = false
        encodeDefaults = true
        explicitNulls = false
    }

    fun extractFromHtml(
        html: String,
        baseUri: String = "https://www.nodeseek.com",
        requireAllCategory: Boolean = true,
    ): ConfigPayloadDto? {
        val document = Jsoup.parse(html, baseUri)
        return extractFromDocument(document, requireAllCategory = requireAllCategory)
    }

    fun extractFromDocument(
        document: Document,
        requireAllCategory: Boolean = true,
    ): ConfigPayloadDto? {
        val scriptElement = document.selectFirst("script#temp-script[type=application/json]")
            ?: document.selectFirst("script#temp-script")
            ?: return null
        val encoded = scriptElement.data().trim()
        if (encoded.isEmpty()) return null
        return decodeBase64Config(encoded, requireAllCategory = requireAllCategory)
    }

    fun decodeBase64Config(
        encodedBase64: String,
        requireAllCategory: Boolean = true,
    ): ConfigPayloadDto {
        val trimmed = encodedBase64.trim()
        if (trimmed.isEmpty()) {
            throw SerializationException("Empty temp-script Base64 payload")
        }
        val decodedBytes = try {
            Base64.getMimeDecoder().decode(trimmed)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Malformed Base64 in temp-script", e)
        }
        val utf8Json = String(decodedBytes, Charsets.UTF_8)
        val config = json.decodeFromString(ConfigPayloadDto.serializer(), utf8Json)
        if (requireAllCategory && config.allCategory == null) {
            throw SerializationException("官网数据格式发生变化: missing allCategory in __config__")
        }
        return config
    }
}
