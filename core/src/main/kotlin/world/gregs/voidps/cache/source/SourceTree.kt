package world.gregs.voidps.cache.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class SourceTree private constructor(
    val directory: Path,
    val format: Int,
    val revision: Int,
    val indexCount: Int
) {

    val cacheFile: Path
        get() = directory.resolve(CACHE_FILE)

    val gamevals: Path
        get() = directory.resolve(GAMEVALS)

    fun indexDirectory(index: Int): Path = directory.resolve(IndexNames.name(index))

    fun indexFile(index: Int): Path = indexDirectory(index).resolve(INDEX_FILE)

    fun indices(): List<Int> {
        val found = ArrayList<Int>(indexCount)
        for (index in 0 until indexCount) {
            if (Files.isRegularFile(indexFile(index))) {
                found.add(index)
            }
        }
        return found
    }

    fun metadata(index: Int): IndexMetadata =
        IndexMetadata.read(Files.readString(indexFile(index)), index)

    fun writeMetadata(metadata: IndexMetadata): Boolean =
        SourceFiles.write(indexFile(metadata.index), metadata.write().toByteArray(Charsets.UTF_8))

    fun status(): SourceStatus = SourceStatus.of(this)

    fun relative(path: Path): String? {
        if (!path.startsWith(directory)) {
            return null
        }
        return directory.relativize(path).joinToString("/") { it.name }
    }

    fun describe(): String = buildString {
        append("{\n")
        append("  \"format\": ").append(format).append(",\n")
        append("  \"revision\": ").append(revision).append(",\n")
        append("  \"indices\": ").append(indexCount).append('\n')
        append("}\n")
    }

    override fun toString(): String = "SourceTree(${directory.toAbsolutePath()}, revision $revision)"

    companion object {
        const val FORMAT = 1

        const val CACHE_FILE = "cache.json"
        const val INDEX_FILE = "index.json"
        const val GAMEVALS = "gamevals"

        const val OPAQUE_EXTENSION = ".container"

        const val REVISION = 727

        private val json = Json { ignoreUnknownKeys = true }

        fun open(directory: File): SourceTree = open(directory.toPath())

        fun open(directory: Path): SourceTree {
            val file = directory.resolve(CACHE_FILE)
            if (!Files.isRegularFile(file)) {
                throw IOException("No $CACHE_FILE in ${directory.toAbsolutePath()}; this is not a cache source tree.")
            }
            val root = json.parseToJsonElement(Files.readString(file)).jsonObject
            val format = root["format"]?.jsonPrimitive?.int ?: FORMAT
            require(format == FORMAT) {
                "The tree at ${directory.toAbsolutePath()} is format $format; this build reads format $FORMAT."
            }
            return SourceTree(
                directory = directory,
                format = format,
                revision = root["revision"]?.jsonPrimitive?.int ?: REVISION,
                indexCount = root["indices"]?.jsonPrimitive?.int ?: IndexNames.COUNT
            )
        }

        fun create(directory: File, revision: Int = REVISION, indexCount: Int = IndexNames.COUNT): SourceTree =
            create(directory.toPath(), revision, indexCount)

        fun create(directory: Path, revision: Int = REVISION, indexCount: Int = IndexNames.COUNT): SourceTree {
            Files.createDirectories(directory)
            val tree = SourceTree(directory, FORMAT, revision, indexCount)
            SourceFiles.write(tree.cacheFile, tree.describe().toByteArray(Charsets.UTF_8))
            return tree
        }
    }
}
