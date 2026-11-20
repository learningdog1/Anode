package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Grouped local SSH benchmark execution records bucketed by server remark name (`备注名称`).
 */
@Immutable
data class SshHistoryServerGroup(
    val groupKey: String,
    val serverAlias: String,
    val serverHost: String,
    val serverId: String,
    val runs: List<SshExecutionRecord>,
    val latestStartedAtEpochMillis: Long,
) {
    val runCount: Int
        get() = runs.size
}

/**
 * Groups persisted [SshExecutionRecord] history entries by their machine remark name (`备注名称`),
 * resolving live server profile renames when [servers] is provided, sorting runs newest-first
 * within each group, and ordering groups by their latest run timestamp descending.
 */
object SshHistoryGrouping {
    const val UNNAMED_SERVER_LABEL: String = "未命名主机"

    fun groupRunsByServerRemark(
        runs: List<SshExecutionRecord>,
        servers: List<SshServerProfile> = emptyList(),
    ): List<SshHistoryServerGroup> {
        if (runs.isEmpty()) return emptyList()

        val serversById = servers.associateBy { it.id }
        val buckets = LinkedHashMap<String, MutableList<SshExecutionRecord>>()
        val displayAliasByKey = LinkedHashMap<String, String>()

        for (run in runs) {
            val matchedServer = run.serverId
                .takeIf { it.isNotBlank() }
                ?.let { serversById[it] }
            val resolvedRemark = matchedServer?.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: run.serverAlias.trim().takeIf { it.isNotEmpty() }
                ?: run.serverHost.trim().takeIf { it.isNotEmpty() }
                ?: UNNAMED_SERVER_LABEL

            val normalizedKey = resolvedRemark.lowercase()
            displayAliasByKey.putIfAbsent(normalizedKey, resolvedRemark)
            buckets.getOrPut(normalizedKey) { mutableListOf() }.add(run)
        }

        return buckets.entries
            .map { (key, groupRuns) ->
                val sortedRuns = groupRuns.sortedWith(
                    compareByDescending<SshExecutionRecord> { it.startedAtEpochMillis }
                        .thenBy { it.id },
                )
                val latest = sortedRuns.first()
                val matchedServer = latest.serverId
                    .takeIf { it.isNotBlank() }
                    ?.let { serversById[it] }
                SshHistoryServerGroup(
                    groupKey = key,
                    serverAlias = displayAliasByKey[key] ?: latest.serverAlias.ifBlank { UNNAMED_SERVER_LABEL },
                    serverHost = matchedServer?.host?.takeIf { it.isNotBlank() } ?: latest.serverHost,
                    serverId = latest.serverId,
                    runs = sortedRuns,
                    latestStartedAtEpochMillis = latest.startedAtEpochMillis,
                )
            }
            .sortedByDescending { it.latestStartedAtEpochMillis }
    }
}
