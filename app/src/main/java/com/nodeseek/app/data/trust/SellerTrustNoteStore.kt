package com.nodeseek.app.data.trust

import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.TrustTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class SellerTrustNotesPayload(
    val version: Int = 1,
    val notes: List<LocalSellerNote> = emptyList(),
)

/**
 * Thread-safe persistent JSON store for [LocalSellerNote] records in `filesDir/seller_trust_notes.json`.
 * Uses atomic temp-file writes and publishes reactive updates via [MutableStateFlow].
 */
internal class SellerTrustNoteStore(
    private val notesFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()
    private var loadedFromDisk = false
    private val notesState = MutableStateFlow<Map<Long, LocalSellerNote>>(emptyMap())

    suspend fun getNote(uid: Long): LocalSellerNote? {
        if (uid <= 0L) return null
        ensureLoaded()
        return notesState.value[uid]
    }

    suspend fun getAllNotes(): List<LocalSellerNote> {
        ensureLoaded()
        return notesState.value.values.sortedByDescending { it.updatedAtEpochMillis }
    }

    fun observeNote(uid: Long): Flow<LocalSellerNote?> = flow {
        if (uid <= 0L) {
            emit(null)
            return@flow
        }
        ensureLoaded()
        emitAll(notesState.map { it[uid] }.distinctUntilChanged())
    }

    /**
     * Saves or removes a seller's local note. When [tag] is [TrustTag.NONE] and [noteText] is blank,
     * the entry is removed to keep storage tidy. Returns the persisted note or `null` if removed.
     */
    suspend fun saveNote(
        uid: Long,
        username: String,
        tag: TrustTag,
        noteText: String,
    ): LocalSellerNote? = withContext(ioDispatcher) {
        if (uid <= 0L) return@withContext null
        val cleanText = noteText.trim()
        val cleanName = username.trim()

        mutex.withLock {
            ensureLoadedLocked()
            val updatedMap = LinkedHashMap(notesState.value)
            val savedNote: LocalSellerNote?
            if (tag == TrustTag.NONE && cleanText.isEmpty()) {
                updatedMap.remove(uid)
                savedNote = null
            } else {
                val existing = updatedMap[uid]
                savedNote = LocalSellerNote(
                    uid = uid,
                    username = cleanName.ifEmpty { existing?.username.orEmpty() },
                    tag = tag,
                    noteText = cleanText,
                    updatedAtEpochMillis = clockMillis(),
                )
                updatedMap[uid] = savedNote
            }
            notesState.value = updatedMap.toMap()
            writeToDiskLocked(updatedMap.values.toList())
            savedNote
        }
    }

    suspend fun restoreNotes(
        notes: List<LocalSellerNote>,
        replaceExisting: Boolean,
    ): Set<Long> = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            val previousUids = notesState.value.keys
            val nextMap = if (replaceExisting) {
                LinkedHashMap<Long, LocalSellerNote>()
            } else {
                LinkedHashMap(notesState.value)
            }
            for (raw in notes) {
                if (raw.uid <= 0L) continue
                val clean = raw.copy(
                    username = raw.username.trim(),
                    noteText = raw.noteText.trim(),
                    updatedAtEpochMillis = raw.updatedAtEpochMillis.coerceAtLeast(0L),
                )
                if (!clean.isMeaningful) continue
                val existing = nextMap[clean.uid]
                if (existing == null || replaceExisting || clean.updatedAtEpochMillis >= existing.updatedAtEpochMillis) {
                    nextMap[clean.uid] = clean
                }
            }
            notesState.value = nextMap.toMap()
            writeToDiskLocked(nextMap.values.toList())
            previousUids + nextMap.keys
        }
    }

    private suspend fun ensureLoaded() {
        if (loadedFromDisk) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
            }
        }
    }

    private fun ensureLoadedLocked() {
        if (loadedFromDisk) return
        loadedFromDisk = true
        if (!notesFile.exists()) return
        val decoded = runCatching {
            val raw = notesFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) null else json.decodeFromString<SellerTrustNotesPayload>(raw)
        }.getOrNull() ?: return

        val map = LinkedHashMap<Long, LocalSellerNote>()
        decoded.notes
            .filter { it.uid > 0L && it.isMeaningful }
            .forEach { note ->
                map[note.uid] = note
            }
        notesState.value = map.toMap()
    }

    private fun writeToDiskLocked(notes: List<LocalSellerNote>) {
        runCatching {
            notesFile.parentFile?.mkdirs()
            val payload = SellerTrustNotesPayload(
                version = 1,
                notes = notes.sortedByDescending { it.updatedAtEpochMillis },
            )
            val encoded = json.encodeToString(payload)
            val tmpFile = File(notesFile.parentFile, "${notesFile.name}.tmp")
            tmpFile.writeText(encoded, Charsets.UTF_8)
            if (!tmpFile.renameTo(notesFile)) {
                notesFile.writeText(encoded, Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }
}
