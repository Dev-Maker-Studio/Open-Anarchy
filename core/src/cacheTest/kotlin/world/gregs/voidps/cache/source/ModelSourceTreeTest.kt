package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.source.codec.ModelCodec
import world.gregs.voidps.cache.source.codec.ModelGltf
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelSourceTreeTest {

    @Test
    fun `models, particles and billboards round trip through a source tree`() {
        val packed = cacheDirectory() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(scratch(), "model-source").toFile()
        val tree = File(work, "tree")
        val output = File(work, "packed")
        try {
            val source = SourceTree.create(tree, SourceTree.REVISION, INDICES_IN_CACHE)
            val unpackTimes = LinkedHashMap<String, Long>()
            val unpacked = FileCache.load(packed.absolutePath).use { cache ->
                var last = System.nanoTime()
                Unpacker.unpack(PartialCache(cache, INDICES), source) { line ->
                    val now = System.nanoTime()
                    unpackTimes[line] = (now - last) / 1_000_000
                    last = now
                    println("  unpack $line")
                }
            }
            println("unpack: $unpacked")
            for ((line, millis) in unpackTimes) {
                println("  ${line.substringBefore(':')}: ${millis}ms")
            }
            assertEquals(0, unpacked.missing, "every table entry should have a container")
            assertEquals(0, unpacked.opaque, "none of these indices is encrypted")
            assertEquals(0, unpacked.pristine, "no model, particle or billboard may need a pristine copy")
            assertEquals(0, unpacked.inexact, "no archive should have needed a pristine fallback")
            val archives = unpacked.archives
            assertTrue(
                archives >= VANILLA_ARCHIVES,
                "$archives archives unpacked, fewer than the $VANILLA_ARCHIVES of vanilla 727"
            )

            for (index in INDICES) {
                val size = size(tree.toPath().resolve(IndexNames.name(index)))
                println("  ${IndexNames.name(index)}: ${size.first} files, ${size.second / 1_000_000} MB")
            }

            val packTimes = LinkedHashMap<String, Long>()
            var last = System.nanoTime()
            val result = Packer.pack(SourceTree.open(tree), output) { line ->
                val now = System.nanoTime()
                packTimes[line] = (now - last) / 1_000_000
                last = now
            }
            println("pack: $result")
            for ((line, millis) in packTimes) {
                println("  ${line.substringBefore(':')}: ${millis}ms")
            }
            assertEquals(archives, result.archives, "everything unpacked should pack again")

            val report = Verifier.verify(SourceTree.open(tree), packed) { println("  verify $it") }
            println("verify: $report")
            assertEquals(0, report.different, "no container may differ")
            assertEquals(0, report.missing)
            assertEquals(0, report.extra)
            assertEquals(INDICES.size, report.indices.size, "all three indices must be verified")
            assertEquals(INDICES.size, report.tables, "every reference table must be identical")
            assertEquals(archives, report.identical, "every unpacked archive must verify identical")

                val stamps = timestamps(tree.toPath())
            val second = FileCache.load(packed.absolutePath).use { cache ->
                Unpacker.unpack(PartialCache(cache, INDICES), SourceTree.open(tree))
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

    private fun edit(tree: SourceTree, output: File) {
        val file = tree.indexDirectory(Index.MODELS).resolve("$EDITED${ModelCodec.EXTENSION}")
        val model = ModelGltf.read(Files.readAllBytes(file), EDITED)
        assertTrue(model.vertexCount > 0, "model $EDITED has no vertices to move")
        val moved = model.vertexX.copyOf()
        moved[0] = EDITED_X
        Files.write(file, ModelGltf.write(model.copy(vertexX = moved)))

        val build = Builder.build(tree, output) { println("  edit build $it") }
        println("one file build: $build")
        assertTrue(!build.full, "an edited file must be patched, not repacked from scratch")
        assertEquals(1, build.changedFiles, "only the edited file changed")
        assertEquals(1, build.archives, "only model $EDITED's archive may be repacked")
        assertEquals(1, build.indices, "only index ${Index.MODELS}'s table may be rewritten")

        FileCache.load(output.absolutePath).use { cache ->
            val model = assertNotNull(ModelDecoder.decode(cache, EDITED), "model $EDITED is not in the built cache")
            assertEquals(EDITED_X, model.vertexX[0], "the built cache must carry the edited vertex")
        }
    }

    private class PartialCache(private val cache: Cache, indices: IntArray) : Cache by cache {
        private val kept = indices.toSet()

        override fun referenceTable(index: Int): ReferenceTable? =
            if (kept.contains(index)) cache.referenceTable(index) else null
    }

    private fun size(root: Path): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        if (!Files.isDirectory(root)) {
            return files to bytes
        }
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
            println("Skipping the model source tree round trip; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {
        private val INDICES = intArrayOf(Index.MODELS, Index.PARTICLES, Index.BILLBOARDS)

        private const val VANILLA_ARCHIVES = 73_420

        private const val INDICES_IN_CACHE = IndexNames.COUNT

        private const val EDITED = 3253

        private const val EDITED_X = 1232
    }
}
