package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.definition.decoder.ModelDecoder
import world.gregs.voidps.cache.definition.encoder.ModelEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object ModelCodec : SourceCodec {

    const val EXTENSION = ".glb"

    override val id: String
        get() = "gltf-model"

    override fun exact(archive: Int): Boolean = false

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one model per archive."
        }
        val data = archive.files[0]
        val name = "${archive.archive}$EXTENSION"
        val glb = ModelGltf.write(ModelDecoder.read(archive.archive, data))
        if (compile(glb, archive.archive).contentEquals(data)) {
            return UnpackedArchive(listOf(SourceFile(name, glb)))
        }
        return UnpackedArchive(
            listOf(SourceFile(name, glb), SourceFile(SourceFiles.pristine(name), data)),
            mapOf(archive.fileIds[0] to SourceFiles.sha256(glb))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            throw IOException("Model $archive has no glb at ${path.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Model $archive is one file on disk but its metadata lists ${ids.size}." }
        val glb = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(glb) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(compile(glb, archive), ids)
    }

    override fun archiveOf(path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        if (!editable.endsWith(EXTENSION) || editable.contains('/')) {
            return null
        }
        return editable.dropLast(EXTENSION.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(path)
        return if (Files.isRegularFile(sidecar)) listOf(path, sidecar) else listOf(path)
    }

    private fun compile(glb: ByteArray, archive: Int): ByteArray =
        ModelEncoder.encode(ModelGltf.read(glb, archive))
}
