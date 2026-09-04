package org.darkan.core.worldlist

import org.darkan.core.EnvVars
import org.darkan.core.net.prot.dto.WorldListEntry
import org.darkan.core.net.prot.dto.WorldListSnapshot

/**
 * [localWorldId], when above 0, pins a "localhost" entry under that number so developers can reach a
 * world on their own machine through this lobby. The pinned entry is permanent: a world registering
 * under the same number cannot replace it, and a disconnect cannot remove it.
 */
class WorldList(val maxWorlds: Int, private val localWorldId: Int = EnvVars.localWorldId) {
    private val worlds = sortedMapOf<Int, World>()
    private var _revision = 10

    init {
        if (localWorldId > 0)
            worlds[localWorldId] = World(WorldMetadata(localWorldId, "localhost", EnvVars.worldPort, "Local development world", Country.USA, false, true, true, true, false))
        updateIndices()
    }

    private fun isPinned(number: Int) = localWorldId > 0 && number == localWorldId

    val revision
        get() = _revision

    fun get(number: Int): World? = synchronized(worlds) {
        worlds[number]
    }

    fun remove(number: Int): World? = synchronized(worlds) {
        if (isPinned(number)) return null
        var orig = worlds.remove(number)
        if (orig != null) {
            updateIndices()
            _revision++
        }
        return orig
    }

    fun put(world: World) {
        synchronized(worlds) {
            if (isPinned(world.metadata.number)) return
            worlds[world.metadata.number] = world
            updateIndices()
            _revision++
        }
    }

    /**
     * Takes an immutable, wire-shaped copy of the list for the world list packet.
     *
     * Snapshotting under the lock keeps the entries, [maxWorlds] and [revision] mutually
     * consistent: the packet is encoded on the writer coroutine well after it was queued, and a
     * world registering in between would otherwise leave the payload describing a list that never
     * existed.
     */
    fun snapshot(): WorldListSnapshot = synchronized(worlds) {
        WorldListSnapshot(
            entries = worlds.values.map { world ->
                val meta = world.metadata
                WorldListEntry(
                    number = meta.number,
                    index = world.index,
                    countryId = meta.country.id,
                    activity = meta.activity,
                    address = meta.ipAddress,
                    port = meta.port,
                    members = meta.members,
                    quickChat = meta.quickchat,
                    botsAllowed = meta.botsAllowed,
                    lootShare = meta.lootShare,
                    highlighted = meta.highlighted,
                    playersOnline = if (world.offline) -1 else world.playersOnline,
                )
            },
            maxWorlds = maxWorlds,
            revision = _revision,
        )
    }

    fun getWorldArray(): Array<World> = synchronized(worlds) {
        val arr = arrayOfNulls<World>(worlds.size)
        var i = 0
        for (key in worlds.keys) {
            arr[i++] = worlds[key]
        }
        return arr.filterNotNull().toTypedArray()
    }

    fun getDefault(): World? = synchronized(worlds) {
        worlds.firstEntry()?.value
    }

    private fun updateIndices() {
        var index = 0
        for (key in worlds.keys)
            worlds[key]?.index = index++
    }
}
