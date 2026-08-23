package com.example.lyretuner.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface AnalyzerThreadFactory {
    fun create(runnable: Runnable): Thread
}

internal fun interface AnalyzerLogger {
    fun warn(message: String, exception: Throwable?)
}

/** Coordinates cancellation when [PitchAnalyzer.start] and [PitchAnalyzer.stop] race. */
internal class StartRequest {
    private val cancelled = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val ownerThread = Thread.currentThread()

    val isCancelled: Boolean
        get() = cancelled.get()

    val isOwnerThread: Boolean
        get() = ownerThread === Thread.currentThread()

    fun cancel() {
        cancelled.set(true)
    }

    fun finish() {
        finished.countDown()
    }

    fun awaitCompletion(): Boolean {
        var interrupted = false
        while (true) {
            try {
                finished.await()
                return interrupted
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
}

/** Gives concurrent callers one shared, idempotent stop operation to await. */
internal class StopOperation(
    val startRequest: StartRequest?,
    val session: AudioCaptureSession?,
    val completionDeferredToSession: Boolean,
) {
    private val finished = CountDownLatch(1)

    fun complete() {
        finished.countDown()
    }

    fun awaitCompletion() {
        var interrupted = false
        while (true) {
            try {
                finished.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
