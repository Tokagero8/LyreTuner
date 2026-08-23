package com.example.lyretuner.analysis

import com.example.lyretuner.tuning.AutomaticStringSelector
import com.example.lyretuner.tuning.LyreString
import com.example.lyretuner.tuning.LyreTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Exercises the production DSP/state components together without Android audio APIs. */
class PitchPipelineIntegrationTest {
    @Test
    fun quietStronglyPeriodicStreamPassesTheAdaptiveGateAndSelectsA4() {
        val pipeline = TestPipeline()
        var latest: Snapshot? = null

        repeat(12) { hop ->
            pipeline.append(sineHop(440.0, hop, amplitude = 0.01))?.let { latest = it }
        }

        val snapshot = requireNotNull(latest)
        assertTrue(snapshot.rms < snapshot.rmsThreshold)
        assertEquals(0.008, snapshot.rmsThreshold, 0.0)
        assertNotNull(snapshot.gatedReading)
        assertNotNull(snapshot.stableReading)
        assertPitchWithinCents(440.0, snapshot.stableReading!!.frequencyHz, 3.0)
        assertEquals("A4", snapshot.selectedString?.name)
    }

    @Test
    fun streamingPipelineMovesFromA4ToB4AfterSustainedEvidence() {
        val pipeline = TestPipeline()
        var latest: Snapshot? = null

        repeat(12) { hop ->
            pipeline.append(sineHop(440.0, hop, amplitude = 0.25))?.let { latest = it }
        }
        assertEquals("A4", latest?.selectedString?.name)

        repeat(12) { offset ->
            val hop = offset + 12
            pipeline.append(sineHop(493.88, hop, amplitude = 0.25))?.let { latest = it }
        }

        val snapshot = requireNotNull(latest)
        val stableReading = requireNotNull(snapshot.stableReading)
        assertPitchWithinCents(493.88, stableReading.frequencyHz, 4.0)
        assertEquals("B4", snapshot.selectedString?.name)
    }

    @Test
    fun broadbandNoiseDoesNotProduceAStableString() {
        val pipeline = TestPipeline()
        val random = Random(31)
        var latest: Snapshot? = null

        repeat(24) {
            val hop = FloatArray(HOP_SIZE) { (random.nextFloat() - 0.5f) * 0.08f }
            pipeline.append(hop)?.let { latest = it }
        }

        val snapshot = requireNotNull(latest)
        assertNull(snapshot.gatedReading)
        assertNull(snapshot.stableReading)
        assertNull(snapshot.selectedString)
    }

    @Test
    fun stronglyPeriodicRejectedBackgroundCannotRaiseTheNoiseFloor() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 4)
        val processor = StreamingPitchProcessor(
            noiseFloor = noiseFloor,
            analysisIntervalHops = 1,
        )
        var analyzedFrames = 0

        repeat(10) { hop ->
            processor.append(sineHop(440.0, hop, amplitude = 0.005))?.let { result ->
                analyzedFrames++
                assertTrue(requireNotNull(result.rawReading).periodicity >= 0.92)
                assertNull(result.gatedReading)
            }
        }

        assertTrue(analyzedFrames >= 4)
        assertEquals(0.002, noiseFloor.estimatedRms, 0.0)
    }

    @Test
    fun stronglyPeriodicRejectedBackgroundCanLowerTheNoiseFloor() {
        val noiseFloor = AdaptiveNoiseFloor(initialNoiseRms = 0.006, historySize = 4)
        val processor = StreamingPitchProcessor(
            noiseFloor = noiseFloor,
            analysisIntervalHops = 1,
        )
        var latest: PitchAnalysisResult? = null

        repeat(5) { hop ->
            processor.append(sineHop(440.0, hop, amplitude = 0.002))?.let { latest = it }
        }

        val result = requireNotNull(latest)
        assertTrue(requireNotNull(result.rawReading).periodicity >= 0.92)
        assertNull(result.gatedReading)
        assertTrue(noiseFloor.estimatedRms < 0.006)
    }

    @Test
    fun resultReportsThresholdCapturedBeforeRejectedFrameUpdatesNoiseFloor() {
        val noiseFloor = AdaptiveNoiseFloor(historySize = 4)
        val processor = StreamingPitchProcessor(
            noiseFloor = noiseFloor,
            analysisIntervalHops = 1,
        )
        val quietHop = FloatArray(HOP_SIZE) { index ->
            if (index % 2 == 0) 0.000_1f else -0.000_1f
        }
        var firstResult: PitchAnalysisResult? = null
        var secondResult: PitchAnalysisResult? = null

        repeat(5) {
            processor.append(quietHop)?.let { result ->
                if (firstResult == null) firstResult = result else secondResult = result
            }
        }

        assertEquals(0.008, requireNotNull(firstResult).rmsThreshold, 0.0)
        assertEquals(0.002, requireNotNull(secondResult).rmsThreshold, 0.0)
    }

    @Test
    fun rejectsNonPositiveAnalysisInterval() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingPitchProcessor(analysisIntervalHops = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingPitchProcessor(analysisIntervalHops = -1)
        }
    }

    @Test
    fun isolatedDetectorOctaveErrorDoesNotChangeTheSelectedA4String() {
        val pipeline = DetectorStabilizerSelectorPipeline()
        val nearA4 = frequencyAtCents(440.0, 3.0)

        assertNull(pipeline.process(nearA4, elapsedMillis = 0).selectedString)
        val confirmed = pipeline.process(nearA4, elapsedMillis = 50)
        assertEquals("A4", confirmed.selectedString?.name)

        val octaveError = pipeline.process(nearA4 * 2.0, elapsedMillis = 50)
        val rawOctaveReading = requireNotNull(octaveError.rawReading)
        assertPitchWithinCents(nearA4 * 2.0, rawOctaveReading.frequencyHz, 3.0)
        assertEquals("A4", octaveError.selectedString?.name)
        assertPitchWithinCents(
            nearA4,
            requireNotNull(octaveError.stableReading).frequencyHz,
            3.0,
        )

        val recovered = pipeline.process(nearA4, elapsedMillis = 50)
        assertEquals("A4", recovered.selectedString?.name)
    }

    private class TestPipeline {
        private var totalSamples = 0L
        private val processor = StreamingPitchProcessor(
            stabilizer = PitchStabilizer(
                nanoTime = { totalSamples * NANOS_PER_SECOND / SAMPLE_RATE },
            ),
        )
        private val selector = AutomaticStringSelector()

        fun append(samples: FloatArray): Snapshot? {
            require(samples.size == HOP_SIZE)
            totalSamples += samples.size
            val result = processor.append(samples) ?: return null
            val selectedString = selector.select(result.stableReading?.frequencyHz)
            return Snapshot(
                rms = result.rms,
                rmsThreshold = result.rmsThreshold,
                gatedReading = result.gatedReading,
                stableReading = result.stableReading,
                selectedString = selectedString,
            )
        }
    }

    private data class Snapshot(
        val rms: Double,
        val rmsThreshold: Double,
        val gatedReading: PitchReading?,
        val stableReading: PitchReading?,
        val selectedString: LyreString?,
    )

    private inner class DetectorStabilizerSelectorPipeline {
        private val workspace = PitchDetector.Workspace()
        private val stabilizer = PitchStabilizer()
        private val selector = AutomaticStringSelector()
        private var timestampNanos = 0L

        fun process(frequencyHz: Double, elapsedMillis: Long): DirectSnapshot {
            timestampNanos += elapsedMillis * NANOS_PER_MILLISECOND
            val rawReading = PitchDetector.detect(
                samples = toneFrame(frequencyHz),
                sampleRate = SAMPLE_RATE,
                minimumRms = 0.008,
                workspace = workspace,
            )
            val stableReading = stabilizer.stabilize(rawReading, timestampNanos)
            val selectedString = selector.select(stableReading?.frequencyHz)
            return DirectSnapshot(rawReading, stableReading, selectedString)
        }
    }

    private data class DirectSnapshot(
        val rawReading: PitchReading?,
        val stableReading: PitchReading?,
        val selectedString: LyreString?,
    )

    private fun sineHop(
        frequencyHz: Double,
        hopIndex: Int,
        amplitude: Double,
    ): FloatArray {
        val firstSample = hopIndex * HOP_SIZE
        return FloatArray(HOP_SIZE) { index ->
            val sampleIndex = firstSample + index
            (amplitude * sin(2.0 * PI * frequencyHz * sampleIndex / SAMPLE_RATE)).toFloat()
        }
    }

    private fun toneFrame(frequencyHz: Double): FloatArray =
        FloatArray(WINDOW_SIZE) { index ->
            (0.25 * sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE)).toFloat()
        }

    private fun frequencyAtCents(frequencyHz: Double, cents: Double): Double =
        frequencyHz * 2.0.pow(cents / 1_200.0)

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

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val WINDOW_SIZE = 4_096
        const val HOP_SIZE = 1_024
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
