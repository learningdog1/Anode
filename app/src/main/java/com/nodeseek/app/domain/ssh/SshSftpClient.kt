package com.nodeseek.app.domain.ssh

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Stateful SFTP session client backed strictly by `com.jcraft.jsch.ChannelSftp` in production.
 */
interface SshSftpClient : AutoCloseable {
    val homeDirectory: String
    val isConnected: Boolean

    suspend fun listDir(path: String = "."): List<SshSftpEntry>

    suspend fun readTextPreview(
        remotePath: String,
        maxBytes: Long = DEFAULT_PREVIEW_MAX_BYTES,
        tailIfLarger: Boolean = true,
    ): SshSftpTextContent

    suspend fun writeWithBakBackup(
        remotePath: String,
        content: ByteArray,
        createBackupIfExists: Boolean = true,
    ): SshSftpWriteResult

    suspend fun uploadStream(
        remotePath: String,
        inputStream: InputStream,
        overwrite: Boolean = true,
    ): Long

    suspend fun uploadBytes(
        remotePath: String,
        bytes: ByteArray,
        overwrite: Boolean = true,
    ): Long = uploadStream(remotePath, ByteArrayInputStream(bytes), overwrite)

    suspend fun mkdir(remotePath: String)

    suspend fun delete(remotePath: String, isDirectory: Boolean)

    override fun close()

    companion object {
        const val DEFAULT_PREVIEW_MAX_BYTES: Long = 512L * 1024L
        const val MAX_EDITABLE_BYTES: Long = 1024L * 1024L
        const val MAX_UPLOAD_BYTES: Long = 50L * 1024L * 1024L
    }
}

/**
 * Optional SFTP capability interface implemented by SSH transports that support `ChannelSftp`
 * (kept separate from [SshTransport] so existing test fakes remain 100% compatible).
 */
interface SshSftpTransport {
    suspend fun openSftpSession(
        request: SshConnectionRequest,
        onHostKeyObserved: (HostKeyVerificationResult, String?) -> Unit = { _, _ -> },
    ): SshSftpClient
}
