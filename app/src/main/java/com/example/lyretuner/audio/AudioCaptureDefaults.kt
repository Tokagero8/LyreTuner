package com.example.lyretuner.audio

/** Shared immutable configuration for audio-capture collaborators. */
internal object AudioCaptureDefaults {
    const val MAX_CONSECUTIVE_EMPTY_READS = 3
    const val STOP_JOIN_TIMEOUT_MS = 500L
    const val PCM_BYTES_PER_SAMPLE = 2
    const val PCM_SCALE = 32_768f
}
