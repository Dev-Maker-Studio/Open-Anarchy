package com.rs.game.content.skills.sailing

import com.rs.engine.command.Commands
import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import org.darkan.core.Constants
import org.darkan.core.EnvVars
import org.darkan.core.model.Rights

private const val CACHE_PRUNE_TICKS = 500
private const val PORT_SEARCH_RADIUS = 6000
private const val PORT_LIST_LIMIT = 8

@ServerStartupEvent
fun startSailing() {
    if (!EnvVars.generateMaps) return
    onLogin { (player) ->
        BoatStore.forget(player.username)
        SailorStore.forget(player.username)
        SailingScreens.forget(player.username)
        Voyages.restore(player)
    }
    Commands.add(Rights.PLAYER, "boat [status|helm|panel|sails|heading|speed|anchor|trim|repair|leave|fire|hook|hold|log|tasks|crew|dock|mote|net|harvest|sort|keg|wind|trial|bounty|summon|teleport|kit|board|guest|job]", "Sailing: steer and manage your boat.") { player, args ->
        handle(player, args)
    }
    Commands.add(Rights.OWNER, "ports [tele]", "Lists the generated ports around you, or teleports to the nearest.") { player, args ->
        ports(player, args.firstOrNull()?.equals("tele", ignoreCase = true) == true)
    }
    WorldTasks.scheduleTimer(1, 1) {
        runCatching { Voyages.tickAll() }
        true
    }
    WorldTasks.scheduleTimer(CACHE_PRUNE_TICKS, CACHE_PRUNE_TICKS) {
        val online = World.players.map { it.username }.toSet()
        BoatStore.prune(online)
        SailorStore.prune(online)
        true
    }
}

private fun handle(player: Player, args: Array<String>) {
    val word = args.firstOrNull()?.lowercase()
    if (word == null || word == "status") return status(player)
    when (word) {
        "helm" -> withVoyage(player) { helm(player, it) }
        "panel" -> withVoyage(player) { SailingPanel.open(player, it) }
        "sails" -> withVoyage(player) { VoyageControls.toggleSails(player, it) }
        "heading" -> withVoyage(player) { voyage -> args.getOrNull(1)?.let { Compass.parse(it) }?.let { VoyageControls.setHeading(player, voyage, it) } ?: player.sendMessage("Usage: boat heading <0-359 or n/ne/e/se/s/sw/w/nw>.") }
        "speed" -> withVoyage(player) { setSpeed(player, it, args.getOrNull(1)) }
        "anchor" -> withVoyage(player) { VoyageControls.toggleAnchor(player, it) }
        "trim" -> withVoyage(player) { VoyageControls.trim(player, it) }
        "repair" -> withVoyage(player) { VoyageControls.useRepairKits(player, it) }
        "leave" -> Voyages.leave(player)
        "fire" -> withVoyage(player) { VoyageControls.toggleCannon(player, it) }
        "hook" -> withVoyage(player) { Voyages.startSalvageNearest(player, it) }
        "hold" -> hold(player)
        "log" -> SailingScreens.openLog(player)
        "tasks" -> SailingScreens.openLog(player)
        "crew" -> crew(player)
        "dock" -> dock(player)
        "mote" -> withVoyage(player) { VoyageControls.releaseMote(player, it) }
        "net" -> withVoyage(player) { net(player, it, args.getOrNull(1)?.toIntOrNull()) }
        "harvest" -> withVoyage(player) { Stations.harvest(player, it) }
        "sort" -> withVoyage(player) { Stations.sort(player, it) }
        "keg" -> withVoyage(player) { if (args.getOrNull(1).equals("drink", ignoreCase = true)) Keg.drink(player, it.boat) else Keg.stock(player, it) }
        "wind" -> withVoyage(player) { player.sendMessage("The wind blows from ${Compass.nameOf(Wind.directionAt(PortSites.sector, World.getServerTicks()))}; headings within 45 degrees of it gain a half-tile boost.") }
        "trial" -> trial(player, args)
        "bounty" -> bounty(player)
        "summon" -> BoatSpells.summon(player)
        "teleport" -> BoatSpells.teleportToBoat(player)
        "kit" -> RepairKitCrafting.menu(player)
        "board" -> boardNumber(player, args.getOrNull(1)?.toIntOrNull())
        "guest" -> PortSites.nearest(player.x, player.y, PortSites.ZONE_RADIUS)?.let { site -> args.getOrNull(1)?.let { Voyages.boardFriend(player, site, it) } ?: player.sendMessage("Usage: boat guest <player name>.") } ?: player.sendMessage("Stand at a port to board a friend's boat.")
        "job" -> job(player, args)
        else -> player.sendMessage("Boat commands: status, helm, panel, sails, heading, speed, anchor, trim, repair, leave, fire, hook, hold, log, tasks, crew, dock, mote, net, harvest, sort, keg, wind, trial, bounty, summon, teleport, kit, board, guest, job.")
    }
}

private fun withVoyage(player: Player, action: (Voyage) -> Unit) {
    val voyage = Voyages.of(player)
    if (voyage == null) player.sendMessage("You are not aboard a boat. Board one at a port gangplank.") else action(voyage)
}

private fun status(player: Player) {
    val voyage = Voyages.of(player)
    val boats = voyage?.let { listOf(it.boat) } ?: BoatStore.ownedBy(player.username)
    if (boats.isEmpty()) return player.sendMessage("You own no boats. Speak to a Shipwright at a port.")
    boats.forEach {
        player.sendMessage("${it.name}: ${it.type.name.lowercase()}, ${it.hitpoints}/${it.maxHitpoints} HP, ${it.hull.name.lowercase()} hull, ${it.state.name.lowercase()}${if (it.dockPort.isNotEmpty()) " (last port ${it.dockPort})" else ""}.")
    }
    voyage?.let {
        player.sendMessage("Motes ${it.trim.motes}, wind from ${Compass.nameOf(Wind.directionAt(PortSites.sector, World.getServerTicks()))}${it.trial?.let { run -> ", racing the ${run.course.label}" } ?: ""}.")
        player.sendMessage("Heading ${Compass.nameOf(it.movement.heading)} (${it.movement.heading}), speed ${"%.1f".format(it.movement.speed)}, sails ${if (it.movement.sailsSet) "set" else "unset"}${if (it.trim.gustOpen) ", a gust is up: trim now!" else ""}.")
    }
}

private fun helm(player: Player, voyage: Voyage) {
    VoyageControls.takeHelm(player, voyage)
    val m = voyage.movement
    player.sendOptionsDialogue("${voyage.boat.name}: ${Compass.nameOf(m.heading)}, speed ${"%.1f".format(m.speed)}") {
        opExec(if (m.sailsSet) "Unset sails" else "Set sails") { VoyageControls.toggleSails(player, voyage) }
        opExec("Set heading") { headingMenu(player, voyage, 0) }
        opExec("Change speed") { speedMenu(player, voyage) }
        opExec(if (voyage.trim.gustOpen) "Trim the sails now" else "Anchor, cannon or hook") {
            if (voyage.trim.gustOpen) VoyageControls.trim(player, voyage) else extras(player, voyage)
        }
        opExec("Leave the boat") { Voyages.leave(player) }
    }
}

private fun extras(player: Player, voyage: Voyage) {
    player.sendOptionsDialogue("Boat stations") {
        opExec(if (voyage.movement.anchored) "Raise the anchor" else "Drop the anchor") { VoyageControls.toggleAnchor(player, voyage) }
        opExec(if (voyage.cannonAuto) "Cease fire" else "Man the cannon") { VoyageControls.toggleCannon(player, voyage) }
        opExec("Lower the salvaging hook") { Voyages.startSalvageNearest(player, voyage) }
        if (voyage.nets.isNotEmpty()) opExec("Work the trawling nets") { net(player, voyage, null) }
        if (Facility.CRYSTAL_EXTRACTOR in voyage.boat.facilities) opExec("Harvest the crystal extractor") { Stations.harvest(player, voyage) }
    }
}

private fun headingMenu(player: Player, voyage: Voyage, page: Int) {
    val points = Compass.entries.chunked(4)[page]
    player.sendOptionsDialogue("Set heading") {
        points.forEach { point -> opExec("${point.words.last().replaceFirstChar { it.uppercase() }} (${point.degrees})") { VoyageControls.setHeading(player, voyage, point.degrees) } }
        opExec(if (page == 0) "South and west" else "North and east") { headingMenu(player, voyage, 1 - page) }
    }
}

private fun speedMenu(player: Player, voyage: Voyage) {
    player.sendOptionsDialogue("Speed") {
        SpeedSetting.entries.forEach { setting -> opExec(setting.name.lowercase().replaceFirstChar { it.uppercase() }) { VoyageControls.setSpeed(player, voyage, setting) } }
    }
}

private fun setSpeed(player: Player, voyage: Voyage, word: String?) {
    val setting = SpeedSetting.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
    if (setting == null) return player.sendMessage("Usage: boat speed <stop|slow|full|reverse>.")
    VoyageControls.setSpeed(player, voyage, setting)
}

private fun hold(player: Player) {
    val boat = Voyages.of(player)?.boat ?: BoatStore.ownedBy(player.username).firstOrNull() ?: return player.sendMessage("You own no boats.")
    SailingScreens.openHold(player, boat)
}

private fun crew(player: Player) {
    val boat = Voyages.of(player)?.boat ?: BoatStore.ownedBy(player.username).firstOrNull() ?: return player.sendMessage("You own no boats.")
    val site = PortSites.nearest(player.x, player.y, PortSites.ZONE_RADIUS)
    if (site == null || Voyages.of(player) != null) return player.sendMessage("The ${boat.name} has ${boat.crew.size} crewmate(s). Hire and manage crew at a Port master (${CrewRules.unlockedSlots(player.skills.getLevelForXp(Constants.SAILING), boat.type)} slot(s) open).")
    SailingScreens.openCrew(player, boat, site)
}

private fun net(player: Player, voyage: Voyage, index: Int?) {
    if (voyage.nets.isEmpty()) return player.sendMessage("Your boat has no trawling net. A Shipwright fits them on a skiff or sloop.")
    if (index == null) {
        voyage.nets.forEachIndexed { number, state ->
            player.sendMessage("Net ${number + 1}: ${state.tier.label}, ${state.total}/${Trawling.CAPACITY} fish, ${if (state.active) "lowered (${state.depth.label})" else "hauled in"}.")
        }
        return player.sendMessage("Use boat net <number> to lower, haul or empty one.")
    }
    Trawling.open(player, voyage, index - 1)
}

private fun trial(player: Player, args: Array<String>) {
    val voyage = Voyages.of(player) ?: return player.sendMessage("Board a skiff and use this near a port, or ask a Shipwright about the Barracuda Trials.")
    val site = PortSites.nearest(voyage.tileX, voyage.tileY, PORT_SEARCH_RADIUS) ?: return player.sendMessage("No port generated near you.")
    when (val word = args.getOrNull(1)?.lowercase()) {
        null, "status" -> Barracuda.status(player, voyage)
        "stop" -> Barracuda.abort(voyage, player, "You abandon the trial.")
        else -> {
            val course = TrialCourse.at(site.kind).firstOrNull { it.name.lowercase().replace('_', ' ').startsWith(word.replace('_', ' ')) || it.name.equals(word, ignoreCase = true) }
                ?: return player.sendMessage("Courses here: ${TrialCourse.at(site.kind).joinToString { it.name.lowercase() }}. Use boat trial <course> <none|swordfish|shark|marlin>.")
            val rank = TrialRank.entries.firstOrNull { it.name.equals(args.getOrNull(2), ignoreCase = true) } ?: TrialRank.NONE
            Barracuda.begin(player, voyage, site, course, rank)
        }
    }
}

private fun bounty(player: Player) {
    val profile = SailorStore.of(player.username)
    val boats = BoatStore.ownedBy(player.username)
    val tasks = profile.tasks.filter { it.kind == TaskKind.BOUNTY }
    if (tasks.isEmpty()) return player.sendMessage("You have no bounty. Notice boards offer one from Sailing level ${PortTasks.BOUNTY_LEVEL}.")
    tasks.forEach { task ->
        val monster = BountyMonster.entries.getOrNull(task.monster) ?: return@forEach
        player.sendMessage("Bounty: ${monster.label}, ${BountyTasks.itemsHeld(boats, monster)}/${task.crates} trophies in your holds, ${task.baseXp} Sailing xp. Claim at a Port master.")
    }
}

private fun boardNumber(player: Player, number: Int?) {
    val site = PortSites.nearest(player.x, player.y, PortSites.ZONE_RADIUS) ?: return player.sendMessage("Stand at a port to board a boat.")
    val boats = BoatStore.ownedBy(player.username).filter { it.state == BoatState.DOCKED && it.dockPort == site.name }.sortedBy { it.id }
    val boat = boats.getOrNull((number ?: 1) - 1) ?: return player.sendMessage("No boat with that number is moored here (${boats.size} moored).")
    Voyages.start(player, boat, site)
}

private fun job(player: Player, args: Array<String>) {
    val boat = Voyages.of(player)?.boat ?: BoatStore.ownedBy(player.username).firstOrNull() ?: return player.sendMessage("You own no boats.")
    val crewmate = args.getOrNull(1)?.toIntOrNull()?.let { boat.crew.getOrNull(it - 1) }
    val job = CrewJob.entries.firstOrNull { it.name.equals(args.getOrNull(2), ignoreCase = true) }
    if (crewmate == null || job == null) return player.sendMessage("Usage: boat job <crew number> <${CrewJob.entries.joinToString("|") { it.name.lowercase() }}>.")
    PortMaster.assign(player, boat, crewmate, job)
}

private fun dock(player: Player) {
    val site = PortSites.nearest(player.x, player.y, PORT_SEARCH_RADIUS) ?: return player.sendMessage("No port generated near you.")
    player.sendMessage("Nearest port: ${site.name} (${site.kind.name.lowercase()}), shore at ${site.shoreX},${site.shoreY}.")
}

private fun ports(player: Player, teleport: Boolean) {
    val found = PortSites.near(player.x, player.y, PORT_SEARCH_RADIUS).sortedBy { maxOf(kotlin.math.abs(it.shoreX - player.x), kotlin.math.abs(it.shoreY - player.y)) }
    if (found.isEmpty()) return player.sendMessage("No ports found within $PORT_SEARCH_RADIUS tiles.")
    if (teleport) {
        val site = found.first()
        player.tele(Ports.landingTile(site))
        return player.sendMessage("Teleported to port ${site.name}.")
    }
    found.take(PORT_LIST_LIMIT).forEach { player.sendMessage("Port ${it.name}: ${it.kind.name.lowercase()}, shore ${it.shoreX},${it.shoreY}, level indicator ${it.threatLevel}.") }
}
