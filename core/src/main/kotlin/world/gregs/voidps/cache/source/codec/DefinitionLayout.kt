package world.gregs.voidps.cache.source.codec

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

sealed class DefinitionLayout(
    val bits: Int
) {

    abstract fun path(archive: Int, file: Int): String

    abstract fun archiveOf(path: String): Int?

    abstract fun fileIds(directory: Path, archive: Int): IntArray

    fun definition(archive: Int, file: Int): Int = (archive shl bits) or file

    fun file(archive: Int, file: Int): String = "${path(archive, file)}$EXTENSION"

    class Packed(bits: Int) : DefinitionLayout(bits) {

        private val mask = (1 shl bits) - 1

        override fun path(archive: Int, file: Int): String = definition(archive, file).toString()

        override fun archiveOf(path: String): Int? {
            if (path.contains('/')) {
                return null
            }
            return id(path)?.ushr(bits)
        }

        override fun fileIds(directory: Path, archive: Int): IntArray {
            val ids = ArrayList<Int>()
            for (id in 0..mask) {
                if (Files.isRegularFile(directory.resolve(file(archive, id)))) {
                    ids.add(id)
                }
            }
            return ids.toIntArray()
        }

        private fun id(path: String): Int? {
            if (!path.endsWith(EXTENSION)) {
                return null
            }
            return path.dropLast(EXTENSION.length).toIntOrNull()
        }
    }

    class Nested(bits: Int) : DefinitionLayout(bits) {

        override fun path(archive: Int, file: Int): String = "$archive/$file"

        override fun archiveOf(path: String): Int? {
            val separator = path.indexOf('/')
            if (separator <= 0 || path.indexOf('/', separator + 1) >= 0 || !path.endsWith(EXTENSION)) {
                return null
            }
            if (path.substring(separator + 1).dropLast(EXTENSION.length).toIntOrNull() == null) {
                return null
            }
            return path.substring(0, separator).toIntOrNull()
        }

        override fun fileIds(directory: Path, archive: Int): IntArray =
            ids(directory.resolve(archive.toString()))
    }

    object Flat : DefinitionLayout(0) {

        override fun path(archive: Int, file: Int): String = file.toString()

        override fun archiveOf(path: String): Int? = null

        override fun fileIds(directory: Path, archive: Int): IntArray = ids(directory)
    }

    companion object {

        const val EXTENSION = ".json"

        internal fun ids(directory: Path): IntArray {
            if (!Files.isDirectory(directory)) {
                return IntArray(0)
            }
            val ids = ArrayList<Int>()
            Files.list(directory).use { stream ->
                for (path in stream) {
                    val name = path.name
                    if (!name.endsWith(EXTENSION)) {
                        continue
                    }
                    name.dropLast(EXTENSION.length).toIntOrNull()?.let { ids.add(it) }
                }
            }
            ids.sort()
            return ids.toIntArray()
        }
    }
}
