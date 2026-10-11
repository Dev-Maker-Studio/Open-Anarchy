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
        Voyages.restore(player)
    }
    Commands.add(Rights.PLAYER, "boat [status|helm|sails|heading|speed|anchor|trim|leave|fire|hook|hold|log|tasks|crew|dock]", "Sailing: steer and manage your boat.") { player, args ->
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
        "sails" -> withVoyage(player) { toggleSails(player, it) }
        "heading" -> withVoyage(player) { voyage -> args.getOrNull(1)?.let { Compass.parse(it) }?.let { setHeading(player, voyage, it) } ?: player.sendMessage("Usage: boat heading <0-359 or n/ne/e/se/s/sw/w/nw>.") }
        "speed" -> withVoyage(player) { setSpeed(player, it, args.getOrNull(1)) }
        "anchor" -> withVoyage(player) { toggleAnchor(player, it) }
        "trim" -> withVoyage(player) { trim(player, it) }
        "leave" -> Voyages.leave(player)
        "fire" -> withVoyage(player) { toggleCannon(player, it) }
        "hook" -> withVoyage(player) { Voyages.startSalvageNearest(player, it) }
        "hold" -> hold(player)
        "log" -> log(player)
        "tasks" -> tasks(player)
        "crew" -> crew(player)
        "dock" -> dock(player)
        else -> player.sendMessage("Boat commands: status, helm, sails, heading, speed, anchor, trim, leave, fire, hook, hold, log, tasks, crew, dock.")
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
        player.sendMessage("Heading ${Compass.nameOf(it.movement.heading)} (${it.movement.heading}), speed ${"%.1f".format(it.movement.speed)}, sails ${if (it.movement.sailsSet) "set" else "unset"}${if (it.trim.gustOpen) ", a gust is up: trim now!" else ""}.")
    }
}

private fun helm(player: Player, voyage: Voyage) {
    val m = voyage.movement
    player.sendOptionsDialogue("${voyage.boat.name}: ${Compass.nameOf(m.heading)}, speed ${"%.1f".format(m.speed)}") {
        opExec(if (m.sailsSet) "Unset sails" else "Set sails") { toggleSails(player, voyage) }
        opExec("Set heading") { headingMenu(player, voyage, 0) }
        opExec("Change speed") { speedMenu(player, voyage) }
        opExec(if (voyage.trim.gustOpen) "Trim the sails now" else "Anchor, cannon or hook") {
            if (voyage.trim.gustOpen) trim(player, voyage) else extras(player, voyage)
        }
        opExec("Leave the boat") { Voyages.leave(player) }
    }
}

private fun extras(player: Player, voyage: Voyage) {
    player.sendOptionsDialogue("Boat stations") {
        opExec(if (voyage.movement.anchored) "Raise the anchor" else "Drop the anchor") { toggleAnchor(player, voyage) }
        opExec(if (voyage.cannonAuto) "Cease fire" else "Man the cannon") { toggleCannon(player, voyage) }
        opExec("Lower the salvaging hook") { Voyages.startSalvageNearest(player, voyage) }
    }
}

private fun headingMenu(player: Player, voyage: Voyage, page: Int) {
    val points = Compass.entries.chunked(4)[page]
    player.sendOptionsDialogue("Set heading") {
        points.forEach { point -> opExec("${point.words.last().replaceFirstChar { it.uppercase() }} (${point.degrees})") { setHeading(player, voyage, point.degrees) } }
        opExec(if (page == 0) "South and west" else "North and east") { headingMenu(player, voyage, 1 - page) }
    }
}

private fun speedMenu(player: Player, voyage: Voyage) {
    player.sendOptionsDialogue("Speed") {
        SpeedSetting.entries.forEach { setting -> opExec(setting.name.lowercase().replaceFirstChar { it.uppercase() }) { setSpeed(player, voyage, setting.name) } }
    }
}

private fun toggleSails(player: Player, voyage: Voyage) {
    voyage.movement.sailsSet = !voyage.movement.sailsSet
    if (voyage.movement.sailsSet && voyage.movement.setting == SpeedSetting.STOP) voyage.movement.setting = SpeedSetting.FULL
    player.sendMessage(if (voyage.movement.sailsSet) "You set the sails." else "You unset the sails.")
}

private fun setHeading(player: Player, voyage: Voyage, degrees: Int) {
    voyage.movement.targetHeading = degrees
    player.sendMessage("You steer ${Compass.nameOf(degrees)} ($degrees).")
}

private fun setSpeed(player: Player, voyage: Voyage, word: String?) {
    val setting = SpeedSetting.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
    if (setting == null) return player.sendMessage("Usage: boat speed <stop|slow|full|reverse>.")
    voyage.movement.setting = setting
    voyage.movement.sailsSet = setting != SpeedSetting.STOP
    player.sendMessage("Speed set to ${setting.name.lowercase()}.")
}

private fun toggleAnchor(player: Player, voyage: Voyage) {
    if (Facility.ANCHOR !in voyage.boat.facilities) return player.sendMessage("Your boat has no anchor. A Shipwright can fit one.")
    voyage.movement.anchored = !voyage.movement.anchored
    player.sendMessage(if (voyage.movement.anchored) "You drop the anchor." else "You raise the anchor.")
}

private fun trim(player: Player, voyage: Voyage) {
    val xp = voyage.trim.trim(player.skills.getLevel(Constants.SAILING), true)
    if (xp <= 0.0) return player.sendMessage(if (voyage.boat.hold.crates.isNotEmpty()) "You cannot trim the sails while carrying cargo." else "There is no gust to trim.")
    player.skills.addXp(Constants.SAILING, xp)
    player.sendMessage("You trim the sails, catching the wind for a burst of speed!")
}

private fun toggleCannon(player: Player, voyage: Voyage) {
    val cannon = voyage.boat.cannon ?: return player.sendMessage("Your boat has no cannon. A Shipwright can fit one.")
    if (player.skills.getLevel(Constants.RANGE) < cannon.rangedLevel) return player.sendMessage("You need Ranged level ${cannon.rangedLevel} to fire a ${cannon.label} cannon.")
    voyage.cannonAuto = !voyage.cannonAuto
    player.sendMessage(if (voyage.cannonAuto) "You man the ${cannon.label} cannon; it fires at monsters in the front arc." else "You stand down from the cannon.")
}

private fun hold(player: Player) {
    val boat = Voyages.of(player)?.boat ?: BoatStore.ownedBy(player.username).firstOrNull() ?: return player.sendMessage("You own no boats.")
    player.sendMessage("${boat.name}'s hold: ${boat.hold.used}/${boat.hold.capacity} slots, ${boat.hold.cannonballs} cannonballs.")
    boat.hold.crates.forEach { (_, amount) -> player.sendMessage("Courier crates: $amount.") }
    boat.hold.goods.forEach { (item, amount) -> player.sendMessage("${org.darkan.core.cache.ItemDefinitions.getDefs(item).name} x$amount") }
}

private fun log(player: Player) {
    val profile = SailorStore.of(player.username)
    player.sendMessage("Captain's log: ${profile.log.charted.size} points charted in ${profile.chartedSeas.size} complete sea(s), ${profile.wrecksSalvaged} wrecks salvaged, ${profile.tasksCompleted} tasks delivered.")
}

private fun tasks(player: Player) {
    val tasks = SailorStore.of(player.username).tasks
    if (tasks.isEmpty()) return player.sendMessage("You have no courier tasks. Visit a port notice board.")
    tasks.forEach { player.sendMessage("${it.crates} crate(s): port ${it.from} to port ${it.to}, ${it.baseXp} xp, ${if (it.collected) "loaded" else "not yet collected"}.") }
}

private fun crew(player: Player) {
    val boat = Voyages.of(player)?.boat ?: BoatStore.ownedBy(player.username).firstOrNull() ?: return player.sendMessage("You own no boats.")
    if (boat.crew.isEmpty()) return player.sendMessage("The ${boat.name} has no crew. Hire some at a Port master (${CrewRules.unlockedSlots(player.skills.getLevelForXp(Constants.SAILING), boat.type)} slot(s) open).")
    boat.crew.forEach { player.sendMessage("${it.name}: helm ${it.helmsmanship}, deckhand ${it.deckhandiness}, privateering ${it.privateering}, wage ${it.wage}, job ${it.job.name.lowercase()}.") }
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
