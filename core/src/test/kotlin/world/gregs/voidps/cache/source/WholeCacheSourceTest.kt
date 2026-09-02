package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source tree against the real 727 cache: unpack it, pack the tree back, and demand that
 * every container and every reference table comes out byte for byte as it went in.
 *
 * This is the parity guarantee `docs/cache-source.md` makes, and the only place it can actually
 * be proved. It also checks the two properties everything downstream leans on: unpacking the same
 * cache twice writes nothing the second time, and building an unchanged tree is a no-op.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class WholeCacheSourceTest {

    @Test
    fun `the whole cache round trips through a source tree`() {
        val packed = cacheDirectory() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(scratch(), "cache-source").toFile()
        val tree = File(work, "tree")
        val output = File(work, "packed")
        try {
            val cache = FileCache.load(packed.absolutePath)
            val source = SourceTree.create(tree, SourceTree.REVISION, cache.indexCount())
            val unpacked = try {
                Unpacker.unpack(cache, source, Unpacker.mapKeys()) { println("  unpack $it") }
            } finally {
                cache.close()
            }
            println("unpack: $unpacked")
            assertEquals(0, unpacked.missing, "every table entry should have a container")
            assertEquals(0, unpacked.inexact, "no codec should have needed a pristine fallback yet")
            assertEquals(OPAQUE, unpacked.opaque, "only the unkeyed map archives should be opaque")
            assertTrue(unpacked.archives > 100_000, "the cache has far more archives than ${unpacked.archives}")
            assertTrue(unpacked.millis < UNPACK_LIMIT, "unpacking took ${unpacked.millis}ms")

            val size = size(tree.toPath())
            println("tree: ${size.first} files, ${size.second / 1_000_000} MB in ${tree.absolutePath}")

            val result = Packer.pack(SourceTree.open(tree), output)
            val packedSize = size(output.toPath())
            println("pack: $result")
            println("packed: ${packedSize.first} files, ${packedSize.second / 1_000_000} MB")
            assertEquals(unpacked.archives, result.archives, "everything unpacked should pack again")

            val report = Verifier.verify(SourceTree.open(tree), packed) { println("  verify $it") }
            println("verify: $report")
            assertEquals(0, report.different, "no container may differ")
            assertEquals(0, report.missing)
            assertEquals(0, report.extra)
            assertEquals(report.indices.size, report.tables, "every reference table must be identical")
            assertTrue(report.versionTablesMatch, "the derived version tables must match")
            assertTrue(report.parity, "the tree must reproduce the cache exactly")
            assertEquals(unpacked.archives, report.identical)

            val build = Builder.build(SourceTree.open(tree), output) { println("  build $it") }
            println("no-op build: $build")
            assertTrue(build.unchanged, "an unchanged tree must rebuild nothing")
            assertEquals(0, build.hashedFiles, "an unchanged tree must not be rehashed")

            val stamps = timestamps(tree.toPath())
            val again = FileCache.load(packed.absolutePath)
            val second = try {
                Unpacker.unpack(again, SourceTree.open(tree), Unpacker.mapKeys())
            } finally {
                again.close()
            }
            println("re-unpack: $second")
            assertEquals(0, second.written, "re-unpacking an unchanged cache must rewrite nothing")
            assertEquals(0, second.deleted)
            assertEquals(stamps, timestamps(tree.toPath()), "not a single file may have been touched")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun size(root: Path): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (Files.isRegularFile(path)) {
                    files++
                    bytes += Files.size(path)
                }
            }
        }
        return files to bytes
    }

    private fun timestamps(root: Path): Map<String, Long> {
        val stamps = HashMap<String, Long>()
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (Files.isRegularFile(path)) {
                    stamps[root.relativize(path).toString()] = Files.getLastModifiedTime(path).toMillis()
                }
            }
        }
        return stamps
    }

    /** Several gigabytes of tree do not belong in a tmpfs; the build directory is on disk. */
    private fun scratch(): Path {
        val directory = File("core/build/tmp").absoluteFile
        directory.mkdirs()
        return directory.toPath()
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping the source tree round trip; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {
        /** The eight `l<x>_<y>` archives `data/map/xteaKeys.json` has no working key for. */
        private const val OPAQUE = 8

        /** Unpacking the whole 876 MB cache has to stay well inside two minutes. */
        private const val UNPACK_LIMIT = 120_000L
    }
}
