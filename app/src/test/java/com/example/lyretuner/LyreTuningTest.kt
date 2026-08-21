package com.example.lyretuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class LyreTuningTest {
    @Test
    fun containsTheExpected24DiatonicStrings() {
        val expected = listOf(
            "D3", "E3", "F3", "G3", "A3", "B3",
            "C4", "D4", "E4", "F4", "G4", "A4", "B4",
            "C5", "D5", "E5", "F5", "G5", "A5", "B5",
            "C6", "D6", "E6", "F6",
        )

        assertEquals(expected, LyreTuning.strings.map(LyreString::name))
        assertEquals(24, LyreTuning.strings.map(LyreString::midi).distinct().size)
        assertTrue(LyreTuning.strings.zipWithNext().all { (low, high) ->
            low.frequencyHz < high.frequencyHz
        })
    }

    @Test
    fun usesA4At440Hertz() {
        val a4 = LyreTuning.strings.single { it.name == "A4" }
        assertEquals(440.0, a4.frequencyHz, 0.000_001)
        assertEquals("A4", LyreTuning.nearestString(440.0)?.name)
    }

    @Test
    fun usesConfiguredFrequenciesAtTheEndsOfTheTuning() {
        assertEquals(146.83, LyreTuning.strings.first().frequencyHz, 0.0)
        assertEquals(1_396.91, LyreTuning.strings.last().frequencyHz, 0.0)
    }

    @Test
    fun nearestStringMatchesEveryConfiguredFrequency() {
        LyreTuning.strings.forEach { string ->
            assertEquals(string, LyreTuning.nearestString(string.frequencyHz))
        }
    }

    @Test
    fun lookupFrequenciesMatchTheirMidiNotesAfterTwoDecimalRounding() {
        LyreTuning.strings.forEach { string ->
            val equalTemperedFrequency = 440.0 * 2.0.pow((string.midi - 69) / 12.0)
            assertEquals(string.name, equalTemperedFrequency, string.frequencyHz, 0.005)
        }
    }

    @Test
    fun lookupNamesAndOctavesMatchTheirMidiNotes() {
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        LyreTuning.strings.forEach { string ->
            assertEquals(noteNames[string.midi % 12], string.note)
            assertEquals(string.midi / 12 - 1, string.octave)
        }
    }

    @Test
    fun nearestStringRejectsInvalidFrequencies() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -440.0).forEach { invalid ->
            assertNull(LyreTuning.nearestString(invalid))
        }
    }

    @Test
    fun nearestStringRejectsPitchesBeyondTheOuterStrings() {
        assertNull(LyreTuning.nearestString(130.0))
        assertNull(LyreTuning.nearestString(1_550.0))
    }

    @Test
    fun nearestStringRejectsAmbiguousAutomaticMatches() {
        val seventyCentsSharp = 440.0 * 2.0.pow(70.0 / 1_200.0)

        assertNull(LyreTuning.nearestString(seventyCentsSharp))
    }

    @Test
    fun centsConversionRoundTripsNegativeZeroPositiveAndOctaveOffsets() {
        val referenceFrequency = 440.0
        val offsets = listOf(-1_200.0, -137.5, -25.0, 0.0, 31.25, 100.0, 1_200.0)

        offsets.forEach { expectedCents ->
            val convertedFrequency = referenceFrequency * 2.0.pow(expectedCents / 1_200.0)
            val actualCents = LyreTuning.centsBetween(convertedFrequency, referenceFrequency)

            assertEquals(expectedCents, actualCents, 0.000_001)
        }
    }
}
