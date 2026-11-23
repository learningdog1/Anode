package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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

    /**
     * Carries an incremental [bufferSnapshot] while keeping [sanitizedAnsiOutput] lazily
     * materialized so streaming consumers never pay `O(N²)` string concatenation per chunk.
     */
    @Immutable
    class OutputUpdated(
        val bufferSnapshot: SanitizedBufferSnapshot,
        val elapsedMillis: Long,
    ) : LiteSshExecutionEvent {
        constructor(
            sanitizedAnsiOutput: String,
            elapsedMillis: Long,
        ) : this(
            bufferSnapshot = SanitizedBufferSnapshot.fromSanitizedText(sanitizedAnsiOutput),
            elapsedMillis = elapsedMillis,
        )

        val sanitizedAnsiOutput: String
            get() = bufferSnapshot.fullAnsiText

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OutputUpdated) return false
            return elapsedMillis == other.elapsedMillis && bufferSnapshot == other.bufferSnapshot
        }

        override fun hashCode(): Int = 31 * bufferSnapshot.hashCode() + elapsedMillis.hashCode()

        override fun toString(): String =
            "OutputUpdated(version=${bufferSnapshot.version}, lines=${bufferSnapshot.completedLines.size}, elapsedMillis=$elapsedMillis)"
    }

    data class Completed(
        val exitCode: Int,
        val sanitizedAnsiOutput: String,
        val elapsedMillis: Long,
        val bufferSnapshot: SanitizedBufferSnapshot = SanitizedBufferSnapshot.fromSanitizedText(sanitizedAnsiOutput),
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
 *
 * Performance guarantees:
 * - Applies a leading + trailing coalescing window ([outputThrottleMillis], default 64ms ~ 15.6fps)
 *   to collapse rapid PTY bursts and `\r` progress bar storms into bounded frame-rate snapshots,
 *   delivering the first chunk with zero latency and flushing final state immediately upon completion.
 * - Executes sanitization and auto-responder state machines on [processingDispatcher] when provided.
 */
class LiteSshExecutor @JvmOverloads constructor(
    private val transport: SshTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxOutputLines: Int = AnsiStreamSanitizer.DEFAULT_MAX_LINES,
    private val outputThrottleMillis: Long = DEFAULT_OUTPUT_THROTTLE_MILLIS,
    private val processingDispatcher: CoroutineDispatcher? = null,
    private val maxLineChars: Int = AnsiStreamSanitizer.DEFAULT_MAX_LINE_CHARS,
) {
    fun runCommand(
        request: SshConnectionRequest,
        command: String,
        autoRespondConfig: SshAutoRespondConfig = SshAutoRespondConfig(),
    ): Flow<LiteSshExecutionEvent> {
        val rawFlow = channelFlow {
            suspend fun emitEvent(event: LiteSshExecutionEvent) = send(event)

            val startedAt = clock()
            val sanitizer = AnsiStreamSanitizer(
                maxLines = maxOutputLines,
                maxLineChars = maxLineChars,
            )
            val sanitizerLock = Any()
            var hasPendingOutput = false
            var lastEmittedVersion = -1L
            var throttleLoopJob: Job? = null

            fun pollSnapshotEventLocked(): LiteSshExecutionEvent.OutputUpdated? {
                val snap = sanitizer.lineSnapshot()
                hasPendingOutput = false
                if (snap.version == lastEmittedVersion) return null
                lastEmittedVersion = snap.version
                return LiteSshExecutionEvent.OutputUpdated(
                    bufferSnapshot = snap,
                    elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                )
            }

            val effectiveAutoConfig = if (SshPromptAutoResponder.commandAlreadyPipesStdin(command)) {
                autoRespondConfig.copy(enabled = false)
            } else {
                autoRespondConfig
            }
            val autoResponder = SshPromptAutoResponder(config = effectiveAutoConfig)
            val stdinWriterRef = AtomicReference<SshStdinWriter?>(null)
            val outerStdinCallback = coroutineContext[SshStdinCallbackContext]?.onStdinReady
            val responderLock = Any()
            var pendingDebounceJob: Job? = null
            emitEvent(LiteSshExecutionEvent.Connecting)

            var sawCompleted = false
            try {
                transport.execute(
                    request = request,
                    command = command,
                    onStdinReady = { writer ->
                        stdinWriterRef.set(writer)
                        outerStdinCallback?.invoke(writer)
                    },
                ).collect { event ->
                    when (event) {
                        is SshStreamEvent.HostKeyObserved -> {
                            emitEvent(LiteSshExecutionEvent.HostKeyChecked(event.verification, event.serverId))
                        }
                        SshStreamEvent.Connected -> {
                            emitEvent(LiteSshExecutionEvent.Connected)
                        }
                        is SshStreamEvent.OutputChunk -> {
                            pendingDebounceJob?.cancel()
                            pendingDebounceJob = null

                            if (outputThrottleMillis <= 0L) {
                                val immediate = synchronized(sanitizerLock) {
                                    sanitizer.append(event.rawText)
                                    pollSnapshotEventLocked()
                                }
                                if (immediate != null) {
                                    emitEvent(immediate)
                                }
                            } else if (throttleLoopJob?.isActive == true) {
                                synchronized(sanitizerLock) {
                                    sanitizer.append(event.rawText)
                                    hasPendingOutput = true
                                }
                            } else {
                                val leading = synchronized(sanitizerLock) {
                                    sanitizer.append(event.rawText)
                                    pollSnapshotEventLocked()
                                }
                                if (leading != null) {
                                    emitEvent(leading)
                                    throttleLoopJob = launch {
                                        while (true) {
                                            delay(outputThrottleMillis)
                                            val trailing = synchronized(sanitizerLock) {
                                                if (!hasPendingOutput) null else pollSnapshotEventLocked()
                                            }
                                            if (trailing != null) {
                                                send(trailing)
                                            } else {
                                                break
                                            }
                                        }
                                    }
                                }
                            }

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
                                        emitEvent(LiteSshExecutionEvent.AutoResponded(decision))
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
                            throttleLoopJob?.cancel()
                            throttleLoopJob = null
                            sawCompleted = true
                            val finalSnap = synchronized(sanitizerLock) {
                                hasPendingOutput = false
                                sanitizer.lineSnapshot()
                            }
                            emitEvent(
                                LiteSshExecutionEvent.Completed(
                                    exitCode = event.exitCode,
                                    sanitizedAnsiOutput = finalSnap.fullAnsiText,
                                    elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                                    bufferSnapshot = finalSnap,
                                ),
                            )
                        }
                    }
                }

                if (!sawCompleted) {
                    pendingDebounceJob?.cancel()
                    pendingDebounceJob = null
                    throttleLoopJob?.cancel()
                    throttleLoopJob = null
                    val finalSnap = synchronized(sanitizerLock) {
                        hasPendingOutput = false
                        sanitizer.lineSnapshot()
                    }
                    emitEvent(
                        LiteSshExecutionEvent.Completed(
                            exitCode = 0,
                            sanitizedAnsiOutput = finalSnap.fullAnsiText,
                            elapsedMillis = (clock() - startedAt).coerceAtLeast(0L),
                            bufferSnapshot = finalSnap,
                        ),
                    )
                }
            } finally {
                pendingDebounceJob?.cancel()
                throttleLoopJob?.cancel()
            }
        }
        return if (processingDispatcher != null) {
            rawFlow.flowOn(processingDispatcher)
        } else {
            rawFlow
        }
    }

    companion object {
        const val DEFAULT_OUTPUT_THROTTLE_MILLIS: Long = 64L
    }
}
