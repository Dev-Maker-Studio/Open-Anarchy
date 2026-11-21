package com.rs.game.content.world.generated

import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.Teleport
import com.rs.game.model.entity.player.Controller
import org.darkan.core.gameval.gen.Bas
import org.darkan.core.gameval.gen.Spotanim
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.type.Tile

private const val GROUND = 0
private const val MOUNT_TICKS = 6
private const val DISMOUNT_TICKS = 2
private const val FLIGHT_LOOP_TICKS = 8
private const val QUEUED_STEPS = 6
private const val FULL_RUN_ENERGY = 100.0
private const val NOT_LANDING = -1

class CarpetRideController(
    private val originX: Int,
    private val originY: Int,
    @Transient private val arm: Int,
    @Transient private var along: Int,
    @Transient private val targetAlong: Int,
    @Transient private val destinationX: Int,
    @Transient private val destinationY: Int
) : Controller() {

    private var runEnergy = 0.0
    private var running = false

    @Transient
    private var tick = 0

    @Transient
    private var landingQueued = false

    @Transient
    private var dismountAt = NOT_LANDING

    override fun start() {
        runEnergy = player.runEnergy
        running = player.run
        player.stopAll()
        player.lock()
        player.setInvulnerable(true)
        player.setRunHidden(true)
        player.appearance.setBAS(Bas.magic_carpet_flight)
        player.spotAnim(Spotanim.magic_carpet_spotanim_mount)
    }

    override fun process() {
        tick++
        if (tick < MOUNT_TICKS) return
        if (dismountAt != NOT_LANDING) {
            if (tick >= dismountAt) removeController()
            return
        }
        if (player.x == destinationX && player.y == destinationY) {
            player.resetWalkSteps()
            player.spotAnim(Spotanim.magic_carpet_spotanim_dismount)
            dismountAt = tick + DISMOUNT_TICKS
            return
        }
        if ((tick - MOUNT_TICKS) % FLIGHT_LOOP_TICKS == 0) player.spotAnim(Spotanim.magic_carpet_spotanim_flight)
        if (player.runEnergy < FULL_RUN_ENERGY) player.runEnergy = FULL_RUN_ENERGY
        if (!player.run) player.setRunHidden(true)
        while (player.walkSteps.size < QUEUED_STEPS && queueStep()) continue
    }

    private fun queueStep(): Boolean {
        if (along != targetAlong) {
            along += if (targetAlong > along) 1 else -1
            val (x, y) = GeneratedWorld.generator.roadCentreAt(arm, along)
            player.addWalkSteps(x, y, -1, false)
            return true
        }
        if (landingQueued) return false
        landingQueued = true
        player.addWalkSteps(destinationX, destinationY, -1, false)
        return true
    }

    override fun processIncomingHit(hit: Hit) {
        hit.damage = 0
    }

    override fun processTeleport(tele: Teleport) = false

    override fun canAttack(target: Entity) = false

    override fun keepCombating(target: Entity) = false

    override fun sendDeath() = false

    override fun login(): Boolean {
        player.tele(origin())
        return true
    }

    override fun logout(): Boolean {
        if (dismountAt == NOT_LANDING) {
            player.resetWalkSteps()
            player.tile = origin()
        }
        return true
    }

    override fun onRemove() {
        player.appearance.setBAS(-1)
        player.spotAnim(-1)
        player.setInvulnerable(false)
        player.setRunHidden(running)
        player.runEnergy = runEnergy
        player.unlock()
    }

    private fun origin() = Tile.of(originX, originY, GROUND)
}
