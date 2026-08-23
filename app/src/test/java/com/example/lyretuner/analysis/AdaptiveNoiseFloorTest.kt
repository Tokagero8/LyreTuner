package com.example.lyretuner.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNoiseFloorTest {
    @Test
    fun remainsStableWithConstantBackgroundNoise() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 10)

        repeat(30) { noiseFloor.observeRejected(0.002) }

        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)
        assertEquals(0.008, noiseFloor.signalThreshold, 0.0)
    }

    @Test
    fun slowlyFollowsIncreasedBackgroundNoise() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 10)

        repeat(8) { noiseFloor.observeRejected(0.006) }
        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)

        noiseFloor.observeRejected(0.006)
        assertEquals(0.006, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun followsReducedBackgroundNoise() {
        val noiseFloor = AdaptiveNoiseFloor(initialNoiseRms = 0.006, historySize = 10)

        noiseFloor.observeRejected(0.001)
        assertEquals(0.006, noiseFloor.estimatedRms, 0.0)

        noiseFloor.observeRejected(0.001)
        assertEquals(0.001, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun shortLoudAttackDoesNotRaiseTheFloor() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 10)

        noiseFloor.observeRejected(0.2)

        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun sustainedAcceptedPitchIsNotObservedAsRejectedNoise() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 10)
        val rms = 0.03

        repeat(100) {
            val accepted = noiseFloor.acceptsPitch(rms = rms, periodicity = 0.96)
            assertTrue(accepted)
            if (!accepted) noiseFloor.observeRejected(rms)
        }

        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun clampsLearnedMeasurementsToConfiguredBounds() {
        val lower = AdaptiveNoiseFloor(
            initialNoiseRms = 0.002,
            historySize = 4,
            minimumRms = 0.001,
            maximumRms = 0.01,
        )
        lower.observeRejected(0.0)
        assertEquals(0.001, lower.estimatedRms, 0.0)

        val upper = AdaptiveNoiseFloor(
            initialNoiseRms = 0.002,
            historySize = 4,
            minimumRms = 0.001,
            maximumRms = 0.01,
        )
        repeat(4) { upper.observeRejected(1.0) }
        assertEquals(0.01, upper.estimatedRms, 0.0)
    }

    @Test
    fun stronglyPeriodicPitchCanPassSlightlyBelowTheRmsThreshold() {
        val noiseFloor = AdaptiveNoiseFloor()

        assertTrue(noiseFloor.acceptsPitch(rms = 0.007, periodicity = 0.96))
        assertFalse(noiseFloor.acceptsPitch(rms = 0.007, periodicity = 0.80))
        assertFalse(noiseFloor.acceptsPitch(rms = 0.004, periodicity = 0.96))
    }

    @Test
    fun strongPeriodicityBypassIsLimitedToItsConfiguredRmsRange() {
        val noiseFloor = AdaptiveNoiseFloor()

        assertEquals(0.006, noiseFloor.strongPeriodicityRmsThreshold, 0.0)
        assertTrue(noiseFloor.acceptsPitch(rms = 0.006, periodicity = 0.96))
        assertFalse(noiseFloor.acceptsPitch(rms = 0.005_9, periodicity = 0.96))
        assertFalse(noiseFloor.acceptsPitch(rms = 0.007_9, periodicity = 0.91))
        assertTrue(noiseFloor.acceptsPitch(rms = 0.008, periodicity = 0.80))
    }

    @Test
    fun acceptedPitchIsExcludedFromNoiseLearning() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 10)
        val rms = 0.007
        val periodicity = 0.96

        repeat(20) {
            val accepted = noiseFloor.acceptsPitch(rms, periodicity)
            assertTrue(accepted)
            if (!accepted) noiseFloor.observeRejected(rms)
        }

        assertTrue(noiseFloor.acceptsPitch(rms, periodicity))
        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun rejectedModeratelyPeriodicBackgroundDoesNotFreezeDownwardAdaptation() {
        val noiseFloor = AdaptiveNoiseFloor(initialNoiseRms = 0.006, historySize = 10)
        val backgroundRms = 0.001
        val periodicity = 0.85

        repeat(20) {
            val accepted = noiseFloor.acceptsPitch(backgroundRms, periodicity)
            assertFalse(accepted)
            if (!accepted) noiseFloor.observeRejected(backgroundRms)
        }

        assertEquals(0.001, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun stronglyPeriodicRejectedHumCannotRaiseTheNoiseFloor() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 10)

        repeat(30) {
            noiseFloor.observeRejected(minOf(0.02, noiseFloor.estimatedRms))
        }

        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun stronglyPeriodicRejectedHumCanLowerTheNoiseFloor() {
        val noiseFloor = AdaptiveNoiseFloor(initialNoiseRms = 0.006, historySize = 10)

        repeat(2) {
            noiseFloor.observeRejected(minOf(0.001, noiseFloor.estimatedRms))
        }

        assertEquals(0.001, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun weakPeriodicityIsRejectedEvenWhenSignalIsLoud() {
        val noiseFloor = AdaptiveNoiseFloor()

        assertFalse(noiseFloor.acceptsPitch(rms = 0.1, periodicity = 0.79))
        assertTrue(noiseFloor.acceptsPitch(rms = 0.1, periodicity = 0.80))
    }

    @Test
    fun rejectsInvalidRmsMeasurements() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 4)

        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.1)
            .forEach(noiseFloor::observeRejected)

        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)
        assertFalse(noiseFloor.acceptsPitch(Double.NaN, 0.99))
        assertFalse(noiseFloor.acceptsPitch(0.1, Double.NaN))
        assertFalse(noiseFloor.acceptsPitch(0.1, -0.1))
        assertFalse(noiseFloor.acceptsPitch(0.1, 1.1))
    }

    @Test
    fun rejectsInvalidConfigurationBeforeCreatingBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveNoiseFloor(minimumRms = 0.01, maximumRms = 0.001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveNoiseFloor(initialNoiseRms = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveNoiseFloor(thresholdMultiplier = Double.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveNoiseFloor(periodicBypassRmsRatio = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveNoiseFloor(
                acceptablePeriodicityThreshold = 0.95,
                strongPeriodicityThreshold = 0.90,
            )
        }
    }

    @Test
    fun centeredRmsIgnoresDcOffset() {
        val samples = floatArrayOf(0.4f, 0.6f, 0.4f, 0.6f)

        assertEquals(0.1, centeredRms(samples), 0.000_001)
    }
}
