package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.decoder.MapTileDecoder
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.MapJson
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCodecParityTest {

    @Test
    fun `every map archive re-encodes byte for byte`() {
        val cache = cache() ?: return
        SourceCodecs.reset()
        val keys = Unpacker.mapKeys()
        val table = cache.referenceTable(Index.MAPS) ?: error("The cache has no index ${Index.MAPS} reference table.")
        val work = Files.createTempDirectory(scratch(), "map-codec")
        val counts = LinkedHashMap<MapSquare.Shape, Counts>()
        for (shape in MapSquare.Shape.entries) {
            counts[shape] = Counts()
        }
        var opaque = 0
        val examples = StringBuilder()
        try {
            for (entry in table.archives) {
                val square = MapSquare.of(entry.nameHash)
                    ?: error("Map archive ${entry.id}'s name hash ${entry.nameHash} is no map square's.")
                val group = group(cache, entry.id, square, keys)
                if (group == null) {
                    opaque++
                    continue
                }
                val count = counts.getValue(square.shape)
                count.files++
                val metadata = ArchiveMetadata(nameHash = entry.nameHash)
                metadata.fileIds(entry.fileIds)
                val source = SourceArchive(
                    Index.MAPS, entry.id, square.name, entry.fileIds, group,
                    ArchiveGroup.split(group, entry.fileIds).files, emptyList(), metadata
                )
                val unpacked = MapCodec.unpack(work, source)
                for (file in unpacked.files) {
                    SourceFiles.write(work.resolve(file.path), file.bytes)
                }
                if (unpacked.pristine.isNotEmpty()) {
                    metadata.pristine = unpacked.pristine
                    count.pristine++
                    if (count.pristine <= EXAMPLES) {
                        examples.append("\n  ${square.name} did not survive its JSON file")
                    }
                }
                val packed = MapCodec.pack(work, entry.id, metadata)
                if (packed.group.contentEquals(group)) {
                    count.identical++
                } else if (count.pristine == 0) {
                    examples.append("\n  ${square.name} packed ${packed.group.size} bytes, not ${group.size}")
                }
            }
        } finally {
            work.toFile().deleteRecursively()
        }

        var files = 0
        var identical = 0
        var pristine = 0
        println("%-22s %-24s %9s %9s %9s".format("shape", "file", "files", "identical", "pristine"))
        for ((shape, count) in counts) {
            files += count.files
            identical += count.identical
            pristine += count.pristine
            println(
                "%-22s %-24s %9d %9d %9d".format(
                    shape.name.lowercase(), "<x>_<y>/${shape.file}.json", count.files, count.identical, count.pristine
                )
            )
        }
        println("%-22s %-24s %9d %9d %9d".format("total", "", files, identical, pristine))
        println("opaque archives skipped: $opaque")

        assertEquals(OPAQUE, opaque, "only the unkeyed location archives should be opaque")
        assertEquals(ARCHIVES - OPAQUE, files, "every named map archive should have been compared")
        assertEquals(0, pristine, "no map archive may need a pristine copy$examples")
        assertEquals(files, identical, "every map archive must re-encode byte for byte$examples")
        assertEquals(TERRAIN, counts.getValue(MapSquare.Shape.TERRAIN).files)
        assertEquals(UNDERWATER, counts.getValue(MapSquare.Shape.UNDERWATER_TERRAIN).files)
        assertEquals(NPCS, counts.getValue(MapSquare.Shape.NPCS).files)
    }

    @Test
    fun `index 5 unpacks into a tree that packs back byte for byte`() {
        val packed = cacheDirectory() ?: return
        SourceCodecs.reset()
        val work = Files.createTempDirectory(scratch(), "map-tree").toFile()
        try {
            val cache = FileCache.load(packed.absolutePath)
            val tree = SourceTree.create(work, SourceTree.REVISION, cache.indexCount())
            val unpacked = try {
                Unpacker.unpack(OnlyMaps(cache), tree, Unpacker.mapKeys()) { println("  unpack $it") }
            } finally {
                cache.close()
            }
            println("unpack: $unpacked")
            assertEquals(1, unpacked.indices, "only index ${Index.MAPS} should have been unpacked")
            assertEquals(ARCHIVES, unpacked.archives)
            assertEquals(0, unpacked.missing, "every table entry should have a container")
            assertEquals(OPAQUE, unpacked.opaque, "only the unkeyed location archives should be opaque")
            assertEquals(0, unpacked.pristine, "no map file should have needed a pristine copy")
            assertEquals(0, unpacked.inexact, "no map archive should have needed a pristine fallback")

            val size = size(work.toPath())
            println("tree: ${size.first} files, ${size.second / 1_000_000} MB")

            val report = Verifier.verify(SourceTree.open(work), packed) { println("  verify $it") }
            println("verify: $report")
            assertEquals(1, report.indices.size, "only index ${Index.MAPS} is in the tree")
            assertEquals(0, report.different, "no container may differ")
            assertEquals(0, report.missing)
            assertEquals(0, report.extra)
            assertEquals(ARCHIVES, report.identical, "every container must come out identical")
            assertTrue(report.indices.single().tableIdentical, "index ${Index.MAPS}'s reference table must match")

            val stamps = timestamps(work.toPath())
            val again = FileCache.load(packed.absolutePath)
            val second = try {
                Unpacker.unpack(OnlyMaps(again), SourceTree.open(work), Unpacker.mapKeys())
            } finally {
                again.close()
            }
            assertEquals(0, second.written, "re-unpacking an unchanged cache must rewrite nothing")
            assertEquals(0, second.deleted)
            assertEquals(stamps, timestamps(work.toPath()), "not a single file may have been touched")

            edit(SourceTree.open(work))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun edit(tree: SourceTree) {
        val output = File(tree.directory.parent.toFile(), "map-build")
        try {
            val directory = tree.indexDirectory(Index.MAPS)
            val square = MapSquare.Square(MapSquare.Shape.TERRAIN, EDITED_X, EDITED_Y)
            val archive = MapCodec.archiveOf(directory, MapCodec.path(square))
                ?: error("${square.name} is not in the tree.")
            assertEquals(1, Packer.pack(tree, output).indices, "the tree is index ${Index.MAPS} alone")

            val file = directory.resolve(MapCodec.path(square))
            val definition = MapJson.readTerrain(Files.readAllBytes(file), square)
            val before = definition.getTile(0, 0, 0)
            val underlay = if (before.underlayId == EDITED_UNDERLAY) EDITED_UNDERLAY - 1 else EDITED_UNDERLAY
            val edited = MapTile(
                height = before.height,
                opcode = before.attrOpcode,
                overlay = before.overlayId,
                path = before.overlayPath,
                rotation = before.overlayRotation,
                settings = before.settings,
                underlay = underlay
            )
            definition.setTile(0, 0, 0, edited)
            Files.write(file, MapJson.writeTerrain(definition, square))

            val build = Builder.build(tree, output) { println("  edit build $it") }
            println("one file build: $build")
            assertTrue(!build.full, "an edited file must be patched, not repacked from scratch")
            assertEquals(1, build.changedFiles, "only the edited file changed")
            assertEquals(1, build.archives, "only ${square.name}'s archive may be repacked")

            val built = FileStore.open(output).use { store ->
                Container.decode(store.read(Index.MAPS, archive) ?: error("${square.name} is not in the built cache."))
                    .data()
            }
            val decoded = MapTileDecoder.decodeArchive(built, square.region).getTile(0, 0, 0)
            assertEquals(underlay, decoded.underlayId, "the built cache must carry the edited underlay")
            assertEquals(before.height, decoded.height, "nothing but the underlay may have changed")
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun `a map file names its square and maps back to its archive`() {
        val square = MapSquare.Square(MapSquare.Shape.TERRAIN, 50, 50)
        assertEquals("50_50/terrain.json", MapCodec.path(square))
        assertEquals("50_50/locs.json", MapCodec.path(square.copy(shape = MapSquare.Shape.LOCATIONS)))
        assertEquals("50_50/npcs.json", MapCodec.path(square.copy(shape = MapSquare.Shape.NPCS)))
        assertEquals(
            "50_50/underwater_terrain.json",
            MapCodec.path(square.copy(shape = MapSquare.Shape.UNDERWATER_TERRAIN))
        )
        assertEquals(
            "50_50/underwater_locs.json",
            MapCodec.path(square.copy(shape = MapSquare.Shape.UNDERWATER_LOCATIONS))
        )

        assertNull(MapCodec.archiveOf("50_50/terrain.json"))

        val directory = Files.createTempDirectory(scratch(), "map-lookup")
        try {
            val metadata = IndexMetadata(Index.MAPS, named = true)
            metadata.archives[7] = ArchiveMetadata(nameHash = MapSquare.terrain(50, 50).hashCode())
            metadata.archives[9] = ArchiveMetadata(nameHash = MapSquare.underwaterLocations(50, 50).hashCode())
            Files.writeString(directory.resolve(SourceTree.INDEX_FILE), metadata.write())

            assertEquals(7, MapCodec.archiveOf(directory, "50_50/terrain.json"))
            assertEquals(7, MapCodec.archiveOf(directory, "50_50/terrain.json.pristine"))
            assertEquals(9, MapCodec.archiveOf(directory, "50_50/underwater_locs.json"))
            assertNull(MapCodec.archiveOf(directory, "50_51/terrain.json"))
            assertNull(MapCodec.archiveOf(directory, "50_50/heights.json"))
            assertNull(MapCodec.archiveOf(directory, "50_50/terrain.dat"))
            assertNull(MapCodec.archiveOf(directory, "1234.container"))
            assertNull(MapCodec.archiveOf(directory, "terrain.json"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class Counts {
        var files = 0
        var identical = 0
        var pristine = 0
    }

    private fun group(cache: Cache, archive: Int, square: MapSquare.Square, keys: Map<Int, IntArray>): ByteArray? {
        val bytes = cache.container(Index.MAPS, archive) ?: return null
        return try {
            Container.decode(bytes, if (square.shape.encrypted) keys[square.region] else null).data()
        } catch (e: Exception) {
            null
        }
    }

    private class OnlyMaps(private val cache: Cache) : Cache by cache {
        override fun referenceTable(index: Int): ReferenceTable? =
            if (index == Index.MAPS) cache.referenceTable(index) else null
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

    private fun cache(): Cache? {
        if (cacheDirectory() == null) {
            return null
        }
        return Cache.get()
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping map codec parity; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {
        private const val ARCHIVES = 7268

        private const val OPAQUE = 8

        private const val TERRAIN = 2407
        private const val UNDERWATER = 1208
        private const val NPCS = 38

        private const val EXAMPLES = 5

        private const val EDITED_X = 50
        private const val EDITED_Y = 50
        private const val EDITED_UNDERLAY = 174
    }
}
