package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Index
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Which codec owns which index.
 *
 * The defaults below are the raw ones - what a source tree falls back to for an index nothing
 * better has been written for. A module that has something better claims it with a
 * [SourceCodecProvider] on the classpath - the `cs2` module claims index 12 that way - which is
 * loaded here after the built-ins, so no entry point has to remember to register anything.
 * [register] is the same decision made by hand, for a test or a tool with its own codec;
 * registration is process-wide, so it belongs in initialisation and not in the middle of a build.
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
     * Put every index back to the codec it starts with: the built-ins, then whatever the
     * [SourceCodecProvider]s on the classpath claim. Only tests need to call this; a codec
     * registered by hand with [register] has to be registered again afterwards.
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
        // A module that ships a codec claims its index by being on the classpath, so every entry
        // point - the servers, the CLI, the tests - reads and writes the same layout.
        for (provider in ServiceLoader.load(SourceCodecProvider::class.java, javaClass.classLoader)) {
            codecs.putAll(provider.codecs())
        }
    }
}
