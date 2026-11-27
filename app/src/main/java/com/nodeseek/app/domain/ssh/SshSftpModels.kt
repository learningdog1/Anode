package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Categorized SFTP error codes mapped from JSch `ChannelSftp` / `SftpException` and client guards.
 */
enum class SshSftpErrorCode {
    NOT_FOUND,
    PERMISSION_DENIED,
    IS_DIRECTORY,
    NOT_DIRECTORY,
    FILE_TOO_LARGE,
    BINARY_FILE,
    CONNECTION_LOST,
    UNKNOWN,
}

/**
 * Domain exception raised by [SshSftpClient] operations.
 */
class SshSftpException(
    val code: SshSftpErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Single directory entry returned by [SshSftpClient.listDir].
 */
@Immutable
data class SshSftpEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean = false,
    val sizeBytes: Long = 0L,
    val permissionsString: String = "",
    val modifiedEpochSeconds: Long = 0L,
)

/**
 * Text/log preview payload returned by [SshSftpClient.readTextPreview].
 */
@Immutable
data class SshSftpTextContent(
    val remotePath: String,
    val text: String,
    val totalBytes: Long,
    val readBytes: Long,
    val isTruncatedFromHead: Boolean = false,
    val isBinaryDetected: Boolean = false,
    val canEdit: Boolean = true,
)

/**
 * Outcome of [SshSftpClient.writeWithBakBackup] indicating whether a `.bak` backup was created.
 */
@Immutable
data class SshSftpWriteResult(
    val remotePath: String,
    val backupPath: String?,
    val bytesWritten: Long,
    val backupCreated: Boolean,
)

/**
 * Curated quick-access shortcut for common VPS configuration files and log paths.
 */
@Immutable
data class SshSftpQuickShortcut(
    val id: String,
    val label: String,
    val path: String,
    val isDirectory: Boolean = false,
    val preferEditMode: Boolean = true,
)

object SshSftpQuickShortcuts {
    val presets: List<SshSftpQuickShortcut> = listOf(
        SshSftpQuickShortcut("sshd_config", "sshd_config", "/etc/ssh/sshd_config", preferEditMode = true),
        SshSftpQuickShortcut("caddyfile", "Caddyfile", "/etc/caddy/Caddyfile", preferEditMode = true),
        SshSftpQuickShortcut("sing_box", "sing-box", "/etc/sing-box/config.json", preferEditMode = true),
        SshSftpQuickShortcut("docker_compose", "docker-compose.yml", "docker-compose.yml", preferEditMode = true),
        SshSftpQuickShortcut("nginx_conf", "nginx.conf", "/etc/nginx/nginx.conf", preferEditMode = true),
        SshSftpQuickShortcut("auth_log", "auth.log", "/var/log/auth.log", preferEditMode = false),
        SshSftpQuickShortcut("syslog", "syslog", "/var/log/syslog", preferEditMode = false),
        SshSftpQuickShortcut("dir_etc", "/etc", "/etc", isDirectory = true),
        SshSftpQuickShortcut("dir_log", "/var/log", "/var/log", isDirectory = true),
    )
}

/**
 * Result of an isolated one-shot SSH command executed via `LiteSshSessionManager.runOneShotCommand`
 * (e.g., reverse traceroute/MTR from a vaulted VPS without disturbing active console sessions).
 */
@Immutable
data class SshOneShotCommandResult(
    val serverId: String,
    val serverAlias: String,
    val serverHost: String,
    val command: String,
    val sanitizedAnsiOutput: String,
    val plainOutput: String,
    val exitCode: Int,
    val durationMillis: Long,
    val connectionMode: SshConnectionMode,
)
