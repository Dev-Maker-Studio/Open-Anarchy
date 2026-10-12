package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardType
import world.gregs.voidps.cache.definition.data.ParticleDefinition
import world.gregs.voidps.cache.definition.data.ParticleProducerDefinition
import world.gregs.voidps.cache.definition.decoder.BillboardDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleDecoder
import world.gregs.voidps.cache.definition.decoder.ParticleProducerDecoder
import world.gregs.voidps.cache.definition.encoder.BillboardEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleEncoder
import world.gregs.voidps.cache.definition.encoder.ParticleProducerEncoder
import world.gregs.voidps.cache.source.ArchiveMetadata
import java.nio.file.Files
import java.nio.file.Path

object ModelCodecs {

    private const val BILLBOARD_BITS = 9

    const val PRODUCER_DIRECTORY = "producer"

    const val PARTICLE_DIRECTORY = "particle"

    val billboards: DefinitionJsonCodec<BillboardType> = run {
        val decoder = BillboardDecoder()
        val encoder = BillboardEncoder()
        DefinitionJsonCodec(
            id = "json-billboard",
            layout = DefinitionLayout.Packed(BILLBOARD_BITS),
            create = { BillboardType(id = it) },
            decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    val producers: DefinitionJsonCodec<ParticleProducerDefinition> = run {
        val decoder = ParticleProducerDecoder()
        val encoder = ParticleProducerEncoder()
        DefinitionJsonCodec(
            id = "json-particle-producer",
            layout = DefinitionLayout.Flat,
            create = { ParticleProducerDefinition(id = it) },
            decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    val particles: DefinitionJsonCodec<ParticleDefinition> = run {
        val decoder = ParticleDecoder()
        val encoder = ParticleEncoder()
        DefinitionJsonCodec(
            id = "json-particle",
            layout = DefinitionLayout.Flat,
            create = { ParticleDefinition(id = it) },
            decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
            encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
        )
    }

    fun register() {
        SourceCodecs.builtin(Index.MODELS, ModelCodec)
        SourceCodecs.builtin(Index.PARTICLES, ParticleCodec)
        SourceCodecs.builtin(Index.BILLBOARDS, billboards)
    }
}

object ParticleCodec : SourceCodec {

    override val id: String
        get() = "particle"

    fun name(archive: Int): String = when (archive) {
        ParticleProducerDecoder.ARCHIVE -> ModelCodecs.PRODUCER_DIRECTORY
        ParticleDecoder.ARCHIVE -> ModelCodecs.PARTICLE_DIRECTORY
        else -> "${ConfigCodec.UNNAMED_PREFIX}$archive"
    }

    fun archive(name: String): Int = when (name) {
        ModelCodecs.PRODUCER_DIRECTORY -> ParticleProducerDecoder.ARCHIVE
        ModelCodecs.PARTICLE_DIRECTORY -> ParticleDecoder.ARCHIVE
        else -> if (!name.startsWith(ConfigCodec.UNNAMED_PREFIX)) {
            -1
        } else {
            name.substring(ConfigCodec.UNNAMED_PREFIX.length).toIntOrNull() ?: -1
        }
    }

    fun codec(archive: Int): SourceCodec = when (archive) {
        ParticleProducerDecoder.ARCHIVE -> ModelCodecs.producers
        ParticleDecoder.ARCHIVE -> ModelCodecs.particles
        else -> RawFilesCodec.FLAT
    }

    override fun exact(archive: Int): Boolean = codec(archive).exact(archive)

    override fun fileIdsFromMetadata(archive: Int): Boolean = codec(archive).fileIdsFromMetadata(archive)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = name(archive.archive)
        val unpacked = codec(archive.archive).unpack(directory.resolve(name), archive)
        return UnpackedArchive(unpacked.files.map { SourceFile("$name/${it.path}", it.bytes) }, unpacked.pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        codec(archive).pack(directory.resolve(name(archive)), archive, metadata)

    override fun archiveOf(path: String): Int? {
        val separator = path.indexOf('/')
        if (separator <= 0) {
            return null
        }
        val archive = archive(path.substring(0, separator))
        return if (archive < 0) null else archive
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = directory.resolve(name(archive))
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return codec(archive).files(folder, archive)
    }
}
