package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.BillboardType
import java.util.concurrent.ConcurrentHashMap

object BillboardTypeLoader {

    private val cache = ConcurrentHashMap<Int, BillboardType>()

    private val decoder = BillboardDecoder()

    fun get(gameCache: Cache, billboardId: Int): BillboardType {
        return cache.computeIfAbsent(billboardId) {
            val definition = BillboardType(id = billboardId)
            val data = gameCache.data(Index.BILLBOARDS, BillboardDecoder.ARCHIVE, billboardId)
            if (data != null) {
                decoder.readLoop(definition, BufferReader(data))
            }
            definition
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
