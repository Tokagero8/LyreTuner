package com.example.lyretuner.presentation

import com.example.lyretuner.analysis.PitchReading
import com.example.lyretuner.audio.PitchAnalyzer
import com.example.lyretuner.tuning.LyreTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TunerViewModelTest {
    @Test
    fun requestedListeningStartsWhenAppEntersForeground() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = TunerViewModel(analyzer)

        viewModel.startListening(hasPermission = true)
        assertEquals(0, analyzer.startCount)
        assertFalse(viewModel.uiState.value.isListening)

        viewModel.enterForeground()
        assertEquals(1, analyzer.startCount)
        assertTrue(viewModel.uiState.value.isListening)
    }

    @Test
    fun duplicateStartDoesNotCreateAnotherAudioSession() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = foregroundViewModel(analyzer)

        viewModel.startListening(hasPermission = true)
        viewModel.startListening(hasPermission = true)

        assertEquals(1, analyzer.startCount)
    }

    @Test
    fun readingsUpdateDerivedTunerState() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)

        analyzer.emit(PitchReading(440.0, 0.98))

        assertEquals("A4", viewModel.uiState.value.targetString?.name)
        assertEquals(0.0, viewModel.uiState.value.centsFromTarget!!, 0.000_001)
        assertTrue(viewModel.uiState.value.isInTune)
    }

    @Test
    fun automaticSelectionUsesHysteresisNearAStringBoundary() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)
        val e4 = LyreTuning.strings.single { it.name == "E4" }

        analyzer.emit(PitchReading(e4.frequencyHz, 0.98))
        analyzer.emit(PitchReading(frequencyAtCents(e4.frequencyHz, 55.0), 0.98))
        assertEquals("E4", viewModel.uiState.value.targetString?.name)

        analyzer.emit(PitchReading(frequencyAtCents(e4.frequencyHz, 60.0), 0.98))
        assertEquals("F4", viewModel.uiState.value.targetString?.name)
    }

    @Test
    fun lockedStringOverridesAutomaticAndUnlockingRestoresIt() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)
        val d3 = LyreTuning.strings.first()
        val a4 = LyreTuning.strings.single { it.name == "A4" }
        analyzer.emit(PitchReading(a4.frequencyHz, 0.98))

        viewModel.toggleStringSelection(d3)
        assertEquals(d3, viewModel.uiState.value.targetString)

        viewModel.useAutomaticSelection()
        assertEquals(a4, viewModel.uiState.value.targetString)
    }

    @Test
    fun failedAnalyzerStartHasTypedErrorAndCanBeRetried() {
        val analyzer = FakePitchAnalyzer(startsSuccessfully = false)
        val viewModel = foregroundViewModel(analyzer)

        viewModel.startListening(hasPermission = true)

        assertEquals(1, analyzer.startCount)
        assertFalse(viewModel.uiState.value.isListening)
        assertNull(viewModel.uiState.value.reading)
        assertEquals(TunerError.ANALYZER_START_FAILED, viewModel.uiState.value.error)

        analyzer.startsSuccessfully = true
        viewModel.startListening(hasPermission = true)

        assertEquals(2, analyzer.startCount)
        assertTrue(viewModel.uiState.value.isListening)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun leavingForegroundStopsActualListeningButPreservesIntent() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)

        viewModel.leaveForeground()

        assertFalse(viewModel.uiState.value.isListening)
        assertEquals(1, analyzer.stopCount)

        viewModel.enterForeground()

        assertTrue(viewModel.uiState.value.isListening)
        assertEquals(2, analyzer.startCount)
    }

    @Test
    fun explicitStopClearsIntentAndPreventsForegroundRestart() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)
        analyzer.emit(PitchReading(440.0, 1.0))

        viewModel.stopListening()
        viewModel.leaveForeground()
        viewModel.enterForeground()

        assertFalse(viewModel.uiState.value.isListening)
        assertNull(viewModel.uiState.value.reading)
        assertNull(viewModel.uiState.value.targetString)
        assertEquals(1, analyzer.startCount)
    }

    @Test
    fun missingReadingClearsAutomaticTargetButPreservesManualLock() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)
        val d3 = LyreTuning.strings.first()
        analyzer.emit(PitchReading(440.0, 0.98))
        viewModel.toggleStringSelection(d3)

        analyzer.emit(null)

        val state = viewModel.uiState.value
        assertTrue(state.isListening)
        assertNull(state.reading)
        assertNull(state.automaticString)
        assertEquals(d3, state.lockedString)
        assertEquals(d3, state.targetString)
    }

    @Test
    fun stopResetsAutomaticSelectorBeforeNewSession() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)
        val a4 = LyreTuning.strings.single { it.name == "A4" }
        analyzer.emit(PitchReading(a4.frequencyHz, 0.98))
        analyzer.emit(PitchReading(frequencyAtCents(a4.frequencyHz, 70.0), 0.98))
        assertEquals(a4, viewModel.uiState.value.automaticString)

        viewModel.stopListening()
        viewModel.startListening(hasPermission = true)
        analyzer.emit(PitchReading(frequencyAtCents(a4.frequencyHz, 70.0), 0.98))

        assertNull(viewModel.uiState.value.automaticString)
    }

    @Test
    fun staleCallbackAfterStopCannotRestoreReadingOrAutomaticSelection() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)
        val stoppedSession = analyzer.currentSessionIndex

        viewModel.stopListening()
        analyzer.emitFromSession(stoppedSession, PitchReading(440.0, 0.98))

        assertFalse(viewModel.uiState.value.isListening)
        assertNull(viewModel.uiState.value.reading)
        assertNull(viewModel.uiState.value.automaticString)
    }

    @Test
    fun staleCallbackFromPreviousSessionCannotAffectNewSession() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = listeningViewModel(analyzer)
        val oldSession = analyzer.currentSessionIndex

        viewModel.leaveForeground()
        viewModel.enterForeground()
        val b4 = LyreTuning.strings.single { it.name == "B4" }
        analyzer.emit(PitchReading(b4.frequencyHz, 0.98))

        analyzer.emitFromSession(oldSession, PitchReading(440.0, 0.98))

        assertEquals(b4.frequencyHz, viewModel.uiState.value.reading!!.frequencyHz, 0.0)
        assertEquals(b4, viewModel.uiState.value.automaticString)
    }

    @Test
    fun grantingPermissionStartsPreviouslyRequestedListening() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = foregroundViewModel(analyzer)

        viewModel.startListening(hasPermission = false)
        assertEquals(0, analyzer.startCount)
        assertFalse(viewModel.uiState.value.isListening)

        viewModel.onMicrophonePermissionResult(granted = true)

        assertEquals(1, analyzer.startCount)
        assertTrue(viewModel.uiState.value.isListening)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun deniedPermissionHasTypedErrorAndGrantClearsIt() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = foregroundViewModel(analyzer)
        viewModel.startListening(hasPermission = false)

        viewModel.onMicrophonePermissionResult(granted = false)

        assertEquals(TunerError.MICROPHONE_PERMISSION_DENIED, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.microphonePermissionDenied)

        viewModel.onMicrophonePermissionResult(granted = true)

        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.isListening)
    }

    @Test
    fun explicitStopWhilePermissionIsPendingPreventsStartAfterGrant() {
        val analyzer = FakePitchAnalyzer()
        val viewModel = foregroundViewModel(analyzer)
        viewModel.startListening(hasPermission = false)

        viewModel.stopListening()
        viewModel.onMicrophonePermissionResult(granted = true)

        assertEquals(0, analyzer.startCount)
        assertFalse(viewModel.uiState.value.isListening)
    }

    @Test
    fun manualSelectionCanBeToggledAndReset() {
        val viewModel = TunerViewModel(FakePitchAnalyzer())
        val d3 = LyreTuning.strings.first()

        viewModel.toggleStringSelection(d3)
        assertEquals(d3, viewModel.uiState.value.lockedString)

        viewModel.toggleStringSelection(d3)
        assertNull(viewModel.uiState.value.lockedString)

        viewModel.toggleStringSelection(d3)
        viewModel.useAutomaticSelection()
        assertNull(viewModel.uiState.value.lockedString)
    }

    private fun foregroundViewModel(analyzer: FakePitchAnalyzer): TunerViewModel =
        TunerViewModel(analyzer).also(TunerViewModel::enterForeground)

    private fun listeningViewModel(analyzer: FakePitchAnalyzer): TunerViewModel =
        foregroundViewModel(analyzer).also {
            it.startListening(hasPermission = true)
        }

    private fun frequencyAtCents(frequencyHz: Double, cents: Double): Double =
        frequencyHz * 2.0.pow(cents / 1_200.0)

    private class FakePitchAnalyzer(
        var startsSuccessfully: Boolean = true,
    ) : PitchAnalyzer {
        private val callbacks = mutableListOf<(PitchReading?) -> Unit>()
        private var activeSessionIndex: Int? = null

        var startCount = 0
            private set
        var stopCount = 0
            private set

        val currentSessionIndex: Int
            @Synchronized get() = requireNotNull(activeSessionIndex)

        @Synchronized
        override fun start(onPitch: (PitchReading?) -> Unit): Boolean {
            startCount++
            if (!startsSuccessfully) return false
            callbacks += onPitch
            activeSessionIndex = callbacks.lastIndex
            return true
        }

        @Synchronized
        override fun stop() {
            stopCount++
            activeSessionIndex = null
        }

        fun emit(reading: PitchReading?) {
            val session = synchronized(this) { activeSessionIndex }
            if (session != null) emitFromSession(session, reading)
        }

        fun emitFromSession(sessionIndex: Int, reading: PitchReading?) {
            val callback = synchronized(this) { callbacks[sessionIndex] }
            callback(reading)
        }
    }
}
