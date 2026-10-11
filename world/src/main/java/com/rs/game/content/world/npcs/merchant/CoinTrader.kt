package com.rs.game.content.world.npcs.merchant

interface CoinTrader {
    val coins: Int
    fun canTrade(): Boolean
    fun earn(amount: Int)
    fun pay(amount: Int)
    fun stockChanged()
}
