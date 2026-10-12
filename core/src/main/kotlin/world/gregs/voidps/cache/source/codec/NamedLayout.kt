package world.gregs.voidps.cache.source.codec

import java.nio.file.Files
import java.nio.file.Path

class NamedLayout(private val name: String) : DefinitionLayout(0) {

    override fun path(archive: Int, file: Int): String = name

    override fun archiveOf(path: String): Int? = null

    override fun fileIds(directory: Path, archive: Int): IntArray =
        if (Files.isRegularFile(directory.resolve(file(archive, 0)))) intArrayOf(0) else IntArray(0)
}
