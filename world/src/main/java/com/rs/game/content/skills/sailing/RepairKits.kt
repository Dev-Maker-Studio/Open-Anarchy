package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Obj

enum class RepairKit(val item: Int, val heals: Int) {
    WOODEN(Obj.sailing_repair_kit_wooden, 5),
    OAK(Obj.sailing_repair_kit_oak, 10),
    TEAK(Obj.sailing_repair_kit_teak, 20),
    MAHOGANY(Obj.sailing_repair_kit_mahogany, 30),
    CAMPHOR(Obj.sailing_repair_kit_camphor, 40),
    IRONWOOD(Obj.sailing_repair_kit_ironwood, 45),
    ROSEWOOD(Obj.sailing_repair_kit_rosewood, 50);

    companion object {
        val ITEMS = entries.map { it.item }.toIntArray()

        fun of(item: Int): RepairKit? = entries.firstOrNull { it.item == item }
    }
}

object RepairKits {
    fun healingInHold(boat: Boat): Int = boat.hold.goods.entries.sumOf { (item, amount) -> (RepairKit.of(item)?.heals ?: 0) * amount }

    fun repairFromHold(boat: Boat): Int {
        var healed = 0
        for (kit in RepairKit.entries.sortedBy { it.heals }) {
            val have = boat.hold.goods[kit.item] ?: continue
            var used = 0
            while (used < have && boat.hitpoints < boat.maxHitpoints) {
                val dealt = minOf(kit.heals, boat.maxHitpoints - boat.hitpoints)
                boat.hitpoints += dealt
                healed += dealt
                used++
            }
            if (used >= have) boat.hold.goods.remove(kit.item) else if (used > 0) boat.hold.goods[kit.item] = have - used
        }
        return healed
    }

    fun useOne(boat: Boat, bonus: Int): Int {
        val missing = boat.maxHitpoints - boat.hitpoints
        val held = RepairKit.entries.filter { (boat.hold.goods[it.item] ?: 0) > 0 }.sortedBy { it.heals }
        val kit = held.firstOrNull { it.heals >= missing } ?: held.lastOrNull() ?: return 0
        val have = boat.hold.goods.getValue(kit.item)
        if (have <= 1) boat.hold.goods.remove(kit.item) else boat.hold.goods[kit.item] = have - 1
        val healed = minOf(kit.heals + bonus, missing)
        boat.hitpoints += healed
        return healed
    }

    fun use(player: Player, kit: RepairKit) {
        val voyage = Voyages.of(player) ?: return player.sendMessage("You need to be aboard your boat to use a repair kit.")
        val boat = voyage.boat
        if (boat.hitpoints >= boat.maxHitpoints) return player.sendMessage("Your boat needs no repairs.")
        if (!player.inventory.containsItem(kit.item)) return
        player.inventory.deleteItem(kit.item, 1)
        val healed = minOf(kit.heals, boat.maxHitpoints - boat.hitpoints)
        boat.hitpoints += healed
        BoatStore.save(boat)
        player.sendMessage("The repair kit mends $healed hit points (${boat.hitpoints}/${boat.maxHitpoints}).")
    }
}

@ServerStartupEvent
fun mapRepairKits() {
    if (!EnvVars.generateMaps) return
    onItemClick(*RepairKit.ITEMS.toTypedArray(), options = arrayOf("Repair")) { e ->
        val kit = RepairKit.of(e.item.id) ?: return@onItemClick
        RepairKits.use(e.player, kit)
    }
}
