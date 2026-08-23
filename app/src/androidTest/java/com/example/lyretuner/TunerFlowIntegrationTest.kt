package com.example.lyretuner

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lyretuner.analysis.PitchReading
import com.example.lyretuner.audio.PitchAnalyzer
import com.example.lyretuner.presentation.TunerViewModel
import com.example.lyretuner.tuning.LyreTuning
import com.example.lyretuner.ui.theme.LyreTunerTheme
import com.example.lyretuner.ui.tuner.TunerScreen
import com.example.lyretuner.ui.tuner.TunerTestTags
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

@RunWith(AndroidJUnit4::class)
class TunerFlowIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var analyzer: FakePitchAnalyzer
    private lateinit var viewModel: TunerViewModel

    @Before
    fun setUp() {
        analyzer = FakePitchAnalyzer()
        viewModel = TunerViewModel(analyzer)
        viewModel.enterForeground()

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            LyreTunerTheme(dynamicColor = false) {
                TunerScreen(
                    state = state,
                    onListeningClick = {
                        if (state.isListening) viewModel.stopListening()
                        else viewModel.startListening(hasPermission = true)
                    },
                    onStringClick = viewModel::toggleStringSelection,
                    onAutoClick = viewModel::useAutomaticSelection,
                )
            }
        }
    }

    @Test
    fun startDetectSelectAutoAndStopFlowUpdatesTheWholeScreen() {
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).performClick()
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).assertTextContains("Stop listening")

        analyzer.emit(PitchReading(440.0, 0.99))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("IN TUNE")
        composeRule.onNodeWithTag(TunerTestTags.TARGET_NOTE).assertTextEquals("A")
        composeRule.onNodeWithTag(TunerTestTags.TARGET_OCTAVE).assertTextEquals("4")
        composeRule.onNodeWithTag(TunerTestTags.CENTS).assertTextEquals("+0 cents")
        composeRule.onNodeWithTag(TunerTestTags.FREQUENCY).assertTextContains("440.0 Hz")

        composeRule.onNodeWithTag(TunerTestTags.string(50)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.string(50)).assertIsSelected()
        composeRule.onNodeWithTag(TunerTestTags.TARGET_NOTE).assertTextEquals("D")
        composeRule.onNodeWithTag(TunerTestTags.TARGET_OCTAVE).assertTextEquals("3")

        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).assertIsSelected()
        composeRule.onNodeWithTag(TunerTestTags.TARGET_NOTE).assertTextEquals("A")

        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).performClick()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("READY WHEN YOU ARE")
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).assertTextContains("Start tuning")
    }

    @Test
    fun flatAndSharpReadingsShowTheCorrectGuidance() {
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).performClick()
        val a4 = 440.0

        analyzer.emit(PitchReading(a4 * 2.0.pow(-12.0 / 1200.0), 0.95))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("TUNE UP")
        composeRule.onNodeWithTag(TunerTestTags.CENTS).assertTextEquals("-12 cents")

        analyzer.emit(PitchReading(a4 * 2.0.pow(12.0 / 1200.0), 0.95))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("TUNE DOWN")
        composeRule.onNodeWithTag(TunerTestTags.CENTS).assertTextEquals("+12 cents")
    }

    @Test
    fun listeningWithoutAMatchedPitchShowsThePromptSafely() {
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).performClick()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("PLAY ONE STRING")

        analyzer.emit(PitchReading(130.0, 0.95))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("PLAY ONE STRING")
        composeRule.onNodeWithTag(TunerTestTags.TARGET_NOTE).assertTextEquals("\u2014")
        composeRule.onNodeWithTag(TunerTestTags.CENTS).assertTextEquals("\u2014 cents")
        composeRule.onNodeWithTag(TunerTestTags.FREQUENCY).assertTextEquals("D3 \u2014 F6")
    }

    @Test
    fun manualStringCanBeSelectedAndToggledBackToAutomaticMode() {
        composeRule.onNodeWithTag(TunerTestTags.string(50)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TunerTestTags.string(50)).assertIsSelected()
        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).assertIsNotSelected()
        composeRule.onNodeWithTag(TunerTestTags.TARGET_NOTE).assertTextEquals("D")
        composeRule.onNodeWithTag(TunerTestTags.TARGET_OCTAVE).assertTextEquals("3")
        composeRule.onNodeWithTag(TunerTestTags.FREQUENCY).assertTextContains("Target 146.8 Hz")

        composeRule.onNodeWithTag(TunerTestTags.string(50)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.string(50)).assertIsNotSelected()
        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).assertIsSelected()
    }

    @Test
    fun foregroundTransitionStopsAndRestartsARequestedSession() {
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).performClick()
        assertEquals(1, analyzer.startCount)

        viewModel.leaveForeground()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("READY WHEN YOU ARE")
        assertEquals(1, analyzer.stopCount)

        viewModel.enterForeground()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON)
            .assertTextContains("Stop listening")
        assertEquals(2, analyzer.startCount)
    }

    @Test
    fun failedAnalyzerStartCanBeRetriedFromTheScreen() {
        analyzer.startsSuccessfully = false
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).performClick()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("READY WHEN YOU ARE")
        assertEquals(1, analyzer.startCount)

        analyzer.startsSuccessfully = true
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).performClick()
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON)
            .assertTextContains("Stop listening")
        assertEquals(2, analyzer.startCount)
    }

    @Test
    fun permissionResultControlsTheErrorMessage() {
        composeRule.onNodeWithTag(TunerTestTags.PERMISSION_MESSAGE).assertDoesNotExist()

        viewModel.onMicrophonePermissionResult(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.PERMISSION_MESSAGE).assertExists()

        viewModel.onMicrophonePermissionResult(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TunerTestTags.PERMISSION_MESSAGE).assertDoesNotExist()
    }

    private class FakePitchAnalyzer : PitchAnalyzer {
        var startsSuccessfully = true
        var startCount = 0
            private set
        var stopCount = 0
            private set
        private var listener: ((PitchReading?) -> Unit)? = null

        override fun start(onPitch: (PitchReading?) -> Unit): Boolean {
            startCount++
            if (startsSuccessfully) listener = onPitch
            return startsSuccessfully
        }

        override fun stop() {
            stopCount++
            listener = null
        }

        fun emit(reading: PitchReading?) {
            listener?.invoke(reading)
        }
    }
}
