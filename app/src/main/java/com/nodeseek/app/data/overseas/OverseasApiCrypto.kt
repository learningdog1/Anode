package com.nodeseek.app.data.overseas

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
internal data class EncryptedApiEnvelope(
    val v: Int = 1,
    val alg: String = "",
    val iv: String = "",
    val data: String = "",
)

/**
 * Implements the LowEndTalk Promotion Monitor API cryptographic contract:
 * - Subkey derivation via `SHA-256(masterSecret || domainTag)`
 * - Request authentication via `HMAC-SHA256` (`X-Timestamp` + `X-Nonce` + `X-Signature`)
 * - Response envelope decryption via `AES-256-GCM` (96-bit IV, 128-bit authentication tag)
 */
object OverseasApiCrypto {

    private const val HMAC_ALGO = "HmacSHA256"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val EXPECTED_ENVELOPE_VERSION = 1
    private const val EXPECTED_ALGORITHM = "AES-256-GCM"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_TAG_LENGTH_BYTES = 16
    private const val NONCE_BYTES = 16

    private val HMAC_DOMAIN_SUFFIX = ":hmac-v1".toByteArray(Charsets.UTF_8)
    private val AES_DOMAIN_SUFFIX = ":aes-gcm-v1".toByteArray(Charsets.UTF_8)

    private val envelopeJson = Json {
        ignoreUnknownKeys = true
    }

    private val secureRandom = SecureRandom()

    fun deriveHmacKey(masterSecret: ByteArray): ByteArray {
        require(masterSecret.isNotEmpty()) { "Master secret must not be empty" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(masterSecret)
        digest.update(HMAC_DOMAIN_SUFFIX)
        return digest.digest()
    }

    fun deriveAesKey(masterSecret: ByteArray): ByteArray {
        require(masterSecret.isNotEmpty()) { "Master secret must not be empty" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(masterSecret)
        digest.update(AES_DOMAIN_SUFFIX)
        return digest.digest()
    }

    fun generateNonce(random: SecureRandom = secureRandom): String {
        val bytes = ByteArray(NONCE_BYTES)
        random.nextBytes(bytes)
        return bytes.toLowerHex()
    }

    fun buildCanonicalRequest(
        method: String,
        pathWithQuery: String,
        timestampSec: String,
        nonce: String,
    ): String = buildString {
        append(method.trim().uppercase(Locale.ROOT))
        append('\n')
        append(pathWithQuery)
        append('\n')
        append(timestampSec)
        append('\n')
        append(nonce)
    }

    fun signRequest(
        hmacKey: ByteArray,
        method: String,
        pathWithQuery: String,
        timestampSec: String,
        nonce: String,
    ): String {
        val canonical = buildCanonicalRequest(
            method = method,
            pathWithQuery = pathWithQuery,
            timestampSec = timestampSec,
            nonce = nonce,
        )
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(hmacKey, HMAC_ALGO))
        val signatureBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return signatureBytes.toLowerHex()
    }

    fun decryptEnvelope(
        aesKey: ByteArray,
        rawEnvelopeJson: String,
        aad: ByteArray? = null,
    ): String {
        if (rawEnvelopeJson.isBlank()) {
            throw GeneralSecurityException("Encrypted response envelope is empty")
        }
        val envelope = try {
            envelopeJson.decodeFromString<EncryptedApiEnvelope>(rawEnvelopeJson)
        } catch (e: Exception) {
            throw GeneralSecurityException("Malformed encrypted response envelope", e)
        }

        if (envelope.v != EXPECTED_ENVELOPE_VERSION) {
            throw GeneralSecurityException("Unsupported envelope version: ${envelope.v}")
        }
        if (!envelope.alg.equals(EXPECTED_ALGORITHM, ignoreCase = true)) {
            throw GeneralSecurityException("Unsupported encryption algorithm: ${envelope.alg}")
        }

        val ivBytes = try {
            Base64.getDecoder().decode(envelope.iv.trim())
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid Base64 IV in response envelope", e)
        }
        if (ivBytes.size != GCM_IV_LENGTH_BYTES) {
            throw GeneralSecurityException("Invalid AES-GCM IV length: ${ivBytes.size}")
        }

        val cipherWithTag = try {
            Base64.getDecoder().decode(envelope.data.trim())
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid Base64 ciphertext in response envelope", e)
        }
        if (cipherWithTag.size < GCM_TAG_LENGTH_BYTES) {
            throw GeneralSecurityException("AES-GCM payload shorter than authentication tag")
        }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val plainBytes = cipher.doFinal(cipherWithTag)
        return plainBytes.toString(Charsets.UTF_8)
    }

    internal fun encryptEnvelope(
        aesKey: ByteArray,
        plaintextUtf8: String,
        ivBytes: ByteArray = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes),
        aad: ByteArray? = null,
    ): String {
        require(ivBytes.size == GCM_IV_LENGTH_BYTES) { "IV must be 12 bytes" }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes),
        )
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val cipherWithTag = cipher.doFinal(plaintextUtf8.toByteArray(Charsets.UTF_8))
        val envelope = EncryptedApiEnvelope(
            v = EXPECTED_ENVELOPE_VERSION,
            alg = EXPECTED_ALGORITHM,
            iv = Base64.getEncoder().encodeToString(ivBytes),
            data = Base64.getEncoder().encodeToString(cipherWithTag),
        )
        return envelopeJson.encodeToString(envelope)
    }

    internal fun ByteArray.toLowerHex(): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(out)
    }
}
