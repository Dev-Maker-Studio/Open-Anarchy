package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.ImageCodecs
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.SpriteCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every archive of the six 2D art indices, through the codec that stores it.
 *
 * The bar is the one `docs/cache-source.md` sets for a typed codec: `pack(unpack(bytes)) == bytes`
 * for every archive of every index, with no pristine sidecar anywhere. Unlike
 * `DefinitionJsonCodecTest` this has to write the files out - a sprite is PNGs on disk and the
 * codec reads them back through `javax.imageio`, which is the half of the round trip that could
 * lose a pixel - so each archive is unpacked into a scratch directory and packed straight back.
 *
 * Skipped when there is no cache to test against, like the other whole-cache tests.
 */
class ImageCodecTest {

    @Test
    fun `every 2D asset round trips through its files`() {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping 2D codec parity; no cache at ${directory.absolutePath}")
            return
        }
        SourceCodecs.reset()
        val cache = Cache.get()
        val work = Files.createTempDirectory(SCRATCH)
        var archives = 0
        var pristine = 0
        var bytes = 0L
        var files = 0
        try {
            println("%-20s %-6s %9s %9s %9s %9s %8s".format("index", "id", "archives", "exact", "pristine", "files", "ms"))
            for (index in ImageCodecs.indices) {
                val counts = count(cache, index, work)
                archives += counts.archives
                pristine += counts.pristine
                files += counts.files
                bytes += counts.bytes
                println(
                    "%-20s %-6d %9d %9d %9d %9d %8d".format(
                        ImageCodecs.directory(index), index, counts.archives,
                        counts.archives - counts.pristine, counts.pristine, counts.files, counts.millis
                    )
                )
                assertEquals(0, counts.pristine, "${ImageCodecs.directory(index)} archives did not round trip")
            }
            println(
                "%-20s %-6s %9d %9d %9d %9d %8s".format(
                    "total", "", archives, archives - pristine, pristine, files, "${bytes / 1_000_000} MB"
                )
            )
        } finally {
            work.toFile().deleteRecursively()
        }
        assertTrue(archives > 14_000, "only $archives archives were compared")
        assertEquals(0, pristine, "no 2D asset may need a pristine copy")
    }

    @Test
    fun `a sprite maps every one of its files back to its archive`() {
        assertEquals(1234, SpriteCodec.archiveOf("1234/${SpriteCodec.METADATA}"))
        assertEquals(1234, SpriteCodec.archiveOf("1234/0${SpriteCodec.EXTENSION}"))
        assertEquals(1234, SpriteCodec.archiveOf("1234/0${SpriteCodec.ALPHA_EXTENSION}"))
        assertEquals(1234, SpriteCodec.archiveOf("1234/${SpriteCodec.METADATA}.pristine"))
        assertEquals(1234, SpriteCodec.archiveOf("1234${SpriteCodec.JPEG_EXTENSION}"))
        assertEquals(null, SpriteCodec.archiveOf("1234.dat"))
        assertEquals(null, SpriteCodec.archiveOf("1234/nested/0${SpriteCodec.EXTENSION}"))
    }

    @Test
    fun `every 2D index has its codec registered`() {
        SourceCodecs.reset()
        assertEquals(SpriteCodec, SourceCodecs.codec(Index.SPRITES))
        assertEquals(SpriteCodec, SourceCodecs.codec(Index.LOADING_SPRITES))
        assertEquals(SpriteCodec, SourceCodecs.codec(Index.LOADING_SPRITES_RAW))
        assertEquals(ImageCodecs.textures, SourceCodecs.codec(Index.TEXTURES))
        assertEquals(ImageCodecs.fonts, SourceCodecs.codec(Index.FONT_METRICS))
        assertEquals("json-material", SourceCodecs.codec(Index.TEXTURE_DEFINITIONS).id)
    }

    private class Counts(
        val archives: Int,
        val pristine: Int,
        val files: Int,
        val bytes: Long,
        val millis: Long
    )

    /** One index, archive by archive: unpack into a scratch directory, pack it back, compare. */
    private fun count(cache: Cache, index: Int, work: Path): Counts {
        val start = System.nanoTime()
        val codec = SourceCodecs.codec(index)
        val directory = work.resolve(ImageCodecs.directory(index))
        Files.createDirectories(directory)
        var archives = 0
        var pristine = 0
        var files = 0
        var bytes = 0L
        for (archive in cache.archiveIds(index)) {
            val ids = cache.files(index, archive)
            val data = ids.map { cache.data(index, archive, it) }.filterNotNull()
            if (data.size != ids.size) {
                continue
            }
            archives++
            val metadata = ArchiveMetadata()
            metadata.fileIds(ids)
            val group = data.single()
            val source = SourceArchive(index, archive, null, ids, group, data, emptyList(), metadata)
            val unpacked = codec.unpack(directory, source)
            for (file in unpacked.files) {
                files++
                bytes += file.bytes.size
                SourceFiles.write(directory.resolve(file.path), file.bytes)
            }
            if (unpacked.pristine.isNotEmpty()) {
                metadata.pristine = unpacked.pristine
                pristine++
                continue
            }
            val packed = pack(codec, directory, archive, metadata)
            assertTrue(
                packed.contentEquals(group),
                "${ImageCodecs.directory(index)} archive $archive packed to ${packed.size} bytes, not ${group.size}"
            )
        }
        return Counts(archives, pristine, files, bytes, (System.nanoTime() - start) / 1_000_000)
    }

    /**
     * The materials index is one archive holding every material, so its files accumulate across
     * the loop rather than being a directory per archive; everything else packs as it goes.
     */
    private fun pack(codec: SourceCodec, directory: Path, archive: Int, metadata: ArchiveMetadata): ByteArray =
        codec.pack(directory, archive, metadata).group

    private companion object {
        private const val SCRATCH = "image-codec"
    }
}
