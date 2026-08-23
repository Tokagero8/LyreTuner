package com.example.lyretuner.tuning

import kotlin.math.abs
import kotlin.math.ln

object LyreTuning {
    /** The 24 diatonic strings from D3 through F6, with A4 = 440 Hz. */
    val strings: List<LyreString> = listOf(
        LyreString(midi = 50, note = "D", octave = 3, frequencyHz = 146.83),
        LyreString(midi = 52, note = "E", octave = 3, frequencyHz = 164.81),
        LyreString(midi = 53, note = "F", octave = 3, frequencyHz = 174.61),
        LyreString(midi = 55, note = "G", octave = 3, frequencyHz = 196.00),
        LyreString(midi = 57, note = "A", octave = 3, frequencyHz = 220.00),
        LyreString(midi = 59, note = "B", octave = 3, frequencyHz = 246.94),
        LyreString(midi = 60, note = "C", octave = 4, frequencyHz = 261.63),
        LyreString(midi = 62, note = "D", octave = 4, frequencyHz = 293.66),
        LyreString(midi = 64, note = "E", octave = 4, frequencyHz = 329.63),
        LyreString(midi = 65, note = "F", octave = 4, frequencyHz = 349.23),
        LyreString(midi = 67, note = "G", octave = 4, frequencyHz = 392.00),
        LyreString(midi = 69, note = "A", octave = 4, frequencyHz = 440.00),
        LyreString(midi = 71, note = "B", octave = 4, frequencyHz = 493.88),
        LyreString(midi = 72, note = "C", octave = 5, frequencyHz = 523.25),
        LyreString(midi = 74, note = "D", octave = 5, frequencyHz = 587.33),
        LyreString(midi = 76, note = "E", octave = 5, frequencyHz = 659.26),
        LyreString(midi = 77, note = "F", octave = 5, frequencyHz = 698.46),
        LyreString(midi = 79, note = "G", octave = 5, frequencyHz = 783.99),
        LyreString(midi = 81, note = "A", octave = 5, frequencyHz = 880.00),
        LyreString(midi = 83, note = "B", octave = 5, frequencyHz = 987.77),
        LyreString(midi = 84, note = "C", octave = 6, frequencyHz = 1_046.50),
        LyreString(midi = 86, note = "D", octave = 6, frequencyHz = 1_174.66),
        LyreString(midi = 88, note = "E", octave = 6, frequencyHz = 1_318.51),
        LyreString(midi = 89, note = "F", octave = 6, frequencyHz = 1_396.91),
    )

    fun nearestString(frequencyHz: Double): LyreString? {
        if (!frequencyHz.isFinite() || frequencyHz <= 0.0) return null
        val nearest = strings.minByOrNull { abs(centsBetween(frequencyHz, it.frequencyHz)) }
            ?: return null
        return nearest.takeIf {
            abs(centsBetween(frequencyHz, it.frequencyHz)) <= MAX_STRING_DISTANCE_CENTS
        }
    }

    fun centsBetween(frequencyHz: Double, targetHz: Double): Double =
        1_200.0 * ln(frequencyHz / targetHz) / ln(2.0)

    private const val MAX_STRING_DISTANCE_CENTS = 60.0
}
