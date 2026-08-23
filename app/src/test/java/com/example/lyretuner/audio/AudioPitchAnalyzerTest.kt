package com.example.lyretuner.audio

import com.example.lyretuner.analysis.PitchReading
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPitchAnalyzerTest {
    @Test
    fun repeatedStartIsRejectedWhileSessionIsActive() {
        val recorder = FakeAudioRecorder()
        val input = FakeAudioInput(recorder)
        val analyzer = analyzer(input)

        assertTrue(analyzer.start { })
        assertFalse(analyzer.start { })

        analyzer.stop()
        assertEquals(1, input.createCount.get())
        assertEquals(1, recorder.startCount.get())
        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun stopDuringPendingStartWaitsForCancellationAndAllowsLaterSession() {
        val createEntered = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val cancelledRecorder = FakeAudioRecorder()
        val laterRecorder = FakeAudioRecorder()
        val input = FakeAudioInput(
            cancelledRecorder,
            laterRecorder,
            createEntered = createEntered,
            allowCreate = allowCreate,
        )
        val analyzer = analyzer(input)
        val startResult = AtomicReference<Boolean>()

        val startThread = Thread {
            startResult.set(analyzer.start { })
        }
        startThread.start()
        assertTrue(createEntered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(analyzer.start { })

        val stopThread = Thread(analyzer::stop, "PendingStartStop")
        stopThread.start()
        assertTrue(awaitThreadBlocked(stopThread))

        allowCreate.countDown()
        startThread.join(TEST_TIMEOUT_MILLIS)
        stopThread.join(TEST_TIMEOUT_MILLIS)

        assertFalse(startResult.get())
        assertFalse(startThread.isAlive)
        assertFalse(stopThread.isAlive)
        assertEquals(0, cancelledRecorder.startCount.get())
        assertEquals(1, cancelledRecorder.releaseCount.get())

        assertTrue(analyzer.start { })
        analyzer.stop()
        assertEquals(1, laterRecorder.startCount.get())
        assertEquals(1, laterRecorder.releaseCount.get())
    }

    @Test
    fun stopIsIdempotentAndReleasesRecorderExactlyOnce() {
        val recorder = FakeAudioRecorder()
        val analyzer = analyzer(FakeAudioInput(recorder))

        assertTrue(analyzer.start { })
        analyzer.stop()
        analyzer.stop()
        analyzer.stop()

        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun stopFromInsideDirectCallbackDoesNotJoinItself() {
        val recorder = FakeAudioRecorder(hopsBeforeBlocking = 4)
        val analyzer = analyzer(FakeAudioInput(recorder))
        val callbackReturned = CountDownLatch(1)

        assertTrue(
            analyzer.start {
                analyzer.stop()
                callbackReturned.countDown()
            },
        )

        assertTrue(callbackReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(recorder.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun concurrentStopWaitsForCallbackThatInitiatedStop() {
        val recorder = FakeAudioRecorder(hopsBeforeBlocking = 4)
        val analyzer = analyzer(FakeAudioInput(recorder))
        val selfStopReturned = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)

        assertTrue(
            analyzer.start {
                analyzer.stop()
                selfStopReturned.countDown()
                allowCallbackReturn.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                callbackReturned.countDown()
            },
        )
        assertTrue(selfStopReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val concurrentStop = Thread(analyzer::stop, "ConcurrentAnalyzerStop")
        concurrentStop.start()
        assertTrue(awaitThreadBlocked(concurrentStop))
        assertTrue(concurrentStop.isAlive)

        allowCallbackReturn.countDown()
        concurrentStop.join(TEST_TIMEOUT_MILLIS)

        assertFalse(concurrentStop.isAlive)
        assertTrue(callbackReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(recorder.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun startIsRejectedUntilCallbackThatInitiatedStopReturns() {
        val firstRecorder = FakeAudioRecorder(hopsBeforeBlocking = 4)
        val secondRecorder = FakeAudioRecorder()
        val input = FakeAudioInput(firstRecorder, secondRecorder)
        val analyzer = analyzer(input)
        val selfStopReturned = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)

        assertTrue(
            analyzer.start {
                analyzer.stop()
                selfStopReturned.countDown()
                allowCallbackReturn.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            },
        )
        assertTrue(selfStopReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertFalse(analyzer.start { })
        assertEquals(1, input.createCount.get())

        allowCallbackReturn.countDown()
        assertTrue(firstRecorder.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun stopWaitsForActiveCallbackOnConfiguredExecutor() {
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val recorder = FakeAudioRecorder(hopsBeforeBlocking = 4)
        val analyzer = analyzer(
            input = FakeAudioInput(recorder),
            callbackExecutor = callbackExecutor,
        )
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)

        try {
            assertTrue(
                analyzer.start {
                    callbackStarted.countDown()
                    releaseCallback.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                },
            )
            assertTrue(callbackStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val stopThread = Thread(analyzer::stop, "ConfiguredExecutorStop")
            stopThread.start()
            assertTrue(awaitThreadBlocked(stopThread))

            releaseCallback.countDown()
            stopThread.join(TEST_TIMEOUT_MILLIS)
            assertFalse(stopThread.isAlive)
            assertEquals(1, recorder.releaseCount.get())
        } finally {
            releaseCallback.countDown()
            analyzer.stop()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun queuedOldSessionCallbackIsDroppedAfterNewSessionStarts() {
        val executor = ManualExecutor()
        val firstRecorder = FakeAudioRecorder(hopsBeforeBlocking = 4)
        val secondRecorder = FakeAudioRecorder(hopsBeforeBlocking = 4)
        val analyzer = analyzer(
            input = FakeAudioInput(firstRecorder, secondRecorder),
            callbackExecutor = executor,
        )
        val firstCallbacks = AtomicInteger()
        val secondCallbacks = AtomicInteger()

        assertTrue(analyzer.start { firstCallbacks.incrementAndGet() })
        val oldTask = executor.take()
        analyzer.stop()

        assertTrue(analyzer.start { secondCallbacks.incrementAndGet() })
        val newTask = executor.take()

        oldTask.run()
        newTask.run()

        assertEquals(0, firstCallbacks.get())
        assertEquals(1, secondCallbacks.get())
        analyzer.stop()
    }

    @Test
    fun callbackDispatcherSerializesAndKeepsLatestPendingReadingIncludingNull() {
        val executor = Executors.newFixedThreadPool(4)
        val firstCallbackStarted = CountDownLatch(1)
        val releaseFirstCallback = CountDownLatch(1)
        val twoCallbacksDelivered = CountDownLatch(2)
        val inCallback = AtomicInteger()
        val maximumConcurrentCallbacks = AtomicInteger()
        val delivered = Collections.synchronizedList(mutableListOf<Double?>())
        val dispatcher = LatestPitchDispatcher(
            executor = executor,
            canDeliver = { true },
            deliver = { reading ->
                val concurrent = inCallback.incrementAndGet()
                maximumConcurrentCallbacks.updateAndGet { maxOf(it, concurrent) }
                val frequency = reading?.frequencyHz
                delivered += frequency
                if (frequency == 1.0) {
                    firstCallbackStarted.countDown()
                    releaseFirstCallback.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                inCallback.decrementAndGet()
                twoCallbacksDelivered.countDown()
            },
            onExecutorFailure = { throw AssertionError(it) },
        )

        try {
            dispatcher.offer(PitchReading(1.0, 0.9))
            assertTrue(firstCallbackStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            dispatcher.offer(PitchReading(2.0, 0.9))
            dispatcher.offer(null)
            releaseFirstCallback.countDown()

            assertTrue(twoCallbacksDelivered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(1.0, null), delivered.toList())
            assertEquals(1, maximumConcurrentCallbacks.get())
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun executorRejectionOrFailureClosesDispatcherAndReportsOnce() {
        listOf(
            RejectedExecutionException("rejected"),
            IllegalStateException("failed"),
        ).forEach { failure ->
            val failures = AtomicInteger()
            val deliveries = AtomicInteger()
            val dispatcher = LatestPitchDispatcher(
                executor = Executor { throw failure },
                canDeliver = { true },
                deliver = { deliveries.incrementAndGet() },
                onExecutorFailure = { failures.incrementAndGet() },
            )

            dispatcher.offer(PitchReading(440.0, 0.9))
            dispatcher.offer(PitchReading(441.0, 0.9))

            assertEquals(0, deliveries.get())
            assertEquals(1, failures.get())
        }
    }

    @Test
    fun uninitializedRecordersFailStartAndAreReleased() {
        val unprocessed = FakeAudioRecorder(initialized = false)
        val microphone = FakeAudioRecorder(initialized = false)
        val analyzer = analyzer(FakeAudioInput(unprocessed, microphone))

        assertFalse(analyzer.start { })

        listOf(unprocessed, microphone).forEach { recorder ->
            assertEquals(0, recorder.startCount.get())
            assertEquals(0, recorder.stopCount.get())
            assertEquals(1, recorder.releaseCount.get())
        }
    }

    @Test
    fun recorderStartFailuresTryFallbackAndReleaseBothRecorders() {
        val unprocessed = FakeAudioRecorder(failStart = true)
        val microphone = FakeAudioRecorder(failStart = true)
        val analyzer = analyzer(FakeAudioInput(unprocessed, microphone))

        assertFalse(analyzer.start { })

        listOf(unprocessed, microphone).forEach { recorder ->
            assertEquals(1, recorder.startCount.get())
            assertEquals(1, recorder.stopCount.get())
            assertEquals(1, recorder.releaseCount.get())
        }
    }

    @Test
    fun partialReadsAreCombinedIntoCompleteAnalysisHops() {
        val recorder = FakeAudioRecorder(
            hopsBeforeBlocking = 16,
            maximumReadSize = AudioPitchAnalyzer.HOP_SIZE / 4,
        )
        val analyzer = analyzer(FakeAudioInput(recorder))
        val callbackDelivered = CountDownLatch(1)

        assertTrue(analyzer.start { callbackDelivered.countDown() })
        assertTrue(callbackDelivered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        analyzer.stop()

        assertTrue(recorder.readCount.get() >= 16)
        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun repeatedEmptyReadsStopTheWorkerAndReleaseTheRecorder() {
        val recorder = FakeAudioRecorder(scriptedReadResults = intArrayOf(0, 0, 0))
        val analyzer = analyzer(FakeAudioInput(recorder))
        val callbacks = AtomicInteger()

        assertTrue(analyzer.start { callbacks.incrementAndGet() })
        assertTrue(recorder.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertEquals(0, callbacks.get())
        assertEquals(3, recorder.readCount.get())
        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun audioRecordErrorCodesStopTheWorkerAndReleaseTheRecorder() {
        listOf(ERROR_BAD_VALUE, ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT, UNKNOWN_READ_ERROR)
            .forEach { errorCode ->
                val recorder = FakeAudioRecorder(
                    scriptedReadResults = intArrayOf(errorCode),
                )
                val analyzer = analyzer(FakeAudioInput(recorder))
                val callbacks = AtomicInteger()

                assertTrue("Could not start for read error $errorCode", analyzer.start {
                    callbacks.incrementAndGet()
                })
                assertTrue(
                    "Recorder was not released for read error $errorCode",
                    recorder.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )

                assertEquals(0, callbacks.get())
                assertEquals(1, recorder.stopCount.get())
                assertEquals(1, recorder.releaseCount.get())
            }
    }

    @Test
    fun readExceptionStopsTheWorkerAndReleasesTheRecorder() {
        val recorder = FakeAudioRecorder(
            readFailure = IllegalStateException("read failed"),
        )
        val analyzer = analyzer(FakeAudioInput(recorder))
        val callbacks = AtomicInteger()

        assertTrue(analyzer.start { callbacks.incrementAndGet() })
        assertTrue(recorder.released.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertEquals(0, callbacks.get())
        assertEquals(1, recorder.readCount.get())
        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun callbackExceptionDoesNotPreventSessionCleanup() {
        val recorder = FakeAudioRecorder(hopsBeforeBlocking = 4)
        val analyzer = analyzer(FakeAudioInput(recorder))
        val callbackEntered = CountDownLatch(1)

        assertTrue(
            analyzer.start {
                callbackEntered.countDown()
                throw IllegalStateException("callback failed")
            },
        )
        assertTrue(callbackEntered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        analyzer.stop()

        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun workerCreationFailureReleasesTheStartedRecorder() {
        val recorder = FakeAudioRecorder()
        val analyzer = analyzer(
            input = FakeAudioInput(recorder),
            threadFactory = AnalyzerThreadFactory {
                throw IllegalStateException("worker creation failed")
            },
        )

        assertFalse(analyzer.start { })
        assertEquals(1, recorder.startCount.get())
        assertEquals(1, recorder.stopCount.get())
        assertEquals(1, recorder.releaseCount.get())
    }

    private fun analyzer(
        input: AudioInput,
        callbackExecutor: Executor = Executor(Runnable::run),
        threadFactory: AnalyzerThreadFactory = AnalyzerThreadFactory { runnable ->
            Thread(runnable, "AudioPitchAnalyzerTest")
        },
    ): AudioPitchAnalyzer = AudioPitchAnalyzer(
        callbackExecutor = callbackExecutor,
        audioInput = input,
        threadFactory = threadFactory,
        logger = AnalyzerLogger { _, _ -> },
    )

    private fun awaitThreadBlocked(thread: Thread): Boolean {
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(TEST_TIMEOUT_SECONDS)
        while (thread.isAlive && System.nanoTime() < deadline) {
            if (thread.state.isBlocked) return true
            Thread.yield()
        }
        return thread.state.isBlocked
    }

    private val Thread.State.isBlocked: Boolean
        get() = this == Thread.State.BLOCKED ||
            this == Thread.State.WAITING ||
            this == Thread.State.TIMED_WAITING

    private class ManualExecutor : Executor {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun take(): Runnable = requireNotNull(
            tasks.poll(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    private class FakeAudioInput(
        vararg recorders: FakeAudioRecorder,
        private val createEntered: CountDownLatch? = null,
        private val allowCreate: CountDownLatch? = null,
    ) : AudioInput {
        private val recorders = ArrayDeque(recorders.toList())
        val createCount = AtomicInteger()

        override fun minimumBufferSize(): Int = 1_024

        override fun create(audioSource: Int, bufferSizeBytes: Int): AudioRecorder {
            createCount.incrementAndGet()
            createEntered?.countDown()
            allowCreate?.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return synchronized(recorders) { recorders.removeFirst() }
        }
    }

    private class FakeAudioRecorder(
        private val initialized: Boolean = true,
        private val failStart: Boolean = false,
        hopsBeforeBlocking: Int = 0,
        private val maximumReadSize: Int = Int.MAX_VALUE,
        scriptedReadResults: IntArray = intArrayOf(),
        private val readFailure: RuntimeException? = null,
    ) : AudioRecorder {
        private val readLock = Any()
        private val stoppedOrReleased = CountDownLatch(1)
        private val scriptedReads = ArrayDeque(scriptedReadResults.toList())
        private var remainingHops = hopsBeforeBlocking
        private var sampleIndex = 0L

        @Volatile
        private var recording = false

        @Volatile
        private var wasReleased = false

        val startCount = AtomicInteger()
        val readCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        val released = CountDownLatch(1)

        init {
            require(maximumReadSize > 0)
        }

        override val isInitialized: Boolean
            get() = initialized

        override val isRecording: Boolean
            get() = recording

        override fun startRecording() {
            startCount.incrementAndGet()
            if (failStart) throw IllegalStateException("start failed")
            recording = true
        }

        override fun read(destination: ShortArray, offset: Int, size: Int): Int {
            readCount.incrementAndGet()
            readFailure?.let { throw it }

            val scriptedResult = synchronized(readLock) {
                if (scriptedReads.isEmpty()) null else scriptedReads.removeFirst()
            }
            if (scriptedResult != null) {
                if (scriptedResult <= 0) return scriptedResult
                return writeSamples(destination, offset, minOf(size, scriptedResult))
            }

            val shouldBlock = synchronized(readLock) {
                if (remainingHops == 0) true else {
                    remainingHops--
                    false
                }
            }
            if (shouldBlock) stoppedOrReleased.await()
            if (!recording || wasReleased) return ERROR_INVALID_OPERATION

            return writeSamples(destination, offset, minOf(size, maximumReadSize))
        }

        private fun writeSamples(destination: ShortArray, offset: Int, size: Int): Int {
            for (index in 0 until size) {
                destination[offset + index] = (
                    sin(2.0 * PI * 440.0 * sampleIndex++ / AudioPitchAnalyzer.SAMPLE_RATE) *
                        Short.MAX_VALUE * 0.2
                    ).toInt().toShort()
            }
            return size
        }

        override fun stop() {
            stopCount.incrementAndGet()
            recording = false
            stoppedOrReleased.countDown()
        }

        override fun release() {
            releaseCount.incrementAndGet()
            wasReleased = true
            stoppedOrReleased.countDown()
            released.countDown()
        }
    }

    private companion object {
        const val ERROR_BAD_VALUE = -2
        const val ERROR_INVALID_OPERATION = -3
        const val ERROR_DEAD_OBJECT = -6
        const val UNKNOWN_READ_ERROR = -99
        const val TEST_TIMEOUT_SECONDS = 3L
        const val TEST_TIMEOUT_MILLIS = 3_000L
    }
}
