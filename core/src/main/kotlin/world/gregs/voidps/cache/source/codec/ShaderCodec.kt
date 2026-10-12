package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.definition.data.ShaderProgramDefinition
import world.gregs.voidps.cache.definition.decoder.ShaderProgramDecoder
import world.gregs.voidps.cache.definition.encoder.ShaderProgramEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

object ShaderCodec : SourceCodec {

    private val decoder = ShaderProgramDecoder()
    private val encoder = ShaderProgramEncoder()
    private val json by lazy { DefinitionJson.of(ShaderProgramDefinition::class.java) }

    private val archives = listOf("gl", "dx").associateBy { it.hashCode() }

    override val id: String
        get() = "shaders"

    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val folder = folder(archive.metadata.nameHash, archive.archive)
        val names = recover(archive.files)
        val files = ArrayList<SourceFile>(archive.files.size + 1)
        val pristine = HashMap<Int, String>(0)
        for ((position, file) in archive.fileIds.withIndex()) {
            val data = archive.files[position]
            val name = names[archive.metadata.fileNames[file] ?: 0]
            if (name == null) {
                files.add(SourceFile("$folder/$file${RawArchiveCodec.EXTENSION}", data))
                continue
            }
            if (!decoder.isProgram(data)) {
                files.add(SourceFile("$folder/$name", data))
                continue
            }
            val path = "$folder/$name${DefinitionLayout.EXTENSION}"
            val definition = ShaderProgramDefinition()
            decoder.read(definition, data)
            val text = json.write(definition)
            files.add(SourceFile(path, text))
            if (!compile(text).contentEquals(data)) {
                files.add(SourceFile(SourceFiles.pristine(path), data))
                pristine[file] = SourceFiles.sha256(text)
            }
        }
        return UnpackedArchive(files, pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val folder = directory.resolve(folder(metadata.nameHash, archive))
        val ids = metadata.fileNames.entries.associate { (file, hash) -> hash to file }
        val contents = HashMap<Int, ByteArray>()
        for (path in list(folder)) {
            val name = path.name
            if (SourceFiles.isPristine(name)) {
                continue
            }
            val program = name.endsWith(DefinitionLayout.EXTENSION)
            val bare = if (program) name.dropLast(DefinitionLayout.EXTENSION.length) else name
            val file = fallback(bare)
                ?: ids[bare.lowercase().hashCode()]
                ?: throw IOException(
                    "No file of archive $archive is named '$bare': ${path.toAbsolutePath()} hashes to " +
                        "${bare.lowercase().hashCode()}, which index.json's fileNames does not list."
                )
            val bytes = if (program) bytes(path, file, metadata) else Files.readAllBytes(path)
            check(contents.put(file, bytes) == null) { "Archive $archive has two files named $file." }
        }
        check(contents.isNotEmpty()) { "Archive $archive has no files in ${folder.toAbsolutePath()}." }
        val order = contents.keys.sorted()
        return PackedArchive(ArchiveGroup.join(order.map { contents.getValue(it) }), order.toIntArray())
    }

    override fun archiveOf(path: String): Int? = null

    override fun archiveOf(directory: Path, path: String): Int? {
        val separator = path.indexOf('/')
        if (separator <= 0 || path.indexOf('/', separator + 1) >= 0) {
            return null
        }
        val folder = path.substring(0, separator)
        return folder.toIntOrNull() ?: ArchiveNames.archive(directory, folder.hashCode())
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val hash = ArchiveNames.nameHash(directory, archive) ?: return emptyList()
        return list(directory.resolve(folder(hash, archive)))
    }

    private fun recover(files: List<ByteArray>): Map<Int, String> {
        val names = HashMap<Int, String>()
        for (data in files) {
            if (!decoder.isProgram(data)) {
                continue
            }
            val definition = ShaderProgramDefinition()
            try {
                decoder.read(definition, data)
            } catch (e: IllegalStateException) {
                continue
            }
            add(names, definition.name)
            for (mapping in definition.mappings) {
                add(names, mapping.name)
                add(names, mapping.vertexSource)
                add(names, mapping.fragmentSource)
            }
        }
        return names
    }

    private fun add(names: MutableMap<Int, String>, name: String?) {
        if (name.isNullOrEmpty()) {
            return
        }
        val lower = name.lowercase()
        names[lower.hashCode()] = lower
    }

    private fun bytes(path: Path, file: Int, metadata: ArchiveMetadata): ByteArray {
        val text = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[file]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return Files.readAllBytes(sidecar)
            }
            Files.delete(sidecar)
        }
        return compile(text)
    }

    private fun compile(text: ByteArray): ByteArray {
        val definition = ShaderProgramDefinition()
        json.read(text, definition)
        val writer = BufferWriter(CAPACITY)
        with(encoder) { writer.encode(definition) }
        return writer.toArray()
    }

    private fun folder(hash: Int, archive: Int): String = archives[hash] ?: archive.toString()

    private fun fallback(name: String): Int? {
        if (!name.endsWith(RawArchiveCodec.EXTENSION)) {
            return null
        }
        return name.dropLast(RawArchiveCodec.EXTENSION.length).toIntOrNull()
    }

    private fun list(folder: Path): List<Path> {
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        Files.list(folder).use { stream ->
            return stream.filter { Files.isRegularFile(it) }.toList().sortedBy { it.name }
        }
    }

    private const val CAPACITY = 64 * 1024
}
