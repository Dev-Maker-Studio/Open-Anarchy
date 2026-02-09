package org.darkan.tools.engine.async

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe queue for GPU upload tasks.
 *
 * Background threads call [enqueue] to submit work.
 * The main GL thread calls [drain] each frame with a nanosecond budget.
 */
class GpuUploadQueue {
    private val queue = ConcurrentLinkedQueue<GpuUploadTask>()
    private val count = AtomicInteger(0)

    /**
     * Enqueue a GPU upload task from any thread.
     */
    fun enqueue(task: GpuUploadTask) {
        queue.add(task)
        count.incrementAndGet()
    }

    /**
     * Drain tasks on the main GL thread within the given time budget.
     * Returns the number of tasks processed.
     *
     * @param budgetNanos Maximum time to spend processing tasks
     * @param processor Callback invoked for each non-cancelled task (on GL thread)
     * @param onCancelled Optional callback for cancelled tasks (to decrement region counters)
     * @return Number of tasks processed
     */
    fun drain(budgetNanos: Long, processor: (GpuUploadTask) -> Unit, onCancelled: ((GpuUploadTask) -> Unit)? = null): Int {
        val startNanos = System.nanoTime()
        var processed = 0

        while (true) {
            if (System.nanoTime() - startNanos >= budgetNanos) break

            val task = queue.poll() ?: break
            count.decrementAndGet()

            if (task.cancelled) {
                onCancelled?.invoke(task)
                continue
            }

            processor(task)
            processed++
        }

        return processed
    }

    /**
     * Cancel all pending tasks for a given region.
     */
    fun cancelRegion(regionKey: Long) {
        for (task in queue) {
            if (task.regionKey == regionKey) {
                task.cancelled = true
            }
        }
    }

    /**
     * Cancel all pending tasks.
     */
    fun cancelAll() {
        for (task in queue) {
            task.cancelled = true
        }
    }

    /**
     * Number of pending tasks (approximate, for metrics).
     */
    fun pendingCount(): Int = count.get()

    /**
     * Whether the queue is empty.
     */
    fun isEmpty(): Boolean = queue.isEmpty()

    /**
     * Clear all tasks from the queue.
     */
    fun clear() {
        queue.clear()
        count.set(0)
    }
}
