package com.nodeseek.app.data.backup

import com.nodeseek.app.domain.backup.EncryptedBackupEnvelope
import com.nodeseek.app.domain.backup.EncryptedBackupErrorKind
import com.nodeseek.app.domain.backup.EncryptedBackupException
import com.nodeseek.app.domain.backup.EncryptedBackupHeader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based authenticated encryption engine for Anode Full Backup v2.
 *
 * Cryptographic construction:
 * - KDF: `PBKDF2WithHmacSHA256` with `>= 310,000` iterations and a fresh 256-bit random salt.
 * - AEAD: `AES/GCM/NoPadding` with a 256-bit derived key, a fresh 96-bit random nonce, and a
 *   128-bit authentication tag.
 * - AAD: All envelope header parameters (`format`, `formatVersion`, KDF params, salt, cipher,
 *   nonce, tag length) are bound as GCM Associated Authenticated Data so tampering with either
 *   the header or the ciphertext/tag is detected before any plaintext is released.
 */
class BackupCryptoV2(
    private val encryptIterations: Int = EncryptedBackupHeader.DEFAULT_PBKDF2_ITERATIONS,
    private val minDecryptIterations: Int = EncryptedBackupHeader.MIN_PBKDF2_ITERATIONS,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    init {
        require(encryptIterations >= minDecryptIterations) {
            "encryptIterations ($encryptIterations) must be >= minDecryptIterations ($minDecryptIterations)"
        }
        require(encryptIterations <= EncryptedBackupHeader.MAX_PBKDF2_ITERATIONS) {
            "encryptIterations ($encryptIterations) exceeds maximum allowed bound"
        }
    }

    data class DecryptedEnvelope(
        val envelope: EncryptedBackupEnvelope,
        val plaintextUtf8: String,
    )

    /**
     * Encrypts [plaintextUtf8] into a self-describing [EncryptedBackupEnvelope] using [passphrase].
     *
     * @throws EncryptedBackupException with [EncryptedBackupErrorKind.WEAK_PASSPHRASE] if
     *   [passphrase] is shorter than [EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH] or blank.
     */
    fun encrypt(
        plaintextUtf8: String,
        passphrase: String,
    ): EncryptedBackupEnvelope {
        validatePassphraseForEncryption(passphrase)

        val salt = ByteArray(EncryptedBackupHeader.SALT_BYTES).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(EncryptedBackupHeader.NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val saltBase64 = Base64.getEncoder().encodeToString(salt)
        val nonceBase64 = Base64.getEncoder().encodeToString(nonce)

        val header = EncryptedBackupHeader(
            kdf = EncryptedBackupHeader.KDF_PBKDF2_HMAC_SHA256,
            iterations = encryptIterations,
            keyLengthBits = EncryptedBackupHeader.KEY_LENGTH_BITS,
            saltBase64 = saltBase64,
            cipher = EncryptedBackupHeader.CIPHER_AES_256_GCM,
            nonceBase64 = nonceBase64,
            tagLengthBits = EncryptedBackupHeader.GCM_TAG_LENGTH_BITS,
        )

        val aad = buildCanonicalAad(
            format = EncryptedBackupEnvelope.FORMAT_MAGIC,
            formatVersion = EncryptedBackupEnvelope.CURRENT_FORMAT_VERSION,
            header = header,
        )

        val keyBytes = deriveKeyBytes(
            passphrase = passphrase,
            salt = salt,
            iterations = header.iterations,
            keyLengthBits = header.keyLengthBits,
        )
        val plaintextBytes = plaintextUtf8.toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance(JCE_CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(header.tagLengthBits, nonce),
            )
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintextBytes)
            EncryptedBackupEnvelope(
                format = EncryptedBackupEnvelope.FORMAT_MAGIC,
                formatVersion = EncryptedBackupEnvelope.CURRENT_FORMAT_VERSION,
                header = header,
                ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            )
        } catch (e: GeneralSecurityException) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.IO_ERROR,
                message = "加密备份失败：${e.message ?: "加密引擎异常"}",
                cause = e,
            )
        } finally {
            keyBytes.fill(0)
            plaintextBytes.fill(0)
        }
    }

    /**
     * Serializes [envelope] into pretty UTF-8 JSON.
     */
    fun encodeEnvelope(envelope: EncryptedBackupEnvelope): String =
        envelopeJson.encodeToString(envelope)

    /**
     * Parses and validates an [EncryptedBackupEnvelope] header without decrypting the payload.
     */
    fun parseEnvelope(rawEnvelopeJson: String): EncryptedBackupEnvelope {
        val trimmed = rawEnvelopeJson.trim()
        if (trimmed.isEmpty()) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "备份文件内容为空",
            )
        }

        val envelope = try {
            envelopeJson.decodeFromString<EncryptedBackupEnvelope>(trimmed)
        } catch (e: SerializationException) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "加密备份文件格式无效或已损坏",
                cause = e,
            )
        } catch (e: IllegalArgumentException) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "加密备份文件格式无效或已损坏",
                cause = e,
            )
        }

        validateEnvelopeHeader(envelope)
        return envelope
    }

    /**
     * Parses, validates, authenticates, and decrypts [rawEnvelopeJson] using [passphrase].
     *
     * @throws EncryptedBackupException with [EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED]
     *   whenever the passphrase is incorrect or any byte of the header/ciphertext/GCM tag was altered.
     */
    fun decrypt(
        rawEnvelopeJson: String,
        passphrase: String,
    ): DecryptedEnvelope {
        val envelope = parseEnvelope(rawEnvelopeJson)
        if (passphrase.isEmpty()) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED,
                message = "请输入解密口令",
            )
        }

        val header = envelope.header
        val salt = decodeBase64Strict(header.saltBase64, "盐值")
        val nonce = decodeBase64Strict(header.nonceBase64, "随机数 Nonce")
        val ciphertext = decodeBase64Strict(envelope.ciphertextBase64, "密文")

        if (salt.size < MIN_SALT_BYTES) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "加密备份文件头盐值长度无效",
            )
        }
        if (nonce.size != EncryptedBackupHeader.NONCE_BYTES) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "加密备份文件头 Nonce 长度无效",
            )
        }
        val minCiphertextLen = header.tagLengthBits / 8
        if (ciphertext.size < minCiphertextLen) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED,
                message = "口令错误或备份文件已被篡改，无法解密（本地数据未被修改）",
            )
        }

        val aad = buildCanonicalAad(
            format = envelope.format,
            formatVersion = envelope.formatVersion,
            header = header,
        )

        val keyBytes = deriveKeyBytes(
            passphrase = passphrase,
            salt = salt,
            iterations = header.iterations,
            keyLengthBits = header.keyLengthBits,
        )
        var plaintextBytes: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance(JCE_CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(header.tagLengthBits, nonce),
            )
            cipher.updateAAD(aad)
            plaintextBytes = cipher.doFinal(ciphertext)
            DecryptedEnvelope(
                envelope = envelope,
                plaintextUtf8 = String(plaintextBytes, Charsets.UTF_8),
            )
        } catch (e: AEADBadTagException) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED,
                message = "口令错误或备份文件已被篡改，无法解密（本地数据未被修改）",
                cause = e,
            )
        } catch (e: GeneralSecurityException) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED,
                message = "口令错误或备份文件已被篡改，无法解密（本地数据未被修改）",
                cause = e,
            )
        } finally {
            keyBytes.fill(0)
            plaintextBytes?.fill(0)
        }
    }

    private fun validateEnvelopeHeader(envelope: EncryptedBackupEnvelope) {
        if (envelope.format != EncryptedBackupEnvelope.FORMAT_MAGIC) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "不是有效的 Anode 加密完整备份文件",
            )
        }
        if (envelope.formatVersion != EncryptedBackupEnvelope.CURRENT_FORMAT_VERSION) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.UNSUPPORTED_VERSION,
                message = "不支持的加密备份格式版本 v${envelope.formatVersion}（当前仅支持 v${EncryptedBackupEnvelope.CURRENT_FORMAT_VERSION}）",
            )
        }

        val header = envelope.header
        if (header.kdf != EncryptedBackupHeader.KDF_PBKDF2_HMAC_SHA256) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.UNSUPPORTED_VERSION,
                message = "不支持的密钥派生算法：${header.kdf}",
            )
        }
        if (header.iterations < minDecryptIterations ||
            header.iterations > EncryptedBackupHeader.MAX_PBKDF2_ITERATIONS
        ) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "加密备份 KDF 迭代次数不安全或不受支持（${header.iterations}）",
            )
        }
        if (header.keyLengthBits != EncryptedBackupHeader.KEY_LENGTH_BITS) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.UNSUPPORTED_VERSION,
                message = "不支持的密钥长度：${header.keyLengthBits} bit",
            )
        }
        if (header.cipher != EncryptedBackupHeader.CIPHER_AES_256_GCM) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.UNSUPPORTED_VERSION,
                message = "不支持的加密算法：${header.cipher}",
            )
        }
        if (header.tagLengthBits != EncryptedBackupHeader.GCM_TAG_LENGTH_BITS) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "不支持的认证标签长度：${header.tagLengthBits} bit",
            )
        }
        if (header.saltBase64.isBlank() || header.nonceBase64.isBlank() || envelope.ciphertextBase64.isBlank()) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "加密备份文件头缺少必要字段",
            )
        }
    }

    private fun deriveKeyBytes(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
    ): ByteArray {
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, keyLengthBits)
        return try {
            val factory = SecretKeyFactory.getInstance(JCE_KDF_ALGORITHM)
            factory.generateSecret(spec).encoded
        } catch (e: GeneralSecurityException) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.IO_ERROR,
                message = "派生加密密钥失败：${e.message ?: "KDF 异常"}",
                cause = e,
            )
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    private fun decodeBase64Strict(encoded: String, fieldLabel: String): ByteArray {
        return try {
            Base64.getDecoder().decode(encoded.trim())
        } catch (e: IllegalArgumentException) {
            throw EncryptedBackupException(
                kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                message = "加密备份文件${fieldLabel}编码无效或已损坏",
                cause = e,
            )
        }
    }

    private fun buildCanonicalAad(
        format: String,
        formatVersion: Int,
        header: EncryptedBackupHeader,
    ): ByteArray = buildString {
        append(format)
        append('|')
        append(formatVersion)
        append('|')
        append(header.kdf)
        append('|')
        append(header.iterations)
        append('|')
        append(header.keyLengthBits)
        append('|')
        append(header.saltBase64.trim())
        append('|')
        append(header.cipher)
        append('|')
        append(header.nonceBase64.trim())
        append('|')
        append(header.tagLengthBits)
    }.toByteArray(Charsets.UTF_8)

    companion object {
        private const val JCE_KDF_ALGORITHM: String = "PBKDF2WithHmacSHA256"
        private const val JCE_CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val MIN_SALT_BYTES: Int = 16

        val envelopeJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /**
         * Validates user-supplied export passphrase strength (`>= 8` non-blank characters).
         */
        fun validatePassphraseForEncryption(passphrase: String) {
            if (passphrase.length < EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH || passphrase.isBlank()) {
                throw EncryptedBackupException(
                    kind = EncryptedBackupErrorKind.WEAK_PASSPHRASE,
                    message = "口令长度至少需要 ${EncryptedBackupHeader.MIN_PASSPHRASE_LENGTH} 位且不能全为空白字符",
                )
            }
        }

        /**
         * Fast non-throwing check detecting whether [rawContent] is an Anode encrypted backup
         * envelope (as opposed to a v1 plaintext JSON backup).
         */
        fun isEncryptedEnvelope(rawContent: String): Boolean {
            val trimmed = rawContent.trim()
            if (!trimmed.startsWith("{") || !trimmed.contains(EncryptedBackupEnvelope.FORMAT_MAGIC)) {
                return false
            }
            return runCatching {
                val elem = envelopeJson.parseToJsonElement(trimmed)
                (elem as? JsonObject)?.get("format")?.jsonPrimitive?.contentOrNull ==
                    EncryptedBackupEnvelope.FORMAT_MAGIC
            }.getOrDefault(false)
        }
    }
}
