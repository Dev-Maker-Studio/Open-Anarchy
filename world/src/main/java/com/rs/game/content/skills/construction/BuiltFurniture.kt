package com.rs.game.content.skills.construction

import com.rs.game.World
import com.rs.game.content.claims.ClaimManager
import com.rs.game.content.combat.XPType
import com.rs.game.content.combat.getMeleeCombatDelay
import com.rs.game.content.combat.getWeaponAttackEmote
import com.rs.game.map.BUILT_FURNITURE_HP
import com.rs.game.map.WorldDelta
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
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Seq
import org.darkan.core.model.Animation
import org.darkan.core.model.Bonus
import world.gregs.voidps.type.Tile

private const val HP_ATTRIB = BUILT_FURNITURE_HP
private const val MISSING_HP = -1
const val PICK_UP_OPTION = "Pick Up"
private const val DESTROY_OPTION = "Destroy"
private const val REPAIR_OPTION = "Repair"
internal const val OPEN_OPTION = "Open"
internal const val CLOSE_OPTION = "Close"
private const val REPAIR_HP_PER_TICK = 6
private const val REPAIR_XP_PER_TICK = 3.0
private const val REPAIR_ANIM_INTERVAL = 3
private const val STRENGTH_DAMAGE_DIVISOR = 8
private const val BASE_DAMAGE = 1
private const val INVISIBLE_NPC = 9167
private const val BAR_HEIGHT_MODEL = 8719
private const val FULL_BAR = 255
private const val BAR_LINGER_TICKS = 4

object BuiltFurniture {
    fun handle(player: Player, obj: GameObject, option: String): Boolean {
        val piece = ConstructionCatalogue.forLoc(obj.id) ?: return false
        when (option) {
            DESTROY_OPTION -> destroyPiece(player, obj, piece)
            PICK_UP_OPTION -> pickUpPiece(player, obj, piece)
            REPAIR_OPTION -> player.actionManager.setAction(RepairFurniture(obj, piece))
            OPEN_OPTION, CLOSE_OPTION -> openPiece(player, obj, piece, option == OPEN_OPTION)
            else -> return false
        }
        return true
    }

    fun init(obj: GameObject) {
        val piece = ConstructionCatalogue.forLoc(obj.id) ?: return
        obj.attribs.setI(HP_ATTRIB, piece.hitPoints)
    }
}

@ServerStartupEvent
fun mapBuiltFurniture() {
    val locs = ConstructionCatalogue.pieces.filter { it.item != Obj.furnace_item }.flatMap { it.placedLocs.toList() }.toTypedArray()
    onObjectClick(*locs) { (player, obj, option) -> BuiltFurniture.handle(player, obj, option) }
}

fun initBuiltFurniture(obj: GameObject) = BuiltFurniture.init(obj)

private fun hitPoints(obj: GameObject, piece: ConstructionPiece): Int =
    obj.attribs.getI(HP_ATTRIB, MISSING_HP).takeIf { it != MISSING_HP } ?: piece.hitPoints

private fun isStanding(obj: GameObject): Boolean = World.getObject(obj.tile, obj.type)?.id == obj.id

private fun destroyPiece(player: Player, obj: GameObject, piece: ConstructionPiece) {
    val claim = ClaimManager.claimAt(obj.tile)
    when {
        claim == null -> player.actionManager.setAction(DestroyFurniture(obj, piece))
        claim.owner == player.username -> pickUpPiece(player, obj, piece)
        else -> player.sendMessage("This ${piece.name.lowercase()} is protected by the land claim of ${claim.owner}.")
    }
}

private fun pickUpPiece(player: Player, obj: GameObject, piece: ConstructionPiece) {
    if (!isStanding(obj)) return
    val claim = ClaimManager.claimAt(obj.tile)
    if (claim == null) {
        player.sendMessage("You can only pick that up on land you have claimed; destroy it instead.")
        return
    }
    if (claim.owner != player.username) {
        player.sendMessage("Only ${claim.owner} can pick that up.")
        return
    }
    if (!player.inventory.hasFreeSlots()) {
        player.sendMessage("You don't have enough inventory space to pick that up.")
        return
    }
    World.removeObject(obj)
    player.inventory.addItem(piece.item, 1)
    player.sendMessage("You pick up the ${piece.name.lowercase()}.")
}

private fun openPiece(player: Player, obj: GameObject, piece: ConstructionPiece, opening: Boolean) {
    val onOpen = piece.onOpen
    if (onOpen != null) {
        onOpen(player)
        return
    }
    if (piece.openLoc == -1 || !isStanding(obj)) return
    val swung = swungDoor(obj, opening, piece.loc, piece.openLoc)
    swung.attribs.setI(HP_ATTRIB, hitPoints(obj, piece))
    val owner = WorldDelta.ownerOf(obj)
    World.removeObject(obj)
    World.spawnObject(swung)
    WorldDelta.setOwner(swung, owner)
}

internal fun swungDoor(door: GameObject, opening: Boolean, closedLoc: Int, openLoc: Int): GameObject =
    GameObject(if (opening) openLoc else closedLoc, door.type, door.getRotation(if (opening) 1 else -1), door.tile.doorSwingTile(door.rotation, opening))

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

private class ObjectHitBar(private val from: Int, private val to: Int, private val max: Int) : HitBar() {
    override fun getType() = 0

    override fun getPercentage() = from * FULL_BAR / max

    override fun getToPercentage() = to * FULL_BAR / max
}

private class DestroyFurniture(private val obj: GameObject, private val piece: ConstructionPiece) : Action() {
    private var barCarrier: NPC? = null

    private fun showBar(from: Int, to: Int) {
        val carrier = barCarrier?.takeUnless { it.hasFinished() } ?: NPC(INVISIBLE_NPC, obj.tile).also {
            it.bodyMeshModifier = NPCBodyMeshModifier(it.definitions).addModels(BAR_HEIGHT_MODEL)
            barCarrier = it
        }
        carrier.nextHitBars.add(ObjectHitBar(from, to, piece.hitPoints))
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
        player.skills.addXp(trainedSkill(style.xpType), damage * piece.material.xpPerDamage)
        val before = hitPoints(obj, piece)
        val remaining = before - damage
        showBar(before, maxOf(remaining, 0))
        if (remaining <= 0) {
            destroy(obj)
            player.sendMessage("You smash the ${obj.definitions.name.lowercase()} to pieces.")
            return -1
        }
        obj.attribs.setI(HP_ATTRIB, remaining)
        WorldDelta.touch(obj)
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

private class RepairFurniture(private val obj: GameObject, private val piece: ConstructionPiece) : Action() {
    private var ticks = 0

    override fun start(entity: Entity): Boolean {
        val player = entity as Player
        if (hitPoints(obj, piece) >= piece.hitPoints) {
            player.sendMessage("The ${obj.definitions.name.lowercase()} doesn't need any repairs.")
            return false
        }
        player.faceObject(obj)
        return true
    }

    override fun process(entity: Entity): Boolean = isStanding(obj) && hitPoints(obj, piece) < piece.hitPoints

    override fun processWithDelay(entity: Entity): Int {
        val player = entity as Player
        if (ticks++ % REPAIR_ANIM_INTERVAL == 0) player.anim(Animation(Seq.human_poh_build_floor))
        val repaired = minOf(piece.hitPoints, hitPoints(obj, piece) + REPAIR_HP_PER_TICK)
        obj.attribs.setI(HP_ATTRIB, repaired)
        WorldDelta.touch(obj)
        player.skills.addXp(Constants.CONSTRUCTION, REPAIR_XP_PER_TICK)
        if (repaired >= piece.hitPoints) {
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
