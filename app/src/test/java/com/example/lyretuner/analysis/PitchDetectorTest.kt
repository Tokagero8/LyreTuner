package com.example.lyretuner.analysis

import com.example.lyretuner.tuning.LyreTuning
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class PitchDetectorTest {
    @Test
    fun detectsEveryLyreString() {
        LyreTuning.strings.forEach { string ->
            val detected = PitchDetector.detect(sineWave(string.frequencyHz), SAMPLE_RATE)

            assertNotNull("Expected to detect ${string.name}", detected)
            assertPitchWithinCents(string.frequencyHz, detected!!.frequencyHz, 2.0)
            assertTrue("Low periodicity for ${string.name}", detected.periodicity > 0.85)
        }
    }

    @Test
    fun detectsEveryLyreStringAt48KhzWithTheProductionFrameSize() {
        LyreTuning.strings.forEach { string ->
            val detected = PitchDetector.detect(
                samples = sineWave(
                    frequencyHz = string.frequencyHz,
                    sampleRate = SAMPLE_RATE_48_KHZ,
                ),
                sampleRate = SAMPLE_RATE_48_KHZ,
            )

            assertNotNull("Expected to detect ${string.name} at 48 kHz", detected)
            assertPitchWithinCents(string.frequencyHz, detected!!.frequencyHz, 2.0)
        }
    }

    @Test
    fun rejectsPitchesOutsideTheSupportedDetectionRange() {
        listOf(MIN_DETECTION_FREQUENCY - 1.0, MAX_DETECTION_FREQUENCY + 1.0)
            .forEach { frequencyHz ->
                assertNull(
                    "Expected $frequencyHz Hz to be outside the detector range",
                    PitchDetector.detect(sineWave(frequencyHz), SAMPLE_RATE),
                )
            }
    }

    @Test
    fun detectsPitchesDetunedBySeveralCents() {
        listOf(-31.0, 23.0).forEach { detuningCents ->
            val frequency = frequencyAtCents(440.0, detuningCents)
            val detected = PitchDetector.detect(sineWave(frequency), SAMPLE_RATE)

            assertNotNull(detected)
            assertPitchWithinCents(frequency, detected!!.frequencyHz, 2.0)
        }
    }

    @Test
    fun ignoresDcOffsetOnAValidTone() {
        val samples = sineWave(329.63, amplitude = 0.25)
        for (index in samples.indices) samples[index] += 0.35f

        val detected = PitchDetector.detect(samples, SAMPLE_RATE)

        assertNotNull(detected)
        assertPitchWithinCents(329.63, detected!!.frequencyHz, 2.0)
    }

    @Test
    fun detectsFundamentalInHarmonicRichPluckedTone() {
        val frequency = 220.0
        val samples = FloatArray(FRAME_SIZE) { index ->
            val phase = 2.0 * PI * frequency * index / SAMPLE_RATE
            val envelope = 1.0 - index.toDouble() / (FRAME_SIZE * 1.5)
            (envelope * (
                0.50 * sin(phase) +
                    0.25 * sin(phase * 2.0) +
                    0.12 * sin(phase * 3.0)
                )).toFloat()
        }

        val detected = PitchDetector.detect(samples, SAMPLE_RATE)

        assertNotNull(detected)
        assertPitchWithinCents(frequency, detected!!.frequencyHz, 5.0)
    }

    @Test
    fun detectsFundamentalWhenTheSecondHarmonicIsStronger() {
        val frequency = 220.0
        val samples = FloatArray(FRAME_SIZE) { index ->
            val phase = 2.0 * PI * frequency * index / SAMPLE_RATE
            (0.18 * sin(phase) + 0.55 * sin(phase * 2.0)).toFloat()
        }

        val detected = PitchDetector.detect(samples, SAMPLE_RATE)

        assertNotNull(detected)
        assertPitchWithinCents(frequency, detected!!.frequencyHz, 5.0)
    }

    @Test
    fun ignoresSilenceDcAndLowLevelNoise() {
        assertNull(PitchDetector.detect(FloatArray(FRAME_SIZE), SAMPLE_RATE))
        assertNull(PitchDetector.detect(FloatArray(FRAME_SIZE) { 0.5f }, SAMPLE_RATE))

        val random = Random(7)
        val quietNoise = FloatArray(FRAME_SIZE) { (random.nextFloat() - 0.5f) * 0.004f }
        assertNull(PitchDetector.detect(quietNoise, SAMPLE_RATE))
    }

    @Test
    fun rejectsAWeakToneBelowTheConfiguredRmsThreshold() {
        val weakTone = sineWave(frequencyHz = 440.0, amplitude = 0.002)

        assertNull(PitchDetector.detect(weakTone, SAMPLE_RATE))
        val ungated = PitchDetector.detect(weakTone, SAMPLE_RATE, minimumRms = 0.0)
        assertNotNull(ungated)
        assertPitchWithinCents(440.0, ungated!!.frequencyHz, 2.0)
    }

    @Test
    fun remainsAccurateWithSmallBroadbandNoise() {
        val random = Random(11)
        val samples = sineWave(frequencyHz = 440.0, amplitude = 0.25)
        for (index in samples.indices) {
            samples[index] += ((random.nextDouble() - 0.5) * 0.02).toFloat()
        }

        val detected = PitchDetector.detect(samples, SAMPLE_RATE)

        assertNotNull(detected)
        assertPitchWithinCents(440.0, detected!!.frequencyHz, 5.0)
    }

    @Test
    fun rejectsNonPeriodicWhiteNoiseEvenWithoutRmsGating() {
        val random = Random(19)
        val noise = FloatArray(FRAME_SIZE) { (random.nextFloat() - 0.5f) * 0.5f }

        assertNull(PitchDetector.detect(noise, SAMPLE_RATE, minimumRms = 0.0))
    }

    @Test
    fun rejectsInvalidInput() {
        assertNull(PitchDetector.detect(FloatArray(128), SAMPLE_RATE))
        assertNull(PitchDetector.detect(FloatArray(FRAME_SIZE), 0))
        assertNull(PitchDetector.detect(FloatArray(FRAME_SIZE), -1))
        assertNull(PitchDetector.detect(FloatArray(FRAME_SIZE), SAMPLE_RATE, minimumRms = -0.1))
        assertNull(PitchDetector.detect(FloatArray(FRAME_SIZE), SAMPLE_RATE, minimumRms = Double.NaN))
    }

    @Test
    fun detectsD3OnlyWhenInputMeetsTheMinimumSampleCount() {
        val d3 = LyreTuning.strings.first().frequencyHz

        assertNull(PitchDetector.detect(sineWave(d3, 2_035), SAMPLE_RATE))
        val detected = PitchDetector.detect(sineWave(d3, 2_036), SAMPLE_RATE)

        assertNotNull(detected)
        assertPitchWithinCents(d3, detected!!.frequencyHz, 2.0)
    }

    @Test
    fun reusableWorkspaceDoesNotLeakResultsBetweenDifferentPitches() {
        val workspace = PitchDetector.Workspace()
        listOf(146.83, 440.0, 1_396.91).forEach { frequencyHz ->
            val detected = PitchDetector.detect(
                samples = sineWave(frequencyHz),
                sampleRate = SAMPLE_RATE,
                minimumRms = 0.008,
                workspace = workspace,
            )

            assertNotNull("Expected to detect $frequencyHz Hz with a reused workspace", detected)
            assertPitchWithinCents(frequencyHz, detected!!.frequencyHz, 2.0)
        }
    }

    @Test
    fun reusableWorkspaceMatchesThePublicDetectionPath() {
        val workspace = PitchDetector.Workspace()
        listOf(146.83, 440.0, 1_396.91).forEach { frequencyHz ->
            val samples = sineWave(frequencyHz)
            val normal = requireNotNull(PitchDetector.detect(samples, SAMPLE_RATE))
            val reusable = requireNotNull(
                PitchDetector.detect(
                    samples = samples,
                    sampleRate = SAMPLE_RATE,
                    minimumRms = 0.008,
                    workspace = workspace,
                ),
            )

            assertPitchWithinCents(normal.frequencyHz, reusable.frequencyHz, 0.000_001)
            assertTrue(abs(normal.periodicity - reusable.periodicity) <= 0.000_000_001)
        }
    }

    private fun sineWave(
        frequencyHz: Double,
        sampleCount: Int = FRAME_SIZE,
        sampleRate: Int = SAMPLE_RATE,
        amplitude: Double = 0.5,
    ): FloatArray =
        FloatArray(sampleCount) { index ->
            (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
        }

    private fun assertPitchWithinCents(
        expectedHz: Double,
        actualHz: Double,
        toleranceCents: Double,
    ) {
        val errorCents = abs(LyreTuning.centsBetween(actualHz, expectedHz))
        assertTrue(
            "Expected $expectedHz Hz, found $actualHz Hz ($errorCents cents)",
            errorCents <= toleranceCents,
        )
    }

    private fun frequencyAtCents(frequencyHz: Double, cents: Double): Double =
        frequencyHz * 2.0.pow(cents / 1_200.0)

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val SAMPLE_RATE_48_KHZ = 48_000
        const val FRAME_SIZE = 4_096
        const val MIN_DETECTION_FREQUENCY = 130.0
        const val MAX_DETECTION_FREQUENCY = 1_550.0
    }
}
