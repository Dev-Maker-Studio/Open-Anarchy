package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.skills.firemaking.Firemaking
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import com.rs.utils.Ticks
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val PLANT_OPTION = "Plant tree root"
private const val STUMP_DELAY_TICKS = 1
private const val GROWTH_MINUTES = 5

enum class TreeRoot(val root: Int, val stump: Int, val tree: Int, val treeName: String) {
    DEAD(Obj.dead_tree_roots_bag, Loc.deadtree1_large_stump, Loc.deadtree1, "Dead tree"),
    REGULAR(Obj.tree_roots_bag, Loc.treestump2, Loc.tree, "Tree"),
    ;

    companion object {
        fun forTreeName(name: String): TreeRoot = entries.firstOrNull { it.treeName == name } ?: REGULAR
    }
}

@ServerStartupEvent
fun mapTreePlanting() {
    WorldDelta.track(*TreeRoot.entries.flatMap { listOf(it.stump, it.tree) }.toIntArray())
    TreeRoot.entries.forEach { type ->
        onItemClick(type.root, options = arrayOf(PLANT_OPTION)) { (player) -> plantTreeRoot(player, type) }
    }
}

private fun plantTreeRoot(player: Player, type: TreeRoot) {
    if (Firemaking.canLightFire(player, player)) {
        player.sendMessage("You can't plant a tree root here.")
        return
    }
    val tile = Tile.of(player.tile)
    player.inventory.deleteItem(type.root, 1)
    player.resetWalkSteps()
    player.addWalkSteps(player.x - 1, player.y, 1) ||
        player.addWalkSteps(player.x + 1, player.y, 1) ||
        player.addWalkSteps(player.x, player.y + 1, 1) ||
        player.addWalkSteps(player.x, player.y - 1, 1)
    player.sendMessage("You plant the tree root in the ground.")
    WorldTasks.schedule(STUMP_DELAY_TICKS) {
        val stump = GameObject(type.stump, ObjectShape.SCENERY_INTERACT, 0, tile)
        World.spawnObject(stump)
        player.setNextFaceTile(tile)
        WorldDelta.scheduleTransform(stump, type.tree, Ticks.fromMinutes(GROWTH_MINUTES))
    }
}
