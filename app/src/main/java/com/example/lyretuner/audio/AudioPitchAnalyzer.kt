package com.example.lyretuner.audio

import android.util.Log
import com.example.lyretuner.analysis.PitchAnalysisDefaults
import com.example.lyretuner.analysis.PitchReading
import java.util.concurrent.Executor

/**
 * Lifecycle facade for microphone pitch analysis.
 *
 * Each successful start owns one [AudioCaptureSession]. Recorder creation, streaming DSP,
 * callback coalescing, and Android audio adaptation are delegated to focused collaborators.
 * The default direct executor runs callbacks on the audio-analysis worker.
 */
class AudioPitchAnalyzer internal constructor(
    private val callbackExecutor: Executor,
    audioInput: AudioInput,
    private val threadFactory: AnalyzerThreadFactory,
    private val logger: AnalyzerLogger,
) : PitchAnalyzer {
    constructor(callbackExecutor: Executor = DIRECT_EXECUTOR) : this(
        callbackExecutor = callbackExecutor,
        audioInput = AndroidAudioInput,
        threadFactory = DEFAULT_THREAD_FACTORY,
        logger = ANDROID_LOGGER,
    )

    private val lifecycleLock = Any()
    private val recorderFactory = AudioRecorderFactory(audioInput, logger)

    @Volatile
    private var activeSession: AudioCaptureSession? = null

    private var pendingStart: StartRequest? = null
    private var stopOperation: StopOperation? = null

    override fun start(onPitch: (PitchReading?) -> Unit): Boolean {
        val request = synchronized(lifecycleLock) {
            if (pendingStart != null || activeSession != null || stopOperation != null) {
                return false
            }
            StartRequest().also { pendingStart = it }
        }

        try {
            val recorder = recorderFactory.createStarted(
                isCancelled = { request.isCancelled },
            ) ?: return false

            if (request.isCancelled) {
                recorder.requestStop()
                recorder.release()
                return false
            }

            val session = try {
                AudioCaptureSession(
                    recorder = recorder,
                    callbackExecutor = callbackExecutor,
                    threadFactory = threadFactory,
                    logger = logger,
                    onPitch = onPitch,
                    isCurrent = ::isActive,
                    onFinished = ::finishSession,
                )
            } catch (exception: RuntimeException) {
                logger.warn("Could not create the audio analysis worker", exception)
                recorder.requestStop()
                recorder.release()
                return false
            }

            var workerStartFailure: RuntimeException? = null
            val started = synchronized(lifecycleLock) {
                if (pendingStart !== request || request.isCancelled || stopOperation != null) {
                    false
                } else {
                    pendingStart = null
                    activeSession = session
                    try {
                        session.start()
                        true
                    } catch (exception: RuntimeException) {
                        activeSession = null
                        workerStartFailure = exception
                        false
                    }
                }
            }
            workerStartFailure?.let {
                logger.warn("Could not start the audio analysis worker", it)
            }
            if (!started) session.abortBeforeStart()
            return started
        } finally {
            synchronized(lifecycleLock) {
                if (pendingStart === request) pendingStart = null
            }
            request.finish()
        }
    }

    override fun stop() {
        val operation: StopOperation
        val ownsOperation: Boolean
        synchronized(lifecycleLock) {
            val existing = stopOperation
            if (existing != null) {
                operation = existing
                ownsOperation = false
            } else {
                val request = pendingStart
                val session = activeSession
                if (request == null && session == null) return

                operation = StopOperation(
                    startRequest = request,
                    session = session,
                    completionDeferredToSession = session?.isCallbackThread == true,
                )
                stopOperation = operation
                request?.cancel()
                if (session != null) activeSession = null
                ownsOperation = true
            }
        }

        if (!ownsOperation) {
            if (operation.session?.isCallbackThread == true) return
            operation.awaitCompletion()
            return
        }

        var interrupted = false
        try {
            operation.startRequest?.let { request ->
                if (!request.isOwnerThread) interrupted = request.awaitCompletion() || interrupted
            }
            operation.session?.let { session ->
                if (operation.completionDeferredToSession) {
                    session.requestStop()
                } else {
                    interrupted = session.stopAndAwait(
                        AudioCaptureDefaults.STOP_JOIN_TIMEOUT_MS,
                    ) || interrupted
                }
            }
        } finally {
            if (!operation.completionDeferredToSession) completeStop(operation)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun isActive(session: AudioCaptureSession): Boolean =
        synchronized(lifecycleLock) { activeSession === session }

    private fun finishSession(session: AudioCaptureSession) {
        synchronized(lifecycleLock) {
            if (activeSession === session) activeSession = null
            val operation = stopOperation
            if (operation?.session === session && operation.completionDeferredToSession) {
                operation.complete()
                stopOperation = null
            }
        }
    }

    private fun completeStop(operation: StopOperation) {
        synchronized(lifecycleLock) {
            operation.session?.let { session ->
                if (activeSession === session) activeSession = null
            }
            operation.complete()
            if (stopOperation === operation) stopOperation = null
        }
    }

    internal companion object {
        const val TAG = "AudioPitchAnalyzer"
        const val SAMPLE_RATE = PitchAnalysisDefaults.SAMPLE_RATE
        const val WINDOW_SIZE = PitchAnalysisDefaults.WINDOW_SIZE
        const val HOP_SIZE = PitchAnalysisDefaults.HOP_SIZE
        const val ANALYSIS_INTERVAL_HOPS = PitchAnalysisDefaults.ANALYSIS_INTERVAL_HOPS
        const val MAX_CONSECUTIVE_EMPTY_READS =
            AudioCaptureDefaults.MAX_CONSECUTIVE_EMPTY_READS
        const val STOP_JOIN_TIMEOUT_MS = AudioCaptureDefaults.STOP_JOIN_TIMEOUT_MS
        const val PCM_BYTES_PER_SAMPLE = AudioCaptureDefaults.PCM_BYTES_PER_SAMPLE
        const val PCM_SCALE = AudioCaptureDefaults.PCM_SCALE

        val DIRECT_EXECUTOR = Executor(Runnable::run)
        val DEFAULT_THREAD_FACTORY = AnalyzerThreadFactory { runnable ->
            Thread(runnable, "LyrePitchAnalyzer")
        }
        val ANDROID_LOGGER = AnalyzerLogger { message, exception ->
            if (exception == null) Log.w(TAG, message) else Log.w(TAG, message, exception)
        }
    }
}
