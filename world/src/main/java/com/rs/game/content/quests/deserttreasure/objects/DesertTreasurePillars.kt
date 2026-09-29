package com.rs.game.content.quests.deserttreasure.objects

import com.rs.engine.quest.Quest
import com.rs.game.content.quests.deserttreasure.DesertTreasureDiamonds
import com.rs.game.content.quests.deserttreasure.STAGE_IFTAH_YA_SIMSIM
import com.rs.game.content.quests.deserttreasure.STAGE_RETURN_TO_JALDRAOCHT
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.*

private const val BLOOD_PILLAR = 6482
private const val SMOKE_PILLAR = 6485
private const val ICE_PILLAR = 6488
private const val SHADOW_PILLAR = 6491

private const val BLOOD_VARBIT = 390
private const val SMOKE_VARBIT = 387
private const val ICE_VARBIT = 389
private const val SHADOW_VARBIT = 388

private const val MAGIC_REQUIREMENT = 50

private const val BLOOD_PLACED = "dtBloodDiamondPlaced"
private const val SMOKE_PLACED = "dtSmokeDiamondPlaced"
private const val ICE_PLACED = "dtIceDiamondPlaced"
private const val SHADOW_PLACED = "dtShadowDiamondPlaced"

private data class Pillar(
    val objectId: Int,
    val x: Int,
    val y: Int,
    val diamondId: Int,
    val varbit: Int,
    val placedKey: String
)

private val pillars = arrayOf(
    Pillar(
        BLOOD_PILLAR, 3220, 2909,
        DesertTreasureDiamonds.BLOOD_DIAMOND, BLOOD_VARBIT, BLOOD_PLACED
    ),
    Pillar(
        SMOKE_PILLAR, 3244, 2909,
        DesertTreasureDiamonds.SMOKE_DIAMOND, SMOKE_VARBIT, SMOKE_PLACED
    ),
    Pillar(
        ICE_PILLAR, 3244, 2885,
        DesertTreasureDiamonds.ICE_DIAMOND, ICE_VARBIT, ICE_PLACED
    ),
    Pillar(
        SHADOW_PILLAR, 3220, 2885,
        DesertTreasureDiamonds.SHADOW_DIAMOND, SHADOW_VARBIT, SHADOW_PLACED
    )
)

@ServerStartupEvent
fun mapDesertTreasurePillars() {
    onItemOnObject(
        objectNamesOrIds = pillars.map { it.objectId }.toTypedArray(),
        itemNamesOrIds = arrayOf(
            DesertTreasureDiamonds.BLOOD_DIAMOND,
            DesertTreasureDiamonds.SMOKE_DIAMOND,
            DesertTreasureDiamonds.ICE_DIAMOND,
            DesertTreasureDiamonds.SHADOW_DIAMOND
        )
    ) { (player, obj, item) ->
        val pillar = pillars.firstOrNull {
            it.objectId == obj.id &&
                it.x == obj.x &&
                it.y == obj.y &&
                obj.plane == 0
        } ?: return@onItemOnObject

        placeDiamond(player, pillar, item.id)
    }

    onLogin { (player) ->
        syncPillarVarbits(player)
    }
}

private fun placeDiamond(player: Player, pillar: Pillar, itemId: Int) {
    if (player.getQuestStage(Quest.DESERT_TREASURE) != STAGE_IFTAH_YA_SIMSIM) {
        return
    }

    if (player.skills.getLevel(Skills.MAGIC) < MAGIC_REQUIREMENT) {
        player.sendMessage("You are not a powerful enough mage to breach the protective aura.")
        player.sendMessage("You need a magic level of at least 50 to enter the Pyramid.")
        return
    }

    if (itemId != pillar.diamondId) {
        player.sendMessage("That doesn't appear to be the correct diamond...")
        return
    }

    if (player.getBool(pillar.placedKey)) {
        return
    }

    if (!player.containsItem(pillar.diamondId)) {
        return
    }

    player.inventory.deleteItem(pillar.diamondId, 1)
    player.set(pillar.placedKey, true)

    player.vars.setVarBit(pillar.varbit, 1)

    player.sendMessage("The diamond is absorbed into the pillar...")

    if (allDiamondsPlaced(player)) {
        player.sendMessage("The force preventing access to the Pyramid has now vanished.")
        player.setQuestStage(Quest.DESERT_TREASURE, STAGE_RETURN_TO_JALDRAOCHT)
    }
}

private fun syncPillarVarbits(player: Player) {
    pillars.forEach { pillar ->
        player.vars.setVarBit(pillar.varbit, if (player.getBool(pillar.placedKey)) 1 else 0)
    }
}

private fun allDiamondsPlaced(player: Player): Boolean =
    player.getBool(BLOOD_PLACED) &&
        player.getBool(SMOKE_PLACED) &&
        player.getBool(ICE_PLACED) &&
        player.getBool(SHADOW_PLACED)
