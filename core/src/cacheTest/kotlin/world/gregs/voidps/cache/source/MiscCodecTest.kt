package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.AnimationFrameCodec
import world.gregs.voidps.cache.source.codec.ArchiveDirectoryCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.WorldMapCodec
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiscCodecTest {

    @Test
    fun `every archive of every misc index packs back byte for byte`() {
        val packed = cacheDirectory() ?: return
        SourceCodecs.reset()
        val cache = FileCache.load(packed.absolutePath)
        AnimationFrameCodec.cache = { cache }
        val work = Files.createTempDirectory(scratch(), "misc-codec")
        var archives = 0
        var files = 0
        var pristine = 0
        var bytes = 0L
        try {
            println("%-20s %-6s %9s %9s %9s %9s %9s".format("index", "id", "archives", "files", "identical", "pristine", "tree KB"))
            for (index in INDICES) {
                val counts = index(cache, index, work.resolve(IndexNames.name(index)))
                archives += counts.archives
                files += counts.files
                pristine += counts.pristine
                bytes += counts.bytes
                println(
                    "%-20s %-6d %9d %9d %9d %9d %9d".format(
                        IndexNames.name(index), index, counts.archives, counts.files,
                        counts.identical, counts.pristine, counts.bytes / 1024
                    )
                )
                assertEquals(
                    cache.archiveIds(index).size,
                    counts.archives,
                    "${IndexNames.name(index)} left archives out of the round trip"
                )
                assertEquals(
                    counts.archives,
                    counts.identical,
                    "${IndexNames.name(index)} did not pack back byte for byte"
                )
                assertEquals(0, counts.pristine, "${IndexNames.name(index)} needed a pristine sidecar")
            }
            println("%-20s %-6s %9d %9d %9d %9d %9d".format("total", "", archives, files, archives, pristine, bytes / 1024))
            assertTrue(
                archives >= VANILLA_ARCHIVES,
                "$archives archives, fewer than the $VANILLA_ARCHIVES of vanilla 727"
            )
            assertTrue(files >= VANILLA_FILES, "$files files, fewer than the $VANILLA_FILES of vanilla 727")
            assertEquals(0, pristine, "no file of any of these indices may need a pristine copy")
        } finally {
            AnimationFrameCodec.reset()
            cache.close()
            work.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an animation frame group keeps its three chunks`() {
        val packed = cacheDirectory() ?: return
        val cache = FileCache.load(packed.absolutePath)
        try {
            var multi = 0
            var singleFile = 0
            val ids = cache.archiveIds(Index.ANIMATION_FRAMES)
            for (archive in ids) {
                val ids = cache.files(Index.ANIMATION_FRAMES, archive)
                val group = Container.decode(cache.container(Index.ANIMATION_FRAMES, archive)!!).data()
                val split = ArchiveGroup.split(group, ids)
                if (ids.size == 1) {
                    singleFile++
                    assertEquals(1, split.chunks.size, "a one file group has no chunk table")
                    continue
                }
                multi++
                assertEquals(FRAME_CHUNKS, split.chunks.size, "frame set $archive is not three chunks")
                for (position in ids.indices) {
                    assertEquals(
                        FRAME_HEADER,
                        split.chunks[0][position],
                        "frame set $archive frame ${ids[position]} has a header of another length"
                    )
                }
            }
            println("index 0: $multi three chunk archives, $singleFile single file archives")
            assertEquals(ids.size, multi + singleFile, "every frame set is either three chunks or one file")
            assertTrue(
                multi >= VANILLA_MULTI_CHUNK_ARCHIVES,
                "$multi three chunk frame sets, fewer than the $VANILLA_MULTI_CHUNK_ARCHIVES of vanilla 727"
            )
        } finally {
            cache.close()
        }
    }

    @Test
    fun `every world map archive name hashes to the cache's own`() {
        val packed = cacheDirectory() ?: return
        val cache = FileCache.load(packed.absolutePath)
        try {
            val hashes = cache.archiveNameHashes(Index.WORLD_MAP)
            assertTrue(
                hashes.size >= VANILLA_WORLD_MAP_ARCHIVES,
                "${hashes.size} named archives, fewer than the $VANILLA_WORLD_MAP_ARCHIVES of vanilla 727"
            )
            for ((archive, hash) in hashes) {
                val name = WorldMapCodec.name(archive)
                assertEquals(hash, name.hashCode(), "index 23 archive $archive is not '$name'")
            }
            assertEquals("details", WorldMapCodec.name(cache.archiveId(Index.WORLD_MAP, "details")))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `every misc index has its codec`() {
        SourceCodecs.reset()
        assertEquals(AnimationFrameCodec, SourceCodecs.codec(Index.ANIMATION_FRAMES))
        assertEquals(WorldMapCodec, SourceCodecs.codec(Index.WORLD_MAP))
        assertEquals(
            SourceCodecs.codec(Index.QUICK_CHAT_MESSAGES),
            SourceCodecs.codec(Index.QUICK_CHAT_MENUS),
            "both quick chat indices hold the same two archives"
        )
        for (index in INDICES) {
            assertTrue(
                SourceCodecs.codec(index).id != "raw-archive",
                "${IndexNames.name(index)} still has the fallback codec"
            )
        }
        assertEquals(12, AnimationFrameCodec.archiveOf("12${AnimationFrameCodec.EXTENSION}"))
        assertEquals(
            12,
            AnimationFrameCodec.archiveOf("12${AnimationFrameCodec.EXTENSION}${SourceFiles.PRISTINE_EXTENSION}")
        )
        assertEquals(null, AnimationFrameCodec.archiveOf("12.json"))
        assertEquals(null, AnimationFrameCodec.archiveOf("12/5${AnimationFrameCodec.EXTENSION}"))
        assertEquals(1, WorldMapCodec.archiveOf("details/29.json"))
        assertEquals(83, WorldMapCodec.archiveOf("main_staticelements/0.json"))
        assertEquals(29, WorldMapCodec.archiveOf("main/4.dat"))
        val defaults = SourceCodecs.codec(Index.DEFAULTS) as ArchiveDirectoryCodec
        assertEquals("equipment", defaults.name(6))
        assertEquals(6, defaults.archiveOf("equipment.json"))
        assertEquals(6, defaults.archiveOf("equipment.json.pristine"))
        val quickChat = SourceCodecs.codec(Index.QUICK_CHAT_MESSAGES) as ArchiveDirectoryCodec
        assertEquals(0, quickChat.archiveOf("quickchatoption/12.json"))
        assertEquals(1, quickChat.archiveOf("quickchatphrase/12.json"))
        val huffman = SourceCodecs.codec(Index.HUFFMAN) as ArchiveDirectoryCodec
        assertEquals(1, huffman.archiveOf("huffman.json"))
    }

    private fun index(cache: Cache, index: Int, directory: Path): Counts {
        val codec = SourceCodecs.codec(index)
        Files.createDirectories(directory)
        val counts = Counts()
        for (archive in cache.archiveIds(index)) {
            val container = cache.container(index, archive) ?: continue
            val ids = cache.files(index, archive)
            val group = Container.decode(container).data()
            val split = ArchiveGroup.split(group, ids)
            val metadata = ArchiveMetadata(nameHash = cache.archiveNameHashes(index)[archive] ?: 0)
            if (codec.fileIdsFromMetadata(archive)) {
                metadata.fileIds(ids)
            }
            val source = SourceArchive(index, archive, null, ids, group, split.files, split.chunks, metadata)
            val unpacked = codec.unpack(directory, source)
            metadata.pristine = unpacked.pristine
            counts.archives++
            counts.pristine += unpacked.pristine.size
            for (file in unpacked.files) {
                val path = directory.resolve(file.path)
                Files.createDirectories(path.parent)
                Files.write(path, file.bytes)
                if (!SourceFiles.isPristine(file.path)) {
                    counts.files++
                    counts.bytes += file.bytes.size
                }
            }
            if (codec.pack(directory, archive, metadata).group.contentEquals(group)) {
                counts.identical++
            } else {
                report(index, archive, codec, directory, metadata, group)
            }
        }
        return counts
    }

    private fun report(
        index: Int,
        archive: Int,
        codec: SourceCodec,
        directory: Path,
        metadata: ArchiveMetadata,
        group: ByteArray
    ) {
        val rebuilt = codec.pack(directory, archive, metadata).group
        val offset = (0 until minOf(group.size, rebuilt.size)).firstOrNull { group[it] != rebuilt[it] } ?: -1
        println(
            "  ${IndexNames.name(index)} archive $archive differs: ${group.size} bytes became " +
                "${rebuilt.size}, first difference at $offset"
        )
    }

    private class Counts {
        var archives = 0
        var identical = 0
        var files = 0
        var pristine = 0
        var bytes = 0L
    }

    private fun scratch(): Path {
        val directory = File("core/build/tmp").absoluteFile
        directory.mkdirs()
        return directory.toPath()
    }

    private fun cacheDirectory(): File? {
        val directory = File(EnvVars.cachePath)
        if (!File(directory, "${FileStore.CACHE_FILE_NAME}.dat2").exists()) {
            println("Skipping the misc codec parity check; no cache at ${directory.absolutePath}")
            return null
        }
        return directory
    }

    private companion object {

        private val INDICES = intArrayOf(
            Index.ANIMATION_FRAMES,
            Index.ANIMATION_SKELETONS,
            Index.HUFFMAN,
            Index.WORLD_MAP,
            Index.QUICK_CHAT_MESSAGES,
            Index.QUICK_CHAT_MENUS,
            Index.DEFAULTS,
            Index.GAME_TIPS,
            Index.CUTSCENES
        )

        private const val VANILLA_ARCHIVES = 7764

        private const val VANILLA_FILES = 10313

        private const val FRAME_CHUNKS = 3
        private const val FRAME_HEADER = 4
        private const val VANILLA_MULTI_CHUNK_ARCHIVES = 4074
        private const val VANILLA_WORLD_MAP_ARCHIVES = 86
    }
}
