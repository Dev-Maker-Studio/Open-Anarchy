package com.rs.game.content.skills.sailing

import com.rs.Settings
import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.World
import com.rs.game.content.world.revealWorldMapFog
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import world.gregs.voidps.cache.source.codec.WorldGenerator
import world.gregs.voidps.type.Tile
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

private const val GROUND_LEVEL = 0
private const val LEAVE_RANGE = 26
private const val PERSIST_TICKS = 50
private const val HAZARD_TICKS = 5
private const val CONTENT_TICKS = 10
private const val MONSTER_TICKS = 25
private const val MONSTER_SPAWN_PERCENT = 4
private const val MAX_MONSTERS = 2
private const val MONSTER_MIN_DISTANCE = 18
private const val MONSTER_MAX_DISTANCE = 32
private const val MONSTER_ATTEMPTS = 6
private const val WAGE_TICKS = 1500
private const val CREW_TRIM_DELAY = 5
private const val SICKNESS_TICKS = 100
private const val KELP_SLOW = 0.5
private const val ICE_SLOW = 0.6
private const val STORM_DRIFT = 40
private const val FULL_TURN = 360
private const val CHART_FOG_RADIUS = 128
private const val SEA_COMPLETE_XP = 500
private const val MESSAGE_GAP_TICKS = 20
private const val SALVAGE_SUCCESSES = 5
private const val EDGE_LOOKAHEAD = 3
private const val BERTH_TRIES = 4
private const val LAUNCH_SEARCH = 10

object Voyages {
    private val active = ConcurrentHashMap<String, Voyage>()
    private val random = Random()

    fun of(player: Player): Voyage? = active[player.username]

    fun all(): Collection<Voyage> = active.values

    fun sectorLocalX(globalX: Int): Int = WorldWindow.toLocal(globalX, GeneratedWorld.windowX)

    fun sectorLocalY(globalY: Int): Int = WorldWindow.toLocal(globalY, GeneratedWorld.windowY)

    private fun sailable(x: Int, y: Int): Boolean {
        val globalX = WorldWindow.toGlobal(x, GeneratedWorld.windowX)
        val globalY = WorldWindow.toGlobal(y, GeneratedWorld.windowY)
        return globalX in 0 until WorldWindow.GRID_TILES && globalY in 0 until WorldWindow.GRID_TILES && PortSites.sailable(x, y)
    }

    fun board(player: Player, site: PortSite) {
        if (active.containsKey(player.username)) return player.sendMessage("You are already aboard a boat.")
        val owned = BoatStore.ownedBy(player.username)
        if (owned.isEmpty()) return player.sendMessage("You own no boats. Speak to a Shipwright.")
        val here = owned.filter { it.dockPort == site.name && it.state == BoatState.DOCKED }
        when {
            here.isEmpty() -> player.sendMessage("None of your boats is moored here. ${whereAre(owned)}")
            here.size == 1 -> start(player, here.first(), site)
            else -> player.sendOptionsDialogue("Select a boat to board") {
                here.take(MAX_MENU).forEach { boat -> opExec("${boat.name} (${boat.type.name.lowercase()})") { start(player, boat, site) } }
            }
        }
    }

    private const val MAX_MENU = 5

    private fun whereAre(owned: List<Boat>): String =
        owned.joinToString(" ") { "${it.name}: ${if (it.dockPort.isEmpty()) "unmoored" else "port ${it.dockPort}"} (${it.state.name.lowercase()})." }

    private fun start(player: Player, boat: Boat, site: PortSite) {
        if (player.skills.getLevelForXp(Constants.SAILING) < boat.type.sailingLevel) {
            return player.sendMessage("You need Sailing level ${boat.type.sailingLevel} to sail a ${boat.type.name.lowercase()}.")
        }
        val slot = BoatStore.dockedAt(site.name).sortedBy { it.id }.indexOfFirst { it.id == boat.id }.coerceAtLeast(0)
        val (berthX, berthY, heading) = site.berth(boat.type, slot)
        val launch = launchPoint(site, boat, berthX, berthY, heading) ?: return player.sendMessage("There is no room to launch the ${boat.name} from this berth.")
        Ports.active[site.name]?.boats?.remove(boat.id)?.finish()
        begin(player, boat, launch.first, launch.second, heading)
        SailorStore.of(player.username).lastDock = site.name
        player.sendMessage("You board the ${boat.name}. Use ::boat helm to take the wheel, then steer out to sea.")
    }

    private fun launchPoint(site: PortSite, boat: Boat, x: Int, y: Int, heading: Int): Pair<Int, Int>? {
        if (BoatVisuals.fits(boat, x, y, heading)) return x to y
        return (1..LAUNCH_SEARCH).map { site.x(PortSites.BERTH_BASE + boat.type.width + it, 0) to site.y(PortSites.BERTH_BASE + boat.type.width + it, 0) }
            .firstOrNull { (px, py) -> BoatVisuals.fits(boat, px, py, heading) }
    }

    private fun begin(player: Player, boat: Boat, x: Int, y: Int, heading: Int): Voyage {
        player.stopAll()
        boat.state = BoatState.SAILING
        boat.heading = heading
        boat.globalX = WorldWindow.toGlobal(x, GeneratedWorld.windowX)
        boat.globalY = WorldWindow.toGlobal(y, GeneratedWorld.windowY)
        val movement = BoatMovement(boat, x.toDouble(), y.toDouble(), heading)
        val voyage = Voyage(player.username, boat, movement, SailTrim(boat, movement), BoatVisuals.spawn(boat, x, y, heading))
        active[player.username] = voyage
        player.tele(Tile.of(x, y, GROUND_LEVEL))
        BoatStore.save(boat)
        return voyage
    }

    fun restore(player: Player) {
        val boat = BoatStore.ownedBy(player.username).firstOrNull { it.state == BoatState.SAILING } ?: return
        if (active.containsKey(player.username)) return
        if (!WorldWindow.contains(GeneratedWorld.windowX, boat.globalX) || !WorldWindow.contains(GeneratedWorld.windowY, boat.globalY)) {
            player.sendMessage("Your boat ${boat.name} is at sea in another sector.")
            return
        }
        val x = sectorLocalX(boat.globalX)
        val y = sectorLocalY(boat.globalY)
        if (!sailable(x, y)) {
            boat.state = BoatState.DOCKED
            BoatStore.save(boat)
            return player.sendMessage("Your boat ${boat.name} drifted into harbour. Find it at port ${boat.dockPort}.")
        }
        begin(player, boat, x, y, boat.heading)
        player.sendMessage("You are back aboard the ${boat.name}.")
    }

    fun leave(player: Player) {
        val voyage = active[player.username] ?: return player.sendMessage("You are not aboard a boat.")
        val site = PortSites.nearest(voyage.tileX, voyage.tileY, LEAVE_RANGE) ?: return player.sendMessage("You can only leave your boat at a port.")
        val boat = voyage.boat
        val others = BoatStore.dockedAt(site.name).count { it.id != boat.id }
        val berth = (others until others + BERTH_TRIES).map { site.berth(boat.type, it) }.firstOrNull { (x, y, heading) -> BoatVisuals.fits(boat, x, y, heading) }
            ?: return player.sendMessage("There is no free berth at this port.")
        end(voyage)
        boat.state = BoatState.DOCKED
        boat.dockPort = site.name
        boat.globalX = WorldWindow.toGlobal(berth.first, GeneratedWorld.windowX)
        boat.globalY = WorldWindow.toGlobal(berth.second, GeneratedWorld.windowY)
        boat.heading = berth.third
        BoatStore.save(boat)
        SailorStore.of(player.username).lastDock = site.name
        SailorStore.save(SailorStore.of(player.username))
        player.tele(Ports.landingTile(site))
        Ports.refreshBoats(site)
        player.sendMessage("You moor the ${boat.name} at port ${site.name}.")
    }

    private fun end(voyage: Voyage) {
        active.remove(voyage.username, voyage)
        voyage.visual.finish()
        voyage.monsters.forEach { it.finish() }
        voyage.monsters.clear()
        voyage.salvage = null
        voyage.cannonAuto = false
    }

    fun tickAll() {
        val ended = active.values.filter { World.getPlayerByUsername(it.username)?.takeIf { p -> !p.hasFinished() } == null }
        ended.forEach { voyage ->
            end(voyage)
            voyage.boat.state = BoatState.SAILING
            voyage.movement.halt()
            BoatStore.save(voyage.boat)
        }
        active.values.forEach { voyage ->
            val player = World.getPlayerByUsername(voyage.username) ?: return@forEach
            runCatching { tick(voyage, player) }.onFailure { org.darkan.core.Logger.handle(Voyages::class.java, "tick", it) }
        }
        if (World.getServerTicks() % CONTENT_TICKS == 0L) SeaContent.sync(active.values.map { it.tileX to it.tileY })
    }

    private fun tick(voyage: Voyage, player: Player) {
        val boat = voyage.boat
        val movement = voyage.movement
        voyage.ticks++
        voyage.trim.windCatcher = Facility.WIND_CATCHER in boat.facilities
        voyage.trim.holdingCargo = boat.hold.crates.isNotEmpty()
        voyage.trim.tick()
        crewTrim(voyage, player)
        movement.slow = 1.0
        if (voyage.sickTicks > 0) voyage.sickTicks--
        if (voyage.ticks % HAZARD_TICKS == 0L) hazards(voyage, player)
        if (boat.state != BoatState.SAILING) return
        val wasMoving = movement.speed > 0.0
        movement.tick(::sailable)
        if (wasMoving && movement.speed == 0.0) blocked(voyage, player)
        BoatVisuals.move(voyage.visual, boat, voyage.tileX, voyage.tileY, movement.heading)
        if (player.x != voyage.tileX || player.y != voyage.tileY) player.tele(Tile.of(voyage.tileX, voyage.tileY, GROUND_LEVEL))
        boat.heading = movement.heading
        boat.globalX = WorldWindow.toGlobal(voyage.tileX, GeneratedWorld.windowX)
        boat.globalY = WorldWindow.toGlobal(voyage.tileY, GeneratedWorld.windowY)
        chart(voyage, player)
        salvage(voyage, player)
        BoatCombat.tick(voyage, player)
        if (voyage.ticks % MONSTER_TICKS == 0L) maybeSpawnMonster(voyage)
        if (voyage.ticks % WAGE_TICKS == 0L) wages(voyage, player)
        if (voyage.ticks - voyage.persistedTick >= PERSIST_TICKS) {
            voyage.persistedTick = voyage.ticks
            BoatStore.save(boat)
        }
        edge(voyage, player)
    }

    private fun blocked(voyage: Voyage, player: Player) {
        if (voyage.ticks - voyage.lastMessageTick < MESSAGE_GAP_TICKS) return
        voyage.lastMessageTick = voyage.ticks
        val radians = Math.toRadians(voyage.movement.heading.toDouble())
        val aheadX = voyage.tileX + (Math.sin(radians) * EDGE_LOOKAHEAD).toInt()
        val aheadY = voyage.tileY + (Math.cos(radians) * EDGE_LOOKAHEAD).toInt()
        player.sendMessage(if (PortSites.generatedAt(aheadX, aheadY)) "Your boat can go no farther that way." else "You cannot sail farther that way.")
    }

    private fun edge(voyage: Voyage, player: Player) {
        val globalX = voyage.boat.globalX
        val globalY = voyage.boat.globalY
        val sectorX = WorldWindow.owner(globalX)
        val sectorY = WorldWindow.owner(globalY)
        if (sectorX == GeneratedWorld.windowX && sectorY == GeneratedWorld.windowY) return
        end(voyage)
        voyage.boat.state = BoatState.SAILING
        BoatStore.save(voyage.boat)
        player.sendMessage("Your boat sails into sector ${sectorName(sectorX, sectorY)}. Select world ${WorldWindow.worldId(sectorX, sectorY)} to continue the voyage.")
    }

    private fun hazards(voyage: Voyage, player: Player) {
        val boat = voyage.boat
        val level = PortSites.levelAt(voyage.tileX, voyage.tileY)
        val hazard = HazardField.at(WorldGenerator.DEFAULT_SEED, voyage.tileX, voyage.tileY, level) ?: return
        when (hazard) {
            Hazard.RAPIDS -> if (hazard.protectedBy(boat)) {
                voyage.movement.addBurst(boat.sail.boostTicks)
            } else {
                hitBoat(voyage, hazard.damage, "rapids")
            }
            Hazard.KELP -> if (!hazard.protectedBy(boat)) voyage.movement.slow = KELP_SLOW
            Hazard.ICE -> if (!hazard.protectedBy(boat)) {
                voyage.movement.slow = ICE_SLOW
                hitBoat(voyage, hazard.damage, "ice")
            }
            Hazard.STORM -> if (!hazard.protectedBy(boat)) {
                voyage.movement.targetHeading = Math.floorMod(voyage.movement.heading + random.nextInt(STORM_DRIFT * 2 + 1) - STORM_DRIFT, FULL_TURN)
                hitBoat(voyage, hazard.damage, "the storm")
            }
            Hazard.CRYSTAL -> if (!hazard.protectedBy(boat)) voyage.movement.slow = KELP_SLOW
            Hazard.SICKNESS -> if (!hazard.protectedBy(boat) && boat.crew.isNotEmpty()) {
                voyage.sickTicks = SICKNESS_TICKS
                if (voyage.ticks - voyage.lastMessageTick >= MESSAGE_GAP_TICKS) {
                    voyage.lastMessageTick = voyage.ticks
                    player.sendMessage("Sickness spreads through your crew in these warm waters.")
                }
            }
            Hazard.REEF -> hitBoat(voyage, hazard.damageTo(boat), "a jagged reef")
        }
    }

    fun hitBoat(voyage: Voyage, raw: Int, source: String) {
        if (voyage.boat.state != BoatState.SAILING || raw <= 0) return
        val player = World.getPlayerByUsername(voyage.username) ?: return
        val dealt = voyage.boat.damage(raw)
        player.sendMessage("Your boat takes $dealt damage from $source (${voyage.boat.hitpoints}/${voyage.boat.maxHitpoints}).")
        if (voyage.boat.capsized) capsize(voyage, player)
    }

    private fun capsize(voyage: Voyage, player: Player) {
        val boat = voyage.boat
        boat.capsize()
        val profile = SailorStore.of(player.username)
        val lost = profile.tasks.count { it.collected }
        profile.tasks.removeIf { it.collected }
        end(voyage)
        boat.state = BoatState.CAPSIZED
        BoatStore.save(boat)
        SailorStore.save(profile)
        val landing = PortSites.byName(profile.lastDock)?.let { Ports.landingTile(it) } ?: Settings.getConfig().playerRespawnTile
        player.tele(landing)
        player.sendMessage("Your boat capsizes! The hold is lost${if (lost > 0) " and $lost courier task(s) failed" else ""}. Recover it from any Shipwright.")
    }

    private fun crewTrim(voyage: Voyage, player: Player) {
        val helmsmen = voyage.crewWith(CrewJob.HELM)
        if (helmsmen.isEmpty() || voyage.sickTicks > 0) return
        if (!voyage.trim.gustOpen) {
            voyage.crewTrimCountdown = CREW_TRIM_DELAY
            return
        }
        if (--voyage.crewTrimCountdown > 0) return
        val helmsman = helmsmen.maxBy { it.helmsmanship }
        if (voyage.trim.trim(player.skills.getLevel(Constants.SAILING), true) > 0.0) {
            player.skills.addXp(Constants.SAILING, voyage.trim.crewTrimXp(helmsman.helmsmanship))
            player.sendMessage("${helmsman.name} trims the sails.")
        }
    }

    private fun wages(voyage: Voyage, player: Player) {
        voyage.boat.crew.toList().forEach { crewmate ->
            if (player.inventory.hasCoins(crewmate.wage)) {
                player.inventory.removeCoins(crewmate.wage)
            } else {
                voyage.boat.crew.remove(crewmate)
                player.sendMessage("${crewmate.name} quits for want of ${crewmate.wage} coins in wages.")
            }
        }
    }

    private fun maybeSpawnMonster(voyage: Voyage) {
        voyage.monsters.removeIf { it.hasFinished() || it.isDead }
        if (voyage.monsters.size >= MAX_MONSTERS || Utils.random(PERCENT) >= MONSTER_SPAWN_PERCENT) return
        repeat(MONSTER_ATTEMPTS) {
            val angle = Math.toRadians(random.nextInt(FULL_TURN).toDouble())
            val distance = MONSTER_MIN_DISTANCE + random.nextInt(MONSTER_MAX_DISTANCE - MONSTER_MIN_DISTANCE + 1)
            val x = voyage.tileX + (Math.sin(angle) * distance).toInt()
            val y = voyage.tileY + (Math.cos(angle) * distance).toInt()
            if (!Seas.clearWater(x, y)) return@repeat
            val monster = SeaMonsterTable.spawn(Tile.of(x, y, GROUND_LEVEL), PortSites.levelAt(x, y))
            monster.voyage = voyage
            voyage.monsters.add(monster)
            return
        }
    }

    private fun chart(voyage: Voyage, player: Player) {
        val point = SeaContent.chartNear(voyage.tileX, voyage.tileY) ?: return
        val profile = SailorStore.of(player.username)
        val key = "${point.sea}:${point.x}:${point.y}"
        if (key in profile.log.charted) return
        if (player.skills.getLevel(Constants.SAILING) < point.kind.sailingLevel) {
            if (voyage.ticks - voyage.lastMessageTick >= MESSAGE_GAP_TICKS) {
                voyage.lastMessageTick = voyage.ticks
                player.sendMessage("You need Sailing level ${point.kind.sailingLevel} to chart this ${point.kind.name.lowercase()} point.")
            }
            return
        }
        val xp = profile.log.record(point)
        if (xp <= 0) return
        player.skills.addXp(Constants.SAILING, xp.toDouble())
        player.sendMessage("You chart a ${point.kind.name.lowercase()} point in the ${Seas.nameAt(point.x, point.y)}.")
        revealWorldMapFog(player, point.x, point.y, CHART_FOG_RADIUS)
        val all = Seas.pointsIn(point.x, point.y)
        if (all.all { "${it.sea}:${it.x}:${it.y}" in profile.log.charted } && profile.chartedSeas.add(point.sea)) {
            player.skills.addXp(Constants.SAILING, SEA_COMPLETE_XP.toDouble())
            player.sendMessage("You have charted every point of the ${Seas.nameAt(point.x, point.y)}!")
        }
        SailorStore.save(profile)
    }

    fun startSalvage(player: Player, spot: SeaSpot) {
        val voyage = active[player.username] ?: return player.sendMessage("You need to be aboard a boat with a salvaging hook.")
        val site = spot.wreck ?: return
        if (voyage.boat.hook == null) return player.sendMessage("Your boat has no salvaging hook. Ask a Shipwright to fit one.")
        if (maxOf(kotlin.math.abs(site.x - voyage.tileX), kotlin.math.abs(site.y - voyage.tileY)) > SalvageRolls.RANGE_TILES) {
            return player.sendMessage("The wreck is too far from your boat. Sail within ${SalvageRolls.RANGE_TILES} tiles.")
        }
        if (player.skills.getLevel(Constants.SAILING) < site.wreck.sailingLevel) {
            return player.sendMessage("You need Sailing level ${site.wreck.sailingLevel} to salvage this ${site.wreck.name.lowercase()} wreck.")
        }
        voyage.salvage = SalvageJob(site, SALVAGE_SUCCESSES)
        player.sendMessage("You lower the salvaging hook.")
    }

    private fun salvage(voyage: Voyage, player: Player) {
        val job = voyage.salvage ?: return
        val boat = voyage.boat
        val site = job.site
        if (maxOf(kotlin.math.abs(site.x - voyage.tileX), kotlin.math.abs(site.y - voyage.tileY)) > SalvageRolls.RANGE_TILES) {
            voyage.salvage = null
            return player.sendMessage("You sail out of range and haul in the hook.")
        }
        val hook = boat.hook ?: return
        var successes = 0
        if (voyage.ticks % SalvageRolls.PLAYER_ROLL_TICKS == 0L && Utils.random(PERCENT) < hook.successPercent) successes++
        if (voyage.ticks % SalvageRolls.CREW_ROLL_TICKS == 0L && voyage.sickTicks <= 0) {
            voyage.crewWith(CrewJob.SALVAGE).forEach { if (random.nextDouble() < SalvageRolls.crewChance(it.deckhandiness)) successes++ }
        }
        repeat(successes) {
            if (job.remaining <= 0) return@repeat
            val (item, amount) = SalvageLoot.roll(site.wreck, PortSites.levelAt(site.x, site.y), random)
            if (boat.hold.addGood(item, amount) <= 0) {
                voyage.salvage = null
                return player.sendMessage("Your cargo hold is full.")
            }
            job.remaining--
            boat.hold.sortingXp += site.wreck.sortingXp / SALVAGE_SUCCESSES
            player.skills.addXp(Constants.SAILING, site.wreck.salvageXp.toDouble() / SALVAGE_SUCCESSES)
        }
        if (job.remaining <= 0) {
            voyage.salvage = null
            SailorStore.of(player.username).wrecksSalvaged++
            SeaContent.wreckAt(site.x, site.y)?.let { SeaContent.consume(it) }
            player.sendMessage("The wreck has been picked clean. Your hold holds the salvage; sort and sell it at port.")
        }
    }

    private const val PERCENT = 100

    fun startSalvageNearest(player: Player, voyage: Voyage) {
        val spot = SeaContent.nearestWreck(voyage.tileX, voyage.tileY, SalvageRolls.RANGE_TILES) ?: return player.sendMessage("There is no wreck within ${SalvageRolls.RANGE_TILES} tiles.")
        startSalvage(player, spot)
    }

    fun nearestTo(x: Int, y: Int, range: Int): Voyage? =
        active.values.filter { maxOf(kotlin.math.abs(it.tileX - x), kotlin.math.abs(it.tileY - y)) <= range }.minByOrNull { maxOf(kotlin.math.abs(it.tileX - x), kotlin.math.abs(it.tileY - y)) }
}
