package com.nodeseek.app.data.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nodeseek.app.domain.ssh.SecretCipher
import com.nodeseek.app.domain.ssh.SshVaultCryptoException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [SecretCipher] backed by Android Keystore AES-256-GCM (`minSdk >= 26`).
 *
 * Each encryption call generates a fresh random 96-bit GCM IV via Keystore and encodes the
 * payload as `akg1:<base64-iv>:<base64-ciphertext-with-tag>`.
 */
class AndroidKeystoreAesGcmCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val ivEncoded = Base64.getEncoder().encodeToString(iv)
            val dataEncoded = Base64.getEncoder().encodeToString(encrypted)
            "$PAYLOAD_PREFIX$ivEncoded:$dataEncoded"
        } catch (t: Throwable) {
            throw SshVaultCryptoException("Failed to encrypt SSH secret with Android Keystore", t)
        }
    }

    override fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        if (!ciphertext.startsWith(PAYLOAD_PREFIX)) {
            throw SshVaultCryptoException("Unsupported SSH vault ciphertext format")
        }
        val body = ciphertext.removePrefix(PAYLOAD_PREFIX)
        val sepIndex = body.indexOf(':')
        if (sepIndex <= 0 || sepIndex >= body.lastIndex) {
            throw SshVaultCryptoException("Malformed SSH vault ciphertext payload")
        }
        return try {
            val iv = Base64.getDecoder().decode(body.substring(0, sepIndex))
            val encrypted = Base64.getDecoder().decode(body.substring(sepIndex + 1))
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plainBytes = cipher.doFinal(encrypted)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            throw SshVaultCryptoException("Failed to decrypt SSH secret with Android Keystore", t)
        }
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "anode_lite_ssh_vault_aes_gcm_v1"
        private const val ANDROID_KEYSTORE_PROVIDER: String = "AndroidKeyStore"
        private const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS: Int = 256
        private const val GCM_TAG_LENGTH_BITS: Int = 128
        private const val PAYLOAD_PREFIX: String = "akg1:"
    }
}
