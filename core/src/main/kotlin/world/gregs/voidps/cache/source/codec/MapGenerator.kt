package world.gregs.voidps.cache.source.codec

interface MapGenerator {

    fun terrain(regionX: Int, regionY: Int): ByteArray

    fun locations(regionX: Int, regionY: Int): ByteArray
}
