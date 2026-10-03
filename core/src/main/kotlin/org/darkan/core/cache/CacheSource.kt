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

/**
 * The server's switch between a prepacked cache and a cache source tree (`docs/cache-source.md`).
 *
 * With `CACHE_SOURCE_PATH` unset nothing happens and [EnvVars.cachePath] is the prepacked cache.
 * With it set, [ensureBuilt] builds the tree into `CACHE_BUILD_PATH` - a full pack the first
 * time, afterwards only the archives whose inputs changed, a stat walk when nothing did - and
 * [EnvVars.cachePath] is that build directory. `Cache.get()` calls [ensureBuilt] before it opens
 * anything, so no caller has to remember to; calling it early (a server's `main`) only moves the
 * log lines to where they are expected.
 */
object CacheSource {

    @Volatile
    private var result: BuildResult? = null

    /** The tree the server serves from, or null when it serves a prepacked cache. */
    val tree: File? = EnvVars.cacheSourcePath?.let { File(it) }?.let { if (EnvVars.windowed) WindowTree.prepare(it) else it }

    /** Whether the server is configured to build from a source tree. */
    val enabled: Boolean get() = tree != null

    private fun <T> queued(build: () -> T): T {
        if (!EnvVars.generateMaps) return build()
        val lock = File(EnvVars.cachePackLock)
        logInfo("Waiting for the cache pack lock ${lock.path}")
        FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { return build() }
        }
    }

    /** What the last [ensureBuilt] did, once it has run. */
    val lastBuild: BuildResult? get() = result

    /**
     * Builds the configured tree into [EnvVars.cacheBuildPath] if it has not been built by this
     * process yet. Idempotent and thread-safe; a no-op without `CACHE_SOURCE_PATH`.
     */
    @Synchronized
    fun ensureBuilt(): BuildResult? {
        val directory = tree ?: return null
        result?.let { return it }
        check(directory.isDirectory) { "CACHE_SOURCE_PATH '${directory.absolutePath}' is not a directory" }
        if (EnvVars.generateMaps) {
            val generator = GeneratedWorld.generator
            MapCodec.generator = generator
            WorldMapImage.write(directory, generator)
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
