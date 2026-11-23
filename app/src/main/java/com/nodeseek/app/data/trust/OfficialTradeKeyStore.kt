package com.nodeseek.app.data.trust

import android.content.Context
import com.nodeseek.app.data.ssh.AndroidKeystoreAesGcmCipher
import com.nodeseek.app.domain.ssh.SecretCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

@Serializable
private data class EncryptedOfficialTradeKeyPayload(
    val version: Int = CURRENT_VERSION,
    val encryptedApiKey: String = "",
    val updatedAtEpochMillis: Long = 0L,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * Device-local encrypted store for the user's NodeSeek Official Trade Platform API Key (`ntk_...`).
 *
 * Security & backup isolation guarantees:
 * - Encrypted at rest via [SecretCipher] ([AndroidKeystoreAesGcmCipher] with dedicated Keystore alias).
 * - Stored under `context.noBackupFilesDir` (excluded from Android Auto Backup) and completely
 *   separate from `SettingsRepository` / `BackupManagerImpl` so it is never exported in JSON backups.
 * - Never logs or includes the raw key in exception messages or `toString()`.
 */
class OfficialTradeKeyStore(
    private val keyFile: File,
    private val cipher: SecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Volatile
    private var loadNeedsRetry: Boolean = false

    @Volatile
    private var cachedPlainKey: String? = loadKeyFromDisk()

    private val _isConfigured = MutableStateFlow(!cachedPlainKey.isNullOrEmpty())
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    fun currentApiKey(): String? = cachedPlainKey

    fun maskedApiKey(): String? = cachedPlainKey?.let(::maskKey)

    suspend fun ensureLoaded() {
        if (!loadNeedsRetry) return
        withContext(ioDispatcher) {
            mutex.withLock {
                if (!loadNeedsRetry) return@withLock
                val loaded = loadKeyFromDisk()
                cachedPlainKey = loaded
                _isConfigured.value = !loaded.isNullOrEmpty()
            }
        }
    }

    /**
     * Trims and validates [rawKey], then encrypts and atomically persists it.
     *
     * @return `true` if the stored key actually changed, `false` if identical to the existing key.
     * @throws IllegalArgumentException if [rawKey] does not have a valid `ntk_` format.
     */
    suspend fun saveApiKey(rawKey: String): Boolean {
        val validated = validateAndNormalizeKey(rawKey)
        return withContext(ioDispatcher) {
            mutex.withLock {
                val previous = cachedPlainKey
                val encrypted = cipher.encrypt(validated)
                val payload = EncryptedOfficialTradeKeyPayload(
                    encryptedApiKey = encrypted,
                    updatedAtEpochMillis = clockMillis(),
                )
                writeAtomicallyLocked(payload)
                cachedPlainKey = validated
                loadNeedsRetry = false
                _isConfigured.value = true
                previous != validated
            }
        }
    }

    suspend fun clearApiKey() {
        withContext(ioDispatcher) {
            mutex.withLock {
                cachedPlainKey = null
                loadNeedsRetry = false
                _isConfigured.value = false
                runCatching {
                    val tmpFile = File(keyFile.parentFile, "${keyFile.name}.tmp")
                    if (tmpFile.exists()) tmpFile.delete()
                    if (keyFile.exists()) keyFile.delete()
                }
            }
        }
    }

    override fun toString(): String =
        "OfficialTradeKeyStore(configured=${_isConfigured.value})"

    private fun loadKeyFromDisk(): String? {
        if (!keyFile.isFile) {
            loadNeedsRetry = false
            return null
        }
        val len = keyFile.length()
        if (len <= 0L || len > MAX_KEY_FILE_BYTES) {
            loadNeedsRetry = false
            return null
        }
        return try {
            val raw = keyFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                loadNeedsRetry = false
                return null
            }
            val payload = json.decodeFromString<EncryptedOfficialTradeKeyPayload>(raw)
            if (payload.encryptedApiKey.isBlank()) {
                loadNeedsRetry = false
                return null
            }
            val decrypted = cipher.decrypt(payload.encryptedApiKey).trim()
            loadNeedsRetry = false
            if (isValidKeyFormat(decrypted)) {
                decrypted
            } else {
                runCatching { keyFile.delete() }
                null
            }
        } catch (t: Throwable) {
            if (isPermanentKeyCorruption(t)) {
                // Permanently corrupted ciphertext / JSON / invalidated Keystore key: clean up safely.
                loadNeedsRetry = false
                runCatching { keyFile.delete() }
            } else {
                // Transient I/O or temporary Keystore error: preserve keyFile so a later retry recovers.
                loadNeedsRetry = true
            }
            null
        }
    }

    private fun isPermanentKeyCorruption(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            if (current is SerializationException ||
                current is IllegalArgumentException ||
                current is AEADBadTagException ||
                current is BadPaddingException ||
                current.javaClass.name == "android.security.keystore.KeyPermanentlyInvalidatedException"
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private fun writeAtomicallyLocked(payload: EncryptedOfficialTradeKeyPayload) {
        keyFile.parentFile?.mkdirs()
        val encoded = json.encodeToString(payload)
        val tempFile = File(keyFile.parentFile, "${keyFile.name}.tmp")
        tempFile.writeText(encoded, Charsets.UTF_8)
        if (!tempFile.renameTo(keyFile)) {
            keyFile.writeText(encoded, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    companion object {
        const val KEY_FILE_NAME: String = "official_trade_api_key_v1.json"
        const val KEYSTORE_ALIAS: String = "anode_official_trade_api_key_aes_gcm_v1"
        const val KEY_PREFIX: String = "ntk_"
        private const val MAX_KEY_FILE_BYTES: Long = 16_384L

        fun forContext(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): OfficialTradeKeyStore {
            val appContext = context.applicationContext ?: context
            val baseDir = appContext.noBackupFilesDir ?: appContext.filesDir
            return OfficialTradeKeyStore(
                keyFile = File(baseDir, KEY_FILE_NAME),
                cipher = AndroidKeystoreAesGcmCipher(keyAlias = KEYSTORE_ALIAS),
                ioDispatcher = ioDispatcher,
            )
        }

        internal fun isValidKeyFormat(candidate: String): Boolean {
            if (!candidate.startsWith(KEY_PREFIX)) return false
            if (candidate.length <= KEY_PREFIX.length) return false
            if (candidate.any { it.isWhitespace() || it.isISOControl() }) return false
            return true
        }

        internal fun validateAndNormalizeKey(rawKey: String): String {
            val trimmed = rawKey.trim()
            require(trimmed.isNotEmpty()) { "API Key 不能为空" }
            require(trimmed.startsWith(KEY_PREFIX)) { "API Key 必须以 ntk_ 开头" }
            require(trimmed.length > KEY_PREFIX.length) { "API Key 长度无效" }
            require(trimmed.none { it.isWhitespace() || it.isISOControl() }) {
                "API Key 不能包含空白或控制字符"
            }
            return trimmed
        }

        internal fun maskKey(cleanKey: String): String? {
            val trimmed = cleanKey.trim()
            if (!isValidKeyFormat(trimmed)) return null
            return when {
                trimmed.length > 12 -> "${trimmed.take(8)}…${trimmed.takeLast(4)}"
                trimmed.length > 6 -> "${KEY_PREFIX}…${trimmed.takeLast(2)}"
                else -> "${KEY_PREFIX}…"
            }
        }
    }
}
