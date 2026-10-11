package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import com.rs.game.model.item.ItemTooltips
import com.rs.game.model.item.TooltipSection
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.EnvVars
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import java.util.Random

private const val COINS_KEY = "portBagCoins"
private const val MINIMUM_COINS = 1

object PortBags {
    private val random = Random()

    private val COIN_BAGS = mapOf(
        BagTier.TINY to Obj.sailing_coin_bag_tiny, BagTier.SMALL to Obj.sailing_coin_bag_small, BagTier.MEDIUM to Obj.sailing_coin_bag_medium,
        BagTier.LARGE to Obj.sailing_coin_bag_large, BagTier.HUGE to Obj.sailing_coin_bag_huge
    )
    private val REWARD_BAGS = mapOf(
        BagTier.TINY to Obj.sailing_reward_bag_tiny, BagTier.SMALL to Obj.sailing_reward_bag_small, BagTier.MEDIUM to Obj.sailing_reward_bag_medium,
        BagTier.LARGE to Obj.sailing_reward_bag_large, BagTier.HUGE to Obj.sailing_reward_bag_huge
    )
    val COIN_ITEMS = COIN_BAGS.values.toIntArray()
    val REWARD_ITEMS = REWARD_BAGS.values.toIntArray()

    fun coinBag(tier: BagTier, coins: Int): Item = Item(COIN_BAGS.getValue(tier), 1).addMetaData(COINS_KEY, coins)

    fun rewardBag(tier: BagTier): Item = Item(REWARD_BAGS.getValue(tier), 1)

    fun openCoinBag(player: Player, slot: Int, item: Item) {
        val coins = item.getMetaDataI(COINS_KEY, MINIMUM_COINS).coerceAtLeast(MINIMUM_COINS)
        player.inventory.deleteItem(slot, Item(item.id, 1))
        player.inventory.addCoins(coins)
        player.sendMessage("You open the ${ItemDefinitions.getDefs(item.id).name.lowercase()} and find $coins coins.")
    }

    fun openRewardBag(player: Player, slot: Int, item: Item) {
        val tier = REWARD_BAGS.entries.firstOrNull { it.value == item.id }?.key ?: return
        player.inventory.deleteItem(slot, Item(item.id, 1))
        val (reward, amount) = PortBagTables.roll(tier, random)
        if (reward == Obj.coins) player.inventory.addCoins(amount) else player.inventory.addItemDrop(reward, amount)
        player.sendMessage("You open the ${ItemDefinitions.getDefs(item.id).name.lowercase()} and find $amount ${if (reward == Obj.coins) "coins" else ItemDefinitions.getDefs(reward).name.lowercase()}.")
    }
}

@ServerStartupEvent
fun mapPortBags() {
    if (!EnvVars.generateMaps) return
    ItemTooltips.metadata(COINS_KEY, TooltipSection.CONTENTS) { _, _, value -> "Coins: ${ItemTooltips.value(value)}" }
    onItemClick(*PortBags.COIN_ITEMS.toTypedArray(), options = arrayOf("Open")) { e -> PortBags.openCoinBag(e.player, e.slotId, e.item) }
    onItemClick(*PortBags.REWARD_ITEMS.toTypedArray(), options = arrayOf("Open")) { e -> PortBags.openRewardBag(e.player, e.slotId, e.item) }
}
