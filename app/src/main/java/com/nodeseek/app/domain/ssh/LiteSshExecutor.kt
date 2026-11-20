package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level streaming updates emitted by [LiteSshExecutor] after applying [AnsiStreamSanitizer]
 * and optional [SshPromptAutoResponder] fallback handling.
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
    data class AutoResponded(
        val decision: SshAutoRespondDecision,
    ) : LiteSshExecutionEvent
}

/**
 * Coordinates streaming remote SSH command execution over an [SshTransport], feeding raw PTY
 * chunks incrementally through [AnsiStreamSanitizer] and optionally [SshPromptAutoResponder]
 * (with idle-silence debouncing and piped-stdin awareness) so consumers receive clean
 * SGR-preserved terminal output without hanging on `[y/N]` / `Enter` prompts or misfiring on
 * already-piped commands.
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
    ): Flow<LiteSshExecutionEvent> = channelFlow {
        suspend fun emit(event: LiteSshExecutionEvent) = send(event)

        val startedAt = clock()
        val sanitizer = AnsiStreamSanitizer(maxLines = maxOutputLines)
        val effectiveAutoConfig = if (SshPromptAutoResponder.commandAlreadyPipesStdin(command)) {
            autoRespondConfig.copy(enabled = false)
        } else {
            autoRespondConfig
        }
        val autoResponder = SshPromptAutoResponder(config = effectiveAutoConfig)
        val stdinWriterRef = AtomicReference<SshStdinWriter?>(null)
        val responderLock = Any()
        var pendingDebounceJob: Job? = null
        emit(LiteSshExecutionEvent.Connecting)

        var sawCompleted = false
        try {
            transport.execute(
                request = request,
                command = command,
                onStdinReady = { writer -> stdinWriterRef.set(writer) },
            ).collect { event ->
                when (event) {
                    is SshStreamEvent.HostKeyObserved -> {
                        emit(LiteSshExecutionEvent.HostKeyChecked(event.verification, event.serverId))
                    }
                    SshStreamEvent.Connected -> {
                        emit(LiteSshExecutionEvent.Connected)
                    }
                    is SshStreamEvent.OutputChunk -> {
                        pendingDebounceJob?.cancel()
                        pendingDebounceJob = null

                        sanitizer.append(event.rawText)
                        emit(
                            LiteSshExecutionEvent.OutputUpdated(
                                sanitizedAnsiOutput = sanitizer.snapshot(),
                                elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                            ),
                        )

                        val hasCandidate = synchronized(responderLock) {
                            autoResponder.appendOutputChunk(event.rawText)
                            autoResponder.hasPendingPrompt()
                        }
                        if (hasCandidate) {
                            val debounceMs = effectiveAutoConfig.idleDebounceMillis
                            if (debounceMs <= 0L) {
                                val decision = synchronized(responderLock) {
                                    autoResponder.pollPendingDecision()
                                }
                                if (decision != null) {
                                    stdinWriterRef.get()?.write(decision.response)
                                    emit(LiteSshExecutionEvent.AutoResponded(decision))
                                }
                            } else {
                                pendingDebounceJob = launch {
                                    delay(debounceMs)
                                    val decision = synchronized(responderLock) {
                                        autoResponder.pollPendingDecision()
                                    }
                                    if (decision != null) {
                                        stdinWriterRef.get()?.write(decision.response)
                                        send(LiteSshExecutionEvent.AutoResponded(decision))
                                    }
                                }
                            }
                        }
                    }
                    is SshStreamEvent.Completed -> {
                        pendingDebounceJob?.cancel()
                        pendingDebounceJob = null
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
                pendingDebounceJob?.cancel()
                pendingDebounceJob = null
                emit(
                    LiteSshExecutionEvent.Completed(
                        exitCode = 0,
                        sanitizedAnsiOutput = sanitizer.snapshot(),
                        elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                    ),
                )
            }
        } finally {
            pendingDebounceJob?.cancel()
        }
    }
}
