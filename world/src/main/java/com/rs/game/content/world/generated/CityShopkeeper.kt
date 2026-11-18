package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.content.world.npcs.merchant.CoinTrader
import com.rs.game.content.world.npcs.merchant.MerchantShop
import com.rs.game.model.entity.player.Player
import org.darkan.core.Logger
import com.rs.utils.shop.ShopsHandler
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

private const val STARTING_COINS = 0
private const val RESTOCK_TICKS = 50
private const val RESTOCK_MILLIS = RESTOCK_TICKS * 600L
private const val DEED_STOCK = 3
private const val DEED_REFRESH_TICKS = 2000
private const val GENERAL_STORE_SUFFIX = "general_store"

private val DEFAULT_STOCK = mapOf(
    GENERAL_STORE_SUFFIX to "varrock_general_store",
    "sword_shop" to "varrock_sword_shop",
    "archery_shop" to "lowes_archery_emporium",
    "magic_shop" to "auburys_rune_shop",
    "clothes_shop" to "thessalias_clothing",
    "food_shop" to "wydins_food_store",
    "armour_shop" to "horviks_armour_shop",
    "fishing_shop" to "gerrants_fishy_business",
    "staff_shop" to "zaffs_superior_staves",
)

private fun defaultStock(key: String): List<Item> {
    val vanilla = DEFAULT_STOCK.entries.firstOrNull { key.endsWith(it.key) }?.value ?: return emptyList()
    val shop = ShopsHandler.getShop(vanilla)
    if (shop == null) {
        Logger.warn(CityShop::class.java, "defaultStock", "City shop $key has no default stock: vanilla shop $vanilla is not loaded")
        return emptyList()
    }
    return shop.mainStock.map { it.item.id }.distinct().map { Item(it, shop.getDefaultQuantity(it)) }
}

private class SavedItem(val id: Int, val amount: Int)

private class SavedShop(val coins: Int, val items: List<SavedItem>, val restocked: Long)

/** One shopkeeper's ledger: [key] identifies the keeper, [kind] (a shop key ending in the service name) picks the wares it sells. */
class CityShop(val key: String, private val kind: String = key) : CoinTrader {
    private val file = File("./data/city-shops/sector-${EnvVars.worldId}/$key.json")
    private var loading = true

    override var coins = STARTING_COINS
        private set

    val name = kind.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    val sellsDeeds = kind.endsWith(GENERAL_STORE_SUFFIX)
    val shop = MerchantShop(this, name, defaultStock(kind))
    private var restocked = System.currentTimeMillis()

    init {
        adoptLegacyFile()
        if (file.isFile) {
            val saved = gson.fromJson(file.readText(), SavedShop::class.java)
            coins = saved.coins
            saved.items.forEach { shop.restore(it.id, it.amount) }
            if (saved.restocked > 0) restocked = saved.restocked
        } else if (sellsDeeds) shop.addItem(Obj.estate_deed, DEED_STOCK)
        loading = false
    }

    /** Ledgers used to be saved per city and shop kind; the keeper standing there first takes that file over. */
    private fun adoptLegacyFile() {
        val legacy = File(file.parentFile, "$kind.json")
        if (kind == key || file.isFile || !legacy.isFile) return
        Files.move(legacy.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun canTrade() = true

    override fun earn(amount: Int) {
        coins = (coins.toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun pay(amount: Int) {
        coins = (coins - amount).coerceAtLeast(0)
    }

    override fun stockChanged() = save()

    fun restock() {
        val due = ((System.currentTimeMillis() - restocked) / RESTOCK_MILLIS).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        if (due < 1) return
        restocked += due * RESTOCK_MILLIS
        if (shop.restockDefaults(due)) save()
    }

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
        temporary.writeText(gson.toJson(SavedShop(coins, (shop.defaultStock() + shop.stock()).map { SavedItem(it.id, it.amount) }, restocked)))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val gson = Gson()
        private val shops = ConcurrentHashMap<String, CityShop>()

        fun of(key: String, kind: String = key): CityShop = shops.computeIfAbsent(key) { CityShop(it, kind) }
    }
}

/** Every keeper has his own stock and purse, saved under his own tile; [kind] only decides what he sells. */
class CityShopkeeper(id: Int, tile: Tile, kind: String) : CityResident(id, tile) {
    private val ledger = CityShop.of("${kind}_${tile.x}_${tile.y}_${tile.plane}", kind)

    fun trade(player: Player) {
        if (player.bank.checkPin()) ledger.shop.addPlayer(player)
    }

    fun talk(player: Player) {
        player.startConversation {
            npc(id, HeadE.CHEERFUL, "Welcome to the ${ledger.name}. I keep my usual wares in stock and sell what others have sold me.")
            npc(id, HeadE.CALM_TALK, "I buy what you bring me too, but only with the coins my customers have paid me.")
        }
    }

    override fun processNPC() {
        super.processNPC()
        if (tickCounter % RESTOCK_TICKS == 0L) ledger.restock()
        if (tickCounter % DEED_REFRESH_TICKS == 0L) ledger.refreshDeeds()
    }
}
