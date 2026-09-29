package com.rs.game.content.quests.observatoryquest

import com.rs.engine.quest.Quest
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import com.rs.utils.Utils

private const val BROKEN_TELESCOPE = 25438
private const val REPAIRED_TELESCOPE = 25439

private const val TELESCOPE_INTERFACE = 816
private const val CONSTELLATION_CONFIG = 1140

const val OBSERVATORY_CONSTELLATION_ATTR =
    "observatoryQuestConstellation"

const val OBSERVATORY_VIEWED_CONSTELLATION_ATTR =
    "observatoryQuestViewedConstellation"

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

@ServerStartupEvent
fun mapObservatoryTelescope() {

    onObjectClick(BROKEN_TELESCOPE) { (player) ->

        player.sendMessage(
            "It seems the telescope is not operational at the moment."
        )
    }

    onObjectClick(REPAIRED_TELESCOPE) { (player) ->

        val stage =
            player.getQuestStage(
                Quest.OBSERVATORY_QUEST
            )

        if (stage < STAGE_OBSERVING_CONSTELLATION) {
            player.sendMessage(
                "It seems the telescope is not operational at the moment."
            )
            return@onObjectClick
        }

        val constellation =
            getOrAssignObservatoryConstellation(player)

        player.sendMessage(
            "You look through the telescope."
        )

        player.set(
            OBSERVATORY_VIEWED_CONSTELLATION_ATTR,
            true
        )

        showObservatoryConstellation(
            player,
            constellation
        )
    }
}

fun getOrAssignObservatoryConstellation(
    player: Player
): Int {

    val existing =
        player.getI(
            OBSERVATORY_CONSTELLATION_ATTR,
            -1
        )

    if (
        existing in
        CONSTELLATION_AQUARIUS..CONSTELLATION_VIRGO
    ) {
        return existing
    }

    val constellation =
        Utils.random(CONSTELLATION_COUNT)

    player.set(
        OBSERVATORY_CONSTELLATION_ATTR,
        constellation
    )

    return constellation
}

fun getObservatoryConstellation(
    player: Player
): Int {
    return player.getI(
        OBSERVATORY_CONSTELLATION_ATTR,
        -1
    )
}

fun hasViewedObservatoryConstellation(
    player: Player
): Boolean {
    return player.getBool(
        OBSERVATORY_VIEWED_CONSTELLATION_ATTR
    )
}

/**
 * Displays the player's assigned constellation through the
 * Observatory telescope.
 *
 * Interface 816 provides the telescope viewport.
 *
 * Config 1140 is believed to select the constellation model
 * rendered inside the telescope's model component.
 */
private fun showObservatoryConstellation(
    player: Player,
    constellation: Int
) {
    val configValue =
        getConstellationConfigValue(
            constellation
        )

    /*
     * Set the constellation before opening the interface so the
     * appropriate model is already selected when the telescope
     * viewport appears.
     */
    player.packets.sendConfig(
        CONSTELLATION_CONFIG,
        configValue
    )

    player.interfaceManager.sendInterface(
        TELESCOPE_INTERFACE
    )
}

/**
 * Converts our internal Observatory constellation value into
 * the client config value used by config 1140.
 *
 * Config values 1-12 are believed to select the twelve
 * constellation models.
 *
 * The exact constellation -> config value ordering still needs
 * validation against this client/cache.
 */
private fun getConstellationConfigValue(
    constellation: Int
): Int {
    return when (constellation) {
        CONSTELLATION_AQUARIUS -> 1
        CONSTELLATION_ARIES -> 2
        CONSTELLATION_CANCER -> 3
        CONSTELLATION_CAPRICORN -> 4
        CONSTELLATION_GEMINI -> 5
        CONSTELLATION_LEO -> 6
        CONSTELLATION_LIBRA -> 7
        CONSTELLATION_PISCES -> 8
        CONSTELLATION_SAGITTARIUS -> 9
        CONSTELLATION_SCORPIO -> 10
        CONSTELLATION_TAURUS -> 11
        CONSTELLATION_VIRGO -> 12
        else -> 1
    }
}

fun getObservatoryConstellationName(
    constellation: Int
): String {
    return when (constellation) {
        CONSTELLATION_AQUARIUS -> "Aquarius"
        CONSTELLATION_ARIES -> "Aries"
        CONSTELLATION_CANCER -> "Cancer"
        CONSTELLATION_CAPRICORN -> "Capricorn"
        CONSTELLATION_GEMINI -> "Gemini"
        CONSTELLATION_LEO -> "Leo"
        CONSTELLATION_LIBRA -> "Libra"
        CONSTELLATION_PISCES -> "Pisces"
        CONSTELLATION_SAGITTARIUS -> "Sagittarius"
        CONSTELLATION_SCORPIO -> "Scorpio"
        CONSTELLATION_TAURUS -> "Taurus"
        CONSTELLATION_VIRGO -> "Virgo"
        else -> "Unknown"
    }
}
