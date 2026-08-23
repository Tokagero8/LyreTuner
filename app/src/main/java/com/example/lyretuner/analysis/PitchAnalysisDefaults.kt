package com.example.lyretuner.analysis

/** Shared immutable configuration for streaming pitch analysis. */
internal object PitchAnalysisDefaults {
    const val SAMPLE_RATE = 44_100
    const val WINDOW_SIZE = 4_096
    const val HOP_SIZE = 1_024
    const val ANALYSIS_INTERVAL_HOPS = 2
}
