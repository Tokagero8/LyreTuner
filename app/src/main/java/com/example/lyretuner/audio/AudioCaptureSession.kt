package com.example.lyretuner.audio

import android.media.AudioRecord
import com.example.lyretuner.analysis.PitchAnalysisDefaults
import com.example.lyretuner.analysis.PitchReading
import com.example.lyretuner.analysis.StreamingPitchProcessor
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the recorder, worker command, and callback state for exactly one capture session. */
internal class AudioCaptureSession(
    private val recorder: RecorderHandle,
    callbackExecutor: Executor,
    threadFactory: AnalyzerThreadFactory,
    private val logger: AnalyzerLogger,
    private val onPitch: (PitchReading?) -> Unit,
    private val isCurrent: (AudioCaptureSession) -> Boolean,
    private val onFinished: (AudioCaptureSession) -> Unit,
) {
    private val running = AtomicBoolean(true)
    private val callbackLock = Any()
    private val captureLoop = AudioCaptureLoop(logger)
    private val callbackDispatcher = LatestPitchDispatcher(
        executor = callbackExecutor,
        canDeliver = { isRunning && isCurrent(this) },
        deliver = ::deliverPitch,
        onExecutorFailure = { exception ->
            logger.warn("Pitch callback executor failed", exception)
            markStopping()
        },
    )
    private val worker = threadFactory.create(Runnable(::run))

    @Volatile
    private var callbackThread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    val isCallbackThread: Boolean
        get() = callbackThread === Thread.currentThread()

    fun start() {
        worker.start()
    }

    fun abortBeforeStart() {
        requestStop()
        recorder.release()
    }

    fun stopAndAwait(workerJoinTimeoutMillis: Long): Boolean {
        requestStop()

        var interrupted = false
        val calledFromCallback = isCallbackThread
        if (Thread.currentThread() !== worker && !calledFromCallback) {
            try {
                worker.join(workerJoinTimeoutMillis)
            } catch (_: InterruptedException) {
                interrupted = true
            }
            if (worker.isAlive) worker.interrupt()
        }
        if (!calledFromCallback) awaitCallbackCompletion()
        recorder.release()
        return interrupted
    }

    private fun run() {
        try {
            captureLoop.run(
                recorder = recorder,
                isRunning = { isRunning },
                onPitch = callbackDispatcher::offer,
            )
        } catch (exception: RuntimeException) {
            logger.warn("Audio analysis worker stopped unexpectedly", exception)
        } finally {
            requestStop()
            recorder.release()
            awaitCallbackCompletion()
            onFinished(this)
        }
    }

    private fun deliverPitch(reading: PitchReading?) {
        synchronized(callbackLock) {
            if (!isRunning || !isCurrent(this)) return
            callbackThread = Thread.currentThread()
            try {
                onPitch(reading)
            } catch (exception: Exception) {
                logger.warn("Pitch callback failed", exception)
            } finally {
                callbackThread = null
            }
        }
    }

    private fun markStopping() {
        running.set(false)
        callbackDispatcher.close()
    }

    fun requestStop() {
        markStopping()
        recorder.requestStop()
    }

    private fun awaitCallbackCompletion() {
        if (isCallbackThread) return
        synchronized(callbackLock) {
            // Entering the lock is the wait; callback delivery holds it throughout.
        }
    }
}

/** Blocking capture command that feeds fixed-size hops into the streaming DSP pipeline. */
private class AudioCaptureLoop(
    private val logger: AnalyzerLogger,
) {
    private val processor = StreamingPitchProcessor()
    private val pcm = ShortArray(PitchAnalysisDefaults.HOP_SIZE)
    private val newSamples = FloatArray(PitchAnalysisDefaults.HOP_SIZE)

    fun run(
        recorder: RecorderHandle,
        isRunning: () -> Boolean,
        onPitch: (PitchReading?) -> Unit,
    ) {
        while (isRunning()) {
            if (!readHop(recorder, pcm, isRunning)) break

            for (index in pcm.indices) {
                newSamples[index] = pcm[index] / AudioCaptureDefaults.PCM_SCALE
            }
            processor.append(newSamples)?.let { onPitch(it.stableReading) }
        }
    }

    private fun readHop(
        recorder: RecorderHandle,
        destination: ShortArray,
        isRunning: () -> Boolean,
    ): Boolean {
        var filled = 0
        var emptyReadCount = 0
        while (filled < destination.size && isRunning()) {
            val count = try {
                recorder.read(destination, filled, destination.size - filled)
            } catch (exception: RuntimeException) {
                if (isRunning()) logger.warn("AudioRecord.read() threw", exception)
                return false
            }

            when {
                count > 0 -> {
                    filled += count
                    emptyReadCount = 0
                }

                count == 0 -> {
                    emptyReadCount++
                    if (emptyReadCount >= AudioCaptureDefaults.MAX_CONSECUTIVE_EMPTY_READS) {
                        if (isRunning()) {
                            logger.warn("AudioRecord repeatedly returned no data", null)
                        }
                        return false
                    }
                    Thread.yield()
                }

                count == AudioRecord.ERROR_BAD_VALUE -> {
                    logger.warn("AudioRecord.read() rejected its buffer arguments", null)
                    return false
                }

                count == AudioRecord.ERROR_INVALID_OPERATION -> {
                    if (isRunning()) {
                        logger.warn("AudioRecord is not in a readable state", null)
                    }
                    return false
                }

                count == AudioRecord.ERROR_DEAD_OBJECT -> {
                    logger.warn("AudioRecord became invalid and must be recreated", null)
                    return false
                }

                else -> {
                    if (isRunning()) {
                        logger.warn("AudioRecord.read() failed with code $count", null)
                    }
                    return false
                }
            }
        }
        return filled == destination.size && isRunning()
    }
}
