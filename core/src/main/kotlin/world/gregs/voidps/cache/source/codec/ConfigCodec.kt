package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.source.ArchiveMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object ConfigCodec : SourceCodec {

    private val names = mapOf(
        Config.FLOOR_UNDERLAY to "underlay",
        Config.IDENTITY_KIT to "idk",
        Config.FLOOR_OVERLAY to "overlay",
        Config.INVENTORIES to "inv",
        Config.PARAMS to "param",
        Config.SEQUENCES to "seq",
        Config.VARC_STRINGS to "varcstr",
        Config.VARP to "varp",
        Config.VARC to "varc",
        Config.STRUCTS to "struct",
        Config.SKY_BOXES to "skybox",
        Config.LIGHT_INTENSITY to "light",
        Config.RENDER_ANIMATIONS to "bas",
        Config.CURSORS to "cursor",
        Config.MAP_SCENES to "mapscene",
        Config.QUESTS to "quest",
        Config.WORLD_MAP_INFO to "mapelement",
        Config.HIT_SPLATS to "hitmark",
        Config.VAR_CLAN to "varclan",
        Config.VAR_CLAN_SETTINGS to "varclansetting",
        Config.VAR_PLAYER to "varplayer",
        Config.VAR_NPC to "varnpc",
        Config.VAR_CLIENT to "varclient",
        Config.VAR_BIT to "varbit",
        Config.HIT_BARS to "headbar",
        Config.ANIM_FLOW_CONTROL to "animflowcontrol",
        Config.VAR_GROUP to "vargroup"
    )

    private val archives = HashMap<String, Int>(names.size * 2).also { map ->
        for ((archive, name) in names) {
            map[name] = archive
        }
    }

    private val codecs = ConcurrentHashMap<Int, SourceCodec>()

    override val id: String
        get() = "config"

    fun name(archive: Int): String = names[archive] ?: "$UNNAMED_PREFIX$archive"

    fun archive(name: String): Int {
        archives[name]?.let { return it }
        if (!name.startsWith(UNNAMED_PREFIX)) {
            return -1
        }
        return name.substring(UNNAMED_PREFIX.length).toIntOrNull() ?: -1
    }

    fun register(archive: Int, codec: SourceCodec) {
        codecs[archive] = codec
    }

    fun codec(archive: Int): SourceCodec = codecs[archive] ?: RawFilesCodec.FLAT

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

    const val UNNAMED_PREFIX = "archive_"
}
