package com.example.lyretuner.tuning

import kotlin.math.abs

/** Keeps automatic string identification stable near note boundaries. */
internal class AutomaticStringSelector(
    private val acquisitionDistanceCents: Double = 60.0,
    private val releaseDistanceCents: Double = 80.0,
    private val switchAdvantageCents: Double = 15.0,
    private val outOfRangeReadingsBeforeRelease: Int = 2,
) {
    private var selectedString: LyreString? = null
    private var outOfRangeReadingCount = 0

    init {
        require(acquisitionDistanceCents.isFinite() && acquisitionDistanceCents > 0.0)
        require(releaseDistanceCents.isFinite() &&
            releaseDistanceCents >= acquisitionDistanceCents)
        require(switchAdvantageCents.isFinite() && switchAdvantageCents >= 0.0)
        require(outOfRangeReadingsBeforeRelease > 0)
    }

    @Synchronized
    fun select(frequencyHz: Double?): LyreString? {
        if (frequencyHz == null || !frequencyHz.isFinite() || frequencyHz <= 0.0) {
            selectedString = null
            outOfRangeReadingCount = 0
            return null
        }

        val candidate = LyreTuning.strings.minByOrNull {
            distanceCents(frequencyHz, it)
        } ?: return null
        val candidateDistance = distanceCents(frequencyHz, candidate)
        val current = selectedString

        if (current == null) {
            selectedString = candidate.takeIf {
                candidateDistance.isWithin(acquisitionDistanceCents)
            }
            outOfRangeReadingCount = 0
            return selectedString
        }

        val currentDistance = distanceCents(frequencyHz, current)
        if (candidate == current) {
            updateReleaseState(currentDistance)
            return selectedString
        }

        val candidateIsClearlyBetter = candidateDistance.isWithin(acquisitionDistanceCents) &&
            currentDistance - candidateDistance + CENTS_COMPARISON_EPSILON >=
            switchAdvantageCents
        if (candidateIsClearlyBetter) {
            selectedString = candidate
            outOfRangeReadingCount = 0
        } else {
            updateReleaseState(currentDistance)
        }
        return selectedString
    }

    @Synchronized
    fun reset() {
        selectedString = null
        outOfRangeReadingCount = 0
    }

    private fun updateReleaseState(currentDistanceCents: Double) {
        if (currentDistanceCents.isWithin(releaseDistanceCents)) {
            outOfRangeReadingCount = 0
            return
        }
        outOfRangeReadingCount++
        if (outOfRangeReadingCount >= outOfRangeReadingsBeforeRelease) {
            selectedString = null
            outOfRangeReadingCount = 0
        }
    }

    private fun distanceCents(frequencyHz: Double, string: LyreString): Double =
        abs(LyreTuning.centsBetween(frequencyHz, string.frequencyHz))

    private fun Double.isWithin(limitCents: Double): Boolean =
        this <= limitCents + CENTS_COMPARISON_EPSILON

    private companion object {
        const val CENTS_COMPARISON_EPSILON = 1e-9
    }
}
