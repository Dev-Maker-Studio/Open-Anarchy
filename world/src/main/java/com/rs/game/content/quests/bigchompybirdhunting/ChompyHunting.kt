package com.rs.game.content.quests.bigchompybirdhunting

import com.rs.engine.dialogue.HeadE.CALM_TALK
import com.rs.engine.dialogue.HeadE.HAPPY_TALKING
import com.rs.engine.dialogue.startConversation
import com.rs.engine.quest.Quest
import com.rs.game.World
import com.rs.game.content.combat.AmmoType
import com.rs.game.content.combat.RangedWeapon
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.async.schedule
import com.rs.game.model.entity.npc.OwnedNPC
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.events.DropItemEvent
import com.rs.plugin.kts.onDropItem
import com.rs.plugin.kts.onItemClick
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onNpcDeath
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.model.GroundItem
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile

const val CHOMPY_KILLS = "big_chompy_kills"

private const val CHOMPY_PLUCK_FEATHERS = 5
private const val QUEST_CHOMPY_ACTIVE = "bigChompyQuestChompyActive"
private const val DEAD_CHOMPY_ACTIVE = "bigChompyDeadChompyActive"

private val FIRST_CHOMPY_SPAWN = Tile.of(2647, 2947, 0)

private class QuestChompy(owner: Player, tile: Tile) : OwnedNPC(owner, Npc.chompybird, tile, true) {
    override fun handlePreHit(hit: Hit) {
        super.handlePreHit(hit)
        val player = hit.source as? Player ?: return
        if (!hasValidChompyWeapon(player)) {
            hit.damage = 0
            player.sendMessage("You need to use an ogre bow and suitable arrows to hunt the chompy bird.")
        }
    }

    override fun onDespawnEarly() {
        owner.set(QUEST_CHOMPY_ACTIVE, false)
    }
}

private class QuestChompyCorpse(owner: Player, tile: Tile) : OwnedNPC(owner, Npc.chompybird_dead, tile, true) {
    override fun onDespawnEarly() {
        owner.set(DEAD_CHOMPY_ACTIVE, false)
    }
}

@ServerStartupEvent
fun mapBigChompyHunting() {
    onLogin { (player) ->
        if (player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) in STAGE_HUNTING_CHOMPY..STAGE_COMPLETE) {
            player.set(QUEST_CHOMPY_ACTIVE, false)
            player.set(DEAD_CHOMPY_ACTIVE, false)
        }
    }

    onDropItem(Obj.bloated_toad) { event ->
        val player = event.player
        when (player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING)) {
            STAGE_BAITING_CHOMPY -> handleRantzBait(player, event)
            STAGE_HUNTING_CHOMPY, STAGE_COMPLETE -> handlePlayerBait(player, event)
            STAGE_COOKING_CHOMPY -> handleCookingStageRecoveryBait(player, event)
        }
    }

    onItemClick(Obj.ogre_bow, Obj.obj_4827, options = arrayOf("Check kills")) { (player) ->
        val kills = getChompyKills(player)
        player.sendMessage("You have killed $kills chompy ${if (kills == 1) "bird" else "birds"}.")
    }

    onNpcDeath(Npc.chompybird) { event ->
        val chompy = event.npc as? QuestChompy ?: return@onNpcDeath
        val player = chompy.owner
        if (event.killer !== player || !canHuntQuestChompy(player) || !player.getBool(QUEST_CHOMPY_ACTIVE))
            return@onNpcDeath
        player.set(QUEST_CHOMPY_ACTIVE, false)
        addChompyKill(player)
        player.set(DEAD_CHOMPY_ACTIVE, true)
        QuestChompyCorpse(player, chompy.tile).setRandomWalk(false)
    }

    onNpcClick(Npc.chompybird_dead, options = arrayOf("Pluck")) { (player, npc) ->
        val chompy = npc as? QuestChompyCorpse ?: return@onNpcClick
        if (chompy.owner !== player || !canHuntQuestChompy(player) || !player.getBool(DEAD_CHOMPY_ACTIVE))
            return@onNpcClick
        pluckQuestChompy(player, chompy)
    }
}

fun getChompyKills(player: Player): Int = player.getI(CHOMPY_KILLS, 0)

private fun addChompyKill(player: Player) = player.set(CHOMPY_KILLS, getChompyKills(player) + 1)

private fun hasValidChompyWeapon(player: Player): Boolean {
    val weaponId = player.equipment.weaponId
    if (weaponId != Obj.ogre_bow && weaponId != Obj.obj_4827) return false
    val weapon = RangedWeapon.forId(weaponId) ?: return false
    val ammo = AmmoType.forId(player.equipment.ammoId) ?: return false
    return weapon.ammos?.contains(ammo) == true
}

private fun placeBait(player: Player, event: DropItemEvent): GroundItem? {
    event.cancelDrop()
    if (!player.inventory.containsItem(Obj.bloated_toad, 1)) return null
    player.inventory.deleteItem(Obj.bloated_toad, 1)
    return World.addGroundItemNoExpire(Item(Obj.bloated_toad, 1), player.tile, player)
}

private fun handleRantzBait(player: Player, event: DropItemEvent) {
    if (!isInRantzClearing(player.tile)) {
        event.cancelDrop()
        player.sendMessage("This is too far away for Rantz to shoot the chompy bird.")
        return
    }
    if (!player.inventory.containsItem(Obj.bloated_toad, 1)) {
        event.cancelDrop()
        return
    }
    val baitTile = player.tile
    firstChompyBaitDialogue(player, placeBait(player, event), baitTile)
}

private fun firstChompyBaitDialogue(player: Player, bait: GroundItem?, baitTile: Tile) {
    player.startConversation {
        player(CALM_TALK, "There you go, I've placed the bait.")
        npc(Npc.npc_8659, CALM_TALK, "Goodz, me now waits for da chompy! It shouldn't be long now. Sneaky...sneaky...")
        player(CALM_TALK, "Yes, I know...stick da chompy!")
        npc(Npc.npc_8659, CALM_TALK, "Hey..you's creature, is da fatsy toady still dere? Go get more fatsy toadies if dey all gone!")
        player(CALM_TALK, "What? I have to get more bait if there's none there? Does this Chompy Bird even exist I wonder?")
        exec { beginRantzHunt(player, bait, baitTile) }
    }
}

private fun beginRantzHunt(player: Player, bait: GroundItem?, baitTile: Tile) {
    player.schedule {
        wait(4)
        bait?.let { World.removeGroundItem(it) }
        val chompy = OwnedNPC(player, Npc.chompybird, FIRST_CHOMPY_SPAWN, true)
        chompy.setRandomWalk(false)
        chompy.setAutoDespawnAtDistance(false)
        chompy.isCantInteract = true
        chompy.faceTile(baitTile)
        player.startConversation {
            npc(chompy, HAPPY_TALKING, "Sqwark! Gobble!")
            npc(Npc.npc_8659, HAPPY_TALKING, "Hey, dere's da chompy, I's gonna shoot it!")
            exec { rantzMissesChompy(player, chompy) }
        }
    }
}

private fun rantzMissesChompy(player: Player, chompy: OwnedNPC) {
    player.schedule {
        repeat(3) {
            World.getNPCsInChunkRange(player.chunkId, 1).firstOrNull { it.id == Npc.npc_8659 }?.let { rantz ->
                rantz.faceEntity(chompy)
                rantz.anim(Seq._100_jubbly_ogre_bow)
            }
            wait(3)
        }
        chompy.finish()
        player.setQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING, STAGE_RANTZ_FAILED_HUNT)
    }
}

private fun handlePlayerBait(player: Player, event: DropItemEvent) {
    if (!isInRantzClearing(player.tile)) {
        event.cancelDrop()
        player.sendMessage("This is too far away to attract a chompy.")
        return
    }
    if (player.getBool(QUEST_CHOMPY_ACTIVE) || player.getBool(DEAD_CHOMPY_ACTIVE)) {
        event.cancelDrop()
        player.sendMessage("You already have a chompy to deal with.")
        return
    }
    if (!player.inventory.containsItem(Obj.bloated_toad, 1)) {
        event.cancelDrop()
        return
    }
    val baitTile = player.tile
    attractQuestChompy(player, placeBait(player, event), baitTile)
}

private fun hasQuestChompy(player: Player) =
    player.inventory.containsItem(Obj.raw_chompy, 1) || player.inventory.containsItem(Obj.cooked_s_chompy, 1)

private fun handleCookingStageRecoveryBait(player: Player, event: DropItemEvent) {
    if (hasQuestChompy(player)) {
        event.cancelDrop()
        player.sendMessage("You already have the chompy you need.")
        return
    }
    handlePlayerBait(player, event)
}

private fun attractQuestChompy(player: Player, bait: GroundItem?, baitTile: Tile) {
    player.schedule {
        wait(4)
        bait?.let { World.removeGroundItem(it) }
        if (!canHuntQuestChompy(player)) return@schedule
        if (player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) == STAGE_COOKING_CHOMPY && hasQuestChompy(player)) return@schedule
        if (player.getBool(QUEST_CHOMPY_ACTIVE) || player.getBool(DEAD_CHOMPY_ACTIVE)) return@schedule
        val chompy = QuestChompy(player, FIRST_CHOMPY_SPAWN)
        chompy.setRandomWalk(false)
        chompy.setAutoDespawnAtDistance(false)
        chompy.isCantInteract = false
        chompy.faceTile(baitTile)
        player.set(QUEST_CHOMPY_ACTIVE, true)
        chompy.forceTalk("Sqwark! Gobble!")
    }
}

private fun pluckQuestChompy(player: Player, chompy: QuestChompyCorpse) {
    if (player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) == STAGE_COOKING_CHOMPY && hasQuestChompy(player)) {
        player.sendMessage("You already have the chompy you need.")
        player.set(DEAD_CHOMPY_ACTIVE, false)
        chompy.finish()
        return
    }
    player.faceEntity(chompy)
    player.sendMessage("You pluck the chompy bird...")
    player.inventory.addItemDrop(Obj.feather, CHOMPY_PLUCK_FEATHERS)
    player.inventory.addItemDrop(Obj.raw_chompy, 1)
    player.sendMessage("You get some feathers and raw chompy bird meat.")
    player.set(DEAD_CHOMPY_ACTIVE, false)
    chompy.finish()
}

private fun canHuntQuestChompy(player: Player): Boolean =
    player.getQuestStage(Quest.BIG_CHOMPY_BIRD_HUNTING) in setOf(STAGE_HUNTING_CHOMPY, STAGE_COOKING_CHOMPY, STAGE_COMPLETE)

private fun isInRantzClearing(tile: Tile): Boolean = tile.level == 0 && tile.x in 2639..2655 && tile.y in 2948..2959
