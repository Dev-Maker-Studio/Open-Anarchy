package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.content.skills.farming.PatchLocation
import com.rs.game.content.skills.farming.ProduceType
import com.rs.game.content.skills.firemaking.Firemaking
import com.rs.game.content.skills.woodcutting.TreeType
import com.rs.game.map.WorldDelta
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import com.rs.utils.Ticks
import org.darkan.core.Constants
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.type.Tile

private const val PLANT_OPTION = "Plant tree root"
private const val STUMP_DELAY_TICKS = 1
private const val GROWTH_MINUTES = 5
private const val DEAD_TREE_NAME = "Dead tree"
private const val COMMON_TREE_FARMING_XP = 7.0

enum class TreeRoot(val root: Int, val stump: Int, val tree: Int, val type: TreeType, val farmingLevel: Int, val farmingXp: Double) {
    DEAD(Obj.dead_tree_roots_bag, Loc.deadtree1_large_stump, Loc.deadtree1, TreeType.NORMAL, 1, COMMON_TREE_FARMING_XP),
    REGULAR(Obj.tree_roots_bag, Loc.treestump2, Loc.tree, TreeType.NORMAL, 1, COMMON_TREE_FARMING_XP),
    OAK(Obj.oak_roots, Loc.oaktree_stump, Loc.oaktree, TreeType.OAK, ProduceType.Oak),
    WILLOW(Obj.willow_roots, Loc.willow_tree2or3or4_stump, Loc.willowtree, TreeType.WILLOW, ProduceType.Willow),
    MAPLE(Obj.maple_roots, Loc.maple_tree_stump_new, Loc.loc_1307, TreeType.MAPLE, ProduceType.Maple),
    YEW(Obj.yew_roots, Loc.yew_tree_stump_new, Loc.yewtree, TreeType.YEW, ProduceType.Yew),
    MAGIC(Obj.magic_roots, Loc.magic_tree_stump_new, Loc.loc_1306, TreeType.MAGIC, ProduceType.Magic),
    ;

    constructor(root: Int, stump: Int, tree: Int, type: TreeType, produce: ProduceType) : this(root, stump, tree, type, produce.level, produce.plantingExperience)

    companion object {
        const val ROOTS_PER_TREE = 2

        fun forTree(player: Player, tree: GameObject): TreeRoot? {
            if (tree.getDefinitions(player).name == DEAD_TREE_NAME) return DEAD
            val type = TreeType.forObject(player, tree) ?: return null
            return entries.firstOrNull { it != DEAD && it.type == type }
        }
    }
}

fun uproot(player: Player, tree: GameObject): Boolean {
    if (PatchLocation.forObject(tree.id) != null) return false
    val root = TreeRoot.forTree(player, tree) ?: return false
    player.inventory.addItem(root.root, TreeRoot.ROOTS_PER_TREE)
    player.skills.addXp(Constants.FARMING, root.farmingXp)
    return true
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
    if (!ClaimManager.canGrowTrees(player, player.tile)) return
    if (player.skills.getLevel(Constants.FARMING) < type.farmingLevel) {
        player.sendMessage("You need a Farming level of ${type.farmingLevel} to plant this tree root.")
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
    player.skills.addXp(Constants.FARMING, type.farmingXp)
    WorldTasks.schedule(STUMP_DELAY_TICKS) {
        val stump = GameObject(type.stump, ObjectShape.SCENERY_INTERACT, 0, tile)
        World.spawnObject(stump)
        player.setNextFaceTile(tile)
        WorldDelta.scheduleTransform(stump, type.tree, Ticks.fromMinutes(GROWTH_MINUTES))
    }
}
