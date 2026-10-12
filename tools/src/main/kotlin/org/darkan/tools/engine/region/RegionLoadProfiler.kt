package org.darkan.tools.engine.region

import com.sun.management.OperatingSystemMXBean
import org.darkan.tools.engine.render.MeshBuffers
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object RegionLoadProfiler {

    const val PHASE_CENTER_TERRAIN = "center:terrain"
    const val PHASE_CENTER_OBJECTS = "center:objectInstances"
    const val PHASE_CENTER_MODELS = "center:modelDecode"
    const val PHASE_CENTER_REGISTER = "center:gpuRegister"
    const val PHASE_CENTER_DISPLACE = "center:displace"
    const val PHASE_TERRAIN = "ctx:terrainMesh"
    const val PHASE_OBJECTS = "ctx:objectInstances"
    const val PHASE_MODELS = "ctx:modelDecode"
    const val PHASE_DISPLACE = "ctx:displace"
    const val PHASE_BATCH = "ctx:batchGeometry"
    const val PHASE_BATCH_MERGE = "ctx:batchMerge"
    const val PHASE_GL_TERRAIN = "gl:terrainUpload"
    const val PHASE_GL_REGISTER = "gl:modelRegister"
    const val PHASE_GL_BATCH = "gl:batchUpload"
    const val PHASE_MERGED_TERRAIN = "gl:mergedTerrain"

    private val PHASE_ORDER = listOf(
        PHASE_CENTER_TERRAIN,
        PHASE_CENTER_OBJECTS,
        PHASE_CENTER_MODELS,
        PHASE_CENTER_REGISTER,
        PHASE_CENTER_DISPLACE,
        PHASE_TERRAIN,
        PHASE_OBJECTS,
        PHASE_MODELS,
        PHASE_DISPLACE,
        PHASE_BATCH,
        PHASE_BATCH_MERGE,
        PHASE_GL_TERRAIN,
        PHASE_GL_REGISTER,
        PHASE_GL_BATCH,
        PHASE_MERGED_TERRAIN
    )

    @Volatile
    var enabled: Boolean = System.getProperty("darkan.profileRegionLoad", "false").toBoolean()

    private val phaseNanos = ConcurrentHashMap<String, AtomicLong>()
    private val phaseCalls = ConcurrentHashMap<String, AtomicInteger>()
    private val threadNames = ConcurrentHashMap.newKeySet<String>()

    private val waveActive = AtomicBoolean(false)
    private val waveStartNanos = AtomicLong(0)
    private val cpuDoneNanos = AtomicLong(0)
    private val expectedRegions = AtomicInteger(0)
    private val cpuDoneRegions = AtomicInteger(0)
    private val objectsProcessed = AtomicInteger(0)
    private val uniqueModelsDecoded = AtomicInteger(0)
    private val modelCacheHits = AtomicInteger(0)

    private val displaceObjects = AtomicLong(0)
    private val displaceFlatSkips = AtomicLong(0)
    private val displaceMeshCalls = AtomicLong(0)
    private val displaceVertices = AtomicLong(0)

    private val displaceZeroMoved = AtomicLong(0)

    private var waveLabel: String = ""

    private val osBean: OperatingSystemMXBean? =
        ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean

    private val waveStartCpuNanos = AtomicLong(0)
    private val cpuDoneCpuNanos = AtomicLong(0)

    private val waveStartPackedBytes = AtomicLong(0)

    private val waveStartPackNanos = AtomicLong(0)

    private fun processCpuNanos(): Long = osBean?.processCpuTime ?: -1L

    fun processCpuTimeNanos(): Long = processCpuNanos()

    fun beginWave(label: String, regions: Int) {
        if (!enabled) return
        phaseNanos.clear()
        phaseCalls.clear()
        threadNames.clear()
        cpuDoneRegions.set(0)
        objectsProcessed.set(0)
        uniqueModelsDecoded.set(0)
        modelCacheHits.set(0)
        displaceObjects.set(0)
        displaceFlatSkips.set(0)
        displaceMeshCalls.set(0)
        displaceVertices.set(0)
        displaceZeroMoved.set(0)
        cpuDoneNanos.set(0)
        cpuDoneCpuNanos.set(0)
        expectedRegions.set(regions)
        waveLabel = label
        waveStartPackedBytes.set(MeshBuffers.packedBytes.get())
        waveStartPackNanos.set(MeshBuffers.packNanos.get())
        waveStartCpuNanos.set(processCpuNanos())
        waveStartNanos.set(System.nanoTime())
        waveActive.set(true)
    }

    fun record(phase: String, nanos: Long) {
        if (!enabled || !waveActive.get()) return
        phaseNanos.computeIfAbsent(phase) { AtomicLong(0) }.addAndGet(nanos)
        phaseCalls.computeIfAbsent(phase) { AtomicInteger(0) }.incrementAndGet()
        threadNames.add(Thread.currentThread().name)
    }

    fun <T> timed(phase: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(phase, System.nanoTime() - start)
        }
    }

    fun noteObjects(count: Int) {
        if (!enabled || !waveActive.get()) return
        objectsProcessed.addAndGet(count)
    }

    fun noteModelDecode(decoded: Int, cacheHits: Int) {
        if (!enabled || !waveActive.get()) return
        uniqueModelsDecoded.addAndGet(decoded)
        modelCacheHits.addAndGet(cacheHits)
    }

    fun noteDisplace(objects: Long, flatSkips: Long, meshCalls: Long, vertices: Long, zeroMoved: Long = 0) {
        if (!enabled || !waveActive.get()) return
        displaceObjects.addAndGet(objects)
        displaceFlatSkips.addAndGet(flatSkips)
        displaceMeshCalls.addAndGet(meshCalls)
        displaceVertices.addAndGet(vertices)
        displaceZeroMoved.addAndGet(zeroMoved)
    }

    fun markRegionCpuDone() {
        if (!enabled || !waveActive.get()) return
        val done = cpuDoneRegions.incrementAndGet()
        if (done >= expectedRegions.get()) {
            if (cpuDoneNanos.compareAndSet(0, System.nanoTime())) {
                cpuDoneCpuNanos.set(processCpuNanos())
            }
        }
    }

    fun noteRegionsReady(ready: Int) {
        if (!enabled || !waveActive.get()) return
        if (ready < expectedRegions.get()) return
        if (!waveActive.compareAndSet(true, false)) return
        printSummary()
    }

    private fun printSummary() {
        val endNanos = System.nanoTime()
        val startNanos = waveStartNanos.get()
        val wallMs = (endNanos - startNanos) / 1e6
        val cpuDone = cpuDoneNanos.get()
        val cpuWallMs = if (cpuDone > 0) (cpuDone - startNanos) / 1e6 else wallMs

        var totalPhaseMs = 0.0
        val sb = StringBuilder()
        sb.append("\n[LOAD-PROF] ==================================================\n")
        sb.append("[LOAD-PROF] wave: $waveLabel  regions=${expectedRegions.get()}\n")
        sb.append("[LOAD-PROF] wall (start -> all regions READY): %.1f ms\n".format(wallMs))
        val sinceJvmStartMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime
        sb.append("[LOAD-PROF] wall (JVM start -> all regions READY): %d ms\n".format(sinceJvmStartMs))
        sb.append("[LOAD-PROF] wall (start -> all CPU phases done): %.1f ms\n".format(cpuWallMs))
        sb.append("[LOAD-PROF] --- phase CPU time (summed over all threads) ---\n")
        for (phase in PHASE_ORDER) {
            val nanos = phaseNanos[phase]?.get() ?: continue
            val calls = phaseCalls[phase]?.get() ?: 0
            val ms = nanos / 1e6
            totalPhaseMs += ms
            sb.append("[LOAD-PROF]   %-24s %9.1f ms  (%d calls)\n".format(phase, ms, calls))
        }
        for ((phase, nanos) in phaseNanos) {
            if (phase in PHASE_ORDER) continue
            val ms = nanos.get() / 1e6
            val calls = phaseCalls[phase]?.get() ?: 0
            totalPhaseMs += ms
            sb.append("[LOAD-PROF]   %-24s %9.1f ms  (%d calls)\n".format(phase, ms, calls))
        }
        sb.append("[LOAD-PROF] --------------------------------------------------\n")
        sb.append("[LOAD-PROF]   %-24s %9.1f ms\n".format("TOTAL cpu", totalPhaseMs))
        sb.append("[LOAD-PROF]   (phase clocks are per-region wall time; regions overlap, so they sum to more than the wave)\n")

        val startCpu = waveStartCpuNanos.get()
        val doneCpu = cpuDoneCpuNanos.get()
        if (startCpu >= 0 && doneCpu > 0 && cpuWallMs > 0) {
            val cpuMs = (doneCpu - startCpu) / 1e6
            sb.append("[LOAD-PROF] process CPU consumed during load: %.1f ms -> %.2f cores busy (of %d)\n"
                .format(cpuMs, cpuMs / cpuWallMs, Runtime.getRuntime().availableProcessors()))
        }
        val uploadedBytes = MeshBuffers.packedBytes.get() - waveStartPackedBytes.get()
        val packMs = (MeshBuffers.packNanos.get() - waveStartPackNanos.get()) / 1e6
        sb.append("[LOAD-PROF] geometry uploaded: %.1f MiB (vertex colours: %s)  pack: %.1f ms\n"
            .format(uploadedBytes / (1024.0 * 1024.0),
                if (MeshBuffers.packColorBytes) "RGBA8" else "float32", packMs))
        sb.append("[LOAD-PROF] worker threads seen: ${threadNames.size}\n")
        sb.append("[LOAD-PROF] objects processed: ${objectsProcessed.get()}  " +
                "unique models decoded: ${uniqueModelsDecoded.get()}  shared-cache hits: ${modelCacheHits.get()}\n")
        val displaceObj = displaceObjects.get()
        if (displaceObj > 0) {
            val skips = displaceFlatSkips.get()
            val zeroMoved = displaceZeroMoved.get()
            sb.append("[LOAD-PROF] displace: objects=%d flat-skipped=%d (%.1f%%)  mesh calls=%d  vertices walked=%d  moved-nothing=%d\n"
                .format(displaceObj, skips, 100.0 * skips / displaceObj,
                    displaceMeshCalls.get(), displaceVertices.get(), zeroMoved))
        }
        sb.append("[LOAD-PROF] ==================================================")
        println(sb)
    }

    fun abortWave() {
        waveActive.set(false)
    }
}
