package com.rs.game.map

import com.rs.game.World
import com.rs.game.map.instance.InstancedChunk
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import org.darkan.core.EnvVars
import org.darkan.core.JsonFileManager
import org.darkan.core.Logger
import org.darkan.core.model.ObjectShape
import org.darkan.core.util.MapUtils
import org.darkan.core.util.Structure
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val BUILT_FURNITURE_HP = "builtFurnitureHp"

object WorldDelta {
    private const val DIRECTORY = "./data/world-delta"
    private const val SAVE_DEBOUNCE_MS = 5000L
    private const val TICK_MS = 600L
    private const val PERSIST_MIN_TICKS = 100
    private const val MISSING = -1
    private const val COORD_BITS = 24
    private const val COORD_MASK = (1L shl COORD_BITS) - 1
    private const val PLANE_BITS = 2
    private const val SLOT_BITS = 2
    private const val TILE_BITS = 3
    private const val VERSION = 1

    class Added(
        var x: Int = 0, var y: Int = 0, var plane: Int = 0, var id: Int = 0, var shape: Int = 0, var rotation: Int = 0,
        var owner: String? = null, var hp: Int = MISSING, var growAt: Long = 0, var growId: Int = MISSING
    )

    class Removed(
        var x: Int = 0, var y: Int = 0, var plane: Int = 0, var id: Int = 0, var shape: Int = 0, var rotation: Int = 0,
        var regrowAt: Long = 0
    )

    class Timed(
        var x: Int = 0, var y: Int = 0, var plane: Int = 0, var shape: Int = 0,
        var originalId: Int = 0, var currentId: Int = 0, var regrowAt: Long = 0
    )

    private class Snapshot(
        val version: Int = VERSION, val sector: Int = 0,
        val added: List<Added> = emptyList(), val removed: List<Removed> = emptyList(), val timed: List<Timed> = emptyList()
    )

    private val tracked = HashSet<Int>()
    private val added = HashMap<Long, Added>()
    private val removed = HashMap<Long, Removed>()
    private val timed = HashMap<Long, Timed>()
    private val byChunk = HashMap<Int, MutableSet<Long>>()
    private val restoring = ThreadLocal.withInitial { false }
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "world-delta").also { thread -> thread.isDaemon = true } }
    private var pendingSave: ScheduledFuture<*>? = null
    private var loaded = false

    private val file: File get() = File(DIRECTORY, "sector-${EnvVars.worldId}.json")

    private fun enabled(chunk: Chunk?) = EnvVars.generateMaps && chunk !is InstancedChunk

    private fun enabled(obj: GameObject) = EnvVars.generateMaps && ChunkManager.findChunk(obj.tile.getChunkId()) !is InstancedChunk

    private fun key(x: Int, y: Int, plane: Int, slot: Int): Long =
        ((x.toLong() and COORD_MASK) shl (COORD_BITS + PLANE_BITS + SLOT_BITS)) or ((y.toLong() and COORD_MASK) shl (PLANE_BITS + SLOT_BITS)) or (plane.toLong() shl SLOT_BITS) or slot.toLong()

    private fun key(obj: GameObject) = key(obj.x, obj.y, obj.plane, obj.slot)

    private fun chunkOf(x: Int, y: Int, plane: Int) = MapUtils.encode(Structure.CHUNK, x shr TILE_BITS, y shr TILE_BITS, plane)

    private fun now() = System.currentTimeMillis()

    private fun ticksUntil(at: Long) = maxOf(1, ((at - now() + TICK_MS - 1) / TICK_MS).toInt())

    @JvmStatic
    fun track(vararg ids: Int) {
        synchronized(this) { ids.forEach { tracked.add(it) } }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val stored = runCatching { JsonFileManager.loadJsonFile(file, Snapshot::class.java) }.getOrNull() ?: return
            for (entry in stored.added) {
                val k = key(entry.x, entry.y, entry.plane, ObjectShape.forId(entry.shape)?.slot ?: continue)
                added[k] = entry
                byChunk.getOrPut(chunkOf(entry.x, entry.y, entry.plane)) { HashSet() }.add(k)
            }
            for (entry in stored.removed) removed[key(entry.x, entry.y, entry.plane, ObjectShape.forId(entry.shape)?.slot ?: continue)] = entry
            for (entry in stored.timed) timed[key(entry.x, entry.y, entry.plane, ObjectShape.forId(entry.shape)?.slot ?: continue)] = entry
            Logger.info(WorldDelta::class.java, "load", "Sector ${EnvVars.worldId}: ${added.size} added, ${removed.size} removed, ${timed.size} timed objects")
        }
    }

    private fun addedEntry(obj: GameObject, owner: String?) = Added(obj.x, obj.y, obj.plane, obj.id, obj.type.id, obj.rotation, owner, obj.attribs.getI(BUILT_FURNITURE_HP, MISSING))

    @JvmStatic
    fun spawned(chunk: Chunk, obj: GameObject): Boolean {
        if (!enabled(chunk)) return false
        ensureLoaded()
        val k = key(obj)
        var cleared: Boolean
        synchronized(this) {
            cleared = removed[k]?.id == obj.id
            if (cleared) removed.remove(k)
            val previous = added.remove(k)
            previous?.let { byChunk[chunkOf(it.x, it.y, it.plane)]?.remove(k) }
            if (obj.id in tracked) {
                added[k] = addedEntry(obj, previous?.owner)
                byChunk.getOrPut(chunkOf(obj.x, obj.y, obj.plane)) { HashSet() }.add(k)
                scheduleSave()
            } else if (cleared || previous != null)
                scheduleSave()
        }
        restoreTimed(obj)
        return cleared
    }

    @JvmStatic
    fun removed(chunk: Chunk, obj: GameObject, ofTheMap: Boolean) {
        if (!enabled(chunk)) return
        ensureLoaded()
        val k = key(obj)
        synchronized(this) {
            val previous = added.remove(k)
            previous?.let { byChunk[chunkOf(it.x, it.y, it.plane)]?.remove(k) }
            val timedEntry = timed.remove(k)
            if (ofTheMap)
                removed[k] = Removed(obj.x, obj.y, obj.plane, obj.id, obj.type.id, obj.rotation)
            if (ofTheMap || previous != null || timedEntry != null)
                scheduleSave()
        }
    }

    @JvmStatic
    fun regrow(obj: GameObject, ticks: Int) {
        if (!enabled(obj) || ticks < PERSIST_MIN_TICKS) return
        ensureLoaded()
        synchronized(this) {
            val entry = removed[key(obj)]?.takeIf { it.id == obj.id } ?: return
            entry.regrowAt = now() + ticks * TICK_MS
            scheduleSave()
        }
    }

    @JvmStatic
    fun hides(chunk: Chunk, obj: GameObject): Boolean {
        if (!enabled(chunk)) return false
        ensureLoaded()
        val k = key(obj)
        val entry = synchronized(this) { removed[k]?.takeIf { it.id == obj.id } } ?: return false
        if (entry.regrowAt == 0L) return true
        if (entry.regrowAt <= now()) {
            synchronized(this) { removed.remove(k) }
            scheduleSave()
            return false
        }
        WorldTasks.schedule(ticksUntil(entry.regrowAt)) {
            if (synchronized(this) { removed[k]?.id == obj.id }) World.spawnObject(obj)
        }
        return true
    }

    @JvmStatic
    fun timed(obj: GameObject, newId: Int, ticks: Int) {
        if (restoring.get() || !enabled(obj) || ticks < PERSIST_MIN_TICKS) return
        ensureLoaded()
        synchronized(this) {
            timed[key(obj)] = Timed(obj.x, obj.y, obj.plane, obj.type.id, obj.originalId, newId, now() + ticks * TICK_MS)
            scheduleSave()
        }
    }

    @JvmStatic
    fun timedCleared(obj: GameObject) {
        if (!loaded) return
        synchronized(this) {
            if (timed.isEmpty()) return
            if (timed.remove(key(obj)) != null) scheduleSave()
        }
    }

    @JvmStatic
    fun restoreTimed(obj: GameObject) {
        ensureLoaded()
        val k = key(obj)
        val entry = synchronized(this) { timed[k]?.takeIf { it.originalId == obj.id && obj.id != it.currentId } } ?: return
        if (entry.regrowAt <= now()) {
            synchronized(this) { timed.remove(k) }
            scheduleSave()
            return
        }
        restoring.set(true)
        try {
            obj.setIdTemporary(entry.currentId, ticksUntil(entry.regrowAt))
        } finally {
            restoring.set(false)
        }
    }

    @JvmStatic
    fun touch(obj: GameObject) {
        if (!EnvVars.generateMaps) return
        ensureLoaded()
        synchronized(this) {
            val entry = added[key(obj)]?.takeIf { it.id == obj.id } ?: return
            entry.hp = obj.attribs.getI(BUILT_FURNITURE_HP, MISSING)
            scheduleSave()
        }
    }

    @JvmStatic
    fun setOwner(obj: GameObject, owner: String?) {
        if (!EnvVars.generateMaps) return
        ensureLoaded()
        synchronized(this) {
            val entry = added[key(obj)]?.takeIf { it.id == obj.id } ?: return
            entry.owner = owner
            scheduleSave()
        }
    }

    @JvmStatic
    fun ownerOf(obj: GameObject): String? = synchronized(this) { added[key(obj)]?.takeIf { it.id == obj.id }?.owner }

    @JvmStatic
    fun scheduleTransform(obj: GameObject, intoId: Int, ticks: Int) {
        val k = key(obj)
        synchronized(this) {
            val entry = added[k]?.takeIf { it.id == obj.id }
            if (entry != null) {
                entry.growAt = now() + ticks * TICK_MS
                entry.growId = intoId
                scheduleSave()
            }
        }
        WorldTasks.schedule(ticks) { transform(obj.tile, obj.type, obj.id, obj.rotation, intoId) }
    }

    private fun transform(tile: world.gregs.voidps.type.Tile, shape: ObjectShape, id: Int, rotation: Int, intoId: Int) {
        if (World.getObject(tile, shape)?.id != id) return
        World.removeObject(GameObject(id, shape, rotation, tile))
        World.spawnObject(GameObject(intoId, shape, rotation, tile))
    }

    @JvmStatic
    fun apply(chunk: Chunk) {
        if (!enabled(chunk)) return
        ensureLoaded()
        val entries = synchronized(this) { byChunk[chunk.chunkId]?.mapNotNull { added[it] } ?: return }
        for (entry in entries) {
            val shape = ObjectShape.forId(entry.shape) ?: continue
            val obj = GameObject(entry.id, shape, entry.rotation, entry.x, entry.y, entry.plane)
            if (entry.hp != MISSING) obj.attribs.setI(BUILT_FURNITURE_HP, entry.hp)
            chunk.spawnObject(obj, true, false)
            val growAt = entry.growAt
            if (growAt > 0 && entry.growId != MISSING)
                WorldTasks.schedule(if (growAt <= now()) 1 else ticksUntil(growAt)) { transform(obj.tile, shape, entry.id, entry.rotation, entry.growId) }
        }
    }

    private fun scheduleSave() {
        synchronized(this) {
            if (pendingSave?.isDone == false) return
            pendingSave = writer.schedule({ saveNow() }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    @JvmStatic
    fun saveNow() {
        if (!EnvVars.generateMaps || !loaded) return
        val json: String
        synchronized(this) {
            val cutoff = now()
            removed.values.removeIf { it.regrowAt in 1..cutoff }
            timed.values.removeIf { it.regrowAt <= cutoff }
            json = JsonFileManager.toJson(Snapshot(VERSION, EnvVars.worldId, added.values.toList(), removed.values.toList(), timed.values.toList()))
        }
        runCatching {
            val target = file
            target.parentFile.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(json)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { Logger.handle(WorldDelta::class.java, "saveNow", it) }
    }
}
