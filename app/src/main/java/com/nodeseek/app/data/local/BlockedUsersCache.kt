package com.nodeseek.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nodeseek.app.domain.backup.BlockedUserBackupDto
import com.nodeseek.app.domain.backup.toBlockedUserBackupDto
import com.nodeseek.app.domain.model.FloorAuthor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

val Context.nodePulseBlockedUsersDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nodepulse_blocked_users_cache",
)

/**
 * Persistent local cache of blocked users used as an offline fallback for backup export
 * and updated whenever [com.nodeseek.app.domain.repository.UserRepository] fetches or mutates blocks.
 */
interface BlockedUsersCache {
    suspend fun getBlockedUsers(): List<FloorAuthor>
    suspend fun replaceAll(users: List<FloorAuthor>)
    suspend fun mergeAll(users: List<FloorAuthor>)
    suspend fun recordBlockedByName(username: String)
    suspend fun removeByUid(uid: Long)
}

class DataStoreBlockedUsersCache(
    private val dataStore: DataStore<Preferences>,
) : BlockedUsersCache {

    constructor(context: Context) : this(context.applicationContext.nodePulseBlockedUsersDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val safePreferencesFlow: Flow<Preferences> = dataStore.data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

    override suspend fun getBlockedUsers(): List<FloorAuthor> =
        decodeUsers(safePreferencesFlow.first()[KEY_BLOCKED_USERS_JSON])

    override suspend fun replaceAll(users: List<FloorAuthor>) {
        val normalized = normalizeBlockedUserList(users)
        dataStore.edit { prefs ->
            prefs[KEY_BLOCKED_USERS_JSON] = encodeUsers(normalized)
        }
    }

    override suspend fun mergeAll(users: List<FloorAuthor>) {
        val incoming = normalizeBlockedUserList(users)
        if (incoming.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeUsers(prefs[KEY_BLOCKED_USERS_JSON])
            val merged = mergeBlockedUserLists(existing = current, incoming = incoming)
            prefs[KEY_BLOCKED_USERS_JSON] = encodeUsers(merged)
        }
    }

    override suspend fun recordBlockedByName(username: String) {
        val clean = username.trim().removePrefix("@").trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeUsers(prefs[KEY_BLOCKED_USERS_JSON])
            val merged = mergeBlockedUserLists(
                existing = current,
                incoming = listOf(FloorAuthor(uid = 0L, username = clean)),
            )
            prefs[KEY_BLOCKED_USERS_JSON] = encodeUsers(merged)
        }
    }

    override suspend fun removeByUid(uid: Long) {
        if (uid <= 0L) return
        dataStore.edit { prefs ->
            val current = decodeUsers(prefs[KEY_BLOCKED_USERS_JSON])
            val filtered = current.filterNot { it.uid == uid }
            prefs[KEY_BLOCKED_USERS_JSON] = encodeUsers(filtered)
        }
    }

    private fun encodeUsers(users: List<FloorAuthor>): String =
        json.encodeToString(users.map { it.toBlockedUserBackupDto() })

    private fun decodeUsers(raw: String?): List<FloorAuthor> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<BlockedUserBackupDto>>(raw)
        }.getOrNull().orEmpty()
        return normalizeBlockedUserList(parsed.map { it.toDomain() })
    }

    companion object {
        private val KEY_BLOCKED_USERS_JSON = stringPreferencesKey("blocked_users_json")
    }
}

class InMemoryBlockedUsersCache(
    initial: List<FloorAuthor> = emptyList(),
) : BlockedUsersCache {
    private val state = MutableStateFlow(normalizeBlockedUserList(initial))

    override suspend fun getBlockedUsers(): List<FloorAuthor> = state.value

    override suspend fun replaceAll(users: List<FloorAuthor>) {
        state.value = normalizeBlockedUserList(users)
    }

    override suspend fun mergeAll(users: List<FloorAuthor>) {
        val incoming = normalizeBlockedUserList(users)
        if (incoming.isEmpty()) return
        state.update { current ->
            mergeBlockedUserLists(existing = current, incoming = incoming)
        }
    }

    override suspend fun recordBlockedByName(username: String) {
        val clean = username.trim().removePrefix("@").trim()
        if (clean.isEmpty()) return
        mergeAll(listOf(FloorAuthor(uid = 0L, username = clean)))
    }

    override suspend fun removeByUid(uid: Long) {
        if (uid <= 0L) return
        state.update { current -> current.filterNot { it.uid == uid } }
    }
}

internal fun normalizeBlockedUserList(users: List<FloorAuthor>): List<FloorAuthor> {
    val result = ArrayList<FloorAuthor>(users.size)
    for (raw in users) {
        val cleanName = raw.username.trim()
        if (cleanName.isEmpty()) continue
        val clean = FloorAuthor(
            uid = raw.uid.coerceAtLeast(0L),
            username = cleanName,
            avatarUrl = raw.avatarUrl.trim(),
        )
        val existingIdx = result.indexOfFirst { existing ->
            (clean.uid > 0L && existing.uid == clean.uid) ||
                existing.username.equals(clean.username, ignoreCase = true)
        }
        if (existingIdx >= 0) {
            val prev = result[existingIdx]
            result[existingIdx] = FloorAuthor(
                uid = if (clean.uid > 0L) clean.uid else prev.uid,
                username = clean.username,
                avatarUrl = clean.avatarUrl.ifBlank { prev.avatarUrl },
            )
        } else {
            result += clean
        }
    }
    return result
}

internal fun mergeBlockedUserLists(
    existing: List<FloorAuthor>,
    incoming: List<FloorAuthor>,
): List<FloorAuthor> {
    val combined = ArrayList<FloorAuthor>(existing.size + incoming.size)
    combined.addAll(normalizeBlockedUserList(existing))
    for (user in normalizeBlockedUserList(incoming)) {
        val idx = combined.indexOfFirst { cur ->
            (user.uid > 0L && cur.uid == user.uid) ||
                cur.username.equals(user.username, ignoreCase = true)
        }
        if (idx >= 0) {
            val prev = combined[idx]
            combined[idx] = FloorAuthor(
                uid = if (prev.uid > 0L) prev.uid else user.uid,
                username = prev.username.ifBlank { user.username },
                avatarUrl = user.avatarUrl.ifBlank { prev.avatarUrl },
            )
        } else {
            combined += user
        }
    }
    return combined
}
