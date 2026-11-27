package com.nodeseek.app.domain.baseline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence contract for saved VPS purchase baselines (`filesDir/benchmark_baselines.json`).
 */
interface BenchmarkBaselineRepository {
    val baselinesFlow: StateFlow<List<BenchmarkBaselineSnapshot>>

    suspend fun ensureLoaded()

    suspend fun saveBaseline(snapshot: BenchmarkBaselineSnapshot): BenchmarkBaselineSnapshot

    suspend fun deleteBaseline(id: String)

    fun findMatchingBaselines(
        serverId: String? = null,
        hostOrIp: String? = null,
    ): List<BenchmarkBaselineSnapshot>

    suspend fun exportForBackup(): List<BenchmarkBaselineSnapshot>

    suspend fun restoreFromBackup(
        items: List<BenchmarkBaselineSnapshot>,
        replaceExisting: Boolean,
    )
}

/**
 * Safe no-op implementation used by [com.nodeseek.app.di.AppContainer] default getters and Compose previews.
 */
object NoOpBenchmarkBaselineRepository : BenchmarkBaselineRepository {
    private val emptyFlow = MutableStateFlow<List<BenchmarkBaselineSnapshot>>(emptyList())
    override val baselinesFlow: StateFlow<List<BenchmarkBaselineSnapshot>> = emptyFlow.asStateFlow()

    override suspend fun ensureLoaded() = Unit

    override suspend fun saveBaseline(snapshot: BenchmarkBaselineSnapshot): BenchmarkBaselineSnapshot = snapshot

    override suspend fun deleteBaseline(id: String) = Unit

    override fun findMatchingBaselines(
        serverId: String?,
        hostOrIp: String?,
    ): List<BenchmarkBaselineSnapshot> = emptyList()

    override suspend fun exportForBackup(): List<BenchmarkBaselineSnapshot> = emptyList()

    override suspend fun restoreFromBackup(
        items: List<BenchmarkBaselineSnapshot>,
        replaceExisting: Boolean,
    ) = Unit
}
