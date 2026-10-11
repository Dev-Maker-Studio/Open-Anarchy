package com.rs.game.content.world.npcs.merchant

import com.rs.engine.Shop
import com.rs.game.model.entity.player.Player
import com.rs.utils.shop.ShopItem
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Varp
import org.darkan.core.model.Item
import kotlin.math.ceil

private const val SHOP_NAME = "Merchant"
private const val SHOP_ATTRIBUTE = "Shop"
private const val SELL_MARKUP = 1.2
private const val DEED_PRICE = 100
private const val MIN_PRICE = 1
private const val MERCHANT_SHOP_INTERFACE = 1326

class MerchantShop(private val merchant: CoinTrader) : Shop(SHOP_NAME, Obj.coins, emptyArray<ShopItem>(), true, false) {
    private val viewers = HashSet<Player>()

    fun stock(): List<Item> = generalStock.orEmpty().filterNotNull().map { it.item }

    fun consume(itemId: Int): Boolean {
        val slots = generalStock ?: return false
        val index = slots.indexOfFirst { it?.item?.id == itemId }
        if (index < 0) return false
        val entry = slots[index] ?: return false
        entry.item.amount -= 1
        if (entry.item.amount <= 0) slots[index] = null
        refreshShop()
        merchant.stockChanged()
        return true
    }

    fun closeForViewers() {
        viewers.filter { it.tempAttribs.getO<Shop>(SHOP_ATTRIBUTE) === this }.forEach { it.closeInterfaces() }
        viewers.clear()
    }

    override fun getShopInterfaceId() = MERCHANT_SHOP_INTERFACE

    override fun addPlayer(player: Player) {
        super.addPlayer(player)
        viewers.add(player)
        showCoins(player)
    }

    fun showCoins(player: Player) = player.vars.setVar(Varp.merchant_coins, merchant.coins)

    fun showCoinsToViewers() = viewers.filter { it.tempAttribs.getO<Shop>(SHOP_ATTRIBUTE) === this }.forEach(::showCoins)

    override fun getSellPrice(item: Item): Int = storePrice(item)

    override fun getBuyPrice(item: ShopItem): Int =
        if (item.item.id == Obj.estate_deed) DEED_PRICE else ceil(storePrice(item.item) * SELL_MARKUP).toInt()

    fun deedCount(): Int = stock().filter { it.id == Obj.estate_deed }.sumOf { it.amount }

    override fun sell(player: Player, slotId: Int, quantity: Int) {
        if (!merchant.canTrade()) return
        val offered = player.inventory.getItem(slotId) ?: return
        val affordable = merchant.coins / storePrice(offered.unnoted())
        if (affordable < 1) {
            player.sendMessage("The merchant can't afford that.")
            return
        }
        val before = player.inventory.coinsAsInt
        super.sell(player, slotId, minOf(quantity, affordable))
        val paid = player.inventory.coinsAsInt - before
        if (paid <= 0) return
        merchant.pay(paid)
        merchant.stockChanged()
        showCoinsToViewers()
    }

    override fun buy(player: Player, slotId: Int, quantity: Int) {
        if (!merchant.canTrade()) return
        val before = player.inventory.coinsAsInt
        super.buy(player, slotId, quantity)
        val spent = before - player.inventory.coinsAsInt
        if (spent <= 0) return
        merchant.earn(spent)
        merchant.stockChanged()
        showCoinsToViewers()
    }

    private fun storePrice(item: Item): Int = maxOf(item.definitions.getValue(), MIN_PRICE)

    private fun Item.unnoted(): Item = if (definitions.isNoted()) Item(definitions.getCertId(), amount) else this
}
