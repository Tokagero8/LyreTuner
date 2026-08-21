package com.example.lyretuner

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Stabilizes YIN readings using elapsed monotonic time rather than frame counts. */
internal class PitchStabilizer(
    private val config: Config = Config(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var stableReading: PitchReading? = null
    private var stableTimestampNanos: Long? = null
    private var candidateReading: PitchReading? = null
    private var candidateTimestampNanos: Long? = null
    private var candidateEvidenceMillis = 0.0
    private var missingSinceNanos: Long? = null

    init {
        require(config.minimumPeriodicity.isFinite() && config.minimumPeriodicity in 0.0..1.0)
        require(config.samePitchToleranceCents.isFinite() && config.samePitchToleranceCents > 0.0)
        require(config.normalTransitionMillis.isFinite() && config.normalTransitionMillis > 0.0)
        require(config.octaveTransitionMillis.isFinite() &&
            config.octaveTransitionMillis >= config.normalTransitionMillis)
        require(config.octaveToleranceCents.isFinite() && config.octaveToleranceCents > 0.0)
        require(config.missingClearMillis.isFinite() && config.missingClearMillis > 0.0)
        require(config.smoothingTimeConstantMillis.isFinite() &&
            config.smoothingTimeConstantMillis > 0.0)
        require(config.maximumObservationGapMillis.isFinite() &&
            config.maximumObservationGapMillis > 0.0)
    }

    fun stabilize(reading: PitchReading?): PitchReading? = stabilize(reading, nanoTime())

    internal fun stabilize(reading: PitchReading?, timestampNanos: Long): PitchReading? {
        if (reading == null || !reading.isUsable()) return handleMissing(timestampNanos)

        val resumedAfterMissing = missingSinceNanos != null
        missingSinceNanos = null
        val stable = stableReading
        if (stable != null && isSamePitch(reading, stable)) {
            clearCandidate()
            val elapsedMillis = if (resumedAfterMissing) {
                0.0
            } else {
                elapsedMillis(stableTimestampNanos, timestampNanos)
                    .coerceAtMost(config.maximumObservationGapMillis)
            }
            return smooth(stable, reading, elapsedMillis).also {
                stableReading = it
                stableTimestampNanos = timestampNanos
            }
        }

        val candidate = candidateReading
        if (candidate == null || !isSamePitch(reading, candidate)) {
            beginCandidate(reading, timestampNanos)
            return stable
        }

        val elapsedMillis = elapsedMillis(candidateTimestampNanos, timestampNanos)
        if (elapsedMillis > config.maximumObservationGapMillis) {
            beginCandidate(reading, timestampNanos)
            return stable
        }

        candidateReading = smooth(candidate, reading, elapsedMillis)
        candidateTimestampNanos = timestampNanos
        candidateEvidenceMillis += elapsedMillis * reading.periodicity

        val requiredEvidenceMillis = if (stable != null && isOctaveLike(candidateReading!!, stable)) {
            config.octaveTransitionMillis
        } else {
            config.normalTransitionMillis
        }
        if (candidateEvidenceMillis < requiredEvidenceMillis) return stable

        val confirmed = requireNotNull(candidateReading)
        stableReading = confirmed
        stableTimestampNanos = timestampNanos
        clearCandidate()
        return confirmed
    }

    private fun handleMissing(timestampNanos: Long): PitchReading? {
        clearCandidate()
        val stable = stableReading ?: return null
        val missingSince = missingSinceNanos
        if (missingSince == null) {
            missingSinceNanos = timestampNanos
            return stable
        }
        if (elapsedMillis(missingSince, timestampNanos) >= config.missingClearMillis) {
            stableReading = null
            stableTimestampNanos = null
            missingSinceNanos = null
        }
        return stableReading
    }

    private fun beginCandidate(reading: PitchReading, timestampNanos: Long) {
        candidateReading = reading
        candidateTimestampNanos = timestampNanos
        candidateEvidenceMillis = 0.0
    }

    private fun isSamePitch(first: PitchReading, second: PitchReading): Boolean =
        abs(LyreTuning.centsBetween(first.frequencyHz, second.frequencyHz)) <=
            config.samePitchToleranceCents

    private fun isOctaveLike(first: PitchReading, second: PitchReading): Boolean {
        val distanceCents = abs(LyreTuning.centsBetween(first.frequencyHz, second.frequencyHz))
        return abs(distanceCents - OCTAVE_CENTS) <= config.octaveToleranceCents
    }

    private fun PitchReading.isUsable(): Boolean =
        frequencyHz.isFinite() && frequencyHz > 0.0 &&
            periodicity.isFinite() && periodicity in config.minimumPeriodicity..1.0

    private fun smooth(
        previous: PitchReading,
        current: PitchReading,
        elapsedMillis: Double,
    ): PitchReading {
        if (elapsedMillis <= 0.0) return previous
        val timeBasedFactor = 1.0 - exp(-elapsedMillis / config.smoothingTimeConstantMillis)
        val smoothingFactor = (timeBasedFactor * current.periodicity).coerceIn(0.0, 1.0)
        val logFrequency = ln(previous.frequencyHz) * (1.0 - smoothingFactor) +
            ln(current.frequencyHz) * smoothingFactor
        return PitchReading(
            frequencyHz = exp(logFrequency),
            periodicity = previous.periodicity * (1.0 - smoothingFactor) +
                current.periodicity * smoothingFactor,
        )
    }

    private fun elapsedMillis(previousNanos: Long?, currentNanos: Long): Double {
        if (previousNanos == null || currentNanos <= previousNanos) return 0.0
        return (currentNanos - previousNanos) / NANOS_PER_MILLISECOND
    }

    private fun clearCandidate() {
        candidateReading = null
        candidateTimestampNanos = null
        candidateEvidenceMillis = 0.0
    }

    internal data class Config(
        val minimumPeriodicity: Double = 0.70,
        val samePitchToleranceCents: Double = 50.0,
        val normalTransitionMillis: Double = 40.0,
        val octaveTransitionMillis: Double = 120.0,
        val octaveToleranceCents: Double = 60.0,
        val missingClearMillis: Double = 90.0,
        val smoothingTimeConstantMillis: Double = 110.0,
        val maximumObservationGapMillis: Double = 250.0,
    )

    private companion object {
        const val OCTAVE_CENTS = 1_200.0
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
