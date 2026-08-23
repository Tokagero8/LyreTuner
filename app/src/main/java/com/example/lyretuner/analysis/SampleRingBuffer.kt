package com.example.lyretuner.analysis

internal class SampleRingBuffer(private val capacity: Int) {
    init {
        require(capacity > 0) { "Capacity must be positive" }
    }

    private val values = FloatArray(capacity)
    private var writeIndex = 0
    private var sampleCount = 0

    val isFull: Boolean
        get() = sampleCount == capacity

    fun append(samples: FloatArray, length: Int = samples.size) {
        require(length in 0..samples.size) { "Length must fit the source array" }
        for (index in 0 until length) {
            values[writeIndex] = samples[index]
            writeIndex = (writeIndex + 1) % capacity
        }
        sampleCount = (sampleCount + length).coerceAtMost(capacity)
    }

    fun copyTo(destination: FloatArray) {
        require(isFull) { "The ring buffer is not full" }
        require(destination.size == capacity) { "Destination must match capacity" }
        for (index in destination.indices) {
            destination[index] = values[(writeIndex + index) % capacity]
        }
    }
}
