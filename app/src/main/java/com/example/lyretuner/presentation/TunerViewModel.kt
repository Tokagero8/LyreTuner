package com.example.lyretuner.presentation

import androidx.lifecycle.ViewModel
import com.example.lyretuner.analysis.PitchReading
import com.example.lyretuner.audio.AudioPitchAnalyzer
import com.example.lyretuner.audio.PitchAnalyzer
import com.example.lyretuner.tuning.AutomaticStringSelector
import com.example.lyretuner.tuning.LyreString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TunerViewModel(
    private val pitchAnalyzer: PitchAnalyzer,
) : ViewModel() {
    constructor() : this(AudioPitchAnalyzer())

    private val _uiState = MutableStateFlow(TunerUiState())
    val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()
    private val automaticStringSelector = AutomaticStringSelector()

    // Lifecycle calls may block in PitchAnalyzer; callbacks only take the shorter state lock.
    private val lifecycleLock = Any()
    private val stateLock = Any()

    private var wantsToListen = false
    private var isInForeground = false
    private var hasMicrophonePermission = false
    private var nextSessionGeneration = 0L
    private var currentSessionGeneration: Long? = null

    /** Records listening intent; [hasPermission] is supplied by the platform-facing UI. */
    fun startListening(hasPermission: Boolean) = synchronized(lifecycleLock) {
        wantsToListen = true
        hasMicrophonePermission = hasPermission
        synchronized(stateLock) {
            val state = _uiState.value
            if (state.error != null) {
                _uiState.value = state.copy(error = null)
            }
        }
        if (!hasPermission) {
            return@synchronized
        }
        startAnalyzerIfEligible()
    }

    fun stopListening() = synchronized(lifecycleLock) {
        wantsToListen = false
        stopAnalyzer(clearStartFailure = true)
    }

    fun toggleStringSelection(string: LyreString) {
        synchronized(stateLock) {
            val state = _uiState.value
            _uiState.value = state.copy(
                lockedString = if (state.lockedString == string) null else string,
            )
        }
    }

    fun useAutomaticSelection() {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(lockedString = null)
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean) = synchronized(lifecycleLock) {
        hasMicrophonePermission = granted
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(
                error = if (granted) null else TunerError.MICROPHONE_PERMISSION_DENIED,
            )
        }
        if (granted) startAnalyzerIfEligible() else stopAnalyzer()
    }

    fun enterForeground() = synchronized(lifecycleLock) {
        isInForeground = true
        startAnalyzerIfEligible()
    }

    fun leaveForeground() = synchronized(lifecycleLock) {
        isInForeground = false
        stopAnalyzer()
    }

    private fun startAnalyzerIfEligible() {
        if (!wantsToListen || !isInForeground || !hasMicrophonePermission) return

        val generation = synchronized(stateLock) {
            if (currentSessionGeneration != null) return
            (++nextSessionGeneration).also { currentSessionGeneration = it }
        }
        val started = pitchAnalyzer.start { reading ->
            handlePitch(generation, reading)
        }

        synchronized(stateLock) {
            if (currentSessionGeneration != generation) return
            val state = _uiState.value
            if (started) {
                _uiState.value = state.copy(
                    isListening = true,
                    error = state.error.takeUnless {
                        it == TunerError.ANALYZER_START_FAILED
                    },
                )
            } else {
                currentSessionGeneration = null
                automaticStringSelector.reset()
                _uiState.value = state.copy(
                    isListening = false,
                    reading = null,
                    automaticString = null,
                    error = TunerError.ANALYZER_START_FAILED,
                )
            }
        }
    }

    private fun handlePitch(generation: Long, reading: PitchReading?) {
        synchronized(stateLock) {
            if (currentSessionGeneration != generation) return
            val automaticString = automaticStringSelector.select(reading?.frequencyHz)
            _uiState.value = _uiState.value.copy(
                reading = reading,
                automaticString = automaticString,
            )
        }
    }

    private fun stopAnalyzer(clearStartFailure: Boolean = false) {
        synchronized(stateLock) {
            currentSessionGeneration = null
            automaticStringSelector.reset()
            val state = _uiState.value
            _uiState.value = state.copy(
                isListening = false,
                reading = null,
                automaticString = null,
                error = if (
                    clearStartFailure && state.error == TunerError.ANALYZER_START_FAILED
                ) null else state.error,
            )
        }
        pitchAnalyzer.stop()
    }

    override fun onCleared() {
        synchronized(lifecycleLock) {
            wantsToListen = false
            isInForeground = false
            stopAnalyzer()
        }
    }
}
