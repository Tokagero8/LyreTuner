package com.example.lyretuner.analysis

import java.util.Arrays
import kotlin.math.sqrt

/**
 * Tracks a low percentile of recent rejected-frame RMS measurements. The percentile
 * ignores isolated attacks and makes upward adaptation intentionally slower than
 * downward adaptation.
 */
internal class AdaptiveNoiseFloor(
    initialNoiseRms: Double = 0.002,
    private val thresholdMultiplier: Double = 4.0,
    private val historySize: Int = 64,
    private val percentile: Double = 0.20,
    private val minimumRms: Double = 0.000_5,
    private val maximumRms: Double = 0.05,
    private val acceptablePeriodicityThreshold: Double = 0.80,
    private val strongPeriodicityThreshold: Double = 0.92,
    private val periodicBypassRmsRatio: Double = 0.75,
) {
    private val history: DoubleArray
    private val sortedHistory: DoubleArray
    private var writeIndex = 0

    var estimatedRms: Double = initialNoiseRms
        private set

    val signalThreshold: Double
        get() = estimatedRms * thresholdMultiplier

    /** Lowest RMS worth inspecting for periodicity, including rejected background frames. */
    val detectorRmsThreshold: Double
        get() = minimumRms

    /** Lowest RMS that the strong-periodicity exception can possibly accept. */
    val strongPeriodicityRmsThreshold: Double
        get() = signalThreshold * periodicBypassRmsRatio

    init {
        require(historySize > 0) { "History size must be positive" }
        require(percentile.isFinite() && percentile in 0.0..1.0) {
            "Percentile must be between 0 and 1"
        }
        require(minimumRms.isFinite() && maximumRms.isFinite() &&
            minimumRms > 0.0 && maximumRms >= minimumRms) {
            "RMS bounds must be finite and ordered"
        }
        require(initialNoiseRms.isFinite() && initialNoiseRms in minimumRms..maximumRms) {
            "Initial RMS must be within the configured bounds"
        }
        require(thresholdMultiplier.isFinite() && thresholdMultiplier > 1.0 &&
            (maximumRms * thresholdMultiplier).isFinite()) {
            "Threshold multiplier must produce a finite threshold"
        }
        require(acceptablePeriodicityThreshold.isFinite() &&
            acceptablePeriodicityThreshold in 0.0..1.0) {
            "Acceptable-periodicity threshold must be between 0 and 1"
        }
        require(strongPeriodicityThreshold.isFinite() &&
            strongPeriodicityThreshold in acceptablePeriodicityThreshold..1.0) {
            "Strong-periodicity threshold must be at least the acceptable threshold"
        }
        require(periodicBypassRmsRatio.isFinite() && periodicBypassRmsRatio > 0.0 &&
            periodicBypassRmsRatio <= 1.0) {
            "Periodic RMS bypass ratio must be greater than 0 and at most 1"
        }

        history = DoubleArray(historySize) { initialNoiseRms }
        sortedHistory = DoubleArray(historySize)
    }

    fun observeRejected(rms: Double) {
        if (!rms.isFinite() || rms < 0.0) return

        history[writeIndex] = rms.coerceIn(minimumRms, maximumRms)
        writeIndex = (writeIndex + 1) % history.size

        history.copyInto(sortedHistory)
        Arrays.sort(sortedHistory)
        val percentileIndex = ((sortedHistory.lastIndex) * percentile).toInt()
        estimatedRms = sortedHistory[percentileIndex]
    }

    fun acceptsPitch(rms: Double, periodicity: Double): Boolean = acceptsPitch(
        rms = rms,
        periodicity = periodicity,
        rmsThreshold = signalThreshold,
        strongPeriodicityRmsThreshold = strongPeriodicityRmsThreshold,
    )

    fun acceptsPitch(
        rms: Double,
        periodicity: Double,
        rmsThreshold: Double,
        strongPeriodicityRmsThreshold: Double,
    ): Boolean {
        if (!rms.isFinite() || rms < 0.0 || !periodicity.isFinite() ||
            periodicity !in 0.0..1.0) return false
        if (periodicity < acceptablePeriodicityThreshold) return false

        if (rms >= rmsThreshold) return true
        return isStronglyPeriodic(periodicity) &&
            rms >= strongPeriodicityRmsThreshold
    }

    fun isStronglyPeriodic(periodicity: Double): Boolean =
        periodicity.isFinite() && periodicity in strongPeriodicityThreshold..1.0
}

internal fun centeredRms(samples: FloatArray): Double {
    if (samples.isEmpty()) return 0.0
    var mean = 0.0
    for (sample in samples) mean += sample
    mean /= samples.size

    var squareSum = 0.0
    for (sample in samples) {
        val centered = sample - mean
        squareSum += centered * centered
    }
    return sqrt(squareSum / samples.size)
}
