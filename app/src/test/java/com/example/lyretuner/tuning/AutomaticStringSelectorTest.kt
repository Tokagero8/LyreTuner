package com.example.lyretuner.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.pow

class AutomaticStringSelectorTest {
    @Test
    fun exactAcquisitionBoundaryAcquiresAboveAndBelowTarget() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }

        listOf(-60.0, 60.0).forEach { cents ->
            val selector = AutomaticStringSelector()
            assertEquals(a4, selector.select(frequencyAtCents(a4.frequencyHz, cents)))
        }
    }

    @Test
    fun beyondAcquisitionBoundaryDoesNotAcquireAboveOrBelowTarget() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }

        listOf(-60.01, 60.01).forEach { cents ->
            val selector = AutomaticStringSelector()
            assertNull(selector.select(frequencyAtCents(a4.frequencyHz, cents)))
        }
    }

    @Test
    fun hysteresisPreventsSwitchingForASmallBoundaryJitter() {
        val selector = AutomaticStringSelector()
        val e4 = LyreTuning.strings.single { it.name == "E4" }

        assertEquals(e4, selector.select(e4.frequencyHz))
        assertEquals(e4, selector.select(frequencyAtCents(e4.frequencyHz, 55.0)))
    }

    @Test
    fun switchesWhenTheAdjacentStringIsClearlyCloser() {
        val selector = AutomaticStringSelector()
        val e4 = LyreTuning.strings.single { it.name == "E4" }

        selector.select(e4.frequencyHz)

        assertEquals("F4", selector.select(frequencyAtCents(e4.frequencyHz, 60.0))?.name)
    }

    @Test
    fun handlesEFAndBCSemitoneBoundariesWithHysteresis() {
        listOf("E4" to "F4", "B4" to "C5").forEach { (lowName, highName) ->
            val selector = AutomaticStringSelector()
            val low = LyreTuning.strings.single { it.name == lowName }

            assertEquals(lowName, selector.select(low.frequencyHz)?.name)
            assertEquals(lowName, selector.select(frequencyAtCents(low.frequencyHz, 52.0))?.name)
            assertEquals(highName, selector.select(frequencyAtCents(low.frequencyHz, 60.0))?.name)
        }
    }

    @Test
    fun driftingAcrossABoundaryDoesNotSwitchBackAndForth() {
        val selector = AutomaticStringSelector()
        val e4 = LyreTuning.strings.single { it.name == "E4" }

        assertEquals("E4", selector.select(e4.frequencyHz)?.name)
        listOf(48.0, 52.0, 49.0, 55.0).forEach { cents ->
            assertEquals("E4", selector.select(frequencyAtCents(e4.frequencyHz, cents))?.name)
        }
        assertEquals("F4", selector.select(frequencyAtCents(e4.frequencyHz, 60.0))?.name)
        assertEquals("F4", selector.select(frequencyAtCents(e4.frequencyHz, 48.0))?.name)
    }

    @Test
    fun exactReleaseBoundaryRetainsAboveAndBelowTarget() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }

        listOf(-80.0, 80.0).forEach { cents ->
            val selector = AutomaticStringSelector()
            assertEquals(a4, selector.select(a4.frequencyHz))
            repeat(2) {
                assertEquals(a4, selector.select(frequencyAtCents(a4.frequencyHz, cents)))
            }
        }
    }

    @Test
    fun beyondReleaseBoundaryCountsAsOutOfRangeAboveAndBelowTarget() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }

        listOf(-80.01, 80.01).forEach { cents ->
            val selector = AutomaticStringSelector()
            val detuned = frequencyAtCents(a4.frequencyHz, cents)
            selector.select(a4.frequencyHz)
            assertEquals(a4, selector.select(detuned))
            assertNull(selector.select(detuned))
        }
    }

    @Test
    fun invalidReadingImmediatelyClearsTheSelection() {
        val selector = AutomaticStringSelector()

        assertEquals("A4", selector.select(440.0)?.name)
        assertNull(selector.select(null))
        assertNull(selector.select(Double.NaN))
    }

    private fun frequencyAtCents(frequencyHz: Double, cents: Double): Double =
        frequencyHz * 2.0.pow(cents / 1_200.0)
}
