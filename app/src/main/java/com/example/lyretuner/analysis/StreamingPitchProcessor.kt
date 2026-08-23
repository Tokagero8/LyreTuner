package com.example.lyretuner.analysis

/** Stateful streaming pipeline from fixed-size PCM hops to stabilized pitch updates. */
internal class StreamingPitchProcessor(
    private val stabilizer: PitchStabilizer = PitchStabilizer(),
    private val noiseFloor: AdaptiveNoiseFloor = AdaptiveNoiseFloor(),
    private val analysisIntervalHops: Int = PitchAnalysisDefaults.ANALYSIS_INTERVAL_HOPS,
) {
    init {
        require(analysisIntervalHops > 0) { "ANALYSIS_INTERVAL_HOPS must be positive" }
    }

    private val analysisWindow = FloatArray(PitchAnalysisDefaults.WINDOW_SIZE)
    private val ringBuffer = SampleRingBuffer(PitchAnalysisDefaults.WINDOW_SIZE)
    private val detectorWorkspace = PitchDetector.Workspace()
    private var hopsUntilAnalysis = 0

    fun append(samples: FloatArray): PitchAnalysisResult? {
        require(samples.size == PitchAnalysisDefaults.HOP_SIZE) {
            "Expected ${PitchAnalysisDefaults.HOP_SIZE} samples, found ${samples.size}"
        }
        ringBuffer.append(samples)
        if (!ringBuffer.isFull) return null

        if (hopsUntilAnalysis > 0) {
            hopsUntilAnalysis--
            return null
        }
        hopsUntilAnalysis = analysisIntervalHops - 1

        ringBuffer.copyTo(analysisWindow)
        val rms = centeredRms(analysisWindow)

        // Every decision and diagnostic for this frame uses the pre-observation floor.
        val estimatedNoiseRms = noiseFloor.estimatedRms
        val detectorRmsThreshold = noiseFloor.detectorRmsThreshold
        val rmsThreshold = noiseFloor.signalThreshold
        val strongPeriodicityRmsThreshold = noiseFloor.strongPeriodicityRmsThreshold

        val rawReading = if (rms >= detectorRmsThreshold) {
            PitchDetector.detect(
                samples = analysisWindow,
                sampleRate = PitchAnalysisDefaults.SAMPLE_RATE,
                minimumRms = 0.0,
                workspace = detectorWorkspace,
            )
        } else {
            null
        }
        val gatedReading = rawReading?.takeIf {
            noiseFloor.acceptsPitch(
                rms = rms,
                periodicity = it.periodicity,
                rmsThreshold = rmsThreshold,
                strongPeriodicityRmsThreshold = strongPeriodicityRmsThreshold,
            )
        }

        if (gatedReading == null) {
            val isStronglyPeriodic = rawReading?.let {
                noiseFloor.isStronglyPeriodic(it.periodicity)
            } == true
            // Periodic hum can age the history and lower the floor, but cannot raise it.
            val rejectedRms = if (isStronglyPeriodic) {
                minOf(rms, estimatedNoiseRms)
            } else rms
            noiseFloor.observeRejected(rejectedRms)
        }

        return PitchAnalysisResult(
            rms = rms,
            rmsThreshold = rmsThreshold,
            rawReading = rawReading,
            gatedReading = gatedReading,
            stableReading = stabilizer.stabilize(gatedReading),
        )
    }
}

internal data class PitchAnalysisResult(
    val rms: Double,
    val rmsThreshold: Double,
    val rawReading: PitchReading?,
    val gatedReading: PitchReading?,
    val stableReading: PitchReading?,
)
