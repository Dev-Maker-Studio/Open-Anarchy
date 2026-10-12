package org.darkan.tools.cache.texture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object TexturePixelStats {

    private val seenIds = ConcurrentHashMap.newKeySet<Int>()

    private val generations = AtomicLong(0)
    private val firstGenerations = AtomicLong(0)
    private val repeatGenerations = AtomicLong(0)
    private val hits = AtomicLong(0)
    private val totalNanos = AtomicLong(0)
    private val repeatNanos = AtomicLong(0)
    private val distinctBytes = AtomicLong(0)

    fun record(textureId: Int, nanos: Long, pixels: Int) {
        generations.incrementAndGet()
        totalNanos.addAndGet(nanos)
        if (seenIds.add(textureId)) {
            firstGenerations.incrementAndGet()
            distinctBytes.addAndGet(pixels.toLong() * Int.SIZE_BYTES)
        } else {
            repeatGenerations.incrementAndGet()
            repeatNanos.addAndGet(nanos)
        }
    }

    fun recordHit() {
        hits.incrementAndGet()
    }

    class Snapshot(
        val generations: Long,
        val firstGenerations: Long,
        val repeatGenerations: Long,
        val hits: Long,
        val totalNanos: Long,
        val repeatNanos: Long,
        val distinctBytes: Long,
        val distinctIds: Int
    ) {
        fun since(earlier: Snapshot): Snapshot = Snapshot(
            generations = generations - earlier.generations,
            firstGenerations = firstGenerations - earlier.firstGenerations,
            repeatGenerations = repeatGenerations - earlier.repeatGenerations,
            hits = hits - earlier.hits,
            totalNanos = totalNanos - earlier.totalNanos,
            repeatNanos = repeatNanos - earlier.repeatNanos,
            distinctBytes = distinctBytes - earlier.distinctBytes,
            distinctIds = distinctIds - earlier.distinctIds
        )

        val reuseRatePercent: Double
            get() {
                val requests = generations + hits
                return if (requests > 0) 100.0 * (hits + repeatGenerations) / requests else 0.0
            }

        fun describe(label: String): String =
            ("[TEXGEN] %-22s requests=%d gen=%d hit=%d reuse=%.1f%%  genTime=%.0f ms  " +
                "repeatGenTime=%.0f ms  resident=%.1f MiB")
                .format(
                    label, generations + hits, generations, hits, reuseRatePercent,
                    totalNanos / 1e6, repeatNanos / 1e6,
                    distinctBytes / (1024.0 * 1024.0)
                )
    }

    fun snapshot(): Snapshot = Snapshot(
        generations = generations.get(),
        firstGenerations = firstGenerations.get(),
        repeatGenerations = repeatGenerations.get(),
        hits = hits.get(),
        totalNanos = totalNanos.get(),
        repeatNanos = repeatNanos.get(),
        distinctBytes = distinctBytes.get(),
        distinctIds = seenIds.size
    )
}
