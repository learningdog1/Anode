package com.nodeseek.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts sensitive API keys (NodeImage / SeekMail) stored inside `nodepulse_settings` DataStore.
 *
 * Supports seamless read migration from legacy unencrypted values while guaranteeing that an
 * unreadable ciphertext envelope (`v1:aks:...` / `v1:sw:...`) is NEVER returned as a raw API key.
 */
interface SettingsSecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(storedValue: String): String
    fun isEncryptedEnvelope(storedValue: String): Boolean =
        storedValue.startsWith(PREFIX_AKS) || storedValue.startsWith(PREFIX_SW)

    companion object {
        const val PREFIX_AKS = "v1:aks:"
        const val PREFIX_SW = "v1:sw:"
    }
}

class AndroidKeystoreSettingsCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SettingsSecretCipher {

    override fun encrypt(plainText: String): String {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty()) return ""
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "${SettingsSecretCipher.PREFIX_AKS}${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    override fun decrypt(storedValue: String): String {
        val trimmed = storedValue.trim()
        if (trimmed.isEmpty()) return ""
        if (!isEncryptedEnvelope(trimmed)) {
            // Legacy plaintext value written before encryption was introduced.
            return trimmed
        }
        if (!trimmed.startsWith(SettingsSecretCipher.PREFIX_AKS)) {
            return ""
        }
        val payload = trimmed.removePrefix(SettingsSecretCipher.PREFIX_AKS)
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep >= payload.lastIndex) return ""
        return runCatching {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(payload.substring(0, sep))
            val encrypted = decoder.decode(payload.substring(sep + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    internal fun isAvailable(): Boolean = runCatching {
        getOrCreateKey()
        true
    }.getOrDefault(false)

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "anode_settings_api_key_aes_gcm_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

class SoftwareSettingsSecretCipher(
    keyBytes: ByteArray,
) : SettingsSecretCipher {

    private val secretKey: SecretKey
    private val secureRandom = SecureRandom()

    init {
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "AES key must be 128/192/256 bits"
        }
        secretKey = SecretKeySpec(keyBytes.copyOf(), "AES")
    }

    override fun encrypt(plainText: String): String {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty()) return ""
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "${SettingsSecretCipher.PREFIX_SW}${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    override fun decrypt(storedValue: String): String {
        val trimmed = storedValue.trim()
        if (trimmed.isEmpty()) return ""
        if (!isEncryptedEnvelope(trimmed)) {
            return trimmed
        }
        if (!trimmed.startsWith(SettingsSecretCipher.PREFIX_SW)) {
            return ""
        }
        val payload = trimmed.removePrefix(SettingsSecretCipher.PREFIX_SW)
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep >= payload.lastIndex) return ""
        return runCatching {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(payload.substring(0, sep))
            val encrypted = decoder.decode(payload.substring(sep + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH_BYTES = 12

        private val processTestCipher: SoftwareSettingsSecretCipher by lazy {
            SoftwareSettingsSecretCipher(randomKey())
        }

        fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

        fun defaultJvmCipher(): SoftwareSettingsSecretCipher = processTestCipher

        @Synchronized
        fun loadOrCreateFromFile(keyFile: File): SoftwareSettingsSecretCipher {
            val existing = runCatching {
                if (keyFile.isFile) {
                    val decoded = Base64.getDecoder().decode(keyFile.readText(Charsets.UTF_8).trim())
                    if (decoded.size == 32) decoded else null
                } else {
                    null
                }
            }.getOrNull()
            if (existing != null) {
                return SoftwareSettingsSecretCipher(existing)
            }
            val fresh = randomKey()
            keyFile.parentFile?.mkdirs()
            val encoded = Base64.getEncoder().encodeToString(fresh).toByteArray(Charsets.UTF_8)
            val tmpFile = File(keyFile.parentFile, "${keyFile.name}.tmp")
            FileOutputStream(tmpFile).use { fos ->
                fos.write(encoded)
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(keyFile)) {
                FileOutputStream(keyFile).use { fos ->
                    fos.write(encoded)
                    fos.fd.sync()
                }
                tmpFile.delete()
            }
            return SoftwareSettingsSecretCipher(fresh)
        }
    }
}

private class CompositeSettingsSecretCipher(
    private val keystoreCipher: AndroidKeystoreSettingsCipher?,
    private val softwareCipher: SoftwareSettingsSecretCipher?,
) : SettingsSecretCipher {

    override fun encrypt(plainText: String): String {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty()) return ""
        keystoreCipher?.let { aks ->
            val encrypted = runCatching { aks.encrypt(trimmed) }.getOrNull()
            if (!encrypted.isNullOrBlank()) return encrypted
        }
        val sw = softwareCipher
            ?: throw IllegalStateException("No available cipher to encrypt settings API key")
        return sw.encrypt(trimmed)
    }

    override fun decrypt(storedValue: String): String {
        val trimmed = storedValue.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith(SettingsSecretCipher.PREFIX_AKS) ->
                keystoreCipher?.decrypt(trimmed).orEmpty()
            trimmed.startsWith(SettingsSecretCipher.PREFIX_SW) ->
                softwareCipher?.decrypt(trimmed).orEmpty()
            else -> trimmed
        }
    }
}

object SettingsSecretCipherFactory {
    private const val FALLBACK_KEY_FILE_NAME = ".anode_settings_secret_sw.key"

    fun createDefault(context: Context? = null): SettingsSecretCipher {
        val aks = AndroidKeystoreSettingsCipher().takeIf { it.isAvailable() }
        val sw = if (context != null) {
            val baseDir = runCatching { context.noBackupFilesDir }.getOrNull() ?: context.filesDir
            runCatching {
                SoftwareSettingsSecretCipher.loadOrCreateFromFile(File(baseDir, FALLBACK_KEY_FILE_NAME))
            }.getOrNull()
        } else {
            SoftwareSettingsSecretCipher.defaultJvmCipher()
        }
        return CompositeSettingsSecretCipher(
            keystoreCipher = aks,
            softwareCipher = sw ?: SoftwareSettingsSecretCipher.defaultJvmCipher(),
        )
    }
}
