package com.rs.game.content.world.generated

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onNpcClick
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture

private const val TALK_OPTION = "Talk-to"
private const val SPAWN_CHECK_TICKS = 10
private const val SPAWN_EVERY_TICK = 1
private const val SPAWN_BUDGET_NANOS = 50_000_000L
private const val MASTER_OFFSET_X = 1
private const val GROUND_LEVEL = 0

class SlayerMasterNPC(tile: Tile) : NPC(Npc.slayer_master_unkillable, tile, null, true) {

    init {
        setRandomWalk(false)
    }

    override fun handlePreHit(hit: Hit) {
        hit.damage = 0
    }

    override fun removeHitpoints(hit: Hit) {}

    override fun canBeAttackedBy(player: Player): Boolean = false

    override fun canAggroPlayer(target: Player): Boolean = false
}

private fun talkToSlayerMaster(player: Player) {
    player.startConversation {
        npc(Npc.slayer_master_unkillable, HeadE.CALM_TALK, "Greetings, adventurer. Do you seek a Slayer task?")
        options {
            if (SlayerAssignments.hasTask(player)) {
                op("Abandon my task.") {
                    player(HeadE.CALM_TALK, "I would like to abandon my task.")
                    exec { SlayerAssignments.abandon(player) }
                    npc(Npc.slayer_master_unkillable, HeadE.CALM_TALK, "Very well. The creatures have scattered.")
                }
            } else {
                op("I need a Slayer task.") {
                    player(HeadE.CALM_TALK, "I need a Slayer task.")
                    npc(Npc.slayer_master_unkillable, HeadE.HAPPY_TALKING, "Creatures suited to the danger of this land gather around you. Slay every one of them.")
                    exec {
                        if (!SlayerAssignments.start(player)) player.sendMessage("There is no room around you for the creatures to gather.")
                    }
                }
            }
            op("Goodbye.") {
                player(HeadE.CALM_TALK, "Goodbye.")
            }
        }
    }
}

@ServerStartupEvent
fun startSlayerMasters() {
    onNpcClick(Npc.slayer_master_unkillable, options = arrayOf(TALK_OPTION)) { (player, _) -> talkToSlayerMaster(player) }
    if (!EnvVars.generateMaps) return
    val sites = CompletableFuture.supplyAsync { GeneratedWorld.lodestoneSites }
    val queue = ArrayDeque<Pair<Int, Int>>()
    var queued = false
    WorldTasks.scheduleTimer(SPAWN_CHECK_TICKS, SPAWN_EVERY_TICK) {
        if (!queued) {
            if (!sites.isDone) return@scheduleTimer true
            queue.addAll(sites.get())
            queued = true
        }
        val deadline = System.nanoTime() + SPAWN_BUDGET_NANOS
        while (queue.isNotEmpty() && System.nanoTime() < deadline) {
            val (x, y) = queue.removeFirst()
            SlayerMasterNPC(Tile.of(x + MASTER_OFFSET_X, y, GROUND_LEVEL))
        }
        queue.isNotEmpty()
    }
}
