package com.rs.game.content.world.generated

import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.game.content.claims.CityHome
import com.rs.game.content.claims.CityHomes
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import com.rs.plugin.kts.onNpcClick
import org.darkan.core.Utils
import org.darkan.core.city.City
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Cs2
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.IFEvents
import world.gregs.voidps.type.Tile

private const val VIEW_OPTION = "View-homes"
private const val ROW_PARTS = 5
private const val FIRST_BUTTON = 2
private const val BUTTONS = ROW_PARTS - FIRST_BUTTON
private const val CITY_ATTRIBUTE = "realtor_city"
private const val NO_BUTTON = ""

class CityRealtor(tile: Tile) : CityResident(Npc.city_realtor, tile)

private enum class HomeAction(val label: String) {
    BUY("Buy"),
    LIST("List for sale"),
    REPRICE("Change price"),
    UNLIST("Take off market"),
    SELL_BACK("Sell to realtor"),
}

private fun coins(amount: Number) = "${Utils.getFormattedNumber(amount.toInt())} coins"

private fun display(username: String) = Utils.formatPlayerNameForDisplay(username)

private fun quarter(home: CityHome): String {
    val building = home.building
    val north = building.y + building.depth / 2 >= home.city.centerY
    val east = building.x + building.width / 2 >= home.city.centerX
    return "${if (north) "north" else "south"}-${if (east) "east" else "west"}"
}

private fun title(home: CityHome): String {
    val building = home.building
    return "Home ${home.number}: ${building.width} x ${building.depth}, ${building.levels} ${if (building.levels == 1) "floor" else "floors"}, ${quarter(home)} quarter"
}

private fun status(player: Player, home: CityHome): String {
    val owner = home.owner
    return when {
        owner == null -> "For sale by the realtor: ${coins(home.price)}"
        home.ownedBy(player) && home.forSale -> "Yours, for sale at ${coins(home.price)}"
        home.ownedBy(player) -> "Yours, not for sale"
        home.forSale -> "For sale by ${display(owner)}: ${coins(home.price)}"
        else -> "Owned by ${display(owner)}, not for sale"
    }
}

private fun actions(player: Player, home: CityHome): List<HomeAction> = when {
    home.ownedBy(player) && home.forSale -> listOf(HomeAction.REPRICE, HomeAction.UNLIST, HomeAction.SELL_BACK)
    home.ownedBy(player) -> listOf(HomeAction.LIST, HomeAction.SELL_BACK)
    home.forSale -> listOf(HomeAction.BUY)
    else -> emptyList()
}

object RealtorOffice {

    fun open(player: Player, city: City) {
        if (player.interfaceManager.containsScreenInter() && !player.interfaceManager.topOpen(Interface.realtor)) {
            player.sendMessage("Please close the interface you have open before looking at the homes.")
            return
        }
        player.stopAll()
        collect(player)
        val homes = CityHomes.homesIn(city)
        player.tempAttribs.setO<City>(CITY_ATTRIBUTE, city)
        player.interfaceManager.sendInterface(Interface.realtor)
        if (homes.isNotEmpty())
            player.packets.setIFEvents(IFEvents(Component.realtor.list, 0, homes.size * ROW_PARTS - 1).enableRightClickOption(0))
        for (home in homes) {
            val buttons = actions(player, home).map { it.label }
            player.packets.sendRunScript(Cs2.realtor_home_add, title(home), status(player, home), *Array(BUTTONS) { buttons.getOrElse(it) { NO_BUTTON } })
        }
    }

    fun collect(player: Player) {
        val paid = CityHomes.collect(player)
        if (paid > 0) player.sendMessage("The realtor hands you ${coins(paid)} from the sale of your home.")
    }

    fun click(player: Player, slot: Int) {
        val city = player.tempAttribs.getO<City>(CITY_ATTRIBUTE) ?: return
        val home = CityHomes.homesIn(city).getOrNull(slot / ROW_PARTS) ?: return
        val action = actions(player, home).getOrNull(slot % ROW_PARTS - FIRST_BUTTON) ?: return
        when (action) {
            HomeAction.BUY -> {
                val quoted = home.price
                confirm(player, city, "Buy home ${home.number} for ${coins(quoted)}?") {
                    if (home.price == quoted) CityHomes.buy(player, home) else player.sendMessage("The price of that home has just changed.")
                }
            }
            HomeAction.LIST, HomeAction.REPRICE -> player.sendInputInteger("What price, in coins, should home ${home.number} sell for?") { price ->
                if (price <= 0) player.sendMessage("The price has to be at least 1 coin.") else CityHomes.list(player, home, price)
                open(player, city)
            }
            HomeAction.UNLIST -> {
                CityHomes.unlist(player, home)
                open(player, city)
            }
            HomeAction.SELL_BACK -> confirm(player, city, "Sell home ${home.number} and all that is in it for ${coins(CityHomes.BUYBACK_PRICE)}?") { CityHomes.sellToRealtor(player, home) }
        }
    }

    private fun confirm(player: Player, city: City, question: String, accepted: () -> Unit) {
        player.interfaceManager.removeCentralInterface()
        player.startConversation {
            options(question) {
                op("Yes.") {
                    exec {
                        accepted()
                        open(player, city)
                    }
                }
                op("No.") {
                    exec { open(player, city) }
                }
            }
        }
    }

    fun talk(player: Player, city: City) {
        val owed = CityHomes.owedTo(player)
        player.startConversation {
            npc(Npc.city_realtor, HeadE.CHEERFUL, "Welcome to the ${city.name} land office. Every home in this city is bought and sold across my desk.")
            if (owed > 0)
                npc(Npc.city_realtor, HeadE.HAPPY_TALKING, "Your home sold while you were away. Here are your ${coins(owed)}.") { collect(player) }
            npc(Npc.city_realtor, HeadE.CALM_TALK, "I sell the empty homes for ${coins(CityHomes.REALTOR_PRICE)}. Once a home is yours you may build inside it, and when you tire of it you name your price and I sell it on for you.")
            options {
                op("Show me the homes.") {
                    exec { open(player, city) }
                }
                op("Not now.") {
                    player(HeadE.CALM_TALK, "Not now, thanks.")
                }
            }
        }
    }
}

@ServerStartupEvent
fun mapCityRealtor() {
    onNpcClick(Npc.city_realtor) { (player, npc, option) ->
        val city = GeneratedWorld.cityAt(npc.tile.x, npc.tile.y) ?: return@onNpcClick
        if (option == VIEW_OPTION) RealtorOffice.open(player, city) else RealtorOffice.talk(player, city)
    }
    onButtonClick(Interface.realtor) { e ->
        when (e.component) {
            Component.realtor.close_button -> e.player.interfaceManager.removeCentralInterface()
            Component.realtor.list -> RealtorOffice.click(e.player, e.slotId)
        }
    }
}
