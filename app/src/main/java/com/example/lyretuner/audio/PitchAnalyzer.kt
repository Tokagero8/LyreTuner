package com.example.lyretuner.audio

import com.example.lyretuner.analysis.PitchReading

/**
 * Thread-safe lifecycle for a microphone pitch-analysis session. [start] and [stop] may be
 * called concurrently from different threads.
 *
 * Callbacks execute in the context chosen by the implementation. They must return promptly
 * and must not block: callback delivery is serialized, and [stop] may wait for an active
 * callback to finish.
 */
interface PitchAnalyzer {
    /**
     * Starts a new analysis session and returns `true` only when that session was started.
     * Concurrent or repeated starts are rejected while a start, session, or stop is active.
     *
     * [onPitch] receives the latest usable pitch, or `null` when the current analysis has no
     * stable pitch (for example, silence, noise, or an uncertain signal). `null` is an analysis
     * result; it does not mean that the session stopped or failed.
     */
    fun start(onPitch: (PitchReading?) -> Unit): Boolean

    /**
     * Stops the current or pending session. This operation is idempotent and normally returns
     * only after capture has stopped and an active callback has finished, after which that
     * session will deliver no more callbacks. When called by the callback itself, it does not
     * wait for that callback to return; the analyzer remains stopping until the callback and
     * its session finish, so other stops wait and new starts are rejected in the meantime.
     */
    fun stop()
}
