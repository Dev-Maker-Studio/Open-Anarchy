package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Index

object MapCodecs {

    fun register() {
        SourceCodecs.builtin(Index.MAPS, MapCodec)
    }
}
