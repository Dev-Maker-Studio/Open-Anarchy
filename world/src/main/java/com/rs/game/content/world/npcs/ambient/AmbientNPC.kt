package com.rs.game.content.world.npcs.ambient

import com.rs.engine.pathfinder.DumbRouteFinder
import com.rs.game.World
import com.rs.game.content.world.npcs.merchant.CoinTrader
import com.rs.game.content.world.npcs.merchant.MerchantShop
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile
import java.util.Random

private const val MIN_COINS = 1
private const val MAX_COINS = 10
private const val WANDER_CHANCE = 0.5
private const val WANDER_RADIUS = 12
private const val WANDER_STEPS = 8
private const val WANDER_PAUSE_TICKS = 15
private const val SEED_PRIME = 31L

class AmbientNPC(id: Int, tile: Tile) : NPC(id, tile, null, true), CoinTrader {
    override var coins = MIN_COINS + Random(tile.x * SEED_PRIME + tile.y).nextInt(MAX_COINS - MIN_COINS + 1)
        private set

    val shop = MerchantShop(this)

    private val path = Random(id * SEED_PRIME * SEED_PRIME + tile.x * SEED_PRIME + tile.y)
    private var pauseWanderUntil = 0L

    init {
        isNoDistanceCheck = true
    }

    override fun canTrade() = !isDead && !hasFinished()

    override fun earn(amount: Int) {
        coins = (coins.toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun pay(amount: Int) {
        coins = (coins - amount).coerceAtLeast(0)
    }

    override fun stockChanged() = Unit

    fun pauseWandering() {
        pauseWanderUntil = tickCounter.toLong() + WANDER_PAUSE_TICKS
    }

    fun trade(player: Player) {
        pauseWandering()
        if (player.bank.checkPin()) shop.addPlayer(player)
    }

    override fun randomWalk() {
        if (hasWalkSteps() || tickCounter < pauseWanderUntil || path.nextDouble() >= WANDER_CHANCE) return
        val target = tile.transform(path.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS, path.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS, 0)
        resetWalkSteps()
        DumbRouteFinder.addDumbPathfinderSteps(this, target, WANDER_STEPS, collisionStrategy)
        setRespawnTile(tile)
    }

    override fun sendDeath(source: Entity) {
        shop.closeForViewers()
        super.sendDeath(source)
    }

    override fun drop() {
        if (coins > 0) World.addGroundItem(Item(Obj.coins, coins), tile, mostDamageReceivedSourcePlayer, false)
    }
}
