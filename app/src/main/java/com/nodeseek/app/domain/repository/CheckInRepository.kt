package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import kotlinx.coroutines.flow.StateFlow

interface CheckInRepository {
    val checkInStatus: StateFlow<CheckInStatus?>
    fun clearCachedStatus()
    suspend fun getCheckInBoard(page: Int = 1, forceRefresh: Boolean = false): Result<CheckInStatus>
    suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus>
    suspend fun getDailyProgress(): Result<DailyQuotaProgress>
}
