package com.rs.game.content.quests.area

import com.rs.game.content.world.generated.DungeonBosses
import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.Utils

private const val SHORT_WEIGHT = 1
private const val MEDIUM_WEIGHT = 3
private const val LONG_WEIGHT = 7
private const val SHORT_POINTS = 1
private const val MEDIUM_POINTS = 2
private const val LONG_POINTS = 3
private const val TIERS_PER_BONUS_POINT = 5
private const val COINS_PER_WEIGHT = 100
private const val XP_PER_WEIGHT = 150
private const val XP_CURVE_OFFSET = 3
private const val XP_CURVE_DIVISOR = 4

class QuestReward(val questPoints: Int, val coins: Int, val xp: Int, val skills: List<Int>, val items: List<Pair<Int, Int>>) {

    fun lines(): List<String> = buildList {
        add("$questPoints quest ${if (questPoints == 1) "point" else "points"}")
        add("${Utils.getFormattedNumber(coins)} coins")
        if (skills.isNotEmpty()) skills.forEach { add("${Utils.getFormattedNumber(xp / skills.size)} ${Constants.SKILL_NAME[it]} XP") }
        items.forEach { (id, amount) -> add("$amount x ${org.darkan.core.cache.ItemDefinitions.getDefs(id).name}") }
    }
}

object AreaQuestRewards {

    private fun weight(length: String) = when (length) {
        LENGTH_SHORT -> SHORT_WEIGHT
        LENGTH_MEDIUM -> MEDIUM_WEIGHT
        else -> LONG_WEIGHT
    }

    private fun basePoints(length: String) = when (length) {
        LENGTH_SHORT -> SHORT_POINTS
        LENGTH_MEDIUM -> MEDIUM_POINTS
        else -> LONG_POINTS
    }

    fun of(quest: AreaQuest): QuestReward {
        val tier = AreaQuestRegistry.tier(quest.area)
        val weight = weight(quest.length)
        return QuestReward(
            questPoints = basePoints(quest.length) + tier / TIERS_PER_BONUS_POINT,
            coins = COINS_PER_WEIGHT * weight * tier,
            xp = XP_PER_WEIGHT * weight * tier * (tier + XP_CURVE_OFFSET) / XP_CURVE_DIVISOR,
            skills = quest.rewards?.xp.orEmpty().mapNotNull(AreaQuestRegistry::skill),
            items = quest.rewards?.items.orEmpty().mapNotNull { reward -> DungeonBosses.objId(reward.item)?.let { it to (reward.amount ?: 1) } },
        )
    }

    fun give(player: Player, reward: QuestReward) {
        player.inventory.addCoins(reward.coins)
        if (reward.skills.isNotEmpty()) reward.skills.forEach { player.skills.addXpQuest(it, reward.xp.toDouble() / reward.skills.size) }
        reward.items.forEach { (id, amount) -> player.inventory.addItemDrop(id, amount) }
    }
}
