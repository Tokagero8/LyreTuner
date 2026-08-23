package com.example.lyretuner.ui.tuner

object TunerTestTags {
    const val SCREEN = "tuner_screen"
    const val STATUS = "tuner_status"
    const val TARGET_NOTE = "target_note"
    const val TARGET_OCTAVE = "target_octave"
    const val CENTS = "cents"
    const val FREQUENCY = "frequency"
    const val LISTENING_BUTTON = "listening_button"
    const val PERMISSION_MESSAGE = "permission_message"
    const val AUTO_CHIP = "auto_chip"
    const val STRING_GRID = "string_grid"

    fun string(midi: Int): String = "lyre_string_$midi"
}
