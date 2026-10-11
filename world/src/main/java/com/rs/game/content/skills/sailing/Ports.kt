package com.rs.game.content.skills.sailing

import com.rs.game.World
import com.rs.game.content.world.npcs.merchant.MerchantNPC
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class PortRole { SHIPWRIGHT, PORT_MASTER }

class PortStaffNPC(id: Int, tile: Tile, val role: PortRole, val port: String) : NPC(id, tile, null, true) {
    init {
        setRandomWalk(false)
    }

    override fun canBeAttackedBy(player: Player): Boolean = false

    override fun randomWalk() {}

    override fun checkAggressivity(): Boolean = false
}

class ActivePort(val site: PortSite) {
    val objects = mutableListOf<GameObject>()
    val npcs = mutableListOf<NPC>()
    val boats = ConcurrentHashMap<Long, NPC>()
    var merchant: MerchantNPC? = null
}

private const val GROUND_LEVEL = 0
private const val CHECK_TICKS = 20
private const val ACTIVATE_RADIUS = 40
private const val DEACTIVATE_RADIUS = 140
private const val PLACE_RADIUS = 4
private const val ROTATION_WEST = 0
private const val ROTATION_NORTH = 1
private const val ROTATION_EAST = 2
private const val ROTATION_SOUTH = 3
private const val PLAZA_HALF_WIDTH = 2
private const val SHIPWRIGHT_FORWARD = -2
private const val SHIPWRIGHT_SIDE = -4
private const val PORT_MASTER_FORWARD = -2
private const val PORT_MASTER_SIDE = 4
private const val MERCHANT_FORWARD = -4
private const val MERCHANT_SIDE = 0
private const val BOARD_FORWARD = -1
private const val BOARD_SIDE = 3
private const val MOORING_SIDE = 2
private const val WARMUP_PRIORITY = Thread.MIN_PRIORITY
private const val MERCHANT_NPC = Npc.wandering_merchant_01

object Ports {
    val active = ConcurrentHashMap<String, ActivePort>()

    fun landingTile(site: PortSite): Tile {
        val used = mutableSetOf<Pair<Int, Int>>()
        return freeTile(site, 0, PLAZA_HALF_WIDTH + 1, used) ?: Tile.of(site.shoreX, site.shoreY, GROUND_LEVEL)
    }

    fun refreshBoats(site: PortSite) {
        val port = active[site.name] ?: return
        val docked = BoatStore.dockedAt(site.name).sortedBy { it.id }
        port.boats.keys.filter { id -> docked.none { it.id == id } }.forEach { id -> port.boats.remove(id)?.finish() }
        docked.forEachIndexed { slot, boat ->
            if (port.boats.containsKey(boat.id)) return@forEachIndexed
            val (x, y, heading) = site.berth(boat.type, slot)
            if (!BoatVisuals.fits(boat, x, y, heading)) return@forEachIndexed
            port.boats[boat.id] = BoatVisuals.spawn(boat, x, y, heading)
        }
    }

    private fun activate(site: PortSite) {
        val port = ActivePort(site)
        if (active.putIfAbsent(site.name, port) != null) return
        val used = mutableSetOf<Pair<Int, Int>>()
        place(port, used, Loc.sailing_shipyard_shipplank_on, ObjectShape.SCENERY_INTERACT, rotationToward(site.dirX, site.dirY), 0, 0)
        place(port, used, Loc.acs_battleships_playerpost, ObjectShape.SCENERY_INTERACT, rotationToward(site.dirX, site.dirY), 0, MOORING_SIDE)
        place(port, used, Loc.fai_falador_noticeboard2, ObjectShape.STRAIGHT_INSIDE_WALL_DEC, rotationToward(-site.dirX, -site.dirY), BOARD_FORWARD, BOARD_SIDE)
        for (side in -PLAZA_HALF_WIDTH..PLAZA_HALF_WIDTH) decorate(port, Loc.pier2, site.x(0, side), site.y(0, side), site)
        freeTile(site, SHIPWRIGHT_FORWARD, SHIPWRIGHT_SIDE, used)?.let { port.npcs.add(PortStaffNPC(Npc.shipyardworker1, it, PortRole.SHIPWRIGHT, site.name)) }
        if (site.kind == PortKind.OCEAN) {
            freeTile(site, PORT_MASTER_FORWARD, PORT_MASTER_SIDE, used)?.let { port.npcs.add(PortStaffNPC(Npc.captain_shanks, it, PortRole.PORT_MASTER, site.name)) }
            freeTile(site, MERCHANT_FORWARD, MERCHANT_SIDE, used)?.let { port.merchant = World.spawnNPC(MERCHANT_NPC, it, true) as? MerchantNPC }
        }
        refreshBoats(site)
    }

    private fun deactivate(port: ActivePort) {
        if (active.remove(port.site.name, port).not()) return
        port.objects.forEach { World.removeObject(it) }
        port.npcs.forEach { it.finish() }
        port.boats.values.forEach { it.finish() }
        port.merchant?.finish()
    }

    private fun place(port: ActivePort, used: MutableSet<Pair<Int, Int>>, id: Int, shape: ObjectShape, rotation: Int, forward: Int, side: Int) {
        val tile = freeTile(port.site, forward, side, used) ?: return
        val obj = GameObject(id, shape, rotation, tile)
        World.spawnObject(obj)
        port.objects.add(obj)
    }

    private fun decorate(port: ActivePort, id: Int, x: Int, y: Int, site: PortSite) {
        if (!PortSites.isLand(x, y)) return
        val obj = GameObject(id, ObjectShape.GROUND_DECORATION, if (site.dirX != 0) 0 else 1, Tile.of(x, y, GROUND_LEVEL))
        World.spawnObject(obj, false)
        port.objects.add(obj)
    }

    private fun freeTile(site: PortSite, forward: Int, side: Int, used: MutableSet<Pair<Int, Int>>): Tile? {
        for (radius in 0..PLACE_RADIUS) for (df in -radius..radius) for (ds in -radius..radius) {
            if (maxOf(kotlin.math.abs(df), kotlin.math.abs(ds)) != radius) continue
            val x = site.x(forward + df, side + ds)
            val y = site.y(forward + df, side + ds)
            if (!PortSites.isLand(x, y) || GeneratedWorld.onRoad(x, y) || !World.floorFree(GROUND_LEVEL, x, y) || !used.add(x to y)) continue
            return Tile.of(x, y, GROUND_LEVEL)
        }
        return null
    }

    private fun rotationToward(dx: Int, dy: Int): Int = when {
        dx > 0 -> ROTATION_EAST
        dx < 0 -> ROTATION_WEST
        dy > 0 -> ROTATION_NORTH
        else -> ROTATION_SOUTH
    }

    fun tick() {
        val players = World.players.filter { it.hasStarted() && !it.hasFinished() && it.plane == GROUND_LEVEL }
        players.forEach { player -> PortSites.near(player.x, player.y, ACTIVATE_RADIUS).forEach(::activate) }
        active.values.toList().forEach { port ->
            if (players.none { maxOf(kotlin.math.abs(it.x - port.site.shoreX), kotlin.math.abs(it.y - port.site.shoreY)) <= DEACTIVATE_RADIUS }) deactivate(port)
        }
    }

    fun warmUp() {
        thread(name = "port-warmup", isDaemon = true) {
            Thread.currentThread().priority = WARMUP_PRIORITY
            runCatching {
                for (cy in 0 until PortSites.CELLS) for (cx in 0 until PortSites.CELLS) PortSites.siteIn(cx, cy)
            }
        }
    }
}

@ServerStartupEvent
fun startPorts() {
    if (!EnvVars.generateMaps) return
    Ports.warmUp()
    WorldTasks.scheduleTimer(CHECK_TICKS, CHECK_TICKS) {
        runCatching { Ports.tick() }
        true
    }
}
