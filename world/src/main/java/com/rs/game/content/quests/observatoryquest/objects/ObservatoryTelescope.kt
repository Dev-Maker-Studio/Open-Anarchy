package com.rs.game.content.quests.observatoryquest

import com.rs.engine.quest.Quest
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Varp

const val OBSERVATORY_CONSTELLATION_ATTR = "observatoryQuestConstellation"
const val OBSERVATORY_VIEWED_CONSTELLATION_ATTR = "observatoryQuestViewedConstellation"

const val CONSTELLATION_AQUARIUS = 0
const val CONSTELLATION_ARIES = 1
const val CONSTELLATION_CANCER = 2
const val CONSTELLATION_CAPRICORN = 3
const val CONSTELLATION_GEMINI = 4
const val CONSTELLATION_LEO = 5
const val CONSTELLATION_LIBRA = 6
const val CONSTELLATION_PISCES = 7
const val CONSTELLATION_SAGITTARIUS = 8
const val CONSTELLATION_SCORPIO = 9
const val CONSTELLATION_TAURUS = 10
const val CONSTELLATION_VIRGO = 11

private const val CONSTELLATION_COUNT = 12

private val CONSTELLATION_NAMES = arrayOf("Aquarius", "Aries", "Cancer", "Capricorn", "Gemini", "Leo", "Libra", "Pisces", "Sagittarius", "Scorpio", "Taurus", "Virgo")

@ServerStartupEvent
fun mapObservatoryTelescope() {
    onObjectClick(Loc.qip_obs_tele_gear_upper) { (player) ->
        player.sendMessage("It seems the telescope is not operational at the moment.")
    }

    onObjectClick(Loc.qip_obs_tele_gear_upper2) { (player) ->
        if (player.getQuestStage(Quest.OBSERVATORY_QUEST) < STAGE_OBSERVING_CONSTELLATION) {
            player.sendMessage("It seems the telescope is not operational at the moment.")
            return@onObjectClick
        }
        val constellation = getOrAssignObservatoryConstellation(player)
        player.sendMessage("You look through the telescope.")
        player.set(OBSERVATORY_VIEWED_CONSTELLATION_ATTR, true)
        showObservatoryConstellation(player, constellation)
    }
}

fun getOrAssignObservatoryConstellation(player: Player): Int {
    val existing = player.getI(OBSERVATORY_CONSTELLATION_ATTR, -1)
    if (existing in CONSTELLATION_AQUARIUS..CONSTELLATION_VIRGO) return existing
    val constellation = Utils.random(CONSTELLATION_COUNT)
    player.set(OBSERVATORY_CONSTELLATION_ATTR, constellation)
    return constellation
}

fun getObservatoryConstellation(player: Player): Int = player.getI(OBSERVATORY_CONSTELLATION_ATTR, -1)

fun hasViewedObservatoryConstellation(player: Player): Boolean = player.getBool(OBSERVATORY_VIEWED_CONSTELLATION_ATTR)

private fun showObservatoryConstellation(player: Player, constellation: Int) {
    player.vars.setVar(Varp.varp_1140, if (constellation in CONSTELLATION_AQUARIUS..CONSTELLATION_VIRGO) constellation + 1 else 1)
    player.interfaceManager.sendInterface(Interface.luc2_telescope_view)
}

fun getObservatoryConstellationName(constellation: Int): String = CONSTELLATION_NAMES.getOrElse(constellation) { "Unknown" }
