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
import world.gregs.voidps.type.Tile
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onDropItem
import com.rs.plugin.kts.onItemClick
import com.rs.plugin.kts.onLogin
import com.rs.plugin.kts.onNpcClick
import com.rs.plugin.kts.onNpcDeath
import org.darkan.core.model.Item

private const val RANTZ = 4471

private const val CHOMPY_BIRD = 1550
private const val DEAD_CHOMPY_BIRD = 1016

private const val FEATHER = 314
private const val CHOMPY_PLUCK_FEATHERS = 5

const val CHOMPY_KILLS = "big_chompy_kills"

private const val COMP_OGRE_BOW = 4827

private const val QUEST_CHOMPY_ACTIVE = "bigChompyQuestChompyActive"
private const val DEAD_CHOMPY_ACTIVE = "bigChompyDeadChompyActive"

private const val BAIT_MIN_X = 2639
private const val BAIT_MAX_X = 2655
private const val BAIT_MIN_Y = 2948
private const val BAIT_MAX_Y = 2959

private val FIRST_CHOMPY_SPAWN = Tile.of(2647, 2947, 0)

private class QuestChompy(
    owner: Player,
    tile: Tile
) : OwnedNPC(
    owner,
    CHOMPY_BIRD,
    tile,
    true
) {

    override fun handlePreHit(hit: Hit) {
        super.handlePreHit(hit)

        val player = hit.source as? Player
            ?: return

        if (!hasValidChompyWeapon(player)) {
            hit.damage = 0

            player.sendMessage(
                "You need to use an ogre bow and suitable arrows to hunt the chompy bird."
            )
        }
    }

    override fun onDespawnEarly() {
        owner.set(
            QUEST_CHOMPY_ACTIVE,
            false
        )
    }
}

private class QuestChompyCorpse(
    owner: Player,
    tile: Tile
) : OwnedNPC(
    owner,
    DEAD_CHOMPY_BIRD,
    tile,
    true
) {

    override fun onDespawnEarly() {
        owner.set(
            DEAD_CHOMPY_ACTIVE,
            false
        )
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

    onDropItem(BLOATED_TOAD) { event ->
        val player = event.player

        when (
            player.getQuestStage(
                Quest.BIG_CHOMPY_BIRD_HUNTING
            )
        ) {
            STAGE_BAITING_CHOMPY -> {
                handleRantzBait(
                    player,
                    event
                )
            }

            STAGE_HUNTING_CHOMPY -> {
                handlePlayerBait(
                    player,
                    event
                )
            }

            STAGE_COOKING_CHOMPY -> {
                handleCookingStageRecoveryBait(
                    player,
                    event
                )
            }

            STAGE_COMPLETE -> {
                handlePlayerBait(
                    player,
                    event
                )
            }
        }
    }

    onItemClick(
        OGRE_BOW,
        COMP_OGRE_BOW,
        options = arrayOf("Check kills")
    ) { event ->
        val kills = getChompyKills(
            event.player
        )

        event.player.sendMessage(
            "You have killed $kills chompy ${if (kills == 1) "bird" else "birds"}."
        )
    }

    onNpcDeath(CHOMPY_BIRD) { event ->

        val chompy = event.npc as? QuestChompy
            ?: return@onNpcDeath

        val player = chompy.owner

        if (event.killer !== player) {
            return@onNpcDeath
        }

        if (!canHuntQuestChompy(player)) {
            return@onNpcDeath
        }

        if (!player.getBool(QUEST_CHOMPY_ACTIVE)) {
            return@onNpcDeath
        }

        player.set(
            QUEST_CHOMPY_ACTIVE,
            false
        )

        addChompyKill(
            player
        )

        player.set(
            DEAD_CHOMPY_ACTIVE,
            true
        )

        val deadChompy = QuestChompyCorpse(
            player,
            chompy.tile
        )

        deadChompy.setRandomWalk(false)
    }

    onNpcClick(
        DEAD_CHOMPY_BIRD,
        options = arrayOf("Pluck")
    ) { event ->

        val player = event.player

        val chompy = event.npc as? QuestChompyCorpse
            ?: return@onNpcClick

        if (chompy.owner !== player) {
            return@onNpcClick
        }

        if (!canHuntQuestChompy(player)) {
            return@onNpcClick
        }

        if (!player.getBool(DEAD_CHOMPY_ACTIVE)) {
            return@onNpcClick
        }

        pluckQuestChompy(
            player,
            chompy
        )
    }
}

fun getChompyKills(
    player: Player
): Int {
    return player.getI(
        CHOMPY_KILLS,
        0
    )
}

private fun addChompyKill(
    player: Player
) {
    player.set(
        CHOMPY_KILLS,
        getChompyKills(player) + 1
    )
}

private fun hasValidChompyWeapon(
    player: Player
): Boolean {
    val weaponId = player.equipment.weaponId

    if (
        weaponId != OGRE_BOW &&
        weaponId != COMP_OGRE_BOW
    ) {
        return false
    }

    val weapon = RangedWeapon.forId(
        weaponId
    ) ?: return false

    val ammo = AmmoType.forId(
        player.equipment.ammoId
    ) ?: return false

    return weapon.ammos?.contains(ammo) == true
}

private fun handleRantzBait(
    player: Player,
    event: com.rs.plugin.events.DropItemEvent
) {
    if (!isInRantzClearing(player.tile)) {
        event.cancelDrop()

        player.sendMessage(
            "This is too far away for Rantz to shoot the chompy bird."
        )

        return
    }

    event.cancelDrop()

    if (
        !player.inventory.containsItem(
            BLOATED_TOAD,
            1
        )
    ) {
        return
    }

    player.inventory.deleteItem(
        BLOATED_TOAD,
        1
    )

    val bait = World.addGroundItemNoExpire(
        Item(BLOATED_TOAD, 1),
        player.tile,
        player
    )

    firstChompyBaitDialogue(
        player,
        bait?.tile ?: player.tile
    )
}

private fun firstChompyBaitDialogue(
    player: Player,
    bait.tile: Tile
) {
    player.startConversation {

        player(
            CALM_TALK,
            "There you go, I've placed the bait."
        )

        npc(
            RANTZ,
            CALM_TALK,
            "Goodz, me now waits for da chompy! It shouldn't be long now. Sneaky...sneaky..."
        )

        player(
            CALM_TALK,
            "Yes, I know...stick da chompy!"
        )

        npc(
            RANTZ,
            CALM_TALK,
            "Hey..you's creature, is da fatsy toady still dere? Go get more fatsy toadies if dey all gone!"
        )

        player(
            CALM_TALK,
            "What? I have to get more bait if there's none there? Does this Chompy Bird even exist I wonder?"
        )

        exec {
            beginRantzHunt(
                player,
                bait.tile
            )
        }
    }
}

private fun beginRantzHunt(
    player: Player,
    bait.tile: Tile
) {
    player.schedule {

        wait(4)

        World.removeGroundItem(player, bait)

        val chompy = OwnedNPC(
            player,
            CHOMPY_BIRD,
            FIRST_CHOMPY_SPAWN,
            true
        )

        chompy.setRandomWalk(false)
        chompy.setAutoDespawnAtDistance(false)
        chompy.isCantInteract = true

        chompy.faceTile(
            bait.tile
        )

        player.startConversation {

            npc(
                chompy,
                HAPPY_TALKING,
                "Sqwark! Gobble!"
            )

            npc(
                RANTZ,
                HAPPY_TALKING,
                "Hey, dere's da chompy, I's gonna shoot it!"
            )

            exec {
                rantzMissesChompy(
                    player,
                    chompy
                )
            }
        }
    }
}

private fun rantzMissesChompy(
    player: Player,
    chompy: OwnedNPC
) {
    player.schedule {

        repeat(3) {

            val rantz = World.getNPCsInChunkRange(
                player.chunkId,
                1
            ).firstOrNull {
                it.id == RANTZ
            }

            if (rantz != null) {
                rantz.faceEntity(
                    chompy
                )

                rantz.anim(
                    3460
                )
            }

            wait(3)
        }

        chompy.finish()

        player.setQuestStage(
            Quest.BIG_CHOMPY_BIRD_HUNTING,
            STAGE_RANTZ_FAILED_HUNT
        )
    }
}

private fun handlePlayerBait(
    player: Player,
    event: com.rs.plugin.events.DropItemEvent
) {
    if (!isInRantzClearing(player.tile)) {
        event.cancelDrop()

        player.sendMessage(
            "This is too far away to attract a chompy."
        )

        return
    }

    if (
        player.getBool(QUEST_CHOMPY_ACTIVE) ||
        player.getBool(DEAD_CHOMPY_ACTIVE)
    ) {
        event.cancelDrop()

        player.sendMessage(
            "You already have a chompy to deal with."
        )

        return
    }

    event.cancelDrop()

    if (
        !player.inventory.containsItem(
            BLOATED_TOAD,
            1
        )
    ) {
        return
    }

    player.inventory.deleteItem(
        BLOATED_TOAD,
        1
    )

    val bait = World.addGroundItemNoExpire(
        Item(BLOATED_TOAD, 1),
        player.tile,
        player
    )

    attractQuestChompy(
        player,
        bait?.tile ?: player.tile
    )
}

private fun handleCookingStageRecoveryBait(
    player: Player,
    event: com.rs.plugin.events.DropItemEvent
) {
    if (
        player.inventory.containsItem(RAW_CHOMPY, 1) ||
        player.inventory.containsItem(SEASONED_CHOMPY, 1)
    ) {
        event.cancelDrop()

        player.sendMessage(
            "You already have the chompy you need."
        )

        return
    }

    handlePlayerBait(
        player,
        event
    )
}

private fun attractQuestChompy(
    player: Player,
    bait.tile: Tile
) {
    player.schedule {

        wait(4)

        if (!canHuntQuestChompy(player)) {
            World.removeGroundItem(player, bait)

            return@schedule
        }

        if (
            player.getQuestStage(
                Quest.BIG_CHOMPY_BIRD_HUNTING
            ) == STAGE_COOKING_CHOMPY &&
            (
                player.inventory.containsItem(RAW_CHOMPY, 1) ||
                player.inventory.containsItem(SEASONED_CHOMPY, 1)
            )
        ) {
            World.removeGroundItem(player, bait)

            return@schedule
        }

        if (
            player.getBool(QUEST_CHOMPY_ACTIVE) ||
            player.getBool(DEAD_CHOMPY_ACTIVE)
        ) {
            World.removeGroundItem(player, bait)

            return@schedule
        }

        World.removeGroundItem(player, bait)

        val chompy = QuestChompy(
            player,
            FIRST_CHOMPY_SPAWN
        )

        chompy.setRandomWalk(false)
        chompy.setAutoDespawnAtDistance(false)
        chompy.isCantInteract = false

        chompy.faceTile(
            bait.tile
        )

        player.set(
            QUEST_CHOMPY_ACTIVE,
            true
        )

        chompy.forceTalk(
            "Sqwark! Gobble!"
        )
    }
}

private fun pluckQuestChompy(
    player: Player,
    chompy: QuestChompyCorpse
) {
    if (
        player.getQuestStage(
            Quest.BIG_CHOMPY_BIRD_HUNTING
        ) == STAGE_COOKING_CHOMPY &&
        (
            player.inventory.containsItem(RAW_CHOMPY, 1) ||
            player.inventory.containsItem(SEASONED_CHOMPY, 1)
        )
    ) {
        player.sendMessage(
            "You already have the chompy you need."
        )

        player.set(
            DEAD_CHOMPY_ACTIVE,
            false
        )

        chompy.finish()
        return
    }

    player.faceEntity(
        chompy
    )

    player.sendMessage(
        "You pluck the chompy bird..."
    )

    player.inventory.addItemDrop(
        FEATHER,
        CHOMPY_PLUCK_FEATHERS
    )

    player.inventory.addItemDrop(
        RAW_CHOMPY,
        1
    )

    player.sendMessage(
        "You get some feathers and raw chompy bird meat."
    )

    player.set(
        DEAD_CHOMPY_ACTIVE,
        false
    )

    chompy.finish()
}

private fun canHuntQuestChompy(
    player: Player
): Boolean {
    return when (
        player.getQuestStage(
            Quest.BIG_CHOMPY_BIRD_HUNTING
        )
    ) {
        STAGE_HUNTING_CHOMPY,
        STAGE_COOKING_CHOMPY,
        STAGE_COMPLETE -> true

        else -> false
    }
}

private fun isInRantzClearing(
    tile: Tile
): Boolean {
    return tile.level == 0 &&
            tile.x in BAIT_MIN_X..BAIT_MAX_X &&
            tile.y in BAIT_MIN_Y..BAIT_MAX_Y
}