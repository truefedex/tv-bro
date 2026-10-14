package com.phlox.tvwebbrowser.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerialWriteQueueTest {
    @Test
    fun writesRunOneByOneInEnqueueOrder() = runTest {
        val queue = SerialWriteQueue(backgroundScope)
        val log = mutableListOf<String>()
        queue.enqueue { log += "a start"; delay(100); log += "a end" }
        queue.enqueue { log += "b start"; delay(10); log += "b end" }
        queue.enqueue { log += "c" }
        queue.awaitPending()
        assertEquals(listOf("a start", "a end", "b start", "b end", "c"), log)
    }

    @Test
    fun awaitPendingWaitsForAllWrites() = runTest {
        val queue = SerialWriteQueue(backgroundScope)
        var done = 0
        repeat(3) { queue.enqueue { delay(100); done++ } }
        queue.awaitPending()
        assertEquals(3, done)
    }

    @Test
    fun awaitPendingWaitsForWritesEnqueuedWhileWaiting() = runTest {
        val queue = SerialWriteQueue(backgroundScope)
        var secondDone = false
        queue.enqueue {
            delay(100)
            queue.enqueue { delay(100); secondDone = true }
        }
        queue.awaitPending()
        assertTrue(secondDone)
    }

    @Test
    fun awaitPendingReturnsImmediatelyWithoutWrites() = runTest {
        SerialWriteQueue(backgroundScope).awaitPending()
    }

    @Test
    fun writesAreNotCancelledWithTheCallerScope() = runTest {
        val queue = SerialWriteQueue(backgroundScope)
        val callerScope = CoroutineScope(coroutineContext + Job())
        var done = false
        callerScope.launch { queue.enqueue { delay(100); done = true } }
        runCurrent()
        callerScope.cancel()
        queue.awaitPending()
        assertTrue(done)
    }

    @Test
    fun returnedJobCompletesWithTheWrite() = runTest {
        val queue = SerialWriteQueue(backgroundScope)
        val job = queue.enqueue { delay(100) }
        assertFalse(job.isCompleted)
        job.join()
        assertTrue(job.isCompleted)
    }
}
