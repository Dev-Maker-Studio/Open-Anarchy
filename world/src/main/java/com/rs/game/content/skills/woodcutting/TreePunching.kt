package com.rs.game.content.skills.woodcutting

import com.rs.game.World
import com.rs.game.content.skills.construction.TreeRoot
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.actions.Action
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.model.Animation
import org.darkan.core.model.ObjectShape

private const val TOP_PLANE = 3
private const val PUNCHES_TO_FELL = 6
private const val PUNCH_DELAY_TICKS = 3
private const val PUNCH_OPTION = "Punch"
private const val FREE_SLOTS_REQUIRED = 3
private const val ROOT_DOUBLE_CHANCE = 10
private const val SHADOW_NAME = "null"

private val TREE_NAMES = arrayOf<Any>("Tree", "Swamp tree", "Dead tree", "Evergreen", "Dying tree", "Jungle Tree")

@ServerStartupEvent
fun mapTreePunching() {
    onObjectClick(*TREE_NAMES) { (player, obj, option) ->
        if (option == PUNCH_OPTION && TreeType.forObject(player, obj) == TreeType.NORMAL)
            player.actionManager.setAction(TreePunch(obj))
    }
}

fun destroyTree(tree: GameObject) {
    val baseTree = !World.isSpawnedObject(tree)
    World.removeObject(tree)
    removeShadows(tree)
    replaceWithNothing(tree, baseTree)
}

private fun removeShadows(tree: GameObject) {
    World.getObject(tree.tile, ObjectShape.GROUND_DECORATION)?.takeUnless(World::isSpawnedObject)?.let(World::removeObject)
    val size = maxOf(tree.definitions.sizeX, tree.definitions.sizeY)
    for (dx in -1..size) for (dy in -1..size) {
        val tile = tree.tile.transform(dx, dy, 0)
        World.getObject(tile, ObjectShape.GROUND_DECORATION)
            ?.takeIf { !World.isSpawnedObject(it) && it.definitions.name == SHADOW_NAME }
            ?.let(World::removeObject)
    }
    if (tree.plane >= TOP_PLANE) return
    for (dx in -1..1) for (dy in -1..1) {
        val above = tree.tile.transform(dx, dy, 1)
        World.getObject(above, ObjectShape.SCENERY_INTERACT)?.let(World::removeObject)
        World.getObject(above, ObjectShape.GROUND_INTERACT)?.let(World::removeObject)
    }
}

/**
 * The client keeps a tree's ground shadow when the tree loc is only destroyed. Swapping the loc for an empty one, the way
 * a tree becomes a stump, makes it redraw the tile without the shadow. A tree from the map file keeps the empty loc,
 * because removing it would bring the map's tree back; a planted tree's empty loc is removed again a tick later.
 */
private fun replaceWithNothing(tree: GameObject, baseTree: Boolean) {
    val empty = GameObject(Loc.invisible_type8_nonblocking, tree.type, tree.rotation, tree.tile)
    World.spawnObject(empty, false)
    if (!baseTree) WorldTasks.schedule(1) { World.removeObject(empty) }
}

private class TreePunch(private val tree: GameObject) : Action() {
    private var punches = 0

    override fun start(entity: Entity): Boolean = check(entity)

    override fun process(entity: Entity): Boolean {
        if (!check(entity)) return false
        entity.anim(Animation(Seq.seq_422))
        return true
    }

    override fun processWithDelay(entity: Entity): Int {
        entity.faceObject(tree)
        if (++punches < PUNCHES_TO_FELL) return PUNCH_DELAY_TICKS
        val player = entity as Player
        player.inventory.addItem(Obj.logs)
        player.inventory.addItem(TreeRoot.forTreeName(tree.definitions.name).root, if (Utils.random(ROOT_DOUBLE_CHANCE) == 0) 2 else 1)
        player.skills.addXp(Constants.WOODCUTTING, TreeType.NORMAL.xp)
        destroyTree(tree)
        player.sendMessage("You punch the tree down completely and find tree roots.")
        entity.anim(-1)
        return -1
    }

    override fun stop(entity: Entity) {
        entity.anim(-1)
    }

    private fun check(entity: Entity): Boolean {
        val player = entity as? Player ?: return false
        if (World.getObject(tree.tile, tree.type)?.id != tree.id) return false
        if (player.inventory.freeSlots < FREE_SLOTS_REQUIRED) {
            player.sendMessage("You need three free inventory slots to fell this tree.")
            return false
        }
        return true
    }
}
