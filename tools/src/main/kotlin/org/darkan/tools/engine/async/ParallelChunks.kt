package org.darkan.tools.engine.async

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

object ParallelChunks {

    val WORKER_COUNT: Int = (System.getProperty("darkan.loadWorkers")?.toIntOrNull()
        ?: Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

    val TEXTURE_WORKER_COUNT: Int = (System.getProperty("darkan.texWorkers")?.toIntOrNull()
        ?: WORKER_COUNT).coerceAtLeast(1)

    const val MIN_ITEMS_PER_CHUNK: Int = 16

    const val CHUNKS_PER_WORKER: Int = 4

    fun chunkBounds(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK
    ): List<IntArray> {
        if (total <= 0) return emptyList()
        if (maxWorkers <= 1) return listOf(intArrayOf(0, total))

        val bySize = (total + minItemsPerChunk - 1) / minItemsPerChunk
        val chunks = bySize.coerceIn(1, maxWorkers * CHUNKS_PER_WORKER)
        if (chunks == 1) return listOf(intArrayOf(0, total))

        val base = total / chunks
        val remainder = total % chunks
        val bounds = ArrayList<IntArray>(chunks)
        var from = 0
        for (w in 0 until chunks) {
            val size = base + (if (w < remainder) 1 else 0)
            if (size == 0) continue
            bounds.add(intArrayOf(from, from + size))
            from += size
        }
        return bounds
    }

    suspend fun <T> mapChunks(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK,
        block: suspend (Int, Int) -> T
    ): List<T> {
        val bounds = chunkBounds(total, maxWorkers, minItemsPerChunk)
        if (bounds.isEmpty()) return emptyList()
        if (bounds.size == 1) return listOf(block(bounds[0][0], bounds[0][1]))
        return coroutineScope {
            bounds.map { range ->
                async(Dispatchers.Default) { block(range[0], range[1]) }
            }.awaitAll()
        }
    }

    suspend fun forEachChunk(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK,
        block: suspend (Int, Int) -> Unit
    ) {
        mapChunks(total, maxWorkers, minItemsPerChunk, block)
    }

    fun forEachIndexBlocking(
        total: Int,
        maxWorkers: Int = WORKER_COUNT,
        minItemsPerChunk: Int = MIN_ITEMS_PER_CHUNK,
        block: (Int) -> Unit
    ) {
        if (total <= 0) return
        val bounds = chunkBounds(total, maxWorkers, minItemsPerChunk)
        if (bounds.size <= 1) {
            for (i in 0 until total) block(i)
            return
        }
        runBlocking {
            bounds.map { range ->
                async(Dispatchers.Default) {
                    for (i in range[0] until range[1]) block(i)
                }
            }.awaitAll()
        }
    }
}
