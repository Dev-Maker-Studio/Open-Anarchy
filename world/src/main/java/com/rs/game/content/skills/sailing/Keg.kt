package com.rs.game.content.skills.sailing

import com.rs.engine.dialogue.sendOptionsDialogue
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.Constants
import org.darkan.core.EnvVars
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj

enum class KegKind(val label: String, val drink: Int, val seasRequired: Int, val boosts: List<Pair<Int, Int>>) {
    GROG("grog", Obj.grog, 0, emptyList()),
    CIDER("cider", Obj.cider, 0, listOf(Constants.FARMING to 1)),
    WHIRLPOOL("whirlpool surprise", Obj.beer, 0, listOf(Constants.SAILING to 3, Constants.COOKING to 2)),
    KRAKEN_INK("kraken ink stout", Obj.asgarnian_ale, 10, listOf(Constants.SAILING to 1, Constants.MAGIC to 2)),
    PERILDANCE("perildance bitter", Obj.greenmans_ale, 13, listOf(Constants.SAILING to 1, Constants.RANGE to 2)),
    TRAWLER("trawler's trust", Obj.mature_asgarnian_ale, 50, listOf(Constants.SAILING to 2, Constants.FISHING to 2)),
    HORIZON("horizon's lure", Obj.mature_greenmans_ale, 70, listOf(Constants.SAILING to 4));

    fun unlocked(profile: SailorProfile): Boolean =
        if (this == WHIRLPOOL) profile.rank(TrialCourse.LAKE_DASH) >= TrialRank.SHARK else profile.chartedSeas.size >= seasRequired
}

object Keg {
    const val CAPACITY = 25
    private const val MAX_OPTIONS = 5

    fun stock(player: Player, voyage: Voyage) {
        val boat = voyage.boat
        val current = boat.keg
        if (current != null) return refill(player, boat, current)
        val profile = SailorStore.of(player.username)
        val choices = KegKind.entries.filter { it.unlocked(profile) && player.inventory.containsItem(it.drink) }.take(MAX_OPTIONS)
        if (choices.isEmpty()) return player.sendMessage("You have no ale the keg could hold. Grog, cider and other ales fill it, 25 at a time.")
        player.sendOptionsDialogue("Fill the keg with") {
            choices.forEach { kind -> opExec(kind.label.replaceFirstChar { it.uppercase() }) { refill(player, boat, kind) } }
        }
    }

    private fun refill(player: Player, boat: Boat, kind: KegKind) {
        if (boat.keg != null && boat.keg != kind) return player.sendMessage("The keg already holds ${boat.keg?.label}; drain it first.")
        val room = CAPACITY - boat.kegDoses
        val have = player.inventory.getAmountOf(kind.drink)
        val moved = minOf(room, have)
        if (moved <= 0) return player.sendMessage(if (room <= 0) "The keg is full." else "You have no ${ItemDefinitions.getDefs(kind.drink).name.lowercase()} to pour in.")
        player.inventory.deleteItem(kind.drink, moved)
        boat.keg = kind
        boat.kegDoses += moved
        BoatStore.save(boat)
        player.sendMessage("You pour $moved measures of ${kind.label} into the keg (${boat.kegDoses}/$CAPACITY).")
    }

    fun drink(player: Player, boat: Boat) {
        val kind = boat.keg
        if (kind == null || boat.kegDoses <= 0) return player.sendMessage("The keg is empty.")
        boat.kegDoses--
        kind.boosts.forEach { (skill, amount) -> player.skills.adjustStat(amount, 0.0, true, skill) }
        BoatStore.save(boat)
        player.sendMessage("You drink a measure of ${kind.label} (${boat.kegDoses}/$CAPACITY left).")
    }
}

@ServerStartupEvent
fun mapKeg() {
    if (!EnvVars.generateMaps) return
    onObjectClick(Loc.sailing_keg, checkDistance = false) { e ->
        val voyage = Voyages.of(e.player) ?: return@onObjectClick
        if (e.opNum == 2) Keg.drink(e.player, voyage.boat) else Keg.stock(e.player, voyage)
    }
}
