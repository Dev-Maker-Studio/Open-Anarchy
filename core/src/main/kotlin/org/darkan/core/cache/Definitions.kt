package org.darkan.core.cache

import org.darkan.core.model.Vars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.GraphicDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.data.VarBitDefinition

/**
 * Looks up [id] in [table], or hands back [blank] when the id is not one the cache has.
 *
 * Every legacy `*Definitions.getDefs(int)` accepted any id at all: it built a definition object,
 * found no file for that id, and returned it with nothing but defaults filled in
 * (`com.rs.cache.loaders.ItemDefinitions:188-199` and `:103-119`). Content leans on that
 * constantly - `AttackStyle.getStyles(player.equipment.weaponId)` is called with -1 for bare hands
 * on every equipment refresh, and `Equipment.getItemSlot`, `ItemConfig.get` and the drop tables do
 * the same - so an out-of-range id must not throw here.
 */
private inline fun <T> lookup(table: Array<T>, id: Int, blank: T): T =
    if (id in table.indices) table[id] else blank

/** `VarBitDefinition` has no shared blank of its own, so this file keeps one. */
private val EMPTY_VARBIT = VarBitDefinition()

object ItemDefinitions {
    @JvmStatic
    fun getDefs(id: Int): ItemDefinition = lookup(Cache.items, id, ItemDefinition.EMPTY)

    @JvmStatic
    fun getItemDefinitions(id: Int, loadTemplates: Boolean = true): ItemDefinition = getDefs(id)
}

object NPCDefinitions {
    @JvmStatic
    fun getDefs(id: Int): NPCDefinition = lookup(Cache.npcs, id, NPCDefinition.EMPTY)

    /**
     * The definition this NPC presents as for a player with the given [vars].
     *
     * Legacy `NPCDefinitions.getDefs(int, VarManager)`: resolve the varbit/varp transform, then look up
     * the definition it names. A transform that resolves to an id outside the table - legacy's -1 "not
     * shown" fallback included - keeps the base definition, since callers dereference the result
     * unconditionally; ask [NPCDefinition.getIdForPlayer] directly to tell those apart.
     */
    @JvmStatic
    fun getDefs(id: Int, vars: Vars?): NPCDefinition {
        val npcs = Cache.npcs
        val definition = getDefs(id)
        val realId = definition.getIdForPlayer(vars)
        if (realId == id || realId < 0 || realId >= npcs.size) {
            return definition
        }
        return npcs[realId]
    }
}

object ObjectDefinitions {
    @JvmStatic
    fun getDefs(id: Int): ObjectDefinition = lookup(Cache.objects, id, ObjectDefinition.EMPTY)

    @JvmStatic
    fun getDefs(id: Int, init: Boolean): ObjectDefinition = getDefs(id)

    /**
     * The definition this object presents as for a player with the given [vars].
     *
     * Legacy `ObjectDefinitions.getDefs(int, VarManager)`: resolve the varbit/varp transform, then look
     * up the definition it names. A transform that resolves to an id outside the table - legacy's -1
     * "not shown" fallback included - keeps the base definition, since callers dereference the result
     * unconditionally; ask [ObjectDefinition.getIdForPlayer] directly to tell those apart.
     */
    @JvmStatic
    fun getDefs(id: Int, vars: Vars?): ObjectDefinition {
        val objects = Cache.objects
        val definition = getDefs(id)
        val realId = definition.getIdForPlayer(vars)
        if (realId == id || realId < 0 || realId >= objects.size) {
            return definition
        }
        return objects[realId]
    }
}

object EnumDefinitions {
    @JvmStatic
    fun getEnum(id: Int): EnumDefinition = lookup(Cache.enums, id, EnumDefinition.EMPTY)
}

object StructDefinitions {
    @JvmStatic
    fun getStruct(id: Int): StructDefinition = lookup(Cache.structs, id, StructDefinition.EMPTY)
}

object AnimationDefinitions {
    @JvmStatic
    fun getDefs(id: Int): AnimationDefinition = lookup(Cache.animations, id, AnimationDefinition.EMPTY)

    /**
     * Finds an animation that uses the given item in either hand.
     * Returns the animation ID, or -1 if none found.
     */
    @JvmStatic
    fun getAnimationWithItem(itemId: Int): Int {
        for (i in 0 until Cache.animations.size) {
            val def = Cache.animations[i]
            if (def.rightHandItem == itemId || def.leftHandItem == itemId)
                return i
        }
        return -1
    }
}

object VarBitDefinitions {
    @JvmStatic
    fun getDefs(id: Int): VarBitDefinition = lookup(Cache.varbits, id, EMPTY_VARBIT)
}

object BASDefinitions {
    @JvmStatic
    fun getDefs(id: Int): BASDefinition = lookup(Cache.bas, id, BASDefinition.EMPTY)
}

object SpotAnimDefinitions {
    @JvmStatic
    fun getDefs(id: Int): GraphicDefinition = lookup(Cache.graphics, id, GraphicDefinition.EMPTY)
}
