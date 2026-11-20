package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level streaming updates emitted by [LiteSshExecutor] after applying [AnsiStreamSanitizer]
 * and optional [SshPromptAutoResponder] fallback handling.
 */
sealed interface LiteSshExecutionEvent {
    data object Connecting : LiteSshExecutionEvent
    data class HostKeyChecked(val verification: HostKeyVerificationResult) : LiteSshExecutionEvent
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
    data class AutoResponded(
        val decision: SshAutoRespondDecision,
    ) : LiteSshExecutionEvent
}

/**
 * Coordinates streaming remote SSH command execution over an [SshTransport], feeding raw PTY
 * chunks incrementally through [AnsiStreamSanitizer] and optionally [SshPromptAutoResponder] so
 * consumers receive clean SGR-preserved terminal output without hanging on `[y/N]` / `Enter` prompts.
 */
class LiteSshExecutor(
    private val transport: SshTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxOutputLines: Int = AnsiStreamSanitizer.DEFAULT_MAX_LINES,
) {
    fun runCommand(
        request: SshConnectionRequest,
        command: String,
        autoRespondConfig: SshAutoRespondConfig = SshAutoRespondConfig(),
    ): Flow<LiteSshExecutionEvent> = flow {
        val startedAt = clock()
        val sanitizer = AnsiStreamSanitizer(maxLines = maxOutputLines)
        val autoResponder = SshPromptAutoResponder(config = autoRespondConfig)
        val stdinWriterRef = AtomicReference<SshStdinWriter?>(null)
        emit(LiteSshExecutionEvent.Connecting)

        var sawCompleted = false
        transport.execute(
            request = request,
            command = command,
            onStdinReady = { writer -> stdinWriterRef.set(writer) },
        ).collect { event ->
            when (event) {
                is SshStreamEvent.HostKeyObserved -> {
                    emit(LiteSshExecutionEvent.HostKeyChecked(event.verification))
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
                    val decision = autoResponder.onOutputChunk(event.rawText)
                    if (decision != null) {
                        stdinWriterRef.get()?.write(decision.response)
                        emit(LiteSshExecutionEvent.AutoResponded(decision))
                    }
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
