package com.nodeseek.app.domain.radar

import kotlinx.coroutines.flow.StateFlow

interface RadarMonitorController {
    val runtimeStatus: StateFlow<RadarRuntimeStatus>

    fun start()
    fun stop()
    fun onForegroundChanged(inForeground: Boolean)
    fun triggerImmediateCheck()
    suspend fun runBackgroundPollPass(): RadarBackgroundPollOutcome
}
