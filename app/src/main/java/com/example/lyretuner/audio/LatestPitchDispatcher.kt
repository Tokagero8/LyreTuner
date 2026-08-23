package com.example.lyretuner.audio

import com.example.lyretuner.analysis.PitchReading
import java.util.concurrent.Executor

/** A single submitted task drains readings serially and coalesces an executor backlog. */
internal class LatestPitchDispatcher(
    private val executor: Executor,
    private val canDeliver: () -> Boolean,
    private val deliver: (PitchReading?) -> Unit,
    private val onExecutorFailure: (RuntimeException) -> Unit,
) {
    private val lock = Any()
    private var pendingReading: PitchReading? = null
    private var hasPendingReading = false
    private var taskScheduled = false
    private var closed = false
    private val drainTask = Runnable(::drain)

    fun offer(reading: PitchReading?) {
        val shouldSchedule = synchronized(lock) {
            if (closed) return
            pendingReading = reading
            hasPendingReading = true
            if (taskScheduled) {
                false
            } else {
                taskScheduled = true
                true
            }
        }
        if (!shouldSchedule) return

        try {
            executor.execute(drainTask)
        } catch (exception: RuntimeException) {
            synchronized(lock) {
                closed = true
                pendingReading = null
                hasPendingReading = false
                taskScheduled = false
            }
            onExecutorFailure(exception)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            pendingReading = null
            hasPendingReading = false
        }
    }

    private fun drain() {
        while (true) {
            val reading = synchronized(lock) {
                if (closed || !hasPendingReading) {
                    taskScheduled = false
                    return
                }
                hasPendingReading = false
                pendingReading.also { pendingReading = null }
            }
            if (canDeliver()) deliver(reading)
        }
    }
}
