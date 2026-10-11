package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import java.util.Random

enum class BountyMonster(val npc: Int, val label: String, val first: Int, val second: Int, val minLevel: Int, val taskXp: Int, val items: Int) {
    LOBSTER(Npc.giant_lobster, "giant lobster", Obj.sailing_bounty_lobster_claw, Obj.sailing_bounty_lobster_shell, 30, 3_465, 8),
    SLUG_LOBSTER(Npc.slug2_giant_lobster, "slug lobster", Obj.sailing_bounty_slug_claw, Obj.sailing_bounty_slug_shell, 40, 8_800, 10),
    SEA_TROLL(Npc.swan_troll_battle, "sea troll", Obj.sailing_bounty_troll_tusk, Obj.sailing_bounty_troll_hide, 50, 14_575, 12),
    SEA_SNAKE(Npc.royal_sea_snake_medium, "sea snake", Obj.sailing_bounty_snake_fang, Obj.sailing_bounty_snake_scale, 60, 25_135, 14),
    GIANT_SEA_SNAKE(Npc.royal_sea_snake_mother_smaller, "giant sea snake", Obj.sailing_bounty_mother_fang, Obj.sailing_bounty_mother_scale, 75, 40_370, 16);

    val drops: IntArray get() = intArrayOf(first, second)

    companion object {
        fun ofNpc(npc: Int): BountyMonster? = entries.firstOrNull { it.npc == npc }
    }
}

object BountyTasks {
    private const val EPOCH_MILLIS = 1_800_000L
    private const val SALT = 0xB0B0L
    private const val NAME_MIX = 31L
    private const val ETERNAL_PERCENT = 6
    private const val ABYSSAL_PERCENT = 3
    private const val PERCENT = 100

    fun hunted(profile: SailorProfile): BountyMonster? =
        profile.tasks.firstOrNull { it.kind == TaskKind.BOUNTY && it.monster != ActiveTask.NO_MONSTER }?.let { BountyMonster.entries.getOrNull(it.monster) }

    fun taskId(site: String, monster: BountyMonster): Long = (site.hashCode().toLong() shl 32) xor ((monster.ordinal + 1).toLong() shl 8) xor SALT

    fun offer(site: PortSite, sailingLevel: Int): PortTask? {
        if (!PortTasks.canTakeBounty(sailingLevel)) return null
        val eligible = BountyMonster.entries.filter { it.minLevel <= sailingLevel }
        if (eligible.isEmpty()) return null
        val random = Random((site.name.hashCode() * NAME_MIX + System.currentTimeMillis() / EPOCH_MILLIS) xor SALT)
        val monster = eligible[random.nextInt(eligible.size)]
        val port = site.asPort()
        return PortTask(TaskKind.BOUNTY, port, port, monster.items, monster.taskXp, 0, monster.ordinal)
    }

    fun itemsHeld(boats: List<Boat>, monster: BountyMonster): Int = boats.sumOf { boat -> monster.drops.sumOf { boat.hold.goods[it] ?: 0 } }

    fun onKill(player: Player, voyage: Voyage, npc: Int) {
        val monster = BountyMonster.ofNpc(npc) ?: return
        val profile = SailorStore.of(player.username)
        val task = profile.tasks.firstOrNull { it.kind == TaskKind.BOUNTY && it.monster == monster.ordinal } ?: return
        if (itemsHeld(listOf(voyage.boat), monster) >= task.crates) return
        val item = monster.drops[Utils.random(monster.drops.size)]
        if (voyage.boat.hold.addGood(item, 1) > 0) player.sendMessage("The ${monster.label} leaves something for your bounty (${itemsHeld(listOf(voyage.boat), monster)}/${task.crates}).")
    }

    fun claim(player: Player, site: PortSite) {
        val profile = SailorStore.of(player.username)
        val boats = BoatStore.ownedBy(player.username).filter { it.state == BoatState.DOCKED && it.dockPort == site.name }
        val done = profile.tasks.filter { it.kind == TaskKind.BOUNTY }.filter { task ->
            BountyMonster.entries.getOrNull(task.monster)?.let { itemsHeld(boats, it) >= task.crates } == true
        }
        if (done.isEmpty()) return player.sendMessage("You have no finished bounty here. Keep the bounty items in the hold of a boat moored at this port.")
        done.forEach { task ->
            val monster = BountyMonster.entries[task.monster]
            var remaining = task.crates
            boats.forEach { boat ->
                monster.drops.forEach { item ->
                    val have = boat.hold.goods[item] ?: 0
                    val taken = minOf(have, remaining)
                    if (taken > 0) {
                        remaining -= taken
                        if (have - taken > 0) boat.hold.goods[item] = have - taken else boat.hold.goods.remove(item)
                    }
                }
                BoatStore.save(boat)
            }
            profile.tasks.remove(task)
            profile.tasksCompleted++
            Courier.reward(player, task)
            bonusSchematic(player, monster)
        }
        SailorStore.save(profile)
    }

    private fun bonusSchematic(player: Player, monster: BountyMonster) {
        val item = when {
            monster == BountyMonster.GIANT_SEA_SNAKE && Utils.random(PERCENT) < ABYSSAL_PERCENT -> Obj.sailing_abyssal_schematic
            monster >= BountyMonster.SEA_TROLL && Utils.random(PERCENT) < ETERNAL_PERCENT -> Obj.sailing_eternal_schematic
            else -> return
        }
        player.inventory.addItemDrop(item, 1)
        player.sendMessage("The bounty officer slips you a rolled-up shipwright schematic.")
    }
}
