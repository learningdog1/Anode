package com.nodeseek.app.data.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.nodeseek.app.domain.ssh.SshSftpClient
import com.nodeseek.app.domain.ssh.SshSftpEntry
import com.nodeseek.app.domain.ssh.SshSftpErrorCode
import com.nodeseek.app.domain.ssh.SshSftpException
import com.nodeseek.app.domain.ssh.SshSftpTextContent
import com.nodeseek.app.domain.ssh.SshSftpWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class SftpLsEntry(
    val filename: String,
    val longname: String = "",
    val isDir: Boolean,
    val isLink: Boolean = false,
    val sizeBytes: Long = 0L,
    val permissionsString: String = "",
    val modifiedEpochSeconds: Long = 0L,
)

internal data class SftpAttrs(
    val isDir: Boolean,
    val isLink: Boolean = false,
    val sizeBytes: Long = 0L,
    val permissionsString: String = "",
    val modifiedEpochSeconds: Long = 0L,
)

/**
 * Thin wrapper over `com.jcraft.jsch.ChannelSftp` allowing deterministic JVM unit testing
 * of [JschSftpClient] without requiring a live `sshd` server.
 */
internal interface ManagedSftpChannel {
    val isClosed: Boolean
    fun connect(connectTimeoutMillis: Int)
    fun pwd(): String
    fun getHome(): String
    fun ls(path: String): List<SftpLsEntry>
    fun stat(path: String): SftpAttrs?
    fun lstat(path: String): SftpAttrs?
    fun get(path: String): InputStream
    fun get(path: String, skipOffsetBytes: Long): InputStream
    fun put(inputStream: InputStream, path: String, overwrite: Boolean = true)
    fun mkdir(path: String)
    fun rm(path: String)
    fun rmdir(path: String)
    fun disconnect()
}

internal class JschManagedSftpChannel(
    private val channel: ChannelSftp,
) : ManagedSftpChannel {

    override val isClosed: Boolean
        get() = channel.isClosed

    override fun connect(connectTimeoutMillis: Int) {
        channel.connect(connectTimeoutMillis)
        runCatching { channel.setFilenameEncoding("UTF-8") }
    }

    override fun pwd(): String = runSftpCall("pwd") { channel.pwd() }

    override fun getHome(): String = runSftpCall("getHome") { channel.home }

    override fun ls(path: String): List<SftpLsEntry> = runSftpCall(path) {
        val raw = channel.ls(path)
        val entries = ArrayList<SftpLsEntry>(raw.size)
        for (lsEntry in raw) {
            if (lsEntry == null) continue
            val attrs: SftpATTRS? = lsEntry.attrs
            entries += SftpLsEntry(
                filename = lsEntry.filename.orEmpty(),
                longname = lsEntry.longname.orEmpty(),
                isDir = attrs?.isDir == true,
                isLink = attrs?.isLink == true,
                sizeBytes = attrs?.size ?: 0L,
                permissionsString = attrs?.permissionsString.orEmpty(),
                modifiedEpochSeconds = attrs?.mTime?.toLong() ?: 0L,
            )
        }
        entries
    }

    override fun stat(path: String): SftpAttrs? = try {
        channel.stat(path)?.toDomainAttrs()
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw mapJschSftpException(e, path)
    }

    override fun lstat(path: String): SftpAttrs? = try {
        channel.lstat(path)?.toDomainAttrs()
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw mapJschSftpException(e, path)
    }

    override fun get(path: String): InputStream = runSftpCall(path) {
        channel.get(path)
    }

    override fun get(path: String, skipOffsetBytes: Long): InputStream = runSftpCall(path) {
        channel.get(path, null, skipOffsetBytes.coerceAtLeast(0L))
    }

    override fun put(inputStream: InputStream, path: String, overwrite: Boolean) {
        runSftpCall(path) {
            val mode = if (overwrite) ChannelSftp.OVERWRITE else ChannelSftp.RESUME
            channel.put(inputStream, path, mode)
        }
    }

    override fun mkdir(path: String) {
        runSftpCall(path) { channel.mkdir(path) }
    }

    override fun rm(path: String) {
        runSftpCall(path) { channel.rm(path) }
    }

    override fun rmdir(path: String) {
        runSftpCall(path) { channel.rmdir(path) }
    }

    override fun disconnect() {
        channel.disconnect()
    }

    private inline fun <T> runSftpCall(target: String, block: () -> T): T {
        return try {
            block()
        } catch (e: SftpException) {
            throw mapJschSftpException(e, target)
        }
    }

    private fun SftpATTRS.toDomainAttrs(): SftpAttrs = SftpAttrs(
        isDir = isDir,
        isLink = isLink,
        sizeBytes = size,
        permissionsString = permissionsString.orEmpty(),
        modifiedEpochSeconds = mTime.toLong(),
    )

    companion object {
        internal fun mapJschSftpException(e: SftpException, target: String): SshSftpException {
            val code = when (e.id) {
                ChannelSftp.SSH_FX_NO_SUCH_FILE -> SshSftpErrorCode.NOT_FOUND
                ChannelSftp.SSH_FX_PERMISSION_DENIED -> SshSftpErrorCode.PERMISSION_DENIED
                else -> SshSftpErrorCode.UNKNOWN
            }
            val msg = when (code) {
                SshSftpErrorCode.NOT_FOUND -> "远程路径不存在：$target"
                SshSftpErrorCode.PERMISSION_DENIED -> "权限不足（$target），请检查账户权限或使用 root 登录"
                else -> "SFTP 操作失败（$target）：${e.message?.takeIf { it.isNotBlank() } ?: "code=${e.id}"}"
            }
            return SshSftpException(code = code, message = msg, cause = e)
        }
    }
}

/**
 * Coroutine-safe [SshSftpClient] serializing all operations against a single `ChannelSftp`
 * via [Mutex] on [ioDispatcher], supporting directory browsing, log tailing, binary guards,
 * streaming uploads, and atomic-intent `.bak` backups before config overwrites.
 */
class JschSftpClient internal constructor(
    private val channel: ManagedSftpChannel,
    override val homeDirectory: String,
    private val onCloseResources: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshSftpClient {

    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = !closed.get() && !channel.isClosed

    override suspend fun listDir(path: String): List<SshSftpEntry> = serializedOp {
        val resolvedDir = normalizeRemoteDirPath(path, homeDirectory)
        val rawEntries = channel.ls(resolvedDir)
        rawEntries.asSequence()
            .filter { it.filename.isNotEmpty() && it.filename != "." && it.filename != ".." }
            .map { entry ->
                val childPath = joinRemotePath(resolvedDir, entry.filename)
                val resolvedIsDir = if (entry.isLink) {
                    runCatching { channel.stat(childPath)?.isDir == true }.getOrDefault(entry.isDir)
                } else {
                    entry.isDir
                }
                SshSftpEntry(
                    name = entry.filename,
                    fullPath = childPath,
                    isDirectory = resolvedIsDir,
                    isSymlink = entry.isLink,
                    sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                    permissionsString = entry.permissionsString,
                    modifiedEpochSeconds = entry.modifiedEpochSeconds.coerceAtLeast(0L),
                )
            }
            .sortedWith(
                compareByDescending<SshSftpEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
    }

    override suspend fun readTextPreview(
        remotePath: String,
        maxBytes: Long,
        tailIfLarger: Boolean,
    ): SshSftpTextContent = serializedOp {
        val cleanPath = resolveFilePath(remotePath, homeDirectory)
        val safeMax = maxBytes.coerceAtLeast(1L)
        val attrs = channel.stat(cleanPath)
            ?: throw SshSftpException(SshSftpErrorCode.NOT_FOUND, "文件不存在：$cleanPath")
        if (attrs.isDir) {
            throw SshSftpException(SshSftpErrorCode.IS_DIRECTORY, "目标是目录，无法作为文本打开：$cleanPath")
        }

        val totalBytes = attrs.sizeBytes.coerceAtLeast(0L)
        if (totalBytes > safeMax && !tailIfLarger) {
            throw SshSftpException(
                code = SshSftpErrorCode.FILE_TOO_LARGE,
                message = "文件超过 ${safeMax / 1024} KB，不支持在线编辑以防截断",
            )
        }

        val isTruncated = totalBytes > safeMax
        val offset = if (isTruncated) (totalBytes - safeMax).coerceAtLeast(0L) else 0L
        val readLimit = minOf(safeMax, Int.MAX_VALUE.toLong()).toInt()
        val payloadBytes = if (offset > 0L) {
            channel.get(cleanPath, offset).use { stream ->
                readAtMost(stream, readLimit)
            }
        } else {
            channel.get(cleanPath).use { stream ->
                readAtMost(stream, readLimit)
            }
        }

        val isBinary = isLikelyBinary(payloadBytes)
        val text = if (isBinary) {
            ""
        } else {
            String(payloadBytes, StandardCharsets.UTF_8)
        }
        val canEdit = !isTruncated && !isBinary && totalBytes <= SshSftpClient.MAX_EDITABLE_BYTES

        SshSftpTextContent(
            remotePath = cleanPath,
            text = text,
            totalBytes = totalBytes,
            readBytes = payloadBytes.size.toLong(),
            isTruncatedFromHead = isTruncated,
            isBinaryDetected = isBinary,
            canEdit = canEdit,
        )
    }

    override suspend fun writeWithBakBackup(
        remotePath: String,
        content: ByteArray,
        createBackupIfExists: Boolean,
    ): SshSftpWriteResult = serializedOp {
        val cleanPath = resolveFilePath(remotePath, homeDirectory)
        if (content.size.toLong() > SshSftpClient.MAX_EDITABLE_BYTES) {
            throw SshSftpException(
                code = SshSftpErrorCode.FILE_TOO_LARGE,
                message = "待保存内容超过 ${SshSftpClient.MAX_EDITABLE_BYTES / 1024} KB 上限",
            )
        }

        val existing = channel.stat(cleanPath)
        if (existing != null && existing.isDir) {
            throw SshSftpException(SshSftpErrorCode.IS_DIRECTORY, "目标路径是目录，无法覆盖写入：$cleanPath")
        }

        var backupCreated = false
        var backupPath: String? = null
        if (existing != null && createBackupIfExists) {
            if (existing.sizeBytes > SshSftpClient.MAX_EDITABLE_BYTES) {
                throw SshSftpException(
                    code = SshSftpErrorCode.FILE_TOO_LARGE,
                    message = "原文件过大（${existing.sizeBytes / 1024} KB），暂不支持在线覆写备份",
                )
            }
            val originalBytes = channel.get(cleanPath).use { it.readBytes() }
            val candidateBackupPath = "$cleanPath.bak"
            try {
                channel.put(
                    inputStream = ByteArrayInputStream(originalBytes),
                    path = candidateBackupPath,
                    overwrite = true,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is SshSftpException) throw t
                if (t is SftpException) {
                    throw JschManagedSftpChannel.mapJschSftpException(t, candidateBackupPath)
                }
                throw SshSftpException(
                    code = SshSftpErrorCode.UNKNOWN,
                    message = "创建备份文件 $candidateBackupPath 失败，已中止覆盖原文件：${t.message ?: t.javaClass.simpleName}",
                    cause = t,
                )
            }
            val verifiedBackup = channel.stat(candidateBackupPath)
                ?: throw SshSftpException(
                    code = SshSftpErrorCode.UNKNOWN,
                    message = "备份文件 $candidateBackupPath 未能通过校验，已中止覆盖原文件",
                )
            if (verifiedBackup.sizeBytes != originalBytes.size.toLong()) {
                throw SshSftpException(
                    code = SshSftpErrorCode.UNKNOWN,
                    message = "备份文件 $candidateBackupPath 写入大小与原文件不一致（期望 ${originalBytes.size} B，实际 ${verifiedBackup.sizeBytes} B），已中止覆盖原文件",
                )
            }
            backupCreated = true
            backupPath = candidateBackupPath
        }

        channel.put(
            inputStream = ByteArrayInputStream(content),
            path = cleanPath,
            overwrite = true,
        )

        SshSftpWriteResult(
            remotePath = cleanPath,
            backupPath = backupPath,
            bytesWritten = content.size.toLong(),
            backupCreated = backupCreated,
        )
    }

    override suspend fun uploadStream(
        remotePath: String,
        inputStream: InputStream,
        overwrite: Boolean,
    ): Long = serializedOp {
        val cleanPath = resolveFilePath(remotePath, homeDirectory)
        val existing = channel.stat(cleanPath)
        if (existing != null && existing.isDir) {
            throw SshSftpException(SshSftpErrorCode.IS_DIRECTORY, "目标路径是目录，无法上传覆盖：$cleanPath")
        }
        val counted = BoundedCountingInputStream(inputStream, SshSftpClient.MAX_UPLOAD_BYTES)
        channel.put(
            inputStream = counted,
            path = cleanPath,
            overwrite = overwrite,
        )
        counted.bytesRead.get()
    }

    override suspend fun mkdir(remotePath: String) = serializedOp {
        val cleanPath = resolveFilePath(remotePath, homeDirectory)
        channel.mkdir(cleanPath)
    }

    override suspend fun delete(remotePath: String, isDirectory: Boolean) = serializedOp {
        val cleanPath = resolveFilePath(remotePath, homeDirectory)
        if (isDirectory) {
            channel.rmdir(cleanPath)
        } else {
            channel.rm(cleanPath)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { channel.disconnect() }
        runCatching { onCloseResources() }
    }

    private suspend inline fun <T> serializedOp(crossinline block: () -> T): T {
        if (closed.get() || channel.isClosed) {
            throw SshSftpException(SshSftpErrorCode.CONNECTION_LOST, "SFTP 会话已断开，请重新连接")
        }
        return mutex.withLock {
            if (closed.get() || channel.isClosed) {
                throw SshSftpException(SshSftpErrorCode.CONNECTION_LOST, "SFTP 会话已断开，请重新连接")
            }
            withContext(ioDispatcher) {
                try {
                    block()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (sftpEx: SshSftpException) {
                    throw sftpEx
                } catch (e: SftpException) {
                    throw JschManagedSftpChannel.mapJschSftpException(e, "")
                } catch (t: Throwable) {
                    val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    val code = if (channel.isClosed ||
                        msg.contains("closed", ignoreCase = true) ||
                        msg.contains("pipe", ignoreCase = true) ||
                        msg.contains("disconnect", ignoreCase = true)
                    ) {
                        SshSftpErrorCode.CONNECTION_LOST
                    } else {
                        SshSftpErrorCode.UNKNOWN
                    }
                    throw SshSftpException(code = code, message = msg, cause = t)
                }
            }
        }
    }

    private class BoundedCountingInputStream(
        delegate: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(delegate) {
        val bytesRead = AtomicLong(0L)

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                val next = bytesRead.incrementAndGet()
                if (next > maxBytes) {
                    throw SshSftpException(
                        SshSftpErrorCode.FILE_TOO_LARGE,
                        "上传文件超过 ${maxBytes / (1024L * 1024L)} MB 上限",
                    )
                }
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                val next = bytesRead.addAndGet(count.toLong())
                if (next > maxBytes) {
                    throw SshSftpException(
                        SshSftpErrorCode.FILE_TOO_LARGE,
                        "上传文件超过 ${maxBytes / (1024L * 1024L)} MB 上限",
                    )
                }
            }
            return count
        }
    }

    companion object {
        private fun rejectControlCharsInPath(raw: String) {
            if (raw.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                throw SshSftpException(
                    code = SshSftpErrorCode.UNKNOWN,
                    message = "远程路径包含非法控制字符",
                )
            }
        }

        internal fun normalizeRemoteDirPath(rawPath: String, homeDirectory: String): String {
            rejectControlCharsInPath(rawPath)
            rejectControlCharsInPath(homeDirectory)
            val cleanHome = homeDirectory.trim().ifBlank { "/" }.let { normalizeSlashes(it) }
            val trimmed = rawPath.trim()
            if (trimmed.isEmpty() || trimmed == ".") return cleanHome
            if (trimmed == "~") return cleanHome
            if (trimmed.startsWith("~/")) {
                return joinRemotePath(cleanHome, trimmed.removePrefix("~/"))
            }
            val absolute = if (trimmed.startsWith("/")) trimmed else joinRemotePath(cleanHome, trimmed)
            return normalizeSlashes(absolute)
        }

        internal fun resolveFilePath(rawPath: String, homeDirectory: String): String {
            rejectControlCharsInPath(rawPath)
            rejectControlCharsInPath(homeDirectory)
            val cleanHome = homeDirectory.trim().ifBlank { "/" }.let { normalizeSlashes(it) }
            val trimmed = rawPath.trim()
            require(trimmed.isNotEmpty()) { "远程路径不能为空" }
            val absolute = when {
                trimmed.startsWith("~/") -> joinRemotePath(cleanHome, trimmed.removePrefix("~/"))
                trimmed.startsWith("/") -> trimmed
                else -> joinRemotePath(cleanHome, trimmed)
            }
            return normalizeSlashes(absolute)
        }

        internal fun joinRemotePath(baseDir: String, child: String): String {
            rejectControlCharsInPath(baseDir)
            rejectControlCharsInPath(child)
            val cleanChild = child.trim().trimStart('/')
            if (cleanChild.isEmpty()) return normalizeSlashes(baseDir)
            val cleanBase = normalizeSlashes(baseDir)
            val joined = if (cleanBase == "/") "/$cleanChild" else "$cleanBase/$cleanChild"
            return normalizeSlashes(joined)
        }

        internal fun normalizeSlashes(path: String): String {
            rejectControlCharsInPath(path)
            val parts = path.split('/')
            val stack = ArrayDeque<String>()
            for (seg in parts) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (stack.isNotEmpty()) stack.removeLast()
                    else -> stack.addLast(seg)
                }
            }
            return if (stack.isEmpty()) "/" else "/" + stack.joinToString("/")
        }

        internal fun parentDirectory(path: String): String {
            val normalized = normalizeSlashes(path)
            if (normalized == "/") return "/"
            val lastSlash = normalized.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else normalized.substring(0, lastSlash)
        }

        internal fun isLikelyBinary(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val sampleLen = bytes.size.coerceAtMost(8192)
            var controlCount = 0
            for (i in 0 until sampleLen) {
                val unsigned = bytes[i].toInt() and 0xFF
                if (unsigned == 0x00) return true
                // Allow TAB (0x09), LF (0x0A), CR (0x0D), ESC (0x1B)
                val isControl = unsigned < 0x20 &&
                    unsigned != 0x09 &&
                    unsigned != 0x0A &&
                    unsigned != 0x0D &&
                    unsigned != 0x1B
                if (isControl) controlCount++
            }
            return controlCount.toDouble() / sampleLen.toDouble() > 0.10
        }

        private fun readAtMost(stream: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
            val buffer = ByteArray(8192)
            var remaining = maxBytes
            while (remaining > 0) {
                val n = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) break
                if (n > 0) {
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
            return out.toByteArray()
        }
    }
}
