package org.darkan.core.map

import org.darkan.core.map.pathfinder.getIndexInZone
import org.darkan.core.map.pathfinder.getZoneIndex

class CollisionBuffer(zoneIds: Collection<Int>) : CollisionWriter {
    private val zones = zoneIds.associateWith { IntArray(ZONE_TILES) }

    override fun add(x: Int, y: Int, level: Int, flag: Int) {
        val zone = zones[getZoneIndex(x, y, level)] ?: return
        val index = getIndexInZone(x, y)
        zone[index] = zone[index] or flag
    }

    fun flags(zoneId: Int): IntArray? = zones[zoneId]

    private companion object {
        const val ZONE_TILES = 64
    }
}
