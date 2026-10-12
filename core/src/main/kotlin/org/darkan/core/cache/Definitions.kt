package org.darkan.core.cache

import org.darkan.core.model.Vars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.data.VarBitDefinition

private inline fun <T> lookup(table: Array<T>, id: Int, blank: T): T =
    if (id in table.indices) table[id] else blank

private val EMPTY_VARBIT = VarBitDefinition(id = -1, index = -1)

object DefinitionTables {
    @JvmStatic
    var items: Array<ItemDefinition>? = null

    @JvmStatic
    var objects: Array<ObjectDefinition>? = null

    @JvmStatic
    var npcs: Array<NPCDefinition>? = null

    @JvmStatic
    var varbits: Array<VarBitDefinition>? = null

    @JvmStatic
    fun clear() {
        items = null
        objects = null
        npcs = null
        varbits = null
    }
}

private val itemTable: Array<ItemDefinition> get() = DefinitionTables.items ?: Cache.items
private val objectTable: Array<ObjectDefinition> get() = DefinitionTables.objects ?: Cache.objects
private val npcTable: Array<NPCDefinition> get() = DefinitionTables.npcs ?: Cache.npcs
private val varbitTable: Array<VarBitDefinition> get() = DefinitionTables.varbits ?: Cache.varbits

object ItemDefinitions {
    @JvmStatic
    fun getDefs(id: Int): ItemDefinition = lookup(itemTable, id, ItemDefinition.EMPTY)

    @JvmStatic
    fun getItemDefinitions(id: Int, loadTemplates: Boolean = true): ItemDefinition = getDefs(id)
}

object NPCDefinitions {
    @JvmStatic
    fun getDefs(id: Int): NPCDefinition = lookup(npcTable, id, NPCDefinition.EMPTY)

    @JvmStatic
    fun getDefs(id: Int, vars: Vars?): NPCDefinition {
        val npcs = npcTable
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
    fun getDefs(id: Int): ObjectDefinition = lookup(objectTable, id, ObjectDefinition.EMPTY)

    @JvmStatic
    fun getDefs(id: Int, init: Boolean): ObjectDefinition = getDefs(id)

    @JvmStatic
    fun getDefs(id: Int, vars: Vars?): ObjectDefinition {
        val objects = objectTable
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
    fun getDefs(id: Int): VarBitDefinition = lookup(varbitTable, id, EMPTY_VARBIT)
}

object BASDefinitions {
    @JvmStatic
    fun getDefs(id: Int): BASDefinition = lookup(Cache.bas, id, BASDefinition.EMPTY)
}

object SpotAnimDefinitions {
    @JvmStatic
    fun getDefs(id: Int): SpotAnimDefinition = lookup(Cache.spotAnims, id, SpotAnimDefinition.EMPTY)
}
