package com.rs.game.content.quests.bigchompybirdhunting

import com.rs.game.model.entity.player.Player

data class ChompyHat(
    val itemId: Int,
    val requiredKills: Int,
    val rank: String
)

val CHOMPY_HATS = listOf(
    ChompyHat(2978, 30, "Ogre Bowman"),
    ChompyHat(2979, 40, "Bowman"),
    ChompyHat(2980, 50, "Ogre Yeoman"),
    ChompyHat(2981, 70, "Yeoman"),
    ChompyHat(2982, 95, "Ogre Marksman"),
    ChompyHat(2983, 125, "Marksman"),
    ChompyHat(2984, 170, "Ogre Woodsman"),
    ChompyHat(2985, 225, "Woodsman"),
    ChompyHat(2986, 300, "Ogre Forester"),
    ChompyHat(2987, 400, "Forester"),
    ChompyHat(2988, 550, "Ogre Bowmaster"),
    ChompyHat(2989, 700, "Bowmaster"),
    ChompyHat(2990, 1000, "Ogre Expert"),
    ChompyHat(2991, 1300, "Expert"),
    ChompyHat(2992, 1700, "Ogre Dragon Archer"),
    ChompyHat(2993, 2250, "Dragon Archer"),
    ChompyHat(2994, 3000, "Expert Ogre Dragon Archer"),
    ChompyHat(2995, 4000, "Expert Dragon Archer")
)

fun claimChompyHats(
    player: Player
): Int {
    val kills = getChompyKills(player)

    var hatsGiven = 0

    for (hat in CHOMPY_HATS) {
        if (kills < hat.requiredKills) {
            continue
        }

        if (ownsChompyHat(player, hat.itemId)) {
            continue
        }

        player.inventory.addItemDrop(
            hat.itemId,
            1
        )

        hatsGiven++
    }

    return hatsGiven
}

fun nextChompyHat(
    player: Player
): ChompyHat? {
    val kills = getChompyKills(player)

    return CHOMPY_HATS.firstOrNull {
        kills < it.requiredKills
    }
}

fun highestEarnedChompyHat(
    player: Player
): ChompyHat? {
    val kills = getChompyKills(player)

    return CHOMPY_HATS.lastOrNull {
        kills >= it.requiredKills
    }
}

private fun ownsChompyHat(
    player: Player,
    itemId: Int
): Boolean {
    if (
        player.inventory.containsItem(
            itemId,
            1
        )
    ) {
        return true
    }

    if (
        player.bank.containsItem(
            itemId
        )
    ) {
        return true
    }

    if (
        player.equipment.containsOneItem(
            itemId
        )
    ) {
        return true
    }

    return false
}
