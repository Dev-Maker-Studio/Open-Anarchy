package world.gregs.voidps.cache.source

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.ConfigCodec
import world.gregs.voidps.cache.source.codec.DefinitionCodecs
import world.gregs.voidps.cache.source.codec.DefinitionLayout
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionJsonCodecTest {

    @Test
    fun `every definition round trips through its JSON file`() {
        val directory = File(EnvVars.cachePath)
        if (!directory.isDirectory) {
            println("Skipping definition JSON parity; no cache at ${directory.absolutePath}")
            return
        }
        val cache = Cache.get()
        var files = 0
        var pristine = 0
        println("%-11s %-6s %9s %9s %9s".format("type", "index", "files", "exact", "pristine"))
        for (type in DefinitionCodecs.types) {
            val counts = count(cache, type)
            files += counts.first
            pristine += counts.second
            println(
                "%-11s %-6s %9d %9d %9d".format(
                    type.name,
                    if (type.archive < 0) "${type.index}" else "${type.index}/${type.archive}",
                    counts.first, counts.first - counts.second, counts.second
                )
            )
            assertEquals(0, counts.second, "${type.name} files did not survive the JSON round trip")
        }
        println("%-11s %-6s %9d %9d %9d".format("total", "", files, files - pristine, pristine))
        assertTrue(files > 240_000, "only $files definition files were compared")
        assertEquals(0, pristine, "no definition may need a pristine copy")
    }

    private fun count(cache: Cache, type: DefinitionCodecs.Type): Pair<Int, Int> {
        var files = 0
        var pristine = 0
        val archives = if (type.archive < 0) cache.archiveIds(type.index) else intArrayOf(type.archive)
        for (archive in archives) {
            val ids = cache.files(type.index, archive)
            val data = ArrayList<ByteArray>(ids.size)
            for (file in ids) {
                data.add(cache.data(type.index, archive, file) ?: continue)
            }
            if (data.size != ids.size) {
                continue
            }
            files += ids.size
            val metadata = ArchiveMetadata()
            val source = SourceArchive(type.index, archive, null, ids, ByteArray(0), data, emptyList(), metadata)
            val unpacked = type.codec.unpack(Path.of("."), source)
            pristine += unpacked.pristine.size
            assertEquals(
                unpacked.pristine.size,
                unpacked.files.count { it.path.endsWith(PRISTINE) },
                "${type.name} archive $archive recorded a pristine hash without a sidecar, or the other way round"
            )
        }
        return files to pristine
    }

    @Test
    fun `a definition file is named by its id and maps back to its archive`() {
        val obj = DefinitionCodecs.types.first { it.name == "obj" }.codec
        assertEquals(16, obj.archiveOf("4151${DefinitionLayout.EXTENSION}"))
        assertEquals(16, obj.archiveOf("4151${DefinitionLayout.EXTENSION}.pristine"))
        assertEquals(null, obj.archiveOf("4151.dat"))
        assertEquals(null, obj.archiveOf("nested/4151${DefinitionLayout.EXTENSION}"))

        val component = DefinitionCodecs.types.first { it.name == "interface" }.codec
        assertEquals(1477, component.archiveOf("1477/32${DefinitionLayout.EXTENSION}"))
        assertEquals(1477, component.archiveOf("1477/32${DefinitionLayout.EXTENSION}.pristine"))
        assertEquals(null, component.archiveOf("1477${DefinitionLayout.EXTENSION}"))

        val struct = DefinitionCodecs.types.first { it.name == "struct" }.codec
        assertEquals(null, struct.archiveOf("26${DefinitionLayout.EXTENSION}"))
    }

    @Test
    fun `every typed index has a codec registered`() {
        for (type in DefinitionCodecs.types) {
            val codec = if (type.archive < 0) {
                SourceCodecs.codec(type.index)
            } else {
                ConfigCodec.codec(type.archive)
            }
            assertEquals(type.codec, codec, "${type.name} is not the codec for its index")
        }
        assertEquals(
            DefinitionCodecs.types.count { it.archive < 0 },
            DefinitionCodecs.types.count { it.archive < 0 && it.index != Index.CONFIGS },
            "every whole index type is its own index"
        )
    }

    private companion object {
        private const val PRISTINE = ".pristine"
    }
}
