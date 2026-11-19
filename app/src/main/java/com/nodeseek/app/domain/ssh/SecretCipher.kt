package com.nodeseek.app.domain.ssh

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Encryption abstraction for securing SSH passwords, private keys, and key passphrases at rest.
 *
 * Production builds use `AndroidKeystoreAesGcmCipher` backed by Android Keystore AES-256-GCM.
 * JVM unit tests use [InMemoryTestCipher] for deterministic round-trip verification.
 */
interface SecretCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * Thrown when encrypting or decrypting a stored SSH vault secret fails.
 */
class SshVaultCryptoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Deterministic, pure-JVM [SecretCipher] implementation intended for unit tests.
 *
 * Applies a reversible keyed XOR transform + Base64 encoding with a versioned prefix so tests
 * can verify that vault files never contain raw plaintext secrets while remaining deterministic.
 */
class InMemoryTestCipher(
    keyMaterial: String = "anode-test-cipher-key-v1",
) : SecretCipher {
    private val keyBytes: ByteArray = keyMaterial.toByteArray(StandardCharsets.UTF_8)
        .takeIf { it.isNotEmpty() }
        ?: byteArrayOf(0x5A)

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val raw = plaintext.toByteArray(StandardCharsets.UTF_8)
        val obfuscated = ByteArray(raw.size) { idx ->
            (raw[idx].toInt() xor keyBytes[idx % keyBytes.size].toInt() xor ((idx * 31) and 0xFF)).toByte()
        }
        return PREFIX + Base64.getEncoder().encodeToString(obfuscated)
    }

    override fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        if (!ciphertext.startsWith(PREFIX)) {
            throw SshVaultCryptoException("Invalid test ciphertext prefix")
        }
        val payload = ciphertext.removePrefix(PREFIX)
        val decoded = runCatching { Base64.getDecoder().decode(payload) }
            .getOrElse { throw SshVaultCryptoException("Corrupted test ciphertext", it) }
        val restored = ByteArray(decoded.size) { idx ->
            (decoded[idx].toInt() xor keyBytes[idx % keyBytes.size].toInt() xor ((idx * 31) and 0xFF)).toByte()
        }
        return String(restored, StandardCharsets.UTF_8)
    }

    companion object {
        const val PREFIX: String = "test_enc_v1:"
    }
}
