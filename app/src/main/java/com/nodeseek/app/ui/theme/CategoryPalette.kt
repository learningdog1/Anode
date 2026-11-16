package com.nodeseek.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Semantic palette, rounded iconography, and editorial descriptions for all 15 NodeSeek boards.
 */
@Immutable
data class CategoryVisualSpec(
    val slug: String,
    val defaultTitle: String,
    val editorialDescription: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val spineColor: Color,
    val isFeaturedHero: Boolean = false,
    val isFeaturedMedium: Boolean = false,
    val requiresWebAuth: Boolean = false,
    val postCoinCost: Int = 0,
    val gradientEndColor: Color = spineColor,
) {
    val heroBrush: Brush
        get() = Brush.linearGradient(colors = listOf(spineColor, gradientEndColor))

    val softTonalBrush: Brush
        get() = Brush.linearGradient(
            colors = listOf(
                containerColor,
                blendColor(containerColor, gradientEndColor, 0.20f),
            )
        )

    fun harmonizeWith(primary: Color, fraction: Float = 0.12f): CategoryVisualSpec = copy(
        containerColor = blendColor(containerColor, primary, fraction),
        contentColor = blendColor(contentColor, primary, fraction * 0.6f),
        spineColor = blendColor(spineColor, primary, fraction),
        gradientEndColor = blendColor(gradientEndColor, primary, fraction),
    )
}

private data class RawBoardSpec(
    val slug: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val lightContainer: Color,
    val lightContent: Color,
    val darkContainer: Color,
    val darkContent: Color,
    val spine: Color,
    val gradientEnd: Color = spine,
    val isHero: Boolean = false,
    val isMedium: Boolean = false,
    val requiresWebAuth: Boolean = false,
    val postCoinCost: Int = 0,
)

private val AllBoardSpecs = listOf(
    RawBoardSpec(
        slug = "",
        title = "全部",
        description = "聚合全站各版块最新帖子与讨论",
        icon = Icons.Rounded.GridView,
        lightContainer = Color(0xFFDDE3FF),
        lightContent = Color(0xFF0B1D6E),
        darkContainer = Color(0xFF213C9E),
        darkContent = Color(0xFFDDE3FF),
        spine = Color(0xFF3655D6),
        gradientEnd = Color(0xFF0EA5E9),
    ),
    RawBoardSpec(
        slug = "daily",
        title = "日常",
        description = "社区日常闲聊、数码见闻与水贴交流",
        icon = Icons.Rounded.LocalCafe,
        lightContainer = Color(0xFFF7E8DC),
        lightContent = Color(0xFF7C3A12),
        darkContainer = Color(0xFF342015),
        darkContent = Color(0xFFFDBA8C),
        spine = Color(0xFFD97736),
        gradientEnd = Color(0xFFF59E0B),
        isMedium = true,
    ),
    RawBoardSpec(
        slug = "tech",
        title = "技术",
        description = "VPS 线路优化、Linux 运维、Docker 与自建服务实战",
        icon = Icons.Rounded.Memory,
        lightContainer = Color(0xFFDCE6FF),
        lightContent = Color(0xFF163B9C),
        darkContainer = Color(0xFF162754),
        darkContent = Color(0xFF93B4FF),
        spine = Color(0xFF3B66F5),
        gradientEnd = Color(0xFF06B6D4),
        isHero = true,
    ),
    RawBoardSpec(
        slug = "info",
        title = "情报",
        description = "主机补货监控、促销折扣与第一手业界线报",
        icon = Icons.Rounded.Radar,
        lightContainer = Color(0xFFEAE0FF),
        lightContent = Color(0xFF4E229E),
        darkContainer = Color(0xFF28194D),
        darkContent = Color(0xFFCBB2FF),
        spine = Color(0xFF7C3AED),
        gradientEnd = Color(0xFFEC4899),
        isMedium = true,
    ),
    RawBoardSpec(
        slug = "review",
        title = "测评",
        description = "YABS 硬件跑分、三网回程 MTR 与流媒体解锁实测",
        icon = Icons.Rounded.Speed,
        lightContainer = Color(0xFFD6F3F8),
        lightContent = Color(0xFF095266),
        darkContainer = Color(0xFF0F313C),
        darkContent = Color(0xFF7CE2F6),
        spine = Color(0xFF0891B2),
        gradientEnd = Color(0xFF38BDF8),
        isMedium = true,
    ),
    RawBoardSpec(
        slug = "trade",
        title = "交易",
        description = "二手闲置服务器、域名与数字资产流转交易",
        icon = Icons.Rounded.SwapHoriz,
        lightContainer = Color(0xFFD7F5E6),
        lightContent = Color(0xFF0B5C3B),
        darkContainer = Color(0xFF103526),
        darkContent = Color(0xFF6EE7B7),
        spine = Color(0xFF10B981),
        gradientEnd = Color(0xFF0EA5E9),
        isMedium = true,
    ),
    RawBoardSpec(
        slug = "carpool",
        title = "拼车",
        description = "流媒体家庭组、专线流量与合租车队招募",
        icon = Icons.Rounded.Groups,
        lightContainer = Color(0xFFD5F2EE),
        lightContent = Color(0xFF0D5652),
        darkContainer = Color(0xFF103332),
        darkContent = Color(0xFF5EEAD4),
        spine = Color(0xFF14B8A6),
        gradientEnd = Color(0xFF34D399),
    ),
    RawBoardSpec(
        slug = "promotion",
        title = "推广",
        description = "主机商官方发布、AFF 推介与新商家自荐",
        icon = Icons.Rounded.Campaign,
        lightContainer = Color(0xFFFFE0EC),
        lightContent = Color(0xFF8F1D4E),
        darkContainer = Color(0xFF3D1528),
        darkContent = Color(0xFFF9A8D4),
        spine = Color(0xFFEC4899),
        gradientEnd = Color(0xFFF43F5E),
    ),
    RawBoardSpec(
        slug = "life",
        title = "生活",
        description = "职场感悟、美食摄影、装机桌面与三次元生活",
        icon = Icons.Rounded.Spa,
        lightContainer = Color(0xFFE3F2D5),
        lightContent = Color(0xFF335916),
        darkContainer = Color(0xFF1E3114),
        darkContent = Color(0xFFBEF264),
        spine = Color(0xFF65A30D),
        gradientEnd = Color(0xFF10B981),
    ),
    RawBoardSpec(
        slug = "dev",
        title = "Dev",
        description = "开源项目、脚本工具、前后端与移动应用开发",
        icon = Icons.Rounded.Terminal,
        lightContainer = Color(0xFFE0E5FF),
        lightContent = Color(0xFF27308A),
        darkContainer = Color(0xFF1B204E),
        darkContent = Color(0xFFA5B4FC),
        spine = Color(0xFF6366F1),
        gradientEnd = Color(0xFF8B5CF6),
    ),
    RawBoardSpec(
        slug = "photo-share",
        title = "贴图",
        description = "壁纸插画、摄影作品与趣味图集分享",
        icon = Icons.Rounded.PhotoLibrary,
        lightContainer = Color(0xFFFFE4DC),
        lightContent = Color(0xFF8A2C14),
        darkContainer = Color(0xFF3B1C15),
        darkContent = Color(0xFFFDA4AF),
        spine = Color(0xFFF43F5E),
        gradientEnd = Color(0xFFFB923C),
    ),
    RawBoardSpec(
        slug = "expose",
        title = "曝光",
        description = "跑路预警、交易纠纷仲裁与不良商家避坑指南",
        icon = Icons.Rounded.ReportProblem,
        lightContainer = Color(0xFFFFDAD8),
        lightContent = Color(0xFF930014),
        darkContainer = Color(0xFF441217),
        darkContent = Color(0xFFFCA5A5),
        spine = Color(0xFFEF4444),
        gradientEnd = Color(0xFFF97316),
    ),
    RawBoardSpec(
        slug = "inside",
        title = "内版",
        description = "高阶会员专属讨论区（需登录会话，发帖消耗 5 鸡腿）",
        icon = Icons.Rounded.Lock,
        lightContainer = Color(0xFFF5E9C8),
        lightContent = Color(0xFF6B4C08),
        darkContainer = Color(0xFF332710),
        darkContent = Color(0xFFFCD34D),
        spine = Color(0xFFD97706),
        gradientEnd = Color(0xFFFBBF24),
        requiresWebAuth = true,
        postCoinCost = 5,
    ),
    RawBoardSpec(
        slug = "meaningless",
        title = "无意义",
        description = "轻松灌水、脑洞分享与日常杂谈角落",
        icon = Icons.Rounded.ChatBubbleOutline,
        lightContainer = Color(0xFFECE6F8),
        lightContent = Color(0xFF4E3D73),
        darkContainer = Color(0xFF272038),
        darkContent = Color(0xFFD3C4F3),
        spine = Color(0xFF8B5CF6),
        gradientEnd = Color(0xFFA78BFA),
    ),
    RawBoardSpec(
        slug = "sandbox",
        title = "沙盒",
        description = "Markdown 排版实验、脚本与投票组件测试场",
        icon = Icons.Rounded.ViewInAr,
        lightContainer = Color(0xFFE6E4EC),
        lightContent = Color(0xFF454352),
        darkContainer = Color(0xFF24242E),
        darkContent = Color(0xFFC4C2D0),
        spine = Color(0xFF71717A),
        gradientEnd = Color(0xFF94A3B8),
    ),
)

object CategoryPalette {
    val defaultOrderedSlugs: List<String> = AllBoardSpecs.map { it.slug }

    fun allSpecs(
        isDark: Boolean,
        dynamicPrimaryToHarmonize: Color? = null,
    ): List<CategoryVisualSpec> = AllBoardSpecs.map { raw ->
        raw.toVisualSpec(isDark, dynamicPrimaryToHarmonize)
    }

    fun resolve(
        slug: String,
        isDark: Boolean,
        dynamicPrimaryToHarmonize: Color? = null,
    ): CategoryVisualSpec {
        val normalized = slug.trim().lowercase()
        val raw = AllBoardSpecs.firstOrNull { it.slug == normalized }
            ?: AllBoardSpecs.last() // fallback to sandbox neutral graphite
        return raw.toVisualSpec(isDark, dynamicPrimaryToHarmonize)
    }

    private fun RawBoardSpec.toVisualSpec(
        isDark: Boolean,
        dynamicPrimaryToHarmonize: Color?,
    ): CategoryVisualSpec {
        val base = CategoryVisualSpec(
            slug = slug,
            defaultTitle = title,
            editorialDescription = description,
            icon = icon,
            containerColor = if (isDark) darkContainer else lightContainer,
            contentColor = if (isDark) darkContent else lightContent,
            spineColor = spine,
            isFeaturedHero = isHero,
            isFeaturedMedium = isMedium,
            requiresWebAuth = requiresWebAuth,
            postCoinCost = postCoinCost,
            gradientEndColor = gradientEnd,
        )
        return if (dynamicPrimaryToHarmonize != null) {
            base.harmonizeWith(dynamicPrimaryToHarmonize, fraction = 0.12f)
        } else {
            base
        }
    }
}
