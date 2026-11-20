package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/**
 * Supported SSH authentication methods for Lite SSH server vault entries.
 */
@Serializable
enum class SshAuthMethod {
    PASSWORD,
    PRIVATE_KEY,
}

/**
 * Category grouping for curated and custom VPS benchmark presets.
 */
@Serializable
enum class SshPresetCategory(val label: String) {
    DIAGNOSTIC("系统快检"),
    BENCHMARK("综合跑分"),
    NETWORK("线路路由"),
    UNLOCK("流媒体 / AI"),
    CUSTOM("自定义脚本"),
}

/**
 * Execution lifecycle status for a single SSH script run.
 */
@Serializable
enum class SshRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/**
 * Stored SSH server profile persisted inside `lite_ssh_vault.json`.
 *
 * Credentials ([encryptedSecret] and optional [encryptedPassphrase]) are always stored
 * encrypted via [SecretCipher] and never written to disk in plaintext.
 */
@Immutable
@Serializable
data class SshServerProfile(
    val id: String,
    val alias: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String = DEFAULT_USERNAME,
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val encryptedSecret: String = "",
    val encryptedPassphrase: String? = null,
    val knownHostKeyFingerprint: String? = null,
    val knownHostKeyAlgorithm: String? = null,
    val lastConnectedEpochMillis: Long? = null,
    val createdAtEpochMillis: Long = 0L,
    val jumpHostId: String? = null,
) {
    val displayName: String
        get() = alias.trim().ifBlank { host.trim() }

    val displayAddress: String
        get() {
            val cleanUser = username.trim().ifBlank { DEFAULT_USERNAME }
            val cleanHost = host.trim()
            return if (port == DEFAULT_PORT) {
                "$cleanUser@$cleanHost"
            } else {
                "$cleanUser@$cleanHost:$port"
            }
        }

    val hasTrustedHostKey: Boolean
        get() = !knownHostKeyFingerprint.isNullOrBlank()

    val usesJumpHost: Boolean
        get() = !jumpHostId.isNullOrBlank()

    companion object {
        const val DEFAULT_PORT: Int = 22
        const val DEFAULT_USERNAME: String = "root"
    }
}

/**
 * Transient plaintext form model used when creating or editing an [SshServerProfile].
 */
@Immutable
data class SshServerDraft(
    val id: String? = null,
    val alias: String,
    val host: String,
    val port: Int = SshServerProfile.DEFAULT_PORT,
    val username: String = SshServerProfile.DEFAULT_USERNAME,
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val secret: String = "",
    val passphrase: String? = null,
    val keepExistingSecret: Boolean = false,
    val keepExistingPassphrase: Boolean = false,
    val jumpHostId: String? = null,
)

/**
 * In-memory decrypted SSH credentials used strictly during connection setup.
 */
data class DecryptedSshCredentials(
    val secret: String,
    val passphrase: String? = null,
)

/**
 * Optional CLI flag toggle exposed on a curated [SshScriptPreset].
 */
@Immutable
@Serializable
data class SshPresetFlag(
    val id: String,
    val label: String,
    val flag: String,
    val description: String = "",
    val enabledByDefault: Boolean = false,
    val exclusiveGroup: String? = null,
)

/**
 * Executable VPS benchmark or diagnostic script preset (either built-in or user-defined).
 */
@Immutable
@Serializable
data class SshScriptPreset(
    val id: String,
    val name: String,
    val tabTitle: String,
    val category: SshPresetCategory,
    val description: String,
    val estimatedDurationLabel: String,
    val commandTemplate: String,
    val flags: List<SshPresetFlag> = emptyList(),
    val isCustom: Boolean = false,
) {
    val defaultEnabledFlagIds: Set<String>
        get() = flags.filter { it.enabledByDefault }.map { it.id }.toSet()

    /**
     * Resolves the concrete shell command with the chosen [enabledFlagIds] and standard
     * headless non-interactive environment exports (`TERM=xterm-256color`, `DEBIAN_FRONTEND=noninteractive`).
     */
    fun buildCommand(enabledFlagIds: Set<String> = defaultEnabledFlagIds): String {
        val activeFlags = flags
            .filter { it.id in enabledFlagIds }
            .map { it.flag.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        val expanded = if (commandTemplate.contains(FLAGS_PLACEHOLDER)) {
            if (activeFlags.isEmpty()) {
                commandTemplate
                    .replace(" -- $FLAGS_PLACEHOLDER", "")
                    .replace(" $FLAGS_PLACEHOLDER", "")
                    .replace(FLAGS_PLACEHOLDER, "")
            } else {
                commandTemplate.replace(FLAGS_PLACEHOLDER, activeFlags)
            }
        } else if (activeFlags.isNotEmpty()) {
            "$commandTemplate $activeFlags"
        } else {
            commandTemplate
        }.trim()

        return SshPresetCatalog.wrapHeadlessCommand(expanded)
    }

    companion object {
        const val FLAGS_PLACEHOLDER: String = "{{FLAGS}}"
    }
}

/**
 * User-defined custom shell preset persisted in the local SSH vault.
 */
@Immutable
@Serializable
data class CustomSshPreset(
    val id: String,
    val name: String,
    val tabTitle: String = "",
    val description: String = "",
    val command: String,
    val createdAtEpochMillis: Long = 0L,
) {
    fun toScriptPreset(): SshScriptPreset = SshScriptPreset(
        id = id,
        name = name.trim().ifBlank { "自定义脚本" },
        tabTitle = tabTitle.trim().ifBlank { name.trim().ifBlank { "自定义脚本" } },
        category = SshPresetCategory.CUSTOM,
        description = description.trim().ifBlank { "用户自定义远程脚本" },
        estimatedDurationLabel = "自定义",
        commandTemplate = command.trim(),
        flags = emptyList(),
        isCustom = true,
    )
}

/**
 * Completed (or cancelled/failed) SSH script execution snapshot.
 * Stored in the session and recent vault history for 1-click NodeSeek posting and reply insertion.
 */
@Immutable
@Serializable
data class SshExecutionRecord(
    val id: String,
    val serverId: String,
    val serverAlias: String,
    val serverHost: String,
    val presetId: String,
    val presetName: String,
    val tabTitle: String,
    val command: String,
    val sanitizedAnsiOutput: String,
    val exitCode: Int? = null,
    val status: SshRunStatus,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val errorMessage: String? = null,
) {
    val hasOutput: Boolean
        get() = sanitizedAnsiOutput.isNotBlank()
}

/**
 * Observed remote SSH server host key metadata.
 */
@Immutable
data class HostKeyInfo(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprintSha256: String,
)

/**
 * Result of applying Trust-On-First-Use (TOFU) verification against a saved server profile.
 */
sealed interface HostKeyVerificationResult {
    val hostKey: HostKeyInfo

    /** Host key matched the stored SHA-256 fingerprint. */
    data class Trusted(
        override val hostKey: HostKeyInfo,
    ) : HostKeyVerificationResult

    /** First connection to this host — fingerprint should be recorded on trust. */
    data class FirstUseTrust(
        override val hostKey: HostKeyInfo,
    ) : HostKeyVerificationResult

    /** Stored fingerprint differs from the presented server key — requires explicit user confirmation. */
    data class KeyChangedWarning(
        val expectedFingerprint: String,
        override val hostKey: HostKeyInfo,
    ) : HostKeyVerificationResult {
        val actualHostKey: HostKeyInfo
            get() = hostKey
    }
}

/**
 * Trust-On-First-Use (TOFU) host key verification policy using OpenSSH-standard SHA-256 fingerprints.
 */
object HostKeyTrustPolicy {
    private const val SHA256_PREFIX = "SHA256:"

    /**
     * Computes an OpenSSH-formatted `SHA256:<base64-no-pad>` host key fingerprint.
     */
    fun computeSha256Fingerprint(rawHostKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawHostKeyBlob)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "$SHA256_PREFIX$encoded"
    }

    /**
     * Normalizes stored or observed fingerprints for constant-time comparison.
     */
    fun normalizeFingerprint(fingerprint: String?): String? {
        val trimmed = fingerprint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.startsWith(SHA256_PREFIX, ignoreCase = true)) {
            "$SHA256_PREFIX${trimmed.substring(SHA256_PREFIX.length).trimEnd('=')}"
        } else {
            trimmed.trimEnd('=')
        }
    }

    /**
     * Evaluates an observed server [actual] host key against [expectedFingerprint].
     */
    fun verify(
        expectedFingerprint: String?,
        actual: HostKeyInfo,
    ): HostKeyVerificationResult {
        val normalizedExpected = normalizeFingerprint(expectedFingerprint)
        val normalizedActual = normalizeFingerprint(actual.fingerprintSha256)
            ?: actual.fingerprintSha256
        val normalizedInfo = actual.copy(fingerprintSha256 = normalizedActual)

        if (normalizedExpected == null) {
            return HostKeyVerificationResult.FirstUseTrust(hostKey = normalizedInfo)
        }
        return if (MessageDigest.isEqual(normalizedExpected.toByteArray(), normalizedActual.toByteArray())) {
            HostKeyVerificationResult.Trusted(hostKey = normalizedInfo)
        } else {
            HostKeyVerificationResult.KeyChangedWarning(
                expectedFingerprint = normalizedExpected,
                hostKey = normalizedInfo,
            )
        }
    }
}
