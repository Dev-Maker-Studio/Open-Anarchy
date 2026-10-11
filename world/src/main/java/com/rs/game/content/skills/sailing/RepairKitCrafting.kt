package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnItem
import org.darkan.core.Constants
import org.darkan.core.EnvVars
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj

enum class RepairRecipe(val kit: RepairKit, val constructionLevel: Int, val planks: Int, val nails: Int, val yield: Int, val xp: Double) {
    WOODEN(RepairKit.WOODEN, 1, 1, 5, 2, 43.5),
    OAK(RepairKit.OAK, 19, 1, 5, 2, 90.0),
    TEAK(RepairKit.TEAK, 30, 1, 5, 2, 135.0),
    MAHOGANY(RepairKit.MAHOGANY, 47, 2, 10, 2, 210.0),
    CAMPHOR(RepairKit.CAMPHOR, 66, 2, 10, 2, 255.0),
    IRONWOOD(RepairKit.IRONWOOD, 80, 2, 10, 3, 300.0),
    ROSEWOOD(RepairKit.ROSEWOOD, 92, 2, 10, 3, 330.0);

    val wood: Wood get() = Wood.entries[ordinal]

    val needsTools: Boolean get() = this != WOODEN
}

object RepairKitCrafting {
    const val PASTE = 5
    private const val PAGE_SIZE = 4

    fun menu(player: Player, page: Int = 0) {
        val pages = RepairRecipe.entries.chunked(PAGE_SIZE)
        player.sendOptionsDialogue("Craft repair kits") {
            pages[page].forEach { recipe ->
                opExec("${recipe.kit.name.lowercase().replaceFirstChar { it.uppercase() }} (Construction ${recipe.constructionLevel}, makes ${recipe.yield})") { craft(player, recipe) }
            }
            opExec(if (page + 1 < pages.size) "Heavier kits" else "Lighter kits") { menu(player, (page + 1) % pages.size) }
        }
    }

    fun craft(player: Player, recipe: RepairRecipe) {
        if (player.skills.getLevel(Constants.CONSTRUCTION) < recipe.constructionLevel) return player.sendMessage("You need Construction level ${recipe.constructionLevel}.")
        if (recipe.needsTools && (!player.inventory.containsItem(Obj.hammer) || !player.inventory.containsItem(Obj.poh_saw))) return player.sendMessage("You need a hammer and a saw, and the workbench of a salvaging station.")
        val plank = Materials.plank(recipe.wood)
        val nail = Materials.nail(recipe.wood)
        var made = 0
        while (player.inventory.getAmountOf(plank) >= recipe.planks && player.inventory.getAmountOf(nail) >= recipe.nails &&
            player.inventory.getAmountOf(Materials.SWAMP_PASTE) >= PASTE && player.inventory.freeSlots >= recipe.yield
        ) {
            player.inventory.deleteItem(plank, recipe.planks)
            player.inventory.deleteItem(nail, recipe.nails)
            player.inventory.deleteItem(Materials.SWAMP_PASTE, PASTE)
            player.inventory.addItem(recipe.kit.item, recipe.yield)
            player.skills.addXp(Constants.CONSTRUCTION, recipe.xp)
            made += recipe.yield
        }
        player.sendMessage(
            if (made > 0) "You make $made ${ItemDefinitions.getDefs(recipe.kit.item).name.lowercase()}s."
            else "You need ${recipe.planks} ${ItemDefinitions.getDefs(plank).name.lowercase()}, ${recipe.nails} ${ItemDefinitions.getDefs(nail).name.lowercase()} and $PASTE swamp paste, with room for ${recipe.yield} kits."
        )
    }
}

@ServerStartupEvent
fun mapRepairKitCrafting() {
    if (!EnvVars.generateMaps) return
    onItemOnItem(intArrayOf(Obj.swamppaste), intArrayOf(Obj.woodplank)) { e -> RepairKitCrafting.craft(e.player, RepairRecipe.WOODEN) }
}
