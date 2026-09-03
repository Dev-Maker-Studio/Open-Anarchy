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

object ItemDefinitions {
    @JvmStatic
    fun getDefs(id: Int): ItemDefinition = Cache.items[id]

    @JvmStatic
    fun getItemDefinitions(id: Int, loadTemplates: Boolean = true): ItemDefinition = Cache.items[id]
}

object NPCDefinitions {
    @JvmStatic
    fun getDefs(id: Int): NPCDefinition = Cache.npcs[id]

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
        val definition = npcs[id]
        val realId = definition.getIdForPlayer(vars)
        if (realId == id || realId < 0 || realId >= npcs.size) {
            return definition
        }
        return npcs[realId]
    }
}

object ObjectDefinitions {
    @JvmStatic
    fun getDefs(id: Int): ObjectDefinition = Cache.objects[id]

    @JvmStatic
    fun getDefs(id: Int, init: Boolean): ObjectDefinition = Cache.objects[id]

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
        val definition = objects[id]
        val realId = definition.getIdForPlayer(vars)
        if (realId == id || realId < 0 || realId >= objects.size) {
            return definition
        }
        return objects[realId]
    }
}

object EnumDefinitions {
    @JvmStatic
    fun getEnum(id: Int): EnumDefinition = Cache.enums[id]
}

object StructDefinitions {
    @JvmStatic
    fun getStruct(id: Int): StructDefinition = Cache.structs[id]
}

object AnimationDefinitions {
    @JvmStatic
    fun getDefs(id: Int): AnimationDefinition = Cache.animations[id]

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
    fun getDefs(id: Int): VarBitDefinition = Cache.varbits[id]
}

object BASDefinitions {
    @JvmStatic
    fun getDefs(id: Int): BASDefinition = Cache.bas[id]
}

object SpotAnimDefinitions {
    @JvmStatic
    fun getDefs(id: Int): GraphicDefinition = Cache.graphics[id]
}
