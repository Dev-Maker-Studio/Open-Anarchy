package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.content.world.npcs.merchant.CoinTrader
import com.rs.game.content.world.npcs.merchant.MerchantShop
import com.rs.game.model.entity.player.Player
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Obj
import world.gregs.voidps.type.Tile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

private const val STARTING_COINS = 10_000
private const val DEED_STOCK = 3
private const val DEED_REFRESH_TICKS = 2000
private const val GENERAL_STORE_SUFFIX = "general_store"

private class SavedItem(val id: Int, val amount: Int)

private class SavedShop(val coins: Int, val items: List<SavedItem>)

class CityShop(val key: String) : CoinTrader {
    private val file = File("./data/city-shops/sector-${EnvVars.worldId}/$key.json")
    private var loading = true

    override var coins = STARTING_COINS
        private set

    val name = key.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    val sellsDeeds = key.endsWith(GENERAL_STORE_SUFFIX)
    val shop = MerchantShop(this, name)

    init {
        if (file.isFile) {
            val saved = gson.fromJson(file.readText(), SavedShop::class.java)
            coins = saved.coins
            saved.items.forEach { shop.addItem(it.id, it.amount) }
        } else if (sellsDeeds) shop.addItem(Obj.estate_deed, DEED_STOCK)
        loading = false
    }

    override fun canTrade() = true

    override fun earn(amount: Int) {
        coins = (coins.toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun pay(amount: Int) {
        coins = (coins - amount).coerceAtLeast(0)
    }

    override fun stockChanged() = save()

    fun refreshDeeds() {
        if (!sellsDeeds || shop.deedCount() >= DEED_STOCK) return
        shop.addItem(Obj.estate_deed, 1)
        save()
    }

    @Synchronized
    private fun save() {
        if (loading) return
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(gson.toJson(SavedShop(coins, shop.stock().map { SavedItem(it.id, it.amount) })))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val gson = Gson()
        private val shops = ConcurrentHashMap<String, CityShop>()

        fun of(key: String): CityShop = shops.computeIfAbsent(key, ::CityShop)
    }
}

class CityShopkeeper(id: Int, tile: Tile, key: String) : CityResident(id, tile) {
    private val ledger = CityShop.of(key)

    fun trade(player: Player) {
        if (player.bank.checkPin()) ledger.shop.addPlayer(player)
    }

    fun talk(player: Player) {
        player.startConversation {
            npc(id, HeadE.CHEERFUL, "Welcome to the ${ledger.name}. I buy what you bring me and sell what others have sold.")
            npc(id, HeadE.CALM_TALK, "Whatever is on my shelves stays there until somebody buys it.")
        }
    }

    override fun processNPC() {
        super.processNPC()
        if (tickCounter % DEED_REFRESH_TICKS == 0L) ledger.refreshDeeds()
    }
}
