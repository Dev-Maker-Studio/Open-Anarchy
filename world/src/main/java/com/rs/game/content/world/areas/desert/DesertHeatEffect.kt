package com.rs.game.content.world.areas.desert

import com.rs.game.content.items.EnchantedWaterTiara
import com.rs.game.content.skills.cooking.Foods
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.player.Equipment
import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.Skills
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.Constants
import org.darkan.core.model.Item
import org.darkan.core.Utils
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.*
import com.rs.utils.Areas
import com.rs.utils.Ticks

const val DESERT_HEAT_AREA = "desert_heat_area"
const val DESERT_HEAT_TASK = "desert_heat_task"
const val DESERT_HEAT_EFFECT_DELAY_ATTR = "desert_heat_effect_delay"

private val WATER_SKINS = arrayOf(Item(1823), Item(1825), Item(1827), Item(1829), Item(1831))
private val WATER_CONTAINERS = arrayOf(intArrayOf(1937, 1935), intArrayOf(1929, 1925), intArrayOf(1921, 1923), intArrayOf(227, 229))
private val CHOC_ICE = Item(6794)

private val dervishHeadM = getDervishItemDelays(20970, 20978, 6)
private val dervishTopM = getDervishItemDelays(20980, 20988, 12)
private val dervishLegsM = getDervishItemDelays(20990, 20998, 12)
private val dervishFeetM = getDervishItemDelays(21000, 21008, 6)
private val dervishHeadF = getDervishItemDelays(21010, 21018, 6)
private val dervishTopF = getDervishItemDelays(21020, 21028, 12)
private val dervishLegsF = getDervishItemDelays(21030, 21038, 12)
private val dervishFeetF = getDervishItemDelays(21040, 21048, 6)

const val HEAD_SLOT = Equipment.HEAD
const val CHEST_SLOT = Equipment.CHEST
const val LEGS_SLOT = Equipment.LEGS
const val FEET_SLOT = Equipment.FEET
const val HANDS_SLOT = Equipment.HANDS

private val slotPenalties = mapOf(
    HEAD_SLOT to 6,
    CHEST_SLOT to 24,
    LEGS_SLOT to 18,
    FEET_SLOT to 6,
    HANDS_SLOT to 6
)

private val EQUIPMENT_DELAYS = arrayOf(
    Pair(1833, 12),
    Pair(1835, 12),
    Pair(1837, 6),
    Pair(6384, 12),
    Pair(6386, 12),
    Pair(6388, 12),
    Pair(3690, 12),
    Pair(6392, 12),
    Pair(6400, 12),
    Pair(1844, 6),
    Pair(1845, 6),
    Pair(1846, 3),
).plus(
    dervishHeadM + dervishTopM + dervishLegsM + dervishFeetM +
            dervishHeadF + dervishTopF + dervishLegsF + dervishFeetF
)

val equipmentIds = EQUIPMENT_DELAYS.map { it.first }.toTypedArray()

@ServerStartupEvent
fun mapDesertHeatEffect() {

    onLogin { e ->
        if (Areas.withinArea(DESERT_HEAT_AREA, e.player.getChunkId())) {
            e.player.tempAttribs.setI(DESERT_HEAT_EFFECT_DELAY_ATTR, Utils.randomInclusive(Ticks.fromSeconds(10), Ticks.fromSeconds(15)))
        }
    }

    onChunkEnter { (entity, _) ->
        val player = entity as? Player ?: return@onChunkEnter

        if (Areas.withinArea(DESERT_HEAT_AREA, player.getChunkId())) {
            if (!player.tasks.hasTask(DESERT_HEAT_TASK)) {
                val currentDelay = player.tempAttribs.getI(DESERT_HEAT_EFFECT_DELAY_ATTR)
                if (currentDelay <= 0) {
                    player.tempAttribs.setI(DESERT_HEAT_EFFECT_DELAY_ATTR, getHeatEffectDelay(player))
                }

                player.tasks.scheduleLooping(DESERT_HEAT_TASK, 0, 0) {
                    var heatEffectDelay = player.tempAttribs.getI(DESERT_HEAT_EFFECT_DELAY_ATTR)

                    val noInterfaceOpen = !player.interfaceManager.containsChatBoxInter() &&
                            !player.interfaceManager.containsScreenInter() &&
                            player.interfaceManager.topInterface != 755

                    if (noInterfaceOpen) {
                        if (heatEffectDelay <= 1) {
                            applyEffect(player)
                            heatEffectDelay = getHeatEffectDelay(player)
                        } else {
                            heatEffectDelay -= 1
                        }
                        player.tempAttribs.setI(DESERT_HEAT_EFFECT_DELAY_ATTR, heatEffectDelay)
                    }
                }
            }
        } else {
            player.tasks.remove(DESERT_HEAT_TASK)
        }
    }

    onObjectClick(2670) { (player, obj) -> cutCactus(player, obj) }
    onItemOnObject(objectNamesOrIds = arrayOf(2670), itemNamesOrIds = arrayOf(946)) { (player, obj) -> cutCactus(player, obj) }

    onItemEquip(*equipmentIds) { e ->
        if (Areas.withinArea(DESERT_HEAT_AREA, e.player.getChunkId())) {
            var heatEffectDelay = e.player.tempAttribs.getI(DESERT_HEAT_EFFECT_DELAY_ATTR)

            EQUIPMENT_DELAYS.forEach { (equipmentId, delayIncrement) ->
                if (e.item.id == equipmentId) {
                    if (e.equip()) {
                        heatEffectDelay += delayIncrement
                    } else if (e.dequip()) {
                        heatEffectDelay -= delayIncrement
                        heatEffectDelay = heatEffectDelay.coerceAtLeast(0)
                    }
                }
            }

            e.player.tempAttribs.setI(DESERT_HEAT_EFFECT_DELAY_ATTR, heatEffectDelay)
        }
    }

}

private fun getHeatEffectDelay(player: Player): Int {
    var heatEffectDelay = Utils.randomInclusive(Ticks.fromSeconds(60), Ticks.fromSeconds(90))

    slotPenalties.forEach { (slot, penalty) ->
        val item = player.equipment.getItem(slot)
        if (hasCombatStats(item)) {
            heatEffectDelay -= penalty
        }
    }

    EQUIPMENT_DELAYS.forEach { (itemId, delayIncrement) ->
        if (player.equipment.containsOneItem(itemId))
            heatEffectDelay += delayIncrement
    }

    return heatEffectDelay.coerceAtLeast(0)
}

private fun cutCactus(player: Player, obj: GameObject): Boolean {
    if (!player.inventory.containsItem(946)) {
        player.sendMessage("You need a knife to cut this cactus.")
        return false
    }
    player.lock(1)
    player.anim(911)
    val successfulCut = Utils.skillSuccess(player.skills.getLevel(Constants.WOODCUTTING), player.auraManager.woodcuttingMul, 30, 252, 255)
    if (!successfulCut) {
        player.sendMessage("You fail to cut the cactus correctly and it gives you no water this time.")
        player.skills.addXp(Skills.WOODCUTTING, 0.4)
    } else {
        val hasRefilled = refillWaterskin(player)
        obj.setIdTemporary(obj.id + 1, Ticks.fromMinutes(1))
        if (hasRefilled) {
            player.sendMessage("You top up your skin with water from the cactus.")
            player.skills.addXp(Skills.WOODCUTTING, 10.0)
        } else {
            player.sendMessage("You have no empty waterskins to put the water in.")
        }
    }
    return true
}

private fun refillWaterskin(player: Player): Boolean {
    for (i in 1 until WATER_SKINS.size) {
        val itemId = WATER_SKINS[i].id
        if (player.inventory.containsOneItem(itemId)) {
            player.inventory.deleteItem(Item(itemId))
            player.inventory.addItem(Item(itemId - 2))
            return true
        }
    }
    return false
}

private fun checkAndDrinkWater(player: Player): Boolean {
    if (EnchantedWaterTiara(player).depleteEnchantedWaterTiara()) {
        return true
    }

    val sortedWaterskins = WATER_SKINS.sortedByDescending { it.id }
    for (waterskin in sortedWaterskins) {
        if (waterskin.id != 1831 && player.inventory.containsOneItem(waterskin.id)) {
            val newWaterskinId = waterskin.id + 2
            player.inventory.deleteItem(Item(waterskin.id))
            player.inventory.addItem(Item(newWaterskinId))
            player.anim(829)
            player.sendMessage("You take a drink of water from the waterskin.")
            player.soundEffect(2401, false)
            return true
        }
    }

    val chocIce = player.inventory.getItemById(CHOC_ICE.id)
    if (chocIce != null) {
        Foods.eat(player, CHOC_ICE, chocIce.slot, null)
        player.sendMessage("And it cools you down.")
        return true
    }

    val message = if (player.inventory.containsOneItem(1831)) "Perhaps you should fill up one of your empty waterskins."
    else "You should get a waterskin for any travelling in the desert."
    player.sendMessage(message, true)
    return false
}

private fun evaporateWater(player: Player) {
    WATER_CONTAINERS.forEach { (fullContainerId, emptyContainerId) ->
        if (player.inventory.containsOneItem(fullContainerId)) {
            player.inventory.deleteItem(Item(fullContainerId))
            player.inventory.addItem(Item(emptyContainerId))
            val containerName = Item(fullContainerId).name.lowercase().replace("of water", "").trim()
            player.sendMessage("The water in your $containerName evaporates in the desert heat.")
        }
    }
}

private fun applyEffect(player: Player) {
    player.tempAttribs.setI(DESERT_HEAT_EFFECT_DELAY_ATTR, getHeatEffectDelay(player))
    evaporateWater(player)
    if (checkAndDrinkWater(player)) return
    val hitAmount = if (player.y < 2990) Utils.random(10, 120) else Utils.random(10, 80)
    player.applyHit(Hit(player, hitAmount, Hit.HitLook.TRUE_DAMAGE))
    player.sendMessage("You start dying of thirst while you're in the desert.")
}

private fun getDervishItemDelays(startId: Int, endId: Int, delay: Int): List<Pair<Int, Int>> {
    return (startId..endId step 2).map { it to delay }
}

private fun hasCombatStats(item: Item?): Boolean {
    return item?.definitions?.getBonuses()?.any { it > 0 } ?: false
}
