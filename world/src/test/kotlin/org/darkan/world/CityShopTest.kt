package org.darkan.world

import com.rs.game.content.world.generated.CityShop
import com.rs.utils.shop.ShopsHandler
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Obj
import org.darkan.world.harness.WorldHarness
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SWORD_SHOP = "harness_sword_shop"
private const val OTHER_SWORD_SHOP = "harness_other_sword_shop"
private const val GENERAL_STORE = "harness_general_store"
private const val RESTOCK_MILLIS = 30_000L

class CityShopTest {

    private fun saved(key: String) = File("./data/city-shops/sector-${EnvVars.worldId}/$key.json")

    private fun amountOf(shop: CityShop, id: Int) = shop.shop.defaultStock().first { it.id == id }.amount

    @AfterTest
    fun removeSavedShops() {
        listOf(SWORD_SHOP, OTHER_SWORD_SHOP, GENERAL_STORE).forEach { saved(it).delete() }
    }

    @Test
    fun `a new shop holds the vanilla stock of its kind and no coins`() {
        WorldHarness.boot()
        val vanilla = ShopsHandler.getShop("varrock_sword_shop")

        val shop = CityShop(SWORD_SHOP)

        assertEquals(0, shop.coins)
        assertEquals(vanilla.mainStock.map { it.item.id }.distinct(), shop.shop.defaultStock().map { it.id })
        assertEquals(vanilla.getDefaultQuantity(Obj.bronze_dagger), amountOf(shop, Obj.bronze_dagger))
        assertTrue(shop.shop.stock().isEmpty())
    }

    @Test
    fun `a new general store sells its deeds beside the vanilla stock`() {
        WorldHarness.boot()

        val shop = CityShop(GENERAL_STORE)

        assertTrue(shop.shop.defaultStock().isNotEmpty())
        assertEquals(listOf(Obj.estate_deed), shop.shop.stock().map { it.id })
    }

    @Test
    fun `a player buying pays the shop and the sale is saved for that shop alone`() {
        val harnessPlayer = WorldHarness.login("shopper")
        try {
            val player = harnessPlayer.player
            val shop = CityShop(SWORD_SHOP)
            val full = amountOf(shop, Obj.bronze_dagger)
            player.inventory.addCoins(100_000)
            shop.shop.addPlayer(player)

            shop.shop.buy(player, 0, 2)

            assertTrue(shop.coins > 0)
            assertEquals(full - 2, amountOf(shop, Obj.bronze_dagger))
            val reloaded = CityShop(SWORD_SHOP)
            assertEquals(shop.coins, reloaded.coins)
            assertEquals(full - 2, amountOf(reloaded, Obj.bronze_dagger))
            assertEquals(full, amountOf(CityShop(OTHER_SWORD_SHOP), Obj.bronze_dagger))
        } finally {
            WorldHarness.logout(harnessPlayer)
        }
    }

    @Test
    fun `two keepers of one kind each keep their own sold items and coins`() {
        WorldHarness.boot()
        val first = CityShop.of("harness_keeper_a", SWORD_SHOP)
        val second = CityShop.of("harness_keeper_b", SWORD_SHOP)

        first.shop.addItem(Obj.abyssal_whip, 3)
        first.earn(50)

        assertEquals(listOf(Obj.abyssal_whip), first.shop.stock().map { it.id })
        assertTrue(second.shop.stock().isEmpty())
        assertEquals(0, second.coins)
        assertEquals(first.shop.defaultStock().map { it.id }, second.shop.defaultStock().map { it.id })
    }

    @Test
    fun `a keeper takes over the ledger saved under the old city and kind key`() {
        WorldHarness.boot()
        val old = saved(SWORD_SHOP)
        old.parentFile.mkdirs()
        old.writeText("""{"coins":9,"items":[{"id":${Obj.abyssal_whip},"amount":4}],"restocked":${System.currentTimeMillis()}}""")

        val keeper = CityShop.of("harness_keeper_c", SWORD_SHOP)

        assertEquals(9, keeper.coins)
        assertEquals(listOf(4), keeper.shop.stock().filter { it.id == Obj.abyssal_whip }.map { it.amount })
        assertTrue(!old.isFile)
        saved("harness_keeper_c").delete()
    }

    @Test
    fun `default stock comes back for the time that has passed and stops at its default`() {
        WorldHarness.boot()
        val full = amountOf(CityShop(SWORD_SHOP), Obj.bronze_dagger)
        val file = saved(SWORD_SHOP)
        file.parentFile.mkdirs()
        val tenRestocksAgo = System.currentTimeMillis() - RESTOCK_MILLIS * 10
        file.writeText("""{"coins":7,"items":[{"id":${Obj.bronze_dagger},"amount":${full - 20}},{"id":${Obj.iron_dagger},"amount":${full - 3}},{"id":${Obj.abyssal_whip},"amount":2}],"restocked":$tenRestocksAgo}""")

        val shop = CityShop(SWORD_SHOP)
        shop.restock()

        assertEquals(7, shop.coins)
        assertEquals(full - 10, amountOf(shop, Obj.bronze_dagger))
        assertEquals(full, amountOf(shop, Obj.iron_dagger))
        assertEquals(listOf(2), shop.shop.stock().filter { it.id == Obj.abyssal_whip }.map { it.amount })
    }
}
