package world.gregs.voidps.cache

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.data.VarBitDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.EnumDecoder
import world.gregs.voidps.cache.definition.decoder.GraphicDecoder
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.StructDecoder
import world.gregs.voidps.cache.definition.decoder.VarBitDecoder
import world.gregs.voidps.cache.secure.Huffman

interface Cache {

    val versionTable: ByteArray

    fun indexCount(): Int

    fun indices(): IntArray

    fun indexCrcs(): IntArray

    fun sector(index: Int, archive: Int): ByteArray?

    fun archives(index: Int): IntArray

    fun archiveCount(index: Int): Int

    fun lastArchiveId(indexId: Int): Int

    fun archiveId(index: Int, hash: Int): Int

    fun archiveId(index: Int, name: String): Int = archiveId(index, name.hashCode())

    fun files(index: Int, archive: Int): IntArray

    fun fileCount(indexId: Int, archiveId: Int): Int

    fun lastFileId(indexId: Int, archive: Int): Int

    fun data(index: Int, archive: Int, file: Int = 0, xtea: IntArray? = null): ByteArray?

    fun data(index: Int, name: String, xtea: IntArray? = null) = data(index, archiveId(index, name), xtea = xtea)

    fun write(index: Int, archive: Int, file: Int, data: ByteArray, xteas: IntArray? = null)

    fun write(index: Int, archive: String, data: ByteArray, xteas: IntArray? = null)

    fun update(): Boolean

    fun close()

    companion object {
        private val singleton: Cache by lazy {
            if (EnvVars.memCache) MemoryCache.load() else FileCache.load()
        }

        private val _huffman: Huffman by lazy {
            Huffman().load(singleton.data(Index.HUFFMAN, 1)!!)
        }

        val varbits: Array<VarBitDefinition> by lazy {
            VarBitDecoder().load(singleton)
        }

        val objects: Array<ObjectDefinition> by lazy {
            ObjectDecoder(EnvVars.members).load(singleton)
        }

        val items: Array<ItemDefinition> by lazy {
            ItemDecoder().load(singleton)
        }

        val npcs: Array<NPCDefinition> by lazy {
            NPCDecoder(EnvVars.members).load(singleton)
        }

        val animations: Array<AnimationDefinition> by lazy {
            AnimationDecoder().load(singleton)
        }

        val enums: Array<EnumDefinition> by lazy {
            EnumDecoder().load(singleton)
        }

        val structs: Array<StructDefinition> by lazy {
            StructDecoder().load(singleton)
        }

        val graphics: Array<GraphicDefinition> by lazy {
            GraphicDecoder().load(singleton)
        }

        val bas: Array<BASDefinition> by lazy {
            BASDecoder().load(singleton)
        }

        val interfaces: Array<InterfaceDefinition> by lazy {
            InterfaceDecoder().load(singleton)
        }

        fun get() = singleton

        /** Legacy init method. Triggers lazy initialization of the cache. */
        @JvmStatic
        fun init(cachePath: String) {
            // The cachePath is already set via EnvVars.cachePath
            // Just trigger lazy initialization
            singleton
        }

        val huffman: Huffman get() = _huffman
    }
}