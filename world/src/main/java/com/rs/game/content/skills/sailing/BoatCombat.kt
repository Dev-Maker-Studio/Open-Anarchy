package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.Utils
import kotlin.math.abs
import kotlin.math.atan2

private const val FULL_TURN = 360
private const val HALF_TURN = 180
private const val SAILING_XP_PER_DAMAGE = 1.0
private const val RANGED_XP_PER_DAMAGE = 4.0
private const val CREW_SAILING_XP_PER_DAMAGE = 0.75
private const val PLAYER_PRIVATEERING = 7
private const val PERILDANCE_BONUS = 1

object BoatCombat {
    fun targets(voyage: Voyage): List<SeaMonster> {
        val boat = voyage.boat
        return voyage.monsters.filter { monster ->
            !monster.isDead && !monster.hasFinished() &&
                maxOf(abs(monster.x - voyage.tileX), abs(monster.y - voyage.tileY)) <= CannonTier.RANGE_TILES &&
                inArc(voyage, monster.x, monster.y) && boat.cannon != null
        }.sortedBy { maxOf(abs(it.x - voyage.tileX), abs(it.y - voyage.tileY)) }
    }

    fun inArc(voyage: Voyage, targetX: Int, targetY: Int): Boolean {
        val bearing = Math.toDegrees(atan2((targetX - voyage.tileX).toDouble(), (targetY - voyage.tileY).toDouble())).toInt()
        val diff = Math.floorMod(bearing - voyage.movement.heading + HALF_TURN, FULL_TURN) - HALF_TURN
        return abs(diff) <= CannonTier.ARC_DEGREES
    }

    fun tick(voyage: Voyage, player: Player) {
        val cannon = voyage.boat.cannon ?: return
        val now = voyage.ticks
        if (voyage.cannonAuto && now >= voyage.nextCannonTick) {
            val target = targets(voyage).firstOrNull()
            if (target != null && takeBall(voyage, player)) {
                voyage.nextCannonTick = now + CannonTier.ATTACK_TICKS
                val max = CrewRules.cannonMaxHit(player.skills.getLevel(Constants.RANGE), cannon.strength, CannonTier.BALL_STRENGTH, PLAYER_PRIVATEERING) + kegBonus(voyage.boat)
                val damage = Utils.random(max + 1)
                target.applyHit(Hit.range(player, damage))
                SailingXp.grant(player, voyage.boat, damage * SAILING_XP_PER_DAMAGE)
                player.skills.addXp(Constants.RANGE, damage * RANGED_XP_PER_DAMAGE)
            } else if (target != null) {
                voyage.cannonAuto = false
                player.sendMessage("Your cannon is out of cannonballs.")
            }
        }
        if (now >= voyage.nextCrewCannonTick) {
            val gunners = voyage.crewWith(CrewJob.CANNON).filter { voyage.sickTicks <= 0 }
            val target = targets(voyage).firstOrNull()
            if (gunners.isNotEmpty() && target != null) {
                voyage.nextCrewCannonTick = now + CannonTier.ATTACK_TICKS
                gunners.forEach { gunner ->
                    if (voyage.boat.hold.cannonballs <= 0) return@forEach
                    voyage.boat.hold.cannonballs--
                    val max = CrewRules.cannonMaxHit(player.skills.getLevel(Constants.SAILING), cannon.strength, CannonTier.BALL_STRENGTH, gunner.privateering) + kegBonus(voyage.boat)
                    val damage = Utils.random(max + 1)
                    target.applyHit(Hit.range(player, damage))
                    SailingXp.grant(player, voyage.boat, damage * CREW_SAILING_XP_PER_DAMAGE)
                }
            }
        }
    }

    private fun kegBonus(boat: Boat): Int = if (boat.activeKeg == KegKind.PERILDANCE) PERILDANCE_BONUS else 0

    private fun takeBall(voyage: Voyage, player: Player): Boolean {
        if (voyage.boat.hold.cannonballs > 0) {
            voyage.boat.hold.cannonballs--
            return true
        }
        val cannon = voyage.boat.cannon ?: return false
        val ball = CannonTier.entries.take(cannon.ordinal + 1).lastOrNull { player.inventory.getAmountOf(it.ballItem) > 0 } ?: return false
        player.inventory.deleteItem(ball.ballItem, 1)
        return true
    }
}
