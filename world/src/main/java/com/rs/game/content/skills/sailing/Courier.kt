package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Obj
import java.util.Random

private const val EPOCH_MILLIS = 1_800_000L
private const val SLOTS_SHOWN = 5
private const val COIN_BAG_IN = 5
private const val CRATES_PER_LEVELS = 25
private const val MAX_CRATES = 4
private const val ID_MIX = 0x9E3779B97F4A7C15uL
private const val NAME_HASH_MIX = 31L
private const val COIN_BAG_WEIGHT = 4

object Courier {
    private val REWARD_BAGS: Map<BagTier, List<Pair<Int, Int>>> = mapOf(
        BagTier.TINY to listOf(Obj.raw_tuna to 3),
        BagTier.SMALL to listOf(Obj.raw_swordfish to 3, Obj.casket to 1),
        BagTier.MEDIUM to listOf(Obj.raw_lobster to 5, Obj.casket to 1),
        BagTier.LARGE to listOf(Obj.raw_shark to 5, Obj.casket to 1),
        BagTier.HUGE to listOf(Obj.raw_shark to 10, Obj.casket to 2)
    )

    fun generate(site: PortSite, sailingLevel: Int): List<PortTask> {
        val from = site.asPort()
        val ports = PortSites.near(site.shoreX, site.shoreY, PortTasks.maxRange(sailingLevel)).filter { it.name != site.name }.map { it.asPort() }
        val destinations = PortTasks.eligibleDestinations(from, ports, sailingLevel).sortedBy { it.name }
        if (destinations.isEmpty()) return emptyList()
        val epoch = System.currentTimeMillis() / EPOCH_MILLIS
        return (0 until SLOTS_SHOWN).map { slot ->
            val random = Random((site.name.hashCode() * NAME_HASH_MIX + epoch) xor ((slot + 1) * ID_MIX.toLong()))
            val crates = 1 + random.nextInt((1 + sailingLevel / CRATES_PER_LEVELS).coerceAtMost(MAX_CRATES))
            PortTasks.courier(from, destinations[random.nextInt(destinations.size)], crates)
        }.distinctBy { taskId(it) }
    }

    fun taskId(task: PortTask): Long = (task.from.name.hashCode().toLong() shl 32) xor task.to.name.hashCode().toLong() xor (task.crates.toLong() shl 24) xor task.baseXp.toLong()

    fun open(player: Player, site: PortSite) {
        player.sendOptionsDialogue("Port ${site.name} notice board") {
            opExec("Take a courier task") { takeMenu(player, site) }
            opExec("Collect crates") { collect(player, site) }
            opExec("Deliver crates") { deliver(player, site) }
            opExec("My tasks") { listTasks(player) }
            opExec("Abandon a task") { abandonMenu(player) }
        }
    }

    private fun takeMenu(player: Player, site: PortSite) {
        val level = player.skills.getLevel(Constants.SAILING)
        val profile = SailorStore.of(player.username)
        if (profile.tasks.size >= PortTasks.slots(level)) return player.sendMessage("You can hold ${PortTasks.slots(level)} task(s) at Sailing level $level.")
        val tasks = generate(site, level).filter { task -> profile.tasks.none { it.id == taskId(task) } }
        if (tasks.isEmpty()) return player.sendMessage("There are no courier tasks posted here for your level.")
        player.sendOptionsDialogue("Courier tasks") {
            tasks.take(SLOTS_SHOWN).forEach { task ->
                opExec("${task.crates}x to ${task.to.name}: ${task.baseXp} xp") { accept(player, task) }
            }
        }
    }

    private fun accept(player: Player, task: PortTask) {
        val profile = SailorStore.of(player.username)
        val id = taskId(task)
        if (profile.tasks.size >= PortTasks.slots(player.skills.getLevel(Constants.SAILING)) || profile.tasks.any { it.id == id }) return
        profile.tasks.add(ActiveTask(id, task.kind, task.from.name, task.to.name, task.crates, task.baseXp, task.coins))
        SailorStore.save(profile)
        player.sendMessage("Task taken: carry ${task.crates} crate(s) from port ${task.from.name} to port ${task.to.name} for ${task.baseXp} Sailing xp and a ${task.bag.name.lowercase()} bag. Collect the crates here with a boat moored at this port.")
    }

    fun collect(player: Player, site: PortSite) {
        val profile = SailorStore.of(player.username)
        val pending = profile.tasks.filter { it.from == site.name && !it.collected }
        if (pending.isEmpty()) return player.sendMessage("You have no crates waiting at port ${site.name}.")
        val boat = BoatStore.ownedBy(player.username).firstOrNull { it.dockPort == site.name && it.state == BoatState.DOCKED }
            ?: return player.sendMessage("You need a boat moored at this port to load the crates.")
        pending.forEach { task ->
            if (boat.hold.free < task.crates) return@forEach player.sendMessage("The ${boat.name}'s hold has no room for ${task.crates} crate(s).")
            boat.hold.addCrates(task.id, task.crates)
            task.collected = true
            player.sendMessage("${task.crates} crate(s) for port ${task.to} are stowed in the ${boat.name}'s hold.")
        }
        BoatStore.save(boat)
        SailorStore.save(profile)
    }

    fun deliver(player: Player, site: PortSite) {
        val profile = SailorStore.of(player.username)
        val due = profile.tasks.filter { it.to == site.name && it.collected }
        if (due.isEmpty()) return player.sendMessage("You have no crates to deliver to port ${site.name}.")
        val boat = BoatStore.ownedBy(player.username).firstOrNull { it.dockPort == site.name && it.state == BoatState.DOCKED && due.any { task -> holds(it, task) } }
            ?: return player.sendMessage("Moor the boat carrying the crates at this port first.")
        due.filter { holds(boat, it) }.forEach { task ->
            boat.hold.crates.remove(task.id)
            profile.tasks.remove(task)
            profile.tasksCompleted++
            reward(player, task)
        }
        BoatStore.save(boat)
        SailorStore.save(profile)
    }

    private fun holds(boat: Boat, task: ActiveTask): Boolean = boat.hold.crates.containsKey(task.id)

    private fun reward(player: Player, task: ActiveTask) {
        player.skills.addXp(Constants.SAILING, task.baseXp.toDouble())
        val tier = BagTier.forXp(task.baseXp)
        if (Utils.random(COIN_BAG_IN) < COIN_BAG_WEIGHT) {
            player.inventory.addCoins(task.coins)
            player.sendMessage("Delivered! You receive ${task.baseXp} Sailing xp and a ${tier.name.lowercase()} port coin bag holding ${task.coins} coins.")
        } else {
            REWARD_BAGS.getValue(tier).forEach { (item, amount) -> player.inventory.addItemDrop(item, amount) }
            player.sendMessage("Delivered! You receive ${task.baseXp} Sailing xp and a ${tier.name.lowercase()} port reward bag.")
        }
    }

    private fun listTasks(player: Player) {
        val tasks = SailorStore.of(player.username).tasks
        if (tasks.isEmpty()) return player.sendMessage("You have no tasks.")
        tasks.forEach { player.sendMessage("${it.crates} crate(s), port ${it.from} to port ${it.to}, ${it.baseXp} xp (${if (it.collected) "loaded" else "not yet collected"}).") }
    }

    private fun abandonMenu(player: Player) {
        val profile = SailorStore.of(player.username)
        if (profile.tasks.isEmpty()) return player.sendMessage("You have no tasks.")
        player.sendOptionsDialogue("Abandon which task?") {
            profile.tasks.take(SLOTS_SHOWN).forEach { task ->
                opExec("${task.crates}x ${task.from} to ${task.to}") { abandon(player, task) }
            }
        }
    }

    private fun abandon(player: Player, task: ActiveTask) {
        val profile = SailorStore.of(player.username)
        if (!profile.tasks.remove(task)) return
        BoatStore.ownedBy(player.username).forEach { boat -> if (boat.hold.crates.remove(task.id) != null) BoatStore.save(boat) }
        SailorStore.save(profile)
        player.sendMessage("You abandon the task.")
    }
}
