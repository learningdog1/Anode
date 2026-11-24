package com.nodeseek.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.pulsePressScale

private const val NODESEEK_ORIGIN = "https://www.nodeseek.com"

val LocalPreviewAvatarResResolver = staticCompositionLocalOf<((String) -> Int?)?> { null }

private data class MonogramTint(
    val lightStart: Color,
    val lightEnd: Color,
    val lightFg: Color,
    val darkStart: Color,
    val darkEnd: Color,
    val darkFg: Color,
)

private val AvatarMonogramTints = listOf(
    MonogramTint(
        lightStart = Color(0xFFDDE5FF),
        lightEnd = Color(0xFFC7D5FE),
        lightFg = Color(0xFF1E3A8A),
        darkStart = Color(0xFF1E2B58),
        darkEnd = Color(0xFF151E40),
        darkFg = Color(0xFFA5B4FC),
    ),
    MonogramTint(
        lightStart = Color(0xFFD1F5EE),
        lightEnd = Color(0xFFB5ECE2),
        lightFg = Color(0xFF065F5B),
        darkStart = Color(0xFF113836),
        darkEnd = Color(0xFF0B2625),
        darkFg = Color(0xFF5EEAD4),
    ),
    MonogramTint(
        lightStart = Color(0xFFEDE2FF),
        lightEnd = Color(0xFFDEC9FE),
        lightFg = Color(0xFF5B21B6),
        darkStart = Color(0xFF2B1C4D),
        darkEnd = Color(0xFF1E1336),
        darkFg = Color(0xFFD8B4FE),
    ),
    MonogramTint(
        lightStart = Color(0xFFFFEBD6),
        lightEnd = Color(0xFFFCD8B6),
        lightFg = Color(0xFF9A3412),
        darkStart = Color(0xFF3A2212),
        darkEnd = Color(0xFF28160C),
        darkFg = Color(0xFFFDBA74),
    ),
    MonogramTint(
        lightStart = Color(0xFFD8F3DC),
        lightEnd = Color(0xFFBBE7C4),
        lightFg = Color(0xFF14532D),
        darkStart = Color(0xFF143521),
        darkEnd = Color(0xFF0D2416),
        darkFg = Color(0xFF86EFAC),
    ),
    MonogramTint(
        lightStart = Color(0xFFFFE0E9),
        lightEnd = Color(0xFFFCC6D8),
        lightFg = Color(0xFF9D174D),
        darkStart = Color(0xFF3F1629),
        darkEnd = Color(0xFF2B0E1C),
        darkFg = Color(0xFFF9A8D4),
    ),
)

/**
 * Normalizes NodeSeek avatar URLs (relative `/avatar/101.png` or `http://`) to HTTPS.
 */
fun normalizeNodeSeekAssetUrl(rawUrl: String?): String? {
    val trimmed = rawUrl?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase(java.util.Locale.US)
    return when {
        lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("android.resource:") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("vbscript:") ||
            lower.startsWith("data:") -> null
        lower.startsWith("https://") -> "https://" + trimmed.drop(8)
        lower.startsWith("http://") -> "https://" + trimmed.drop(7)
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/") -> "$NODESEEK_ORIGIN$trimmed"
        else -> "$NODESEEK_ORIGIN/$trimmed"
    }
}

/**
 * Anode Avatar with Lv.0–Lv.6 metallic/gemstone gradient ring, Coil 3 AsyncImage,
 * deterministic tonal monogram fallback, press micro-scale, and gradient "楼主" OP pill.
 */
@Composable
fun PulseAvatar(
    avatarUrl: String?,
    username: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    rankLevel: Int? = null,
    ringWidth: Dp = 1.75.dp,
    showOpBadge: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val previewDrawableRes = LocalPreviewAvatarResResolver.current?.invoke(username)
    val resolvedUrl = remember(avatarUrl) { normalizeNodeSeekAssetUrl(avatarUrl) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = NodePulseThemeTokens.isDark
    val sizePx = remember(size, density) { with(density) { size.roundToPx().coerceAtLeast(32) } }
    val imageRequest = remember(context, resolvedUrl, sizePx) {
        resolvedUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .size(sizePx)
                .crossfade(false)
                .build()
        }
    }
    var imageLoadedSuccessfully by remember(resolvedUrl) { mutableStateOf(false) }

    val rankSpec = rankLevel?.let { NodePulseThemeTokens.rankSpec(it) }
    val ringBrush = if (rankSpec != null && ringWidth > 0.dp) {
        rankSpec.ringBrush
    } else {
        SolidColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    }

    val monogram = remember(username) {
        username.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "N"
    }
    val monogramTint = remember(username) {
        val hash = (username.trim().hashCode() and 0x7FFFFFFF)
        AvatarMonogramTints[hash % AvatarMonogramTints.size]
    }
    val fallbackBrush = remember(monogramTint, isDark) {
        Brush.linearGradient(
            colors = if (isDark) {
                listOf(monogramTint.darkStart, monogramTint.darkEnd)
            } else {
                listOf(monogramTint.lightStart, monogramTint.lightEnd)
            },
        )
    }
    val fallbackTextColor = if (isDark) monogramTint.darkFg else monogramTint.lightFg

    val interactionSource = remember { MutableInteractionSource() }
    val interactiveModifier = if (onClick != null) {
        Modifier
            .pulsePressScale(interactionSource = interactionSource, pressedScale = 0.92f)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "查看 $username 的主页",
                onClick = onClick,
            )
    } else {
        Modifier
    }

    val haloGlowModifier = if (!isDark && rankSpec != null && rankSpec.level >= 4 && size >= 44.dp) {
        Modifier.shadow(
            elevation = 4.dp,
            shape = CircleShape,
            clip = false,
            ambientColor = rankSpec.glowColor.copy(alpha = 0.45f),
            spotColor = rankSpec.glowColor.copy(alpha = 0.45f),
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(haloGlowModifier)
                .then(interactiveModifier)
                .clip(CircleShape)
                .then(
                    if (ringWidth > 0.dp) {
                        val ringGap = if (size < 22.dp) 0.75.dp else 1.1.dp
                        Modifier
                            .border(width = ringWidth, brush = ringBrush, shape = CircleShape)
                            .padding(ringWidth + ringGap)
                            .clip(CircleShape)
                    } else {
                        Modifier
                    }
                )
                .background(
                    if (previewDrawableRes != null) {
                        SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        fallbackBrush
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (previewDrawableRes != null) {
                Image(
                    painter = painterResource(id = previewDrawableRes),
                    contentDescription = "$username 头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                if (!imageLoadedSuccessfully) {
                    Text(
                        text = monogram,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = (size.value * 0.40f).coerceAtLeast(8.5f).sp,
                        ),
                        color = fallbackTextColor,
                    )
                }

                if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "$username 头像",
                        contentScale = ContentScale.Crop,
                        onSuccess = { imageLoadedSuccessfully = true },
                        onError = { imageLoadedSuccessfully = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (showOpBadge) {
            val fontScale = NodePulseThemeTokens.fontScale
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 4.dp)
                    .shadow(elevation = if (isDark) 0.dp else 2.dp, shape = RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(PulseBrushes.brandGradient())
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "楼主",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (9f * fontScale).sp,
                        lineHeight = (11f * fontScale).sp,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
