package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Index
import java.util.concurrent.ConcurrentHashMap

/**
 * Which codec owns which index.
 *
 * The defaults below are the raw ones - what a source tree falls back to for an index nothing
 * better has been written for. A module that has something better registers it before the tree
 * is read or written; the `cs2` module claims index 12, and each typed definition codec claims
 * its own index the same way. Registration is a process-wide decision, so it belongs in a
 * module's initialisation and not in the middle of a build.
 */
object SourceCodecs {

    private val codecs = ConcurrentHashMap<Int, SourceCodec>()

    /**
     * The indices with multi-file archives in 727. Everything else holds one file per archive and
     * gets [RawArchiveCodec], which says so loudly if that ever stops being true.
     */
    private val MULTI_FILE = intArrayOf(
        Index.INTERFACES,
        Index.OBJECTS,
        Index.ENUMS,
        Index.NPCS,
        Index.ITEMS,
        Index.ANIMATIONS,
        Index.SPOTANIMS,
        Index.VAR_BIT,
        Index.WORLD_MAP,
        Index.QUICK_CHAT_MESSAGES,
        Index.QUICK_CHAT_MENUS,
        Index.PARTICLES,
        Index.BILLBOARDS,
        Index.SHADERS
    )

    init {
        reset()
    }

    /** [index]'s codec. */
    fun codec(index: Int): SourceCodec = codecs[index] ?: RawArchiveCodec

    /** Give [index] a codec, replacing whatever it had. */
    fun register(index: Int, codec: SourceCodec) {
        codecs[index] = codec
    }

    /** Every index that has been given a codec explicitly, ascending. */
    fun registered(): List<Int> = codecs.keys.sorted()

    /**
     * Put every index back to the codec it starts with. Only tests need this; a module that
     * registered a codec has to register it again afterwards.
     */
    fun reset() {
        codecs.clear()
        // Three chunks per archive that nothing can recompute, so the group is kept whole.
        codecs[Index.ANIMATION_FRAMES] = VerbatimGroupCodec
        // Forty-five unrelated definition types, one directory each.
        codecs[Index.CONFIGS] = ConfigCodec
        // Indices whose archives hold more than one file.
        for (index in MULTI_FILE) {
            codecs[index] = RawFilesCodec.NESTED
        }
    }
}
