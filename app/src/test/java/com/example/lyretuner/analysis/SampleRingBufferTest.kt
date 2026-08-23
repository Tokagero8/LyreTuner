package com.example.lyretuner.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRingBufferTest {
    @Test
    fun startsEmptyAndCannotBeCopiedYet() {
        val ringBuffer = SampleRingBuffer(capacity = 4)

        assertFalse(ringBuffer.isFull)
        assertThrows(IllegalArgumentException::class.java) {
            ringBuffer.copyTo(FloatArray(4))
        }
    }

    @Test
    fun preservesChronologicalOrderWhenFirstFilled() {
        val ringBuffer = SampleRingBuffer(capacity = 4)
        val output = FloatArray(4)

        ringBuffer.append(floatArrayOf(1f, 2f))
        assertFalse(ringBuffer.isFull)

        ringBuffer.append(floatArrayOf(3f, 4f))
        assertTrue(ringBuffer.isFull)
        ringBuffer.copyTo(output)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), output, 0f)
    }

    @Test
    fun preservesChronologicalOrderAfterWrapAround() {
        val ringBuffer = SampleRingBuffer(capacity = 4)
        val output = FloatArray(4)

        ringBuffer.append(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        ringBuffer.copyTo(output)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), output, 0f)
    }

    @Test
    fun appendsMultipleChunksAndUsesOnlyTheRequestedLength() {
        val ringBuffer = SampleRingBuffer(capacity = 5)
        val output = FloatArray(5)

        ringBuffer.append(floatArrayOf(1f, 2f, 99f), length = 2)
        ringBuffer.append(floatArrayOf(3f, 4f, 5f, 6f))
        ringBuffer.copyTo(output)

        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f), output, 0f)
    }

    @Test
    fun rejectsInvalidCapacityLengthsAndDestination() {
        assertThrows(IllegalArgumentException::class.java) { SampleRingBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { SampleRingBuffer(-1) }

        val ringBuffer = SampleRingBuffer(2)
        assertThrows(IllegalArgumentException::class.java) {
            ringBuffer.append(floatArrayOf(1f), length = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ringBuffer.append(floatArrayOf(1f), length = 2)
        }
        ringBuffer.append(floatArrayOf(1f, 2f))
        assertThrows(IllegalArgumentException::class.java) {
            ringBuffer.copyTo(FloatArray(3))
        }
    }
}
