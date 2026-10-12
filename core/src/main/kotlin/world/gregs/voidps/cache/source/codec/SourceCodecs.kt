package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Index
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

object SourceCodecs {

    private val codecs = ConcurrentHashMap<Int, SourceCodec>()

    private val explicit = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var loaded = false

    private val MULTI_FILE = intArrayOf(
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

    fun codec(index: Int): SourceCodec {
        if (!loaded) {
            load()
        }
        return codecs[index] ?: RawArchiveCodec
    }

    fun register(index: Int, codec: SourceCodec) {
        codecs[index] = codec
        explicit.add(index)
    }

    fun registered(): List<Int> {
        if (!loaded) {
            load()
        }
        return codecs.keys.sorted()
    }

    @Synchronized
    fun reset() {
        codecs.clear()
        explicit.clear()
        loaded = false
        codecs[Index.CONFIGS] = ConfigCodec
        for (index in MULTI_FILE) {
            codecs[index] = RawFilesCodec.NESTED
        }
        DefinitionCodecs.register()
        MapCodecs.register()
        ImageCodecs.register()
        MiscCodecs.register()
        AudioCodecs.register()
        ModelCodecs.register()
    }

    internal fun builtin(index: Int, codec: SourceCodec) {
        codecs[index] = codec
    }

    @Synchronized
    private fun load() {
        if (loaded) {
            return
        }
        for (provider in ServiceLoader.load(SourceCodecProvider::class.java)) {
            for ((index, codec) in provider.codecs()) {
                if (!explicit.contains(index)) {
                    codecs[index] = codec
                }
            }
        }
        loaded = true
    }
}
