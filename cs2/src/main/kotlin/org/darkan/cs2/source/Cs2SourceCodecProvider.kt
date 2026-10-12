package org.darkan.cs2.source

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecProvider

class Cs2SourceCodecProvider : SourceCodecProvider {

    override fun codecs(): Map<Int, SourceCodec> = mapOf(Index.CLIENT_SCRIPTS to Cs2SourceCodec)
}
