package org.darkan.tools.engine.async

import org.darkan.tools.engine.region.RegionLoadProfiler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class GpuUploadQueue {
    private companion object {
        const val DRAIN_WALL = "drain:wall"
        const val DRAIN_WORK = "drain:work"
        const val DRAIN_STARVED = "drain:starved"
        const val DRAIN_EMPTY = "drain:empty"
    }

    private val queue = ConcurrentLinkedQueue<GpuUploadTask>()
    private val count = AtomicInteger(0)

    fun enqueue(task: GpuUploadTask) {
        queue.add(task)
        count.incrementAndGet()
    }

    fun drain(budgetNanos: Long, processor: (GpuUploadTask) -> Unit, onCancelled: ((GpuUploadTask) -> Unit)? = null): Int {
        val startNanos = System.nanoTime()
        var processed = 0
        var workNanos = 0L
        var starved = false
        var now = startNanos

        while (now - startNanos < budgetNanos) {
            val task = queue.poll()
            if (task == null) {
                starved = true
                break
            }
            count.decrementAndGet()

            if (task.cancelled) {
                onCancelled?.invoke(task)
                now = System.nanoTime()
                continue
            }

            val taskStart = System.nanoTime()
            processor(task)
            now = System.nanoTime()
            workNanos += now - taskStart
            processed++
        }

        if (RegionLoadProfiler.enabled) {
            if (processed > 0) {
                RegionLoadProfiler.record(DRAIN_WALL, System.nanoTime() - startNanos)
                RegionLoadProfiler.record(DRAIN_WORK, workNanos)
                if (starved) RegionLoadProfiler.record(DRAIN_STARVED, 0L)
            } else {
                RegionLoadProfiler.record(DRAIN_EMPTY, 0L)
            }
        }

        return processed
    }

    fun cancelRegion(regionKey: Long) {
        for (task in queue) {
            if (task.regionKey == regionKey) {
                task.cancelled = true
            }
        }
    }

    fun cancelAll() {
        for (task in queue) {
            task.cancelled = true
        }
    }

    fun pendingCount(): Int = count.get()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() {
        queue.clear()
        count.set(0)
    }
}
