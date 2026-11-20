package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * High-level streaming updates emitted by [LiteSshExecutor] after applying [AnsiStreamSanitizer].
 */
sealed interface LiteSshExecutionEvent {
    data object Connecting : LiteSshExecutionEvent
    data class HostKeyChecked(
        val verification: HostKeyVerificationResult,
        val serverId: String? = null,
    ) : LiteSshExecutionEvent
    data object Connected : LiteSshExecutionEvent
    data class OutputUpdated(
        val sanitizedAnsiOutput: String,
        val elapsedMillis: Long,
    ) : LiteSshExecutionEvent
    data class Completed(
        val exitCode: Int,
        val sanitizedAnsiOutput: String,
        val elapsedMillis: Long,
    ) : LiteSshExecutionEvent
}

/**
 * Coordinates streaming remote SSH command execution over an [SshTransport], feeding raw PTY
 * chunks incrementally through [AnsiStreamSanitizer] so consumers always receive clean,
 * progress-bar-collapsed, SGR-preserved terminal output.
 */
class LiteSshExecutor(
    private val transport: SshTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxOutputLines: Int = AnsiStreamSanitizer.DEFAULT_MAX_LINES,
) {
    fun runCommand(
        request: SshConnectionRequest,
        command: String,
    ): Flow<LiteSshExecutionEvent> = flow {
        val startedAt = clock()
        val sanitizer = AnsiStreamSanitizer(maxLines = maxOutputLines)
        emit(LiteSshExecutionEvent.Connecting)

        var sawCompleted = false
        transport.execute(request = request, command = command).collect { event ->
            when (event) {
                is SshStreamEvent.HostKeyObserved -> {
                    emit(LiteSshExecutionEvent.HostKeyChecked(event.verification, event.serverId))
                }
                SshStreamEvent.Connected -> {
                    emit(LiteSshExecutionEvent.Connected)
                }
                is SshStreamEvent.OutputChunk -> {
                    sanitizer.append(event.rawText)
                    emit(
                        LiteSshExecutionEvent.OutputUpdated(
                            sanitizedAnsiOutput = sanitizer.snapshot(),
                            elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                        ),
                    )
                }
                is SshStreamEvent.Completed -> {
                    sawCompleted = true
                    emit(
                        LiteSshExecutionEvent.Completed(
                            exitCode = event.exitCode,
                            sanitizedAnsiOutput = sanitizer.snapshot(),
                            elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }

        if (!sawCompleted) {
            emit(
                LiteSshExecutionEvent.Completed(
                    exitCode = 0,
                    sanitizedAnsiOutput = sanitizer.snapshot(),
                    elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                ),
            )
        }
    }
}
