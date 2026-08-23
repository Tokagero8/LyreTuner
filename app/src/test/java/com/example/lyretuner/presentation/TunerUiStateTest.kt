package com.example.lyretuner.presentation

import com.example.lyretuner.analysis.PitchReading
import com.example.lyretuner.tuning.LyreTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TunerUiStateTest {
    @Test
    fun emptyStateHasNoTargetOrDeviation() {
        val state = TunerUiState()

        assertNull(state.targetString)
        assertNull(state.centsFromTarget)
        assertFalse(state.isInTune)
    }

    @Test
    fun automaticSelectorResultProvidesTargetAndDerivedValues() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }
        val state = TunerUiState(
            reading = PitchReading(440.0, 1.0),
            automaticString = a4,
        )

        assertEquals("A4", state.targetString?.name)
        assertEquals(0.0, state.centsFromTarget!!, 0.000_001)
        assertTrue(state.isInTune)
    }

    @Test
    fun lockedStringOverridesAutomaticSelection() {
        val d3 = LyreTuning.strings.first()
        val a4 = LyreTuning.strings.single { it.name == "A4" }
        val state = TunerUiState(
            reading = PitchReading(440.0, 1.0),
            lockedString = d3,
            automaticString = a4,
        )

        assertEquals(d3, state.targetString)
        assertTrue(state.centsFromTarget!! > 1_000.0)
        assertFalse(state.isInTune)
    }

    @Test
    fun automaticSelectorResultIsTheSoleAutomaticTarget() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }
        val b4 = LyreTuning.strings.single { it.name == "B4" }
        val state = TunerUiState(
            reading = PitchReading(a4.frequencyHz, 1.0),
            automaticString = b4,
        )

        assertEquals(b4, state.targetString)
        assertTrue(state.centsFromTarget!! < -100.0)
    }

    @Test
    fun lockedStringRemainsATargetWithoutAReading() {
        val d3 = LyreTuning.strings.first()
        val state = TunerUiState(lockedString = d3)

        assertEquals(d3, state.targetString)
        assertNull(state.centsFromTarget)
        assertFalse(state.isInTune)
    }

    @Test
    fun readingWithoutAutomaticSelectionHasNoTarget() {
        val state = TunerUiState(reading = PitchReading(440.0, 0.95))

        assertNull(state.targetString)
        assertNull(state.centsFromTarget)
        assertFalse(state.isInTune)
    }

    @Test
    fun inTuneToleranceIncludesFourCentsButNotMore() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }
        fun stateAt(cents: Double) = TunerUiState(
            reading = PitchReading(440.0 * 2.0.pow(cents / 1200.0), 1.0),
            automaticString = a4,
        )

        assertTrue(stateAt(-4.0).isInTune)
        assertTrue(stateAt(4.0).isInTune)
        assertFalse(stateAt(-4.01).isInTune)
        assertFalse(stateAt(4.01).isInTune)
    }
}
