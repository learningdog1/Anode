package com.nodeseek.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class FeedSort(val queryValue: String, val displayLabel: String) {
    LATEST_REPLY("replyTime", "新评论"),
    NEWEST_TOPIC("postTime", "新帖子"),
    CURATED("award", "推荐阅读"),
    HOT("hot", "热门");

    companion object {
        /** Compatibility alias for [NEWEST_TOPIC]. */
        val LATEST_TOPIC: FeedSort get() = NEWEST_TOPIC

        fun fromQueryValue(value: String): FeedSort =
            entries.firstOrNull { it.queryValue.equals(value, ignoreCase = true) } ?: LATEST_REPLY
    }
}

@Serializable
data class Category(
    val slug: String,
    val name: String,
    val isCustomizable: Boolean = true,
    val visibleOnHome: Boolean = true,
    val isAdminOnly: Boolean = false,
    val topicCoinCost: Int = if (slug == "inside") 5 else 0,
    val description: String = defaultDescriptionFor(slug),
    val supportsRss: Boolean = slug != "inside",
) {
    companion object {
        fun defaultDescriptionFor(slug: String): String = when (slug) {
            "" -> "聚合全站可见频道的最新技术讨论与社区动态"
            "daily" -> "社区日常闲聊、数码见闻与水贴交流"
            "tech" -> "VPS 线路优化、Linux 运维、Docker 与自建服务"
            "info" -> "主机补货监控、促销折扣与第一手业界线报"
            "review" -> "YABS 硬件跑分、三网回程 MTR 与流媒体解锁实测"
            "trade" -> "二手闲置服务器、域名与数字资产流转交易"
            "carpool" -> "流媒体家庭组、专线流量与合租车队招募"
            "promotion" -> "主机商官方发布、AFF 推介与新商家自荐"
            "life" -> "职场感悟、美食摄影、装机桌面与三次元生活"
            "dev" -> "开源项目 Show HN、脚本工具、前后端与移动开发"
            "photo-share" -> "壁纸插画、摄影作品与趣味图集分享"
            "expose" -> "跑路预警、交易纠纷仲裁与不良商家避坑指南"
            "inside" -> "高阶会员专属讨论区（需登录并满足等级权限）"
            "meaningless" -> "无意义灌水与轻松杂谈专区"
            "sandbox" -> "Markdown 排版语法与机器人测试沙盒"
            else -> "NodeSeek 社区讨论频道"
        }

        val DEFAULT_CATEGORIES: List<Category> = listOf(
            Category(slug = "", name = "全部", isCustomizable = false, supportsRss = true),
            Category(slug = "daily", name = "日常"),
            Category(slug = "tech", name = "技术"),
            Category(slug = "info", name = "情报"),
            Category(slug = "review", name = "测评"),
            Category(slug = "trade", name = "交易"),
            Category(slug = "carpool", name = "拼车"),
            Category(slug = "promotion", name = "推广"),
            Category(slug = "life", name = "生活"),
            Category(slug = "dev", name = "Dev"),
            Category(slug = "photo-share", name = "贴图"),
            Category(slug = "expose", name = "曝光"),
            Category(slug = "inside", name = "内版", topicCoinCost = 5, supportsRss = false),
            Category(slug = "meaningless", name = "无意义"),
            Category(slug = "sandbox", name = "沙盒"),
        )
    }
}

@Serializable
data class TopicSummary(
    val id: Long,
    val title: String,
    val categorySlug: String,
    val categoryName: String,
    val authorId: Long? = null,
    val authorName: String,
    val authorAvatarUrl: String = "",
    val viewCount: Int = 0,
    val replyCount: Int = 0,
    val lastActivityIso: String = "",
    val isPinned: Boolean = false,
    val requiredViewLevel: Int? = null,
    val isRead: Boolean = false,
    val lastReadFloor: Int? = null,
    val isLocked: Boolean = false,
    val isBookmarked: Boolean = false,
    val authorLevel: Int? = null,
    val lastReplierName: String? = null,
) {
    val isHot: Boolean
        get() = replyCount >= 30 || viewCount >= 1500
}

@Serializable
data class FeedPageResult(
    val pinnedTopics: List<TopicSummary> = emptyList(),
    val topics: List<TopicSummary>,
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val isFromRssFallback: Boolean = false,
)

@Serializable
data class AuthorBadge(
    val code: String,
    val label: String,
)

typealias RoleBadge = AuthorBadge

@Serializable
data class FloorAuthor(
    val uid: Long,
    val username: String,
    val avatarUrl: String = "",
    val isTopicAuthor: Boolean = false,
    val roleBadges: List<AuthorBadge> = emptyList(),
    val level: Int? = null,
) {
    val badges: List<AuthorBadge>
        get() = roleBadges
}

@Serializable
data class FloorComment(
    val commentId: Long,
    val floorNumber: Int,
    val author: FloorAuthor,
    val createdAtIso: String,
    val editedAtIso: String? = null,
    val markdownBody: String,
    val signatureMarkdown: String? = null,
    val upvoteCount: Int = 0,
    val drumstickCount: Int = 0,
    val dislikeCount: Int = 0,
    val hasUpvoted: Boolean = false,
    val hasGivenDrumstick: Boolean = false,
    val hasDisliked: Boolean = false,
    val isPinned: Boolean = false,
    val isHot: Boolean = false,
    val isBlocked: Boolean = false,
)

@Serializable
data class TopicDetailPage(
    val topicId: Long,
    val title: String,
    val categorySlug: String,
    val categoryName: String,
    val author: FloorAuthor,
    val viewCount: Int = 0,
    val bookmarkCount: Int = 0,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val requiredViewLevel: Int = 0,
    val isLocked: Boolean = false,
    val isBookmarked: Boolean? = null,
    val isCuratedAward: Boolean = false,
    val openingFloor: FloorComment? = null,
    val replies: List<FloorComment> = emptyList(),
    val createdAtIso: String? = null,
    val isOfflineSnapshot: Boolean = false,
) {
    val estimatedTotalFloors: Int
        get() = when {
            currentPage >= totalPages && replies.isNotEmpty() -> replies.maxOf { it.floorNumber }
            totalPages <= 1 -> replies.size
            else -> totalPages * 10
        }

    companion object {
        fun pageForFloor(floorNumber: Int): Int =
            if (floorNumber <= 10) 1 else ((floorNumber - 1) / 10) + 1
    }
}

sealed class TopicAccessException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    data object SignInRequired : TopicAccessException("需要登录 NodeSeek 账号后才能访问该内容") {
        private fun readResolve(): Any = SignInRequired
        override fun fillInStackTrace(): Throwable = this
        operator fun invoke(): SignInRequired = this
    }

    data object PrivateTopic : TopicAccessException("该主题已被设为私有或仅特定成员可见") {
        private fun readResolve(): Any = PrivateTopic
        override fun fillInStackTrace(): Throwable = this
        operator fun invoke(): PrivateTopic = this
    }

    data class LevelRequired(
        val minimumLevel: Int? = null,
        override val message: String = if (minimumLevel != null) {
            "需要达到 Lv.$minimumLevel 才能查看此主题"
        } else {
            "当前账号等级不足以查看此主题"
        },
    ) : TopicAccessException(message)

    data object CloudflareChallengeRequired : TopicAccessException("需要完成浏览器安全验证后继续访问") {
        private fun readResolve(): Any = CloudflareChallengeRequired
        override fun fillInStackTrace(): Throwable = this
        operator fun invoke(): CloudflareChallengeRequired = this
    }
}
