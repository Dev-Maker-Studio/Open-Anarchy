package com.rs.utils

import com.rs.game.model.entity.Entity
import org.darkan.core.cache.AnimationDefinitions
import org.darkan.core.model.Animation
import org.darkan.core.model.ObjectShape
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.type.Tile
import org.darkan.core.model.Bonus
import org.darkan.core.model.Item
import org.darkan.core.Utils.getDistance
import org.darkan.core.model.MoveType

fun Set<Entity>.closestOrNull(tile: Tile): Entity? = this.minByOrNull { entity -> getDistance(entity.tile, tile) }

fun Tile.isAt(x: Int, y: Int): Boolean = this.x == x && this.y == y

fun Tile.isAt(x: Int, y: Int, plane: Int): Boolean = this.x == x && this.y == y && this.plane == plane

fun Tile.withinArea(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
    this.x >= x1 && this.x <= x2 && this.y >= y1 && this.y <= y2

val Tile.chunkX: Int get() = x shr 3

val Tile.chunkY: Int get() = y shr 3

val Tile.chunkLocalHash: Int get() = (x and 7) or ((y and 7) shl 4) or (plane shl 8)



val ObjectDefinition.clipType: Int get() = solid

fun ObjectDefinition.blocks(): Boolean = (block and ObjectDefinition.PROJECTILE) != 0

val ObjectDefinition.ignoresPathfinder: Boolean get() = ignoreOnRoute

val ObjectDefinition.types: Array<ObjectShape> get() =
    modelTypes?.map { ObjectShape.forId(it.toInt()) ?: ObjectShape.SCENERY_INTERACT }?.toTypedArray()
        ?: arrayOf(ObjectShape.SCENERY_INTERACT)

val Animation.defs: AnimationDefinition get() = AnimationDefinitions.getDefs(primaryId)

val AnimationDefinition.emoteGameTicks: Int get() = (durations?.sum() ?: 0) / 30


val ItemDefinition.equipSlot: Int get() = wearPos

val ItemDefinition.maleEquip1: Int get() = primaryMaleModel

val ItemDefinition.maleEquip2: Int get() = secondaryMaleModel

fun ItemDefinition.getParamVal(key: Int): Int = params?.get(key) as? Int ?: 0

val NPCDefinition.compatibleAnimations: BASDefinition? get() =
    if (renderEmote != -1) org.darkan.core.cache.BASDefinitions.getDefs(renderEmote) else null

val NPCDefinition.modifiedColors: ShortArray? get() = modifiedColours

val NPCDefinition.modifiedTextures: ShortArray? get() = modifiedTextureColours

val NPCDefinition.isUndead: Boolean get() = (params?.get(1352) as? Int) == 1


val EnumDefinition.values: Map<Int, Any> get() = map ?: emptyMap()

val ItemDefinition.value: Int get() = cost

val NPCDefinition.walkingAnimation: Int get() {
    val bas = compatibleAnimations ?: return -1
    return bas.walkAnimation
}

val NPCDefinition.rotate180Animation: Int get() {
    val bas = compatibleAnimations ?: return -1
    return bas.walkDir1
}

val NPCDefinition.rotate90RightAnimation: Int get() {
    val bas = compatibleAnimations ?: return -1
    return bas.walkDir2
}

val NPCDefinition.rotate90LeftAnimation: Int get() {
    val bas = compatibleAnimations ?: return -1
    return bas.walkDir3
}

val NPCDefinition.specialByte: Int get() = size

val NPCDefinition.basId: Int get() = renderEmote

fun NPCDefinition.getName(vars: Any?): String = name

fun NPCDefinition.getAttackDelay(): Int {
    return params?.get(14) as? Int ?: 4
}

val NPCDefinition.crawls: Boolean get() = moveSpeed == MoveType.CRAWL.id

fun Item.copyItem(): Item = clone()