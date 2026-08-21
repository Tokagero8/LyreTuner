package com.example.lyretuner

import kotlin.math.ceil
import kotlin.math.sqrt

data class PitchReading(
    val frequencyHz: Double,
    val periodicity: Double,
)

/** YIN pitch detection tuned for the D3–F6 range of this lyre. */
object PitchDetector {
    private const val MIN_FREQUENCY = 130.0
    private const val MAX_FREQUENCY = 1_550.0
    private const val YIN_THRESHOLD = 0.15
    private const val DEFAULT_MIN_RMS = 0.008
    private const val MIN_PERIOD_COUNT = 6

    fun detect(
        samples: FloatArray,
        sampleRate: Int,
        minimumRms: Double = DEFAULT_MIN_RMS,
    ): PitchReading? = detectInternal(samples, sampleRate, minimumRms, workspace = null)

    internal fun detect(
        samples: FloatArray,
        sampleRate: Int,
        minimumRms: Double,
        workspace: Workspace,
    ): PitchReading? = detectInternal(samples, sampleRate, minimumRms, workspace)

    private fun detectInternal(
        samples: FloatArray,
        sampleRate: Int,
        minimumRms: Double,
        workspace: Workspace?,
    ): PitchReading? {
        if (sampleRate <= 0 || !minimumRms.isFinite() || minimumRms < 0.0) return null
        val minimumSampleCount = ceil(sampleRate / MIN_FREQUENCY * MIN_PERIOD_COUNT).toInt()
        if (samples.size < minimumSampleCount) return null

        if (minimumRms > 0.0) {
            var squareSum = 0.0
            var mean = 0.0
            for (sample in samples) mean += sample
            mean /= samples.size
            for (sample in samples) {
                val centered = sample - mean
                squareSum += centered * centered
            }
            if (sqrt(squareSum / samples.size) < minimumRms) return null
        }

        val minLag = ceil(sampleRate.toDouble() / MAX_FREQUENCY)
            .toInt()
            .coerceAtLeast(2)
        val maxLag = (sampleRate / MIN_FREQUENCY).toInt().coerceAtMost(samples.size / 2)
        if (maxLag <= minLag) return null

        val difference = workspace?.differenceBuffer(maxLag + 1) ?: DoubleArray(maxLag + 1)
        val windowSize = samples.size - maxLag
        for (lag in 1..maxLag) {
            var sum = 0.0
            for (index in 0 until windowSize) {
                val delta = samples[index] - samples[index + lag]
                sum += delta * delta
            }
            difference[lag] = sum
        }

        var runningSum = 0.0
        difference[0] = 1.0
        for (lag in 1..maxLag) {
            runningSum += difference[lag]
            difference[lag] = if (runningSum == 0.0) 1.0 else difference[lag] * lag / runningSum
        }

        var bestLag = -1
        var lag = minLag
        while (lag <= maxLag) {
            if (difference[lag] < YIN_THRESHOLD) {
                while (lag + 1 <= maxLag && difference[lag + 1] < difference[lag]) lag++
                bestLag = lag
                break
            }
            lag++
        }
        if (bestLag < 0) {
            bestLag = (minLag..maxLag).minByOrNull { difference[it] } ?: return null
            if (difference[bestLag] > 0.30) return null
        }

        val refinedLag = parabolicInterpolation(difference, bestLag)
        if (!refinedLag.isFinite() || refinedLag <= 0.0) return null
        val frequency = sampleRate.toDouble() / refinedLag
        if (frequency !in MIN_FREQUENCY..MAX_FREQUENCY) return null
        return PitchReading(
            frequencyHz = frequency,
            periodicity = (1.0 - difference[bestLag]).coerceIn(0.0, 1.0),
        )
    }

    private fun parabolicInterpolation(values: DoubleArray, index: Int): Double {
        if (index <= 0 || index >= values.lastIndex) return index.toDouble()
        val left = values[index - 1]
        val center = values[index]
        val right = values[index + 1]
        val denominator = 2.0 * (2.0 * center - right - left)
        return if (denominator == 0.0) index.toDouble()
        else index + (right - left) / denominator
    }

    internal class Workspace {
        private var difference = DoubleArray(0)

        fun differenceBuffer(requiredSize: Int): DoubleArray {
            if (difference.size < requiredSize) difference = DoubleArray(requiredSize)
            return difference
        }
    }
}
