package com.example.lyretuner.analysis

import com.example.lyretuner.tuning.LyreTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class PitchStabilizerTest {
    @Test
    fun normalTransitionConfirmsExactlyAtTheEvidenceThreshold() {
        val harness = Harness()

        assertNull(harness.stabilize(220.0, periodicity = 1.0))
        harness.advance(39)
        assertNull(harness.stabilize(220.0, periodicity = 1.0))
        harness.advance(1)
        assertFrequency(220.0, harness.stabilize(220.0, periodicity = 1.0))
    }

    @Test
    fun smoothsJitterAroundTheSamePitch() {
        val harness = stabilizedAt(220.0)
        val tenCentsSharp = frequencyAtCents(220.0, 10.0)

        harness.advance(50)
        val smoothed = harness.stabilize(tenCentsSharp)

        assertNotNull(smoothed)
        assertTrue(smoothed!!.frequencyHz > 220.0)
        assertTrue(smoothed.frequencyHz < tenCentsSharp)
    }

    @Test
    fun suppressesAnIsolatedBadReading() {
        val harness = stabilizedAt(220.0)

        harness.advance(50)
        assertFrequency(220.0, harness.stabilize(293.66))
        harness.advance(50)
        assertFrequency(220.0, harness.stabilize(220.0))
    }

    @Test
    fun acceptsANormalPitchTransition() {
        val harness = stabilizedAt(220.0)

        harness.advance(50)
        assertFrequency(220.0, harness.stabilize(293.66))
        harness.advance(50)
        assertFrequency(293.66, harness.stabilize(293.66))
    }

    @Test
    fun suppressesAnIsolatedOctaveUpReading() {
        val harness = stabilizedAt(220.0)

        harness.advance(50)
        assertFrequency(220.0, harness.stabilize(440.0))
    }

    @Test
    fun suppressesAnIsolatedOctaveDownReading() {
        val harness = stabilizedAt(440.0)

        harness.advance(50)
        assertFrequency(440.0, harness.stabilize(220.0))
    }

    @Test
    fun eventuallyAcceptsAPersistentIntentionalOctaveTransition() {
        val harness = stabilizedAt(220.0)

        harness.advance(50)
        assertFrequency(220.0, harness.stabilize(440.0))
        repeat(2) {
            harness.advance(50)
            assertFrequency(220.0, harness.stabilize(440.0))
        }
        harness.advance(50)
        assertFrequency(440.0, harness.stabilize(440.0))
    }

    @Test
    fun weakPeriodicityCandidateNeedsMoreElapsedEvidence() {
        val harness = Harness()

        assertNull(harness.stabilize(220.0, periodicity = 0.70))
        harness.advance(50)
        assertNull(harness.stabilize(220.0, periodicity = 0.70))
        harness.advance(50)
        assertFrequency(220.0, harness.stabilize(220.0, periodicity = 0.70))
    }

    @Test
    fun readingsBelowMinimumPeriodicityAreRejected() {
        val harness = Harness()

        repeat(4) {
            harness.advance(50)
            assertNull(harness.stabilize(220.0, periodicity = 0.69))
        }
        assertNull(harness.stabilize(220.0, periodicity = 1.01))
    }

    @Test
    fun clearsAfterTheConfiguredMissingDuration() {
        val harness = stabilizedAt(220.0)

        assertFrequency(220.0, harness.stabilizeMissing())
        harness.advance(89)
        assertFrequency(220.0, harness.stabilizeMissing())
        harness.advance(1)
        assertNull(harness.stabilizeMissing())
    }

    @Test
    fun transitionTimingIsIndependentOfCallbackRate() {
        val fast = stabilizedAt(220.0)
        val slow = stabilizedAt(220.0)

        fast.stabilize(293.66)
        repeat(2) {
            fast.advance(25)
            fast.stabilize(293.66)
        }

        slow.stabilize(293.66)
        slow.advance(50)
        val slowResult = slow.stabilize(293.66)

        assertFrequency(293.66, fast.currentResult)
        assertFrequency(293.66, slowResult)
    }

    @Test
    fun longObservationGapDoesNotCountAsTransitionEvidence() {
        val config = PitchStabilizer.Config(maximumObservationGapMillis = 60.0)
        val harness = stabilizedAt(220.0, config)

        harness.advance(10)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))
        harness.advance(61)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))

        harness.advance(39)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))
        harness.advance(1)
        assertFrequency(293.66, harness.stabilize(293.66, periodicity = 1.0))
    }

    @Test
    fun observationAtMaximumGapStillCountsAsTransitionEvidence() {
        val config = PitchStabilizer.Config(maximumObservationGapMillis = 60.0)
        val harness = stabilizedAt(220.0, config)

        harness.advance(10)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))
        harness.advance(60)
        assertFrequency(293.66, harness.stabilize(293.66, periodicity = 1.0))
    }

    @Test
    fun missingReadingClearsCurrentTransitionCandidate() {
        val harness = stabilizedAt(220.0)

        harness.advance(10)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))
        harness.advance(20)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))
        assertFrequency(220.0, harness.stabilizeMissing())

        harness.advance(10)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))
        harness.advance(39)
        assertFrequency(220.0, harness.stabilize(293.66, periodicity = 1.0))
        harness.advance(1)
        assertFrequency(293.66, harness.stabilize(293.66, periodicity = 1.0))
    }

    private fun stabilizedAt(
        frequencyHz: Double,
        config: PitchStabilizer.Config = PitchStabilizer.Config(),
    ): Harness = Harness(config).also { harness ->
        assertNull(harness.stabilize(frequencyHz))
        harness.advance(50)
        assertFrequency(frequencyHz, harness.stabilize(frequencyHz))
    }

    private fun assertFrequency(expectedHz: Double, reading: PitchReading?) {
        assertNotNull(reading)
        val errorCents = abs(LyreTuning.centsBetween(reading!!.frequencyHz, expectedHz))
        assertTrue("Expected $expectedHz Hz, found ${reading.frequencyHz}", errorCents < 0.01)
    }

    private fun frequencyAtCents(frequencyHz: Double, cents: Double): Double =
        frequencyHz * 2.0.pow(cents / 1_200.0)

    private class Harness(config: PitchStabilizer.Config = PitchStabilizer.Config()) {
        private var nowNanos = 0L
        private val stabilizer = PitchStabilizer(config = config, nanoTime = { nowNanos })

        var currentResult: PitchReading? = null
            private set

        fun advance(milliseconds: Long) {
            nowNanos += milliseconds * 1_000_000L
        }

        fun stabilize(frequencyHz: Double, periodicity: Double = 0.98): PitchReading? =
            stabilizer.stabilize(PitchReading(frequencyHz, periodicity)).also {
                currentResult = it
            }

        fun stabilizeMissing(): PitchReading? = stabilizer.stabilize(null).also {
            currentResult = it
        }
    }
}
