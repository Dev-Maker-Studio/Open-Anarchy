package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.source.codec.DefinitionCodecs
import world.gregs.voidps.cache.source.codec.DefinitionLayout
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            assertEquals(0, unpacked.inexact, "no archive should have needed a pristine fallback")
            assertEquals(0, unpacked.pristine, "no file should have needed a pristine copy")
            assertEquals(OPAQUE, unpacked.opaque, "only the unkeyed map archives should be opaque")
            assertTrue(unpacked.archives > 100_000, "the cache has far more archives than ${unpacked.archives}")
            assertTrue(unpacked.millis < UNPACK_LIMIT, "unpacking took ${unpacked.millis}ms")

            val size = size(tree.toPath())
            println("tree: ${size.first} files, ${size.second / 1_000_000} MB in ${tree.absolutePath}")
            assertEquals(0, types(SourceTree.open(tree)), "no definition type may need a pristine copy")

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
            assertEquals(
                unpacked.archives,
                report.identical + report.recompiled,
                "every archive must come back identical or as a guarded recompile"
            )
            assertEquals(
                report.indices.size,
                report.tables + report.tablesRecompiled,
                "a reference table may only differ where its recompiled containers explain it"
            )
            assertTrue(
                report.versionTablesMatch || report.versionTableAccountedFor,
                "the derived version table may only differ through a recompiled index's table"
            )
            assertTrue(report.parity, "the tree must reproduce the cache: $report")

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

            edit(SourceTree.open(tree), output)
        } finally {
            work.deleteRecursively()
        }
    }


    private fun types(tree: SourceTree): Int {
        var files = 0
        var pristine = 0
        println("%-11s %-24s %9s %9s %9s".format("type", "directory", "files", "exact", "pristine"))
        for (type in DefinitionCodecs.types) {
            val directory = tree.directory.resolve(type.directory)
            val written = json(directory)
            val metadata = tree.metadata(type.index)
            val kept = if (type.archive < 0) {
                metadata.archives.values.sumOf { it.pristine.size }
            } else {
                metadata.archive(type.archive)?.pristine?.size ?: 0
            }
            files += written
            pristine += kept
            println("%-11s %-24s %9d %9d %9d".format(type.name, type.directory, written, written - kept, kept))
        }
        println("%-11s %-24s %9d %9d %9d".format("total", "", files, files - pristine, pristine))
        return pristine
    }

    private fun json(directory: Path): Int {
        if (!Files.isDirectory(directory)) {
            return 0
        }
        var count = 0
        Files.walk(directory).use { stream ->
            for (path in stream) {
                if (path.name == SourceTree.INDEX_FILE || !Files.isRegularFile(path)) {
                    continue
                }
                if (path.name.endsWith(DefinitionLayout.EXTENSION)) {
                    count++
                }
            }
        }
        return count
    }

    private fun edit(tree: SourceTree, output: File) {
        val file = tree.indexDirectory(Index.ITEMS).resolve("$EDITED${DefinitionLayout.EXTENSION}")
        val before = Files.readString(file)
        assertTrue(before.contains(""""name": "$ORIGINAL""""), "obj $EDITED is not $ORIGINAL: $before")
        Files.writeString(file, before.replace(""""name": "$ORIGINAL"""", """"name": "$RENAMED""""))

        val build = Builder.build(tree, output) { println("  edit build $it") }
        println("one file build: $build")
        assertTrue(!build.full, "an edited file must be patched, not repacked from scratch")
        assertEquals(1, build.changedFiles, "only the edited file changed")
        assertEquals(1, build.archives, "only obj $EDITED's archive may be repacked")
        assertEquals(1, build.indices, "only index ${Index.ITEMS}'s table may be rewritten")

        val cache = FileCache.load(output.absolutePath)
        try {
            val data = cache.data(Index.ITEMS, EDITED ushr 8, EDITED and 0xff)
            assertTrue(data != null, "obj $EDITED is not in the built cache")
            val definition = ItemDefinition(EDITED)
            ItemDecoder().readLoop(definition, BufferReader(data!!))
            assertEquals(RENAMED, definition.name, "the built cache must carry the edited name")
        } finally {
            cache.close()
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
        private const val OPAQUE = 8

        private const val UNPACK_LIMIT = 900_000L

        private const val EDITED = 4151
        private const val ORIGINAL = "Abyssal whip"
        private const val RENAMED = "Abyssal thwip"
    }
}
