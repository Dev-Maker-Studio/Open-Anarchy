package org.darkan.core.worldlist

import org.darkan.core.EnvVars
import org.darkan.core.net.prot.dto.WorldListEntry
import org.darkan.core.net.prot.dto.WorldListSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class WorldList(val maxWorlds: Int, private val localWorldId: Int = EnvVars.localWorldId, private val offlineTtl: Duration = OFFLINE_TTL) {
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

    fun markOffline(number: Int): World? = synchronized(worlds) {
        if (isPinned(number)) return null
        val world = worlds[number] ?: return null
        if (!world.offline) {
            world.offline = true
            world.playersOnline = -1
            _revision++
        }
        return world
    }

    fun expireOffline(): List<World> = synchronized(worlds) {
        val expired = worlds.values.filter { it.offline && it.timeOffline >= offlineTtl.inWholeMilliseconds && !isPinned(it.metadata.number) }
        if (expired.isNotEmpty()) {
            expired.forEach { worlds.remove(it.metadata.number) }
            updateIndices()
            _revision++
        }
        return expired
    }

    fun put(world: World) {
        synchronized(worlds) {
            if (isPinned(world.metadata.number)) return
            worlds[world.metadata.number] = world
            updateIndices()
            _revision++
        }
    }

    fun snapshot(): WorldListSnapshot = synchronized(worlds) {
        val advertisedMaxWorld = if (worlds.isEmpty()) maxWorlds else maxOf(maxWorlds, worlds.lastKey())
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
                    customCache = world.customCache,
                )
            },
            maxWorlds = advertisedMaxWorld,
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

    companion object {
        val OFFLINE_TTL = 12.hours
    }
}
