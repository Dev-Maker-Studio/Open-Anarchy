package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.MaterialDefinition
import world.gregs.voidps.cache.definition.decoder.MaterialDecoder
import world.gregs.voidps.cache.definition.encoder.MaterialEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object MaterialCodec : SourceCodec {

    private val decoder = MaterialDecoder()

    private val encoder = MaterialEncoder()

    private val json by lazy { DefinitionJson.of(MaterialDefinition::class.java) }

    override val id: String
        get() = "json-material"

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "the materials are one blob."
        }
        val materials = decode(archive.files[0])
        val files = ArrayList<SourceFile>(materials.size + 1)
        for (material in materials) {
            files.add(SourceFile("${material.id}$EXTENSION", json.write(material)))
        }
        if (compile(files).contentEquals(archive.files[0])) {
            return UnpackedArchive(files)
        }
        val guard = guard(files)
        return UnpackedArchive(
            files + SourceFile(SourceFiles.pristine(PRISTINE), archive.files[0]),
            mapOf(archive.fileIds[0] to guard)
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one blob but its metadata lists ${ids.size} files." }
        val files = read(directory)
        val sidecar = directory.resolve(SourceFiles.pristine(PRISTINE))
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (guard(files) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(compile(files), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (path.contains('/')) {
            return null
        }
        val name = SourceFiles.guarded(path) ?: path
        if (name == PRISTINE) {
            return ARCHIVE
        }
        if (!name.endsWith(EXTENSION) || name.dropLast(EXTENSION.length).toIntOrNull() == null) {
            return null
        }
        return ARCHIVE
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val files = ArrayList<Path>()
        for (id in ids(directory)) {
            files.add(directory.resolve("$id$EXTENSION"))
        }
        val sidecar = directory.resolve(SourceFiles.pristine(PRISTINE))
        if (Files.isRegularFile(sidecar)) {
            files.add(sidecar)
        }
        return files
    }

    fun decode(data: ByteArray): Array<MaterialDefinition> {
        val reader = BufferReader(data)
        val materials = decoder.create(reader.readUnsignedShort())
        decoder.load(materials, reader)
        return materials
    }

    fun encode(materials: List<MaterialDefinition>): ByteArray {
        val writer = BufferWriter(HEADER + materials.size * ENTRY)
        with(encoder) { writer.encode(materials) }
        return writer.toArray()
    }

    private fun read(directory: Path): List<SourceFile> {
        val ids = ids(directory)
        if (ids.isEmpty()) {
            throw IOException("There are no material files in ${directory.toAbsolutePath()}.")
        }
        return ids.map { id ->
            val path = directory.resolve("$id$EXTENSION")
            if (!Files.isRegularFile(path)) {
                throw IOException("Material $id has no JSON at ${path.toAbsolutePath()}.")
            }
            SourceFile("$id$EXTENSION", Files.readAllBytes(path))
        }
    }

    private fun compile(files: List<SourceFile>): ByteArray {
        val materials = ArrayList<MaterialDefinition>(files.size)
        for (position in files.indices) {
            val file = files[position]
            val id = file.path.dropLast(EXTENSION.length).toInt()
            require(id == position) { "Material ids run 0 until the count; ${file.path} is at $position." }
            val material = MaterialDefinition(id)
            json.read(file.bytes, material)
            materials.add(material)
        }
        return encode(materials)
    }

    private fun ids(directory: Path): IntArray = DefinitionLayout.ids(directory)

    private fun guard(files: List<SourceFile>): String {
        val out = StringBuilder()
        for (file in files) {
            out.append(file.path).append(':').append(SourceFiles.sha256(file.bytes)).append('\n')
        }
        return SourceFiles.sha256(out.toString().toByteArray(Charsets.UTF_8))
    }

    private const val EXTENSION = DefinitionLayout.EXTENSION

    private const val ARCHIVE = 0

    private const val PRISTINE = "materials$EXTENSION"

    private const val HEADER = 2

    private const val ENTRY = 24
}
