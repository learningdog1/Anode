package com.nodeseek.app.data.fleet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Abstraction for encrypting/decrypting probe panel API tokens at rest in `fleet_store.json`.
 */
interface FleetTokenCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

/**
 * Android Keystore AES-256-GCM cipher for encrypting panel API tokens on Android devices (API 26+).
 *
 * Encoded format: `v1:aks:<base64Iv>:<base64Ciphertext>`
 */
class AndroidKeystoreTokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : FleetTokenCipher {

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$PREFIX_AKS${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    override fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        if (!cipherText.startsWith(PREFIX_AKS)) return cipherText
        val payload = cipherText.removePrefix(PREFIX_AKS)
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep >= payload.lastIndex) return ""
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(payload.substring(0, sep))
        val encrypted = decoder.decode(payload.substring(sep + 1))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    internal fun isAvailable(): Boolean = runCatching {
        getOrCreateKey()
        true
    }.getOrDefault(false)

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
        const val DEFAULT_KEY_ALIAS = "anode_fleet_probe_token_aes_gcm_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        const val PREFIX_AKS = "v1:aks:"
    }
}

/**
 * Pure JCA AES-256-GCM cipher used as fallback on non-Android JVM environments (unit tests)
 * or devices where AndroidKeyStore initialization fails.
 *
 * Encoded format: `v1:sw:<base64Iv>:<base64Ciphertext>`
 */
class SoftwareAesGcmTokenCipher(
    keyBytes: ByteArray,
) : FleetTokenCipher {

    private val secretKey: SecretKey
    private val secureRandom = SecureRandom()

    init {
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "AES key must be 128/192/256 bits"
        }
        secretKey = SecretKeySpec(keyBytes.copyOf(), "AES")
    }

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$PREFIX_SW${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    override fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        if (!cipherText.startsWith(PREFIX_SW)) return cipherText
        val payload = cipherText.removePrefix(PREFIX_SW)
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep >= payload.lastIndex) return ""
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(payload.substring(0, sep))
        val encrypted = decoder.decode(payload.substring(sep + 1))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        const val PREFIX_SW = "v1:sw:"

        fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

        fun loadOrCreateFromFile(keyFile: File): SoftwareAesGcmTokenCipher {
            val bytes = runCatching {
                if (keyFile.isFile) {
                    val decoded = Base64.getDecoder().decode(keyFile.readText(Charsets.UTF_8).trim())
                    if (decoded.size == 32) decoded else null
                } else {
                    null
                }
            }.getOrNull() ?: randomKey().also { fresh ->
                runCatching {
                    keyFile.parentFile?.mkdirs()
                    keyFile.writeText(Base64.getEncoder().encodeToString(fresh), Charsets.UTF_8)
                }
            }
            return SoftwareAesGcmTokenCipher(bytes)
        }
    }
}

/**
 * Resolves the platform-appropriate [FleetTokenCipher]: prefers [AndroidKeystoreTokenCipher]
 * on Android and seamlessly falls back to [SoftwareAesGcmTokenCipher] on JVM / test runtimes.
 */
object FleetTokenCipherFactory {

    fun createDefault(fallbackKeyFile: File? = null): FleetTokenCipher {
        val keystoreCipher = AndroidKeystoreTokenCipher()
        if (keystoreCipher.isAvailable()) {
            return keystoreCipher
        }
        return if (fallbackKeyFile != null) {
            SoftwareAesGcmTokenCipher.loadOrCreateFromFile(fallbackKeyFile)
        } else {
            SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        }
    }
}
