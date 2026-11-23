package com.nodeseek.app.data.fleet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thrown when encrypting or decrypting a probe panel API token fails.
 */
class FleetTokenCipherException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Abstraction for encrypting/decrypting probe panel API tokens at rest in `fleet_store.json`.
 */
interface FleetTokenCipher {
    /**
     * Whether newly encrypted tokens are protected by Android Keystore rather than a local
     * software fallback key file.
     */
    val isHardwareBacked: Boolean
        get() = false

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

    override val isHardwareBacked: Boolean
        get() = true

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
        if (cipherText.startsWith(SoftwareAesGcmTokenCipher.PREFIX_SW)) {
            throw FleetTokenCipherException("探针 Token 使用软件密钥加密，无法通过 Android Keystore 解密")
        }
        if (!cipherText.startsWith(PREFIX_AKS)) return cipherText
        val payload = cipherText.removePrefix(PREFIX_AKS)
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep >= payload.lastIndex) {
            throw FleetTokenCipherException("Android Keystore 探针 Token 密文格式损坏")
        }
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

    override val isHardwareBacked: Boolean
        get() = false

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
        if (cipherText.startsWith(AndroidKeystoreTokenCipher.PREFIX_AKS)) {
            throw FleetTokenCipherException("探针 Token 使用 Android Keystore 加密，无法通过软件降级密钥解密")
        }
        if (!cipherText.startsWith(PREFIX_SW)) return cipherText
        val payload = cipherText.removePrefix(PREFIX_SW)
        val sep = payload.indexOf(':')
        if (sep <= 0 || sep >= payload.lastIndex) {
            throw FleetTokenCipherException("软件加密探针 Token 密文格式损坏")
        }
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

        @Throws(IOException::class)
        fun loadOrCreateFromFile(keyFile: File): SoftwareAesGcmTokenCipher {
            val existingBytes = if (keyFile.exists()) {
                if (!keyFile.isFile) {
                    throw FleetTokenCipherException("本地软件密钥路径异常: ${keyFile.path}")
                }
                val decoded = runCatching {
                    Base64.getDecoder().decode(keyFile.readText(Charsets.UTF_8).trim())
                }.getOrElse { err ->
                    throw FleetTokenCipherException("读取本地软件密钥失败: ${keyFile.path}", err)
                }
                if (decoded.size != 32) {
                    throw FleetTokenCipherException("本地软件密钥文件长度异常，已拒绝覆盖: ${keyFile.path}")
                }
                decoded
            } else {
                null
            }
            if (existingBytes != null) {
                return SoftwareAesGcmTokenCipher(existingBytes)
            }

            val fresh = randomKey()
            val targetFile = keyFile.absoluteFile
            val parentDir = targetFile.parentFile
                ?: throw FleetTokenCipherException("无法解析本地软件密钥存储目录: ${targetFile.path}")
            if (!parentDir.exists() && !parentDir.mkdirs() && !parentDir.isDirectory) {
                throw FleetTokenCipherException("无法创建本地软件密钥存储目录: ${parentDir.path}")
            }
            val tmpFile = File(parentDir, "${targetFile.name}.tmp")
            val encoded = Base64.getEncoder().encodeToString(fresh).toByteArray(Charsets.UTF_8)
            try {
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(encoded)
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
                applyOwnerOnlyPermissions(tmpFile)
                try {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                applyOwnerOnlyPermissions(targetFile)
            } catch (t: Throwable) {
                runCatching { tmpFile.delete() }
                if (t is IOException) throw t
                throw FleetTokenCipherException("持久化本地软件密钥失败: ${targetFile.path}", t)
            }
            return SoftwareAesGcmTokenCipher(fresh)
        }

        private fun applyOwnerOnlyPermissions(file: File) {
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                file.setExecutable(false, false)
            }
        }
    }
}

/**
 * Composite [FleetTokenCipher] that encrypts with the preferred primary backend (Android Keystore
 * when available, otherwise software AES-GCM) and strictly routes decryption by ciphertext prefix
 * so encrypted tokens are NEVER returned raw as plaintext.
 */
internal class RoutingFleetTokenCipher(
    private val primary: FleetTokenCipher,
    private val keystoreCipher: AndroidKeystoreTokenCipher?,
    private val fallbackKeyFile: File?,
) : FleetTokenCipher {

    @Volatile
    private var cachedSoftwareCipher: SoftwareAesGcmTokenCipher? =
        primary as? SoftwareAesGcmTokenCipher

    override val isHardwareBacked: Boolean
        get() = primary.isHardwareBacked

    override fun encrypt(plainText: String): String = primary.encrypt(plainText)

    override fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return when {
            cipherText.startsWith(AndroidKeystoreTokenCipher.PREFIX_AKS) -> {
                val aks = keystoreCipher
                    ?: throw FleetTokenCipherException("当前设备 Android Keystore 不可用，无法解密探针 Token")
                aks.decrypt(cipherText)
            }
            cipherText.startsWith(SoftwareAesGcmTokenCipher.PREFIX_SW) -> {
                val sw = resolveSoftwareCipherForDecrypt()
                    ?: throw FleetTokenCipherException("未找到本地软件降级密钥，无法解密探针 Token")
                sw.decrypt(cipherText)
            }
            else -> cipherText
        }
    }

    @Synchronized
    private fun resolveSoftwareCipherForDecrypt(): SoftwareAesGcmTokenCipher? {
        cachedSoftwareCipher?.let { return it }
        val file = fallbackKeyFile ?: return null
        if (!file.isFile) return null
        val loaded = SoftwareAesGcmTokenCipher.loadOrCreateFromFile(file)
        cachedSoftwareCipher = loaded
        return loaded
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
            return RoutingFleetTokenCipher(
                primary = keystoreCipher,
                keystoreCipher = keystoreCipher,
                fallbackKeyFile = fallbackKeyFile,
            )
        }
        val softwareCipher = if (fallbackKeyFile != null) {
            SoftwareAesGcmTokenCipher.loadOrCreateFromFile(fallbackKeyFile)
        } else {
            SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        }
        return RoutingFleetTokenCipher(
            primary = softwareCipher,
            keystoreCipher = null,
            fallbackKeyFile = fallbackKeyFile,
        )
    }
}
