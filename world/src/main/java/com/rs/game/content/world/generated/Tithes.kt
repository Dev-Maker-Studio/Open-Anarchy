package com.rs.game.content.world.generated

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import com.rs.utils.Ticks
import org.darkan.core.Utils

private const val TITHED = "Coins tithed"
private const val RETURN_CARRY = "Tithe return carried"
private const val RETURN_SHARE = 168
private const val RETURN_MINUTES = 20

object Tithes {

    fun total(player: Player): Long = player.getCounterValueL(TITHED)

    fun give(player: Player, church: Church, coins: Int): Boolean {
        val merchants = church.merchants()
        if (merchants.isEmpty()) return false
        player.inventory.removeCoins(coins)
        val share = coins / merchants.size
        val spare = coins % merchants.size
        merchants.forEachIndexed { index, merchant -> merchant.receive(share + if (index < spare) 1 else 0) }
        player.incrementCount(TITHED, coins)
        return true
    }

    fun payReturn(player: Player) {
        val tithed = total(player)
        if (tithed <= 0) return
        val owed = player.getCounterValueL(RETURN_CARRY) + tithed
        val coins = owed / RETURN_SHARE
        player.incrementCount(RETURN_CARRY, tithed - coins * RETURN_SHARE)
        if (coins <= 0) return
        player.inventory.addCoins(coins)
        player.sendMessage("<col=00FFFF>Your tithes return ${Utils.formatLong(coins)} coins to your pouch.")
    }
}

@ServerStartupEvent
fun mapTitheReturns() {
    val period = Ticks.fromMinutes(RETURN_MINUTES)
    onLogin { (player) ->
        player.tasks.scheduleLooping(period - (player.timePlayed % period).toInt(), period) { Tithes.payReturn(player) }
    }
}
