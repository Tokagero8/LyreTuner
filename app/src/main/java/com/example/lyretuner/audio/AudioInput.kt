package com.example.lyretuner.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.lyretuner.analysis.PitchAnalysisDefaults
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal interface AudioInput {
    fun minimumBufferSize(): Int

    fun create(audioSource: Int, bufferSizeBytes: Int): AudioRecorder
}

internal interface AudioRecorder {
    val isInitialized: Boolean
    val isRecording: Boolean

    fun startRecording()
    fun read(destination: ShortArray, offset: Int, size: Int): Int
    fun stop()
    fun release()
}

/** Factory that tries preferred audio sources and returns an already-started recorder. */
internal class AudioRecorderFactory(
    private val audioInput: AudioInput,
    private val logger: AnalyzerLogger,
) {
    @SuppressLint("MissingPermission")
    fun createStarted(
        isCancelled: () -> Boolean,
    ): RecorderHandle? {
        val minimumBuffer = try {
            audioInput.minimumBufferSize()
        } catch (exception: RuntimeException) {
            logger.warn("Could not query the minimum audio buffer size", exception)
            return null
        }
        if (minimumBuffer <= 0 || isCancelled()) return null

        val bufferSizeBytes = max(
            minimumBuffer.toLong() * 2L,
            PitchAnalysisDefaults.WINDOW_SIZE.toLong() *
                AudioCaptureDefaults.PCM_BYTES_PER_SAMPLE,
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        for (audioSource in AUDIO_SOURCES) {
            if (isCancelled()) return null

            val handle = try {
                RecorderHandle(audioInput.create(audioSource, bufferSizeBytes))
            } catch (exception: RuntimeException) {
                logger.warn("Could not create AudioRecord for source $audioSource", exception)
                continue
            }

            if (isCancelled()) {
                handle.release()
                return null
            }
            if (!handle.isInitialized) {
                handle.release()
                continue
            }

            try {
                handle.startRecording()
                if (handle.isRecording && !isCancelled()) return handle
            } catch (exception: RuntimeException) {
                logger.warn("Could not start AudioRecord for source $audioSource", exception)
            }

            handle.requestStop()
            handle.release()
            if (isCancelled()) return null
        }
        return null
    }

    private companion object {
        val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )
    }
}

internal object AndroidAudioInput : AudioInput {
    override fun minimumBufferSize(): Int = AudioRecord.getMinBufferSize(
        PitchAnalysisDefaults.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    @SuppressLint("MissingPermission")
    override fun create(audioSource: Int, bufferSizeBytes: Int): AudioRecorder =
        AndroidAudioRecorder(
            AudioRecord(
                audioSource,
                PitchAnalysisDefaults.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            ),
        )
}

private class AndroidAudioRecorder(
    private val recorder: AudioRecord,
) : AudioRecorder {
    override val isInitialized: Boolean
        get() = recorder.state == AudioRecord.STATE_INITIALIZED

    override val isRecording: Boolean
        get() = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override fun startRecording() {
        recorder.startRecording()
    }

    override fun read(destination: ShortArray, offset: Int, size: Int): Int =
        recorder.read(destination, offset, size, AudioRecord.READ_BLOCKING)

    override fun stop() {
        recorder.stop()
    }

    override fun release() {
        recorder.release()
    }
}

/** Idempotent ownership wrapper around one recorder resource. */
internal class RecorderHandle(
    private val recorder: AudioRecorder,
) {
    private val stopIssued = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    val isInitialized: Boolean
        get() = recorder.isInitialized

    val isRecording: Boolean
        get() = recorder.isRecording

    fun startRecording() {
        recorder.startRecording()
    }

    fun read(destination: ShortArray, offset: Int, size: Int): Int =
        recorder.read(destination, offset, size)

    fun requestStop() {
        if (!stopIssued.compareAndSet(false, true)) return
        try {
            recorder.stop()
        } catch (_: RuntimeException) {
            // It may be uninitialized, already stopped, or invalid after a read error.
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            recorder.release()
        } catch (_: RuntimeException) {
            // There is no further safe recovery when AudioRecord.release() itself fails.
        }
    }
}
