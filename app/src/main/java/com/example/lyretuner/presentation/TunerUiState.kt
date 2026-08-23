package com.example.lyretuner.presentation

import com.example.lyretuner.analysis.PitchReading
import com.example.lyretuner.tuning.LyreString
import com.example.lyretuner.tuning.LyreTuning
import kotlin.math.abs

enum class TunerError {
    MICROPHONE_PERMISSION_DENIED,
    ANALYZER_START_FAILED,
}

data class TunerUiState(
    val reading: PitchReading? = null,
    val isListening: Boolean = false,
    val error: TunerError? = null,
    val lockedString: LyreString? = null,
    val automaticString: LyreString? = null,
) {
    val targetString: LyreString?
        get() = lockedString ?: automaticString

    val microphonePermissionDenied: Boolean
        get() = error == TunerError.MICROPHONE_PERMISSION_DENIED

    val centsFromTarget: Double?
        get() {
            val currentReading = reading ?: return null
            val target = targetString ?: return null
            return LyreTuning.centsBetween(currentReading.frequencyHz, target.frequencyHz)
        }

    val isInTune: Boolean
        get() = centsFromTarget?.let { abs(it) <= IN_TUNE_TOLERANCE_CENTS } == true

    private companion object {
        const val IN_TUNE_TOLERANCE_CENTS = 4.0
    }
}
