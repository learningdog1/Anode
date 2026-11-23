package com.nodeseek.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nodeseek.app.data.activity.ActivityMonitorHost
import com.nodeseek.app.data.overseas.OverseasPromoMonitorHost
import com.nodeseek.app.data.radar.AppContainerHost
import com.nodeseek.app.data.restock.RestockMonitorHost
import com.nodeseek.app.di.DefaultAppContainer
import com.nodeseek.app.domain.activity.ActivityMonitorController
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorController
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.ui.components.WebmStickerDecoder

class NodePulseApp :
    Application(),
    SingletonImageLoader.Factory,
    AppContainerHost,
    RestockMonitorHost,
    OverseasPromoMonitorHost,
    ActivityMonitorHost {

    override lateinit var container: DefaultAppContainer
        private set

    override val restockMonitorController: RestockMonitorController
        get() = container.restockMonitorController

    override val overseasPromoMonitorController: OverseasPromoMonitorController
        get() = container.overseasPromoMonitorController

    override val activityMonitorController: ActivityMonitorController
        get() = container.activityMonitorController

    private var startedActivityCount: Int = 0
    private var isActivityChangingConfigurations: Boolean = false

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        container.notificationDispatcher.ensureChannels()
        container.startBackgroundRadarScheduling()
        container.startBackgroundFeatureSchedulers()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) {
                    val transitioningFromConfigChange = isActivityChangingConfigurations
                    isActivityChangingConfigurations = false
                    startedActivityCount += 1
                    if (startedActivityCount > 0 && !transitioningFromConfigChange) {
                        container.onAppEnteredForeground()
                        container.radarMonitorController.onForegroundChanged(startedActivityCount > 0)
                        container.restockMonitorController.onForegroundChanged(startedActivityCount > 0)
                        container.overseasPromoMonitorController.onForegroundChanged(startedActivityCount > 0)
                        container.activityMonitorController.onForegroundChanged(startedActivityCount > 0)
                    }
                }

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    isActivityChangingConfigurations = activity.isChangingConfigurations
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    if (startedActivityCount == 0 && !isActivityChangingConfigurations) {
                        container.radarMonitorController.onForegroundChanged(false)
                        container.onAppEnteredBackground()
                        if (activity is MainActivity && activity.isFinishing) {
                            container.onAppClosed()
                        }
                        container.restockMonitorController.onForegroundChanged(false)
                        container.overseasPromoMonitorController.onForegroundChanged(false)
                        container.overseasPromoWorkScheduler.onAppEnteredBackground(
                            container.overseasPromoMonitorController.settingsFlow.value,
                        )
                        container.activityMonitorController.onForegroundChanged(false)
                        container.activityWorkScheduler.onAppEnteredBackground(
                            container.activityMonitorController.settingsFlow.value,
                        )
                    }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity is MainActivity &&
                        !activity.isChangingConfigurations &&
                        startedActivityCount == 0
                    ) {
                        container.onAppClosed()
                    }
                }
            },
        )
        container.radarMonitorController.onForegroundChanged(startedActivityCount > 0)
        container.radarMonitorController.start()
        container.restockMonitorController.onForegroundChanged(startedActivityCount > 0)
        container.restockMonitorController.start()
        container.overseasPromoMonitorController.onForegroundChanged(startedActivityCount > 0)
        container.overseasPromoMonitorController.start()
        container.activityMonitorController.onForegroundChanged(startedActivityCount > 0)
        container.activityMonitorController.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { container.coilOkHttpClient },
                    ),
                )
                add(SvgDecoder.Factory())
                add(WebmStickerDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024L * 1024L)
                    .build()
            }
            .crossfade(120)
            .build()
    }
}
