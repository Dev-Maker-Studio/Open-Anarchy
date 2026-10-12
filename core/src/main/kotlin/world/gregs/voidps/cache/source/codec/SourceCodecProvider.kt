package world.gregs.voidps.cache.source.codec

interface SourceCodecProvider {

    fun codecs(): Map<Int, SourceCodec>
}
