package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.Utils
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
    fun generate(site: PortSite, sailingLevel: Int): List<PortTask> = listOfNotNull(BountyTasks.offer(site, sailingLevel)) + courierTasks(site, sailingLevel)

    private fun courierTasks(site: PortSite, sailingLevel: Int): List<PortTask> {
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

    fun taskId(task: PortTask): Long = if (task.kind == TaskKind.BOUNTY) BountyTasks.taskId(task.from.name, BountyMonster.entries[task.monster]) else (task.from.name.hashCode().toLong() shl 32) xor task.to.name.hashCode().toLong() xor (task.crates.toLong() shl 24) xor task.baseXp.toLong()

    fun open(player: Player, site: PortSite) {
        SailingScreens.openNoticeBoard(player, site)
    }

    fun accept(player: Player, task: PortTask) {
        val profile = SailorStore.of(player.username)
        val id = taskId(task)
        if (profile.tasks.size >= PortTasks.slots(player.skills.getLevel(Constants.SAILING)) || profile.tasks.any { it.id == id }) return
        profile.tasks.add(ActiveTask(id, task.kind, task.from.name, task.to.name, task.crates, task.baseXp, task.coins, monster = task.monster))
        SailorStore.save(profile)
        player.sendMessage(
            if (task.kind == TaskKind.BOUNTY) "Bounty taken: bring back ${task.crates} trophies from ${BountyMonster.entries[task.monster].label}s for ${task.baseXp} Sailing xp and a ${task.bag.name.lowercase()} bag. Keep them in the hold and claim at a Port master."
            else "Task taken: carry ${task.crates} crate(s) from port ${task.from.name} to port ${task.to.name} for ${task.baseXp} Sailing xp and a ${task.bag.name.lowercase()} bag. Collect the crates here with a boat moored at this port."
        )
    }

    fun collect(player: Player, site: PortSite) {
        val profile = SailorStore.of(player.username)
        val pending = profile.tasks.filter { it.kind == TaskKind.COURIER && it.from == site.name && !it.collected }
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
        val due = profile.tasks.filter { it.kind == TaskKind.COURIER && it.to == site.name && it.collected }
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

    fun reward(player: Player, task: ActiveTask) {
        SailingXp.grant(player, BoatStore.ownedBy(player.username).firstOrNull { it.dockPort == task.to && it.state == BoatState.DOCKED }, task.baseXp.toDouble())
        val tier = BagTier.forXp(task.baseXp)
        if (Utils.random(COIN_BAG_IN) < COIN_BAG_WEIGHT) {
            player.inventory.addItemDrop(PortBags.coinBag(tier, task.coins))
            player.sendMessage("Delivered! You receive ${task.baseXp} Sailing xp and a ${tier.name.lowercase()} port coin bag.")
        } else {
            player.inventory.addItemDrop(PortBags.rewardBag(tier))
            player.sendMessage("Delivered! You receive ${task.baseXp} Sailing xp and a ${tier.name.lowercase()} port reward bag.")
        }
    }

    fun abandon(player: Player, task: ActiveTask) {
        val profile = SailorStore.of(player.username)
        if (!profile.tasks.remove(task)) return
        BoatStore.ownedBy(player.username).forEach { boat -> if (boat.hold.crates.remove(task.id) != null) BoatStore.save(boat) }
        SailorStore.save(profile)
        player.sendMessage("You abandon the task.")
    }
}
