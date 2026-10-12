package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.source.codec.Owner
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class StaleFile(
    val path: String,
    val reason: String
) {
    override fun toString(): String = "$path: $reason"
}

class IndexStatus(
    val index: Int,
    val name: String,
    val codec: String,
    val archives: Int,
    val files: Int,
    val pristine: Int,
    val opaque: Int,
    val stale: List<StaleFile>
) {
    override fun toString(): String =
        "$name: $codec, $archives archives, $files files, $pristine pristine, $opaque opaque, " +
            "${stale.size} stale"
}

class SourceStatus(
    val tree: SourceTree,
    val indices: List<IndexStatus>,
    val gamevals: Int
) {

    val archives: Int
        get() = indices.sumOf { it.archives }

    val files: Int
        get() = indices.sumOf { it.files }

    val pristine: Int
        get() = indices.sumOf { it.pristine }

    val opaque: Int
        get() = indices.sumOf { it.opaque }

    val stale: List<StaleFile>
        get() = indices.flatMap { it.stale }

    override fun toString(): String =
        "$archives archives, $files files, $pristine pristine, $opaque opaque, ${stale.size} stale"

    companion object {

        fun of(tree: SourceTree): SourceStatus {
            val indices = ArrayList<IndexStatus>()
            for (index in tree.indices()) {
                indices.add(index(tree, index))
            }
            return SourceStatus(tree, indices, gamevals(tree))
        }

        private fun index(tree: SourceTree, index: Int): IndexStatus {
            val metadata = tree.metadata(index)
            val directory = tree.indexDirectory(index)
            val codec = SourceCodecs.codec(index)
            val walk = walk(directory)
            val stale = ArrayList<StaleFile>()
            for (sidecar in walk.sidecars) {
                val guarded = SourceFiles.guarded(sidecar) ?: continue
                val relative = directory.relativize(guarded).joinToString("/") { it.name }
                val reason = staleReason(directory, guarded, relative, metadata, codec) ?: continue
                stale.add(StaleFile("${directory.name}/$relative", reason))
            }
            return IndexStatus(
                index = index,
                name = IndexNames.name(index),
                codec = codec.id,
                archives = metadata.archives.size,
                files = walk.files,
                pristine = metadata.archives.values.sumOf { it.pristine.size },
                opaque = metadata.archives.values.count { it.opaque },
                stale = stale
            )
        }

        private fun staleReason(
            directory: Path,
            guarded: Path,
            relative: String,
            metadata: IndexMetadata,
            codec: SourceCodec
        ): String? {
            if (!Files.isRegularFile(guarded)) {
                return "the file it guards is gone"
            }
            val archive = when (val owner = codec.owner(directory, relative)) {
                is Owner.Archive -> owner.id
                else -> return "belongs to no archive"
            }
            val recorded = metadata.archive(archive) ?: return "archive $archive is not in index.json"
            if (recorded.pristine.isEmpty()) {
                return "archive $archive records no pristine hash"
            }
            val hash = try {
                SourceFiles.sha256(guarded)
            } catch (e: IOException) {
                return "could not be read: ${e.message}"
            }
            if (!recorded.pristine.containsValue(hash)) {
                return "edited since it was unpacked; the pack will compile it"
            }
            return null
        }

        private class Walk(val files: Int, val sidecars: List<Path>)

        private fun walk(directory: Path): Walk {
            if (!Files.isDirectory(directory)) {
                return Walk(0, emptyList())
            }
            var files = 0
            val sidecars = ArrayList<Path>()
            Files.walk(directory).use { stream ->
                for (path in stream) {
                    if (!Files.isRegularFile(path)) {
                        continue
                    }
                    if (path.name != SourceTree.INDEX_FILE) {
                        files++
                    }
                    if (SourceFiles.isPristine(path.name)) {
                        sidecars.add(path)
                    }
                }
            }
            return Walk(files, sidecars)
        }

        private fun gamevals(tree: SourceTree): Int {
            if (!Files.isDirectory(tree.gamevals)) {
                return -1
            }
            Files.list(tree.gamevals).use { stream ->
                return stream.filter { it.name.endsWith(CATALOG_EXTENSION) }.count().toInt()
            }
        }

        private const val CATALOG_EXTENSION = ".json"
    }
}
