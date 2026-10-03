package org.darkan.core.net.js5

import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.delay
import org.darkan.core.Logger.logWarn
import org.darkan.core.map.MapDiscovery
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.file.FileProvider

class DiscoveredMapFileProvider(private val delegate: FileProvider, cache: Cache) : FileProvider {

    private val squares: Map<Int, MapSquare.Square> = cache.archiveNameHashes(Index.MAPS)
        .mapNotNull { (archive, hash) -> MapSquare.of(hash)?.let { archive to it } }
        .toMap()

    override fun data(index: Int, archive: Int): ByteArray? = delegate.data(index, archive)

    override suspend fun encode(write: ByteWriteChannel, data: ByteArray) = delegate.encode(write, data)

    override suspend fun serve(write: ByteWriteChannel, ref: Long, prefetch: Boolean): Boolean {
        val index = (ref ushr 32).toInt()
        val archive = (ref and 0xFFFFFFFFL).toInt()
        if (index == Index.MAPS && !explored(archive)) {
            logWarn("Refused undiscovered map archive $archive")
            return false
        }
        return delegate.serve(write, ref, prefetch)
    }

    private suspend fun explored(archive: Int): Boolean {
        val square = squares[archive] ?: return true
        val regionId = (square.regionX shl REGION_BITS) or square.regionY
        var waited = 0L
        while (!MapDiscovery.isDiscovered(regionId)) {
            if (waited >= GRACE_MILLIS) {
                return false
            }
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        return true
    }

    private companion object {
        const val REGION_BITS = 8
        const val GRACE_MILLIS = 3000L
        const val POLL_MILLIS = 50L
    }
}
