package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.combat.XPType
import com.rs.game.content.combat.getMeleeCombatDelay
import com.rs.game.content.combat.getWeaponAttackEmote
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.HitBar
import com.rs.game.model.entity.actions.Action
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.npc.NPCBodyMeshModifier
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.Constants
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.model.Animation
import org.darkan.core.model.Bonus
import world.gregs.voidps.type.Tile

private const val HP_ATTRIB = "builtFurnitureHp"
private const val MISSING_HP = -1
private const val DESTROY_OPTION = "Destroy"
private const val REPAIR_OPTION = "Repair"
private const val OPEN_OPTION = "Open"
private const val CLOSE_OPTION = "Close"
private const val REPAIR_HP_PER_TICK = 6
private const val REPAIR_XP_PER_TICK = 3.0
private const val REPAIR_ANIM_INTERVAL = 3
private const val STRENGTH_DAMAGE_DIVISOR = 8
private const val BASE_DAMAGE = 1
private const val INVISIBLE_NPC = 9167
/** A speck of a model about 2.9 tiles up; the client places the bar just above the NPC's model, so this sets the bar's height. */
private const val BAR_HEIGHT_MODEL = 8719
private const val FULL_BAR = 255
private const val BAR_LINGER_TICKS = 4

enum class Material(val xpPerDamage: Double) {
    LIGHT_WOOD(0.5),
    TIMBER(0.75),
    HEAVY_TIMBER(1.0),
}

enum class BuiltFurniture(val loc: Int, val maxHp: Int, val material: Material, val openLoc: Int = -1) {
    CHAIR(Loc.built_wooden_chair, 40, Material.LIGHT_WOOD),
    WALL(Loc.built_wooden_wall, 150, Material.HEAVY_TIMBER),
    DOOR(Loc.built_wooden_door, 100, Material.TIMBER, Loc.built_wooden_door_open),
    ;

    companion object {
        private val byLoc = entries.flatMap { f -> listOf(f.loc, f.openLoc).filter { it != -1 }.map { it to f } }.toMap()

        fun forLoc(loc: Int): BuiltFurniture? = byLoc[loc]
    }
}

@ServerStartupEvent
fun mapBuiltFurniture() {
    val locs = BuiltFurniture.entries.flatMap { listOf(it.loc, it.openLoc) }.filter { it != -1 }.toTypedArray()
    onObjectClick(*locs) { (player, obj, option) ->
        val furniture = BuiltFurniture.forLoc(obj.id) ?: return@onObjectClick
        when (option) {
            DESTROY_OPTION -> player.actionManager.setAction(DestroyFurniture(obj, furniture))
            REPAIR_OPTION -> player.actionManager.setAction(RepairFurniture(obj, furniture))
            OPEN_OPTION, CLOSE_OPTION -> toggleDoor(obj, option == OPEN_OPTION, furniture)
        }
    }
}

fun initBuiltFurniture(obj: GameObject) {
    val furniture = BuiltFurniture.forLoc(obj.id) ?: return
    obj.attribs.setI(HP_ATTRIB, furniture.maxHp)
}

private fun hitPoints(obj: GameObject, furniture: BuiltFurniture): Int =
    obj.attribs.getI(HP_ATTRIB, MISSING_HP).takeIf { it != MISSING_HP } ?: furniture.maxHp

private fun isStanding(obj: GameObject): Boolean = World.getObject(obj.tile, obj.type)?.id == obj.id

private fun toggleDoor(door: GameObject, opening: Boolean, furniture: BuiltFurniture) {
    val newTile = door.tile.doorSwingTile(door.rotation, opening)
    val swung = GameObject(if (opening) furniture.openLoc else furniture.loc, door.type, door.getRotation(if (opening) 1 else -1), newTile)
    swung.attribs.setI(HP_ATTRIB, hitPoints(door, furniture))
    World.removeObject(door)
    World.spawnObject(swung)
}

private fun Tile.doorSwingTile(rotation: Int, opening: Boolean): Tile {
    val effective = if (opening) rotation else (rotation - 1) and 3
    val direction = if (opening) 1 else -1
    return when (effective) {
        0 -> transform(-direction, 0, 0)
        1 -> transform(0, direction, 0)
        2 -> transform(direction, 0, 0)
        else -> transform(0, -direction, 0)
    }
}

private fun destroy(obj: GameObject) {
    World.removeObject(obj)
}

/** An object cannot carry a hit bar, so the bar is shown on an invisible, unclickable NPC standing on the object's tile. */
private class ObjectHitBar(private val from: Int, private val to: Int, private val max: Int) : HitBar() {
    override fun getType() = 0

    override fun getPercentage() = from * FULL_BAR / max

    override fun getToPercentage() = to * FULL_BAR / max
}

private class DestroyFurniture(private val obj: GameObject, private val furniture: BuiltFurniture) : Action() {
    private var barCarrier: NPC? = null

    private fun showBar(from: Int, to: Int) {
        val carrier = barCarrier?.takeUnless { it.hasFinished() } ?: NPC(INVISIBLE_NPC, obj.tile).also {
            it.bodyMeshModifier = NPCBodyMeshModifier(it.definitions).addModels(BAR_HEIGHT_MODEL)
            barCarrier = it
        }
        carrier.nextHitBars.add(ObjectHitBar(from, to, furniture.maxHp))
    }

    override fun start(entity: Entity): Boolean = canAttack(entity as Player)

    override fun process(entity: Entity): Boolean = isStanding(obj) && canAttack(entity as Player)

    override fun processWithDelay(entity: Entity): Int {
        val player = entity as Player
        val style = player.combatDefinitions.getAttackStyle()
        val weapon = player.equipment.weaponId
        val maxDamage = BASE_DAMAGE + (player.skills.getLevel(Constants.STRENGTH) + player.combatDefinitions.getBonus(Bonus.MELEE_STR)) / STRENGTH_DAMAGE_DIVISOR
        val damage = Utils.random(BASE_DAMAGE, maxDamage + 1)
        player.faceObject(obj)
        player.anim(Animation(getWeaponAttackEmote(weapon, style)))
        player.skills.addXp(trainedSkill(style.xpType), damage * furniture.material.xpPerDamage)
        val before = hitPoints(obj, furniture)
        val remaining = before - damage
        showBar(before, maxOf(remaining, 0))
        if (remaining <= 0) {
            destroy(obj)
            player.sendMessage("You smash the ${obj.definitions.name.lowercase()} to pieces.")
            return -1
        }
        obj.attribs.setI(HP_ATTRIB, remaining)
        player.sendMessage("You hit the ${obj.definitions.name.lowercase()} for $damage damage ($remaining hit points left).", true)
        return getMeleeCombatDelay(weapon)
    }

    override fun stop(entity: Entity) {
        entity.anim(-1)
        barCarrier?.finishAfterTicks(BAR_LINGER_TICKS)
    }

    private fun canAttack(player: Player): Boolean {
        if (trainedSkill(player.combatDefinitions.getAttackStyle().xpType) != NOT_MELEE) return true
        player.sendMessage("You can't destroy that with your current attack style.")
        return false
    }
}

private const val NOT_MELEE = -1

private fun trainedSkill(type: XPType): Int = when (type) {
    XPType.ACCURATE, XPType.CONTROLLED -> Constants.ATTACK
    XPType.AGGRESSIVE -> Constants.STRENGTH
    XPType.DEFENSIVE -> Constants.DEFENSE
    else -> NOT_MELEE
}

private class RepairFurniture(private val obj: GameObject, private val furniture: BuiltFurniture) : Action() {
    private var ticks = 0

    override fun start(entity: Entity): Boolean {
        val player = entity as Player
        if (hitPoints(obj, furniture) >= furniture.maxHp) {
            player.sendMessage("The ${obj.definitions.name.lowercase()} doesn't need any repairs.")
            return false
        }
        player.faceObject(obj)
        return true
    }

    override fun process(entity: Entity): Boolean = isStanding(obj) && hitPoints(obj, furniture) < furniture.maxHp

    override fun processWithDelay(entity: Entity): Int {
        val player = entity as Player
        if (ticks++ % REPAIR_ANIM_INTERVAL == 0) player.anim(Animation(Seq.human_poh_build_floor))
        val repaired = minOf(furniture.maxHp, hitPoints(obj, furniture) + REPAIR_HP_PER_TICK)
        obj.attribs.setI(HP_ATTRIB, repaired)
        player.skills.addXp(Constants.CONSTRUCTION, REPAIR_XP_PER_TICK)
        if (repaired >= furniture.maxHp) {
            player.anim(-1)
            player.sendMessage("You finish repairing the ${obj.definitions.name.lowercase()}.")
            return -1
        }
        return 1
    }

    override fun stop(entity: Entity) {
        entity.anim(-1)
    }
}
