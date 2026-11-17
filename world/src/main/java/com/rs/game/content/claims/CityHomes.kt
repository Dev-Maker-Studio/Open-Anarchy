package com.rs.game.content.claims

import com.google.gson.Gson
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import org.darkan.core.EnvVars
import org.darkan.core.Utils
import org.darkan.core.city.Building
import org.darkan.core.city.City
import org.darkan.core.map.GeneratedWorld
import world.gregs.voidps.type.Tile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class CityHome(val city: City, val number: Int, val building: Building) {
    val key = "${city.id}_${building.x}_${building.y}"

    val owner: String? get() = CityHomes.deed(this)?.owner

    val askingPrice: Int get() = CityHomes.deed(this)?.price ?: 0

    val price: Int get() = if (owner == null) CityHomes.REALTOR_PRICE else askingPrice

    val forSale: Boolean get() = owner == null || askingPrice > 0

    fun ownedBy(player: Player): Boolean = owner == player.username

    fun contains(tile: Tile): Boolean = tile.plane < building.levels && building.touches(tile.x, tile.y, tile.x, tile.y)
}

object CityHomes {
    const val REALTOR_PRICE = 10_000
    const val BUYBACK_PRICE = 5_000
    const val REALTOR_OWNER = "the realtor"

    class Deed(var owner: String? = null, var price: Int = 0)

    private class Ledger(val deeds: HashMap<String, Deed> = HashMap(), val owed: HashMap<String, Long> = HashMap())

    private val gson = Gson()
    private val file = File("./data/city-homes/sector-${EnvVars.worldId}.json")
    private val ledger: Ledger by lazy { if (file.isFile) gson.fromJson(file.readText(), Ledger::class.java) else Ledger() }
    private val homes: Map<String, List<CityHome>> by lazy {
        GeneratedWorld.cities.associate { city -> city.id to city.homes.mapIndexed { index, building -> CityHome(city, index + 1, building) } }
    }

    fun deed(home: CityHome): Deed? = ledger.deeds[home.key]

    fun homesIn(city: City): List<CityHome> = homes[city.id].orEmpty()

    fun homeAt(tile: Tile): CityHome? {
        val city = GeneratedWorld.cityAt(tile.x, tile.y) ?: return null
        return homesIn(city).firstOrNull { it.contains(tile) }
    }

    fun claimAt(tile: Tile): Claim? {
        val home = homeAt(tile) ?: return null
        val building = home.building
        return Claim(owner = home.owner ?: REALTOR_OWNER, plane = tile.plane, minX = building.x, minY = building.y, maxX = building.maxX, maxY = building.maxY)
    }

    fun owedTo(player: Player): Long = ledger.owed[player.username] ?: 0L

    fun buy(player: Player, home: CityHome): Boolean {
        val seller = home.owner
        val price = home.price
        if (!home.forSale || seller == player.username) return false
        if (!player.inventory.hasCoins(price)) {
            player.sendMessage("You need ${Utils.getFormattedNumber(price)} coins to buy that home.")
            return false
        }
        player.inventory.removeCoins(price)
        if (seller != null) {
            ledger.owed.merge(seller, price.toLong(), Long::plus)
            World.getPlayerByUsername(seller)?.sendMessage("Your home in ${home.city.name} has sold for ${Utils.getFormattedNumber(price)} coins. Collect them from the city's realtor.")
        }
        ledger.deeds[home.key] = Deed(player.username)
        save()
        player.sendMessage("You buy home ${home.number} in ${home.city.name} for ${Utils.getFormattedNumber(price)} coins. You can now build inside it.")
        return true
    }

    fun list(player: Player, home: CityHome, price: Int): Boolean {
        if (!home.ownedBy(player) || price <= 0) return false
        deed(home)?.price = price
        save()
        player.sendMessage("Home ${home.number} in ${home.city.name} is now for sale at ${Utils.getFormattedNumber(price)} coins.")
        return true
    }

    fun unlist(player: Player, home: CityHome): Boolean {
        if (!home.ownedBy(player) || home.askingPrice <= 0) return false
        deed(home)?.price = 0
        save()
        player.sendMessage("Home ${home.number} in ${home.city.name} is no longer for sale.")
        return true
    }

    fun sellToRealtor(player: Player, home: CityHome): Boolean {
        if (!home.ownedBy(player)) return false
        ledger.deeds.remove(home.key)
        save()
        player.inventory.addCoins(BUYBACK_PRICE)
        player.sendMessage("The realtor buys home ${home.number} in ${home.city.name} back for ${Utils.getFormattedNumber(BUYBACK_PRICE)} coins.")
        return true
    }

    fun collect(player: Player): Long {
        val owed = ledger.owed.remove(player.username) ?: return 0L
        save()
        player.inventory.addCoins(owed)
        return owed
    }

    private fun save() {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(gson.toJson(ledger))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
