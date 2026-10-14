package com.phlox.tvwebbrowser.utils

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs write operations one by one, in the order they were enqueued, in a [scope]
 * that outlives activities and models (so the writes are not cancelled when they are destroyed).
 */
class SerialWriteQueue(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val pendingJobs = LinkedHashSet<Job>()

    /**
     * Should be called from the main thread: the order of writes is the order of calls.
     */
    @MainThread
    fun enqueue(write: suspend () -> Unit): Job {
        val job = scope.launch {
            mutex.withLock { write() }
        }
        if (!job.isCompleted) {
            pendingJobs.add(job)
            job.invokeOnCompletion { scope.launch { pendingJobs.remove(job) } }
        }
        return job
    }

    /**
     * Waits until all writes enqueued before and during this call are done.
     */
    @MainThread
    suspend fun awaitPending() {
        while (pendingJobs.isNotEmpty()) {
            pendingJobs.toList().joinAll()
            pendingJobs.removeAll { it.isCompleted }
        }
    }
}
