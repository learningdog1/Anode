package com.nodeseek.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nodeseek.app.MainActivity
import com.nodeseek.app.data.radar.AppContainerHost
import com.nodeseek.app.domain.widget.DailyPulseWidgetSnapshot
import com.nodeseek.app.navigation.ShortcutIntentParser
import kotlinx.coroutines.CancellationException

private val KEY_WIDGET_CHECKIN_HINT = stringPreferencesKey("widget_checkin_hint")

class DailyPulseGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyPulseGlanceWidget()
}

class DailyPulseGlanceWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = runCatching {
            val container = (context.applicationContext as? AppContainerHost)?.container
            container?.widgetSnapshotRepository?.loadDailyPulseSnapshot()
        }.getOrNull() ?: DailyPulseWidgetSnapshot()

        val openCheckInBoardIntent = buildMainActivityIntent(
            context = context,
            actionName = ShortcutIntentParser.ACTION_WIDGET_OPEN_CHECKIN_BOARD,
        )
        val openRadarIntent = buildMainActivityIntent(
            context = context,
            actionName = ShortcutIntentParser.ACTION_WIDGET_OPEN_RADAR,
        )
        val openSeekMailIntent = buildMainActivityIntent(
            context = context,
            actionName = ShortcutIntentParser.ACTION_WIDGET_OPEN_SEEK_MAIL,
        )
        val copyOtpIntent = buildMainActivityIntent(
            context = context,
            actionName = ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP,
        )

        provideContent {
            val prefs = currentState<Preferences>()
            val checkInFailureHint = prefs[KEY_WIDGET_CHECKIN_HINT]?.takeIf { it.isNotBlank() }
            GlanceTheme(colors = AnodeGlanceTheme.colors) {
                DailyPulseWidgetContent(
                    snapshot = snapshot,
                    checkInFailureHint = checkInFailureHint,
                    openCheckInBoardIntent = openCheckInBoardIntent,
                    openRadarIntent = openRadarIntent,
                    openSeekMailIntent = openSeekMailIntent,
                    copyOtpIntent = copyOtpIntent,
                )
            }
        }
    }

    private fun buildMainActivityIntent(
        context: Context,
        actionName: String,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        action = actionName
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
}

class CheckInActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val container = (context.applicationContext as? AppContainerHost)?.container ?: return
        if (container.sessionRepository.sessionState.value == null) {
            try {
                container.sessionRepository.refreshSession()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Handled below via null session check.
            }
        }
        val preferRandom = container.settingsRepository.awaitLoadedSettings().preferRandomCheckIn
        val failureHint: String? = if (container.sessionRepository.sessionState.value == null) {
            "登录状态已失效，请点击去登录"
        } else {
            try {
                val result = container.checkInRepository.checkIn(randomReward = preferRandom)
                if (result.isSuccess) {
                    null
                } else {
                    result.exceptionOrNull()?.message?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "签到失败，点击打开客户端重试"
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                t.message?.trim()?.takeIf { it.isNotEmpty() } ?: "签到失败，点击打开客户端重试"
            }
        }
        runCatching {
            updateAppWidgetState(context, glanceId) { prefs ->
                if (failureHint == null) {
                    prefs.remove(KEY_WIDGET_CHECKIN_HINT)
                } else {
                    prefs[KEY_WIDGET_CHECKIN_HINT] = failureHint
                }
            }
        }
        GlanceWidgetUpdater.updateAll(context)
    }
}

@Composable
private fun DailyPulseWidgetContent(
    snapshot: DailyPulseWidgetSnapshot,
    checkInFailureHint: String?,
    openCheckInBoardIntent: Intent,
    openRadarIntent: Intent,
    openSeekMailIntent: Intent,
    copyOtpIntent: Intent,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(AnodeGlanceTokens.cardSurface)
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Top row: Account + Daily check-in action
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity(openCheckInBoardIntent)),
            ) {
                val titleText = if (snapshot.isSignedIn) {
                    val name = snapshot.username.ifBlank { "NodeSeek" }
                    "$name · 🍗 ${snapshot.drumsticks}"
                } else {
                    "NodeSeek 每日脉冲"
                }
                Text(
                    text = titleText,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                val activeFailureHint = if (!snapshot.hasCheckedInToday) checkInFailureHint else null
                val subtitleText = when {
                    activeFailureHint != null -> activeFailureHint
                    !snapshot.isSignedIn -> "点击打开客户端登录"
                    snapshot.hasCheckedInToday -> buildString {
                        append("今日已签到")
                        snapshot.todayGain?.let { append(" · +$it 鸡腿") }
                        snapshot.todayOrder?.let { append(" · #$it") }
                    }
                    else -> {
                        val modeLabel = if (snapshot.preferRandomCheckIn) "随机手气" else "固定 +5"
                        "今日待签到 · $modeLabel"
                    }
                }
                Text(
                    text = subtitleText,
                    style = TextStyle(
                        color = if (activeFailureHint != null) {
                            AnodeGlanceTokens.warningFg
                        } else {
                            AnodeGlanceTokens.mutedText
                        },
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            when {
                !snapshot.isSignedIn -> {
                    Box(
                        modifier = GlanceModifier
                            .background(AnodeGlanceTokens.pillSurface)
                            .cornerRadius(12.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable(actionStartActivity(openCheckInBoardIntent)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "去登录",
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                snapshot.hasCheckedInToday -> {
                    val checkedLabel = snapshot.todayGain?.let { "已签 +$it" } ?: "已签"
                    Box(
                        modifier = GlanceModifier
                            .background(AnodeGlanceTokens.onlineBg)
                            .cornerRadius(12.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable(actionStartActivity(openCheckInBoardIntent)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = checkedLabel,
                            style = TextStyle(
                                color = AnodeGlanceTokens.onlineFg,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                else -> {
                    Box(
                        modifier = GlanceModifier
                            .background(AnodeGlanceTokens.drumstickBg)
                            .cornerRadius(12.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable(actionRunCallback<CheckInActionCallback>()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "签到",
                            style = TextStyle(
                                color = AnodeGlanceTokens.drumstickFg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(modifier = GlanceModifier.height(10.dp))

        // Middle row: Radar pulse unread count & latest hit
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(AnodeGlanceTokens.pillSurface)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .clickable(actionStartActivity(openRadarIntent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (badgeBg, badgeFg, badgeText) = when {
                snapshot.unreadAlertCount > 0 -> Triple(
                    AnodeGlanceTokens.warningBg,
                    AnodeGlanceTokens.warningFg,
                    "雷达 ${snapshot.unreadAlertCount}",
                )
                snapshot.radarEnabled -> Triple(
                    AnodeGlanceTokens.accentBg,
                    AnodeGlanceTokens.accentFg,
                    "雷达监控中",
                )
                else -> Triple(
                    AnodeGlanceTokens.cardSurface,
                    AnodeGlanceTokens.mutedText,
                    "雷达已暂停",
                )
            }
            Box(
                modifier = GlanceModifier
                    .background(badgeBg)
                    .cornerRadius(8.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badgeText,
                    style = TextStyle(
                        color = badgeFg,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            val radarHeadline = when {
                !snapshot.latestAlertTitle.isNullOrBlank() -> {
                    val kw = snapshot.latestAlertKeyword?.takeIf { it.isNotBlank() }
                    if (kw != null) "[$kw] ${snapshot.latestAlertTitle}" else snapshot.latestAlertTitle
                }
                snapshot.radarEnabled -> "暂无未读命中"
                else -> "点击管理雷达规则"
            }
            Text(
                text = radarHeadline,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        // Bottom row: Seek Mail latest OTP chip
        val hasOtp = !snapshot.latestOtpCode.isNullOrBlank()
        val otpAction = actionStartActivity(
            if (hasOtp) copyOtpIntent else openSeekMailIntent,
        )
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(if (hasOtp) AnodeGlanceTokens.accentBg else AnodeGlanceTokens.pillSurface)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .clickable(otpAction),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasOtp) {
                Text(
                    text = "验证码 ${snapshot.latestOtpCode}",
                    style = TextStyle(
                        color = AnodeGlanceTokens.accentFg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = snapshot.latestOtpSender.orEmpty(),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = AnodeGlanceTokens.accentFg,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = "复制",
                    style = TextStyle(
                        color = AnodeGlanceTokens.accentFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            } else {
                Text(
                    text = if (snapshot.hasSeekMailKey) {
                        "Seek 邮箱 · 暂无缓存验证码"
                    } else {
                        "Seek 邮箱 · 点击配置 API Key"
                    },
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = AnodeGlanceTokens.mutedText,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
