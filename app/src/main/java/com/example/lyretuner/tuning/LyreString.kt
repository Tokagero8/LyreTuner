package com.example.lyretuner.tuning

data class LyreString(
    val midi: Int,
    val note: String,
    val octave: Int,
    val frequencyHz: Double,
) {
    val name: String = "$note$octave"
}
