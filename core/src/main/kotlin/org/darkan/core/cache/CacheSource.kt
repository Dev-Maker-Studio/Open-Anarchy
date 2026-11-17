package org.darkan.core.cache

import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import world.gregs.voidps.cache.source.BuildResult
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.PackThreads
import world.gregs.voidps.cache.source.SourceTree
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.cache.source.codec.MapCodec
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

object CacheSource {

    @Volatile
    private var result: BuildResult? = null

    val tree: File? = EnvVars.cacheSourcePath?.let { File(it) }?.let { if (EnvVars.windowed) WindowTree.prepare(it) else it }

    val enabled: Boolean get() = tree != null

    private fun <T> queued(build: () -> T): T {
        if (!EnvVars.generateMaps) return build()
        val lock = File(EnvVars.cachePackLock)
        logInfo("Waiting for the cache pack lock ${lock.path}")
        FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { return build() }
        }
    }

    val lastBuild: BuildResult? get() = result

    @Synchronized
    fun ensureBuilt(): BuildResult? {
        val directory = tree ?: return null
        result?.let { return it }
        check(directory.isDirectory) { "CACHE_SOURCE_PATH '${directory.absolutePath}' is not a directory" }
        if (EnvVars.generateMaps) {
            val generator = GeneratedWorld.generator
            MapCodec.generator = generator
            WorldMapImage.write(directory, generator, GeneratedWorld.cities)
            CityLabels.write(directory, GeneratedWorld.cities)
        }
        val output = File(EnvVars.cacheBuildPath)
        logInfo("Building cache from source tree ${directory.absolutePath} into ${output.absolutePath}")
        if (EnvVars.generateMaps) {
            System.setProperty(PackThreads.DURABLE_PROPERTY, "false")
        }
        if (EnvVars.cachePackThreads > 0) {
            System.setProperty(PackThreads.PROPERTY, EnvVars.cachePackThreads.toString())
        }
        val built = queued { Builder.build(SourceTree.open(directory), output) { line -> logInfo("cache build: $line") } }
        logInfo("Cache build ${if (built.unchanged) "up to date" else "done"}: $built")
        result = built
        return built
    }
}
