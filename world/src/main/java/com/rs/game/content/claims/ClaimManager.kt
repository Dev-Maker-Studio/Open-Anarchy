package com.rs.game.content.claims

import com.rs.engine.command.Commands
import com.rs.game.World
import com.rs.game.content.skills.sailing.PortSites
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import org.darkan.core.EnvVars
import org.darkan.core.JsonFileManager
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Varp
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import org.darkan.core.model.ObjectShape
import org.darkan.core.model.Rights
import world.gregs.voidps.type.Tile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ClaimManager {
    const val STARTING_TILES = 100

    private const val NORTH = 1
    private const val EAST = 2
    private const val SOUTH = 4
    private const val WEST = 8
    private const val STARTUP_DELAY_TICKS = 5
    private const val MODE_ON = 1
    private const val SELECTED = 2
    private const val CONSTRUCT = 4
    private const val DESTROY = 8
    private const val PVP = 16
    private const val LODESTONE_MESSAGE = "The land around a lodestone can't be claimed or built on."
    private const val PORT_MESSAGE = "The land around a port can't be claimed or built on."
    private const val ROAD_MESSAGE = "Nothing can be built on the road."
    private val LEGACY_FILE = File("./data/claims.json")
    private val DESTROY_OPTIONS = setOf("chop down", "chop", "mine", "cut", "cut down", "pick", "pick-up", "pick up", "smash", "break", "burn", "destroy", "dismantle", "demolish", "remove", "dig", "rake", "harvest", "clear", "pull")
    private val BORDER_BY_MASK = intArrayOf(
        -1,
        Loc.claim_border_n, Loc.claim_border_e, Loc.claim_border_ne, Loc.claim_border_s, Loc.claim_border_ns, Loc.claim_border_es, Loc.claim_border_nes,
        Loc.claim_border_w, Loc.claim_border_nw, Loc.claim_border_ew, Loc.claim_border_new, Loc.claim_border_sw, Loc.claim_border_nsw, Loc.claim_border_esw, Loc.claim_border_nesw
    )

    private val claims = ArrayList<Claim>()
    private val borders = HashMap<Int, List<GameObject>>()
    private val sessions = HashMap<String, Session>()
    private val selected = HashMap<String, Int>()
    private val pendingDelete = HashMap<String, Int>()
    private val appliedPvp = HashMap<String, Boolean?>()
    private var nextId = 1

    private class Session {
        var start: Tile? = null
        var moving: Claim? = null
        var corner: Pair<Int, Int>? = null
        var highlight: GameObject? = null
    }

    @JvmStatic
    fun claimAt(tile: Tile): Claim? = claims.firstOrNull { it.contains(tile) }

    fun all(): List<Claim> = claims

    @JvmStatic
    fun canBuild(player: Player, tile: Tile, notify: Boolean = true): Boolean {
        if (GeneratedWorld.onRoad(tile.x, tile.y)) {
            if (notify)
                player.sendMessage(ROAD_MESSAGE)
            return false
        }
        if (GeneratedWorld.inLodestoneZone(tile.x, tile.y)) {
            if (notify)
                player.sendMessage(LODESTONE_MESSAGE)
            return false
        }
        if (PortSites.inZone(tile.x, tile.y)) {
            if (notify)
                player.sendMessage(PORT_MESSAGE)
            return false
        }
        val claim = claimAt(tile) ?: return true
        if (claim.owner == player.username || claim.allowConstruct)
            return true
        if (notify)
            player.sendMessage("This land is claimed by ${claim.owner}; you can't build here.")
        return false
    }

    /** Construction pieces are only made, placed and picked up on land claimed by the player, or by someone who allows building. */
    @JvmStatic
    fun canConstruct(player: Player, tile: Tile, notify: Boolean = true): Boolean {
        if (GeneratedWorld.onRoad(tile.x, tile.y)) {
            if (notify)
                player.sendMessage(ROAD_MESSAGE)
            return false
        }
        if (GeneratedWorld.inLodestoneZone(tile.x, tile.y)) {
            if (notify)
                player.sendMessage(LODESTONE_MESSAGE)
            return false
        }
        if (PortSites.inZone(tile.x, tile.y)) {
            if (notify)
                player.sendMessage(PORT_MESSAGE)
            return false
        }
        val claim = claimAt(tile)
        if (claim != null && (claim.owner == player.username || claim.allowConstruct))
            return true
        if (notify)
            player.sendMessage(if (claim == null) "You can only do that on land you have claimed." else "This land is claimed by ${claim.owner}; you can't build here.")
        return false
    }

    @JvmStatic
    fun canDestroy(player: Player, tile: Tile, notify: Boolean = true): Boolean {
        val claim = claimAt(tile) ?: return true
        if (claim.owner == player.username || claim.allowDestroy)
            return true
        if (notify)
            player.sendMessage("This land is claimed by ${claim.owner}; you can't destroy things here.")
        return false
    }

    @JvmStatic
    fun allowObjectOption(player: Player, obj: GameObject, opNum: Int): Boolean {
        val option = obj.getDefinitions(player).getOption(opNum)?.lowercase() ?: return true
        return option !in DESTROY_OPTIONS || canDestroy(player, obj.tile)
    }

    @JvmStatic
    fun pvpOverride(tile: Tile): Boolean? = if (GeneratedWorld.onRoad(tile.x, tile.y)) false else claimAt(tile)?.allowPvp

    @JvmStatic
    fun refreshPvp(player: Player) {
        val state = pvpOverride(player.tile)
        if (appliedPvp[player.username] == state && appliedPvp.containsKey(player.username))
            return
        appliedPvp[player.username] = state
        player.setPlayerOption(if (player.isCanPvp) "Attack" else "null", 1, false)
        player.appearance.generateAppearanceData()
    }

    @JvmStatic
    fun inClaimMode(player: Player): Boolean = sessions.containsKey(player.username)

    private fun ownedTiles(owner: String, ignoring: Claim? = null): Int =
        claims.filter { it.owner == owner && it !== ignoring }.sumOf { it.tileCount }

    private fun toggleMode(player: Player) {
        if (sessions.remove(player.username)?.also { clearSelection(player, it) } != null) {
            player.sendMessage("Claim mode off.")
            pushState(player)
            return
        }
        sessions[player.username] = Session()
        player.sendMessage("Claim mode on. Click the ground to pick a first corner, then click again to finish the claim. Click the edge of your claim to move a corner, or inside it to select it.")
        pushState(player)
    }

    private fun clearSelection(player: Player, session: Session) {
        session.highlight?.let { player.packets.sendRemoveObject(it) }
        session.highlight = null
        session.start = null
        session.moving = null
        session.corner = null
    }

    private fun showHighlight(player: Player, session: Session, tile: Tile) {
        session.highlight?.let { player.packets.sendRemoveObject(it) }
        val marker = GameObject(Loc.claim_highlight, ObjectShape.GROUND_DECORATION, 0, tile)
        session.highlight = marker
        player.packets.sendAddObject(marker)
    }

    @JvmStatic
    fun handleClick(player: Player, tile: Tile): Boolean {
        val session = sessions[player.username] ?: return false
        val moving = session.moving
        val start = session.start
        if (moving != null) {
            val corner = session.corner!!
            val fixedX = if (corner.first == moving.minX) moving.maxX else moving.minX
            val fixedY = if (corner.second == moving.minY) moving.maxY else moving.minY
            finish(player, session, tile, Tile.of(fixedX, fixedY, moving.plane), moving)
            return true
        }
        if (start != null) {
            finish(player, session, start, tile, null)
            return true
        }
        val existing = claimAt(tile)
        when {
            existing == null && GeneratedWorld.onRoad(tile.x, tile.y) -> player.sendMessage("Roads can't be claimed.")
            existing == null && GeneratedWorld.inLodestoneZone(tile.x, tile.y) -> player.sendMessage(LODESTONE_MESSAGE)
            existing == null && PortSites.inZone(tile.x, tile.y) -> player.sendMessage(PORT_MESSAGE)
            existing == null -> {
                session.start = tile
                showHighlight(player, session, tile)
                player.sendMessage("First corner selected. Click the opposite corner to claim.")
            }
            existing.owner != player.username -> player.sendMessage("This land is claimed by ${existing.owner}.")
            existing.isEdge(tile) -> {
                session.moving = existing
                session.corner = existing.nearestCorner(tile)
                showHighlight(player, session, Tile.of(session.corner!!.first, session.corner!!.second, existing.plane))
                player.sendMessage("Corner selected. Click where the corner should go.")
            }
            else -> select(player, existing)
        }
        return true
    }

    private fun finish(player: Player, session: Session, a: Tile, b: Tile, editing: Claim?) {
        clearSelection(player, session)
        if (a.plane != b.plane) {
            player.sendMessage("A claim has to stay on one level.")
            return
        }
        val candidate = Claim(
            id = editing?.id ?: nextId,
            owner = player.username,
            plane = a.plane,
            minX = minOf(a.x, b.x),
            minY = minOf(a.y, b.y),
            maxX = maxOf(a.x, b.x),
            maxY = maxOf(a.y, b.y),
            allowConstruct = editing?.allowConstruct ?: false,
            allowDestroy = editing?.allowDestroy ?: false,
            allowPvp = editing?.allowPvp ?: false
        )
        if (ownedTiles(player.username, editing) + candidate.tileCount > player.claimTileAllowance) {
            player.sendMessage("You can claim at most ${player.claimTileAllowance} tiles; that would make ${ownedTiles(player.username, editing) + candidate.tileCount}.")
            return
        }
        if (GeneratedWorld.roadTouches(candidate.minX, candidate.minY, candidate.maxX, candidate.maxY)) {
            player.sendMessage("Roads can't be claimed; that claim would cover one.")
            return
        }
        if (GeneratedWorld.lodestoneZoneTouches(candidate.minX, candidate.minY, candidate.maxX, candidate.maxY)) {
            player.sendMessage("That claim would cover land around a lodestone, which can't be claimed.")
            return
        }
        if (PortSites.zoneTouches(candidate.minX, candidate.minY, candidate.maxX, candidate.maxY)) {
            player.sendMessage("That claim would cover land around a port, which can't be claimed.")
            return
        }
        if (claims.any { it !== editing && it.overlaps(candidate) }) {
            player.sendMessage("That overlaps another claim.")
            return
        }
        if (editing == null) {
            nextId++
            claims.add(candidate)
        } else {
            clearBorder(editing)
            editing.minX = candidate.minX
            editing.minY = candidate.minY
            editing.maxX = candidate.maxX
            editing.maxY = candidate.maxY
        }
        val result = editing ?: candidate
        drawBorder(result)
        save()
        player.sendMessage("Claim saved: ${result.width} x ${result.height} (${result.tileCount} tiles).")
        selected[player.username] = result.id
        pushState(player)
    }

    private fun drawBorder(claim: Claim) {
        val objects = ArrayList<GameObject>()
        for (x in claim.minX..claim.maxX)
            for (y in claim.minY..claim.maxY) {
                var mask = 0
                if (y == claim.maxY) mask = mask or NORTH
                if (x == claim.maxX) mask = mask or EAST
                if (y == claim.minY) mask = mask or SOUTH
                if (x == claim.minX) mask = mask or WEST
                if (mask == 0)
                    continue
                val marker = GameObject(BORDER_BY_MASK[mask], ObjectShape.GROUND_DECORATION, 0, Tile.of(x, y, claim.plane))
                World.spawnObject(marker, false)
                objects.add(marker)
            }
        borders[claim.id] = objects
    }

    private fun clearBorder(claim: Claim) {
        borders.remove(claim.id)?.forEach { World.removeObject(it) }
    }

    private fun select(player: Player, claim: Claim) {
        selected[player.username] = claim.id
        player.sendMessage("Claim selected. Use the Claims tab to change its permissions.")
        pushState(player)
    }

    private fun deleteClaim(player: Player, claim: Claim) {
        if (pendingDelete[player.username] != claim.id) {
            pendingDelete[player.username] = claim.id
            player.sendMessage("Click Delete claim again to confirm. This removes the ${claim.tileCount} tile claim for good.")
            return
        }
        pendingDelete.remove(player.username)
        clearBorder(claim)
        claims.remove(claim)
        selected.remove(player.username)
        save()
        player.sendMessage("Claim deleted.")
        pushState(player)
    }

    private fun selectedClaim(player: Player): Claim? =
        claims.firstOrNull { it.id == selected[player.username] && it.owner == player.username }

    @JvmStatic
    fun pushState(player: Player) {
        val claim = selectedClaim(player)
        var state = 0
        if (sessions.containsKey(player.username))
            state = state or MODE_ON
        if (claim != null) {
            state = state or SELECTED
            if (claim.allowConstruct) state = state or CONSTRUCT
            if (claim.allowDestroy) state = state or DESTROY
            if (claim.allowPvp) state = state or PVP
        }
        player.vars.setVar(Varp.claim_state, state)
        player.vars.setVar(Varp.claim_tiles, ownedTiles(player.username))
    }

    private val file: File get() = File(WorldDelta.DIRECTORY, "sector-${EnvVars.worldId}-claims.json")

    private fun save() {
        val target = file
        val temporary = File(target.parentFile, "${target.name}.tmp")
        JsonFileManager.saveJsonFile(claims, temporary)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load() {
        val migrating = !file.exists() && LEGACY_FILE.exists() && GeneratedWorld.windowX == WorldWindow.CENTER_SECTOR_X && GeneratedWorld.windowY == WorldWindow.CENTER_SECTOR_Y
        val source = if (migrating) LEGACY_FILE else file
        val stored = JsonFileManager.loadJsonFile(source, Array<Claim>::class.java) ?: return
        claims.addAll(stored)
        nextId = (claims.maxOfOrNull { it.id } ?: 0) + 1
        if (migrating)
            save()
        WorldTasks.schedule(STARTUP_DELAY_TICKS, Runnable { claims.forEach { drawBorder(it) } })
    }

    @ServerStartupEvent
    @JvmStatic
    fun init() {
        load()
        Commands.add(Rights.PLAYER, "claim", "Toggles claim mode.") { p, _ -> toggleMode(p) }
        onButtonClick(Interface.claim_tab) { e ->
            val player = e.player
            if (e.component == Component.claim_tab.toggle_mode) {
                toggleMode(player)
                return@onButtonClick
            }
            val claim = selectedClaim(player) ?: return@onButtonClick
            if (e.component == Component.claim_tab.delete_claim) {
                deleteClaim(player, claim)
                return@onButtonClick
            }
            pendingDelete.remove(player.username)
            when (e.component) {
                Component.claim_tab.construct_toggle -> claim.allowConstruct = !claim.allowConstruct
                Component.claim_tab.destroy_toggle -> claim.allowDestroy = !claim.allowDestroy
                Component.claim_tab.pvp_toggle -> claim.allowPvp = !claim.allowPvp
                else -> return@onButtonClick
            }
            save()
            pushState(player)
        }
    }
}
