package com.rs.game.content.world.areas.karamja.npcs
import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import org.darkan.core.model.Item
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemOnNpc


@ServerStartupEvent

    fun mapAntiques() {
    val YANNI_ID = 515

    val antiques = mapOf(
        605 to 100,
        606 to 100,
        607 to 100,
        608 to 100,
        624 to 100,
        611 to 500,
        616 to 1000
    )

    onItemOnNpc(YANNI_ID) { (player, item, npc) ->
        antiques[item.id]?.let { value ->
            player.startConversation {
                npc(npc,HeadE.CALM_TALK,"Would you sell me the ${item.name} for $value coins?")
                options("Sell the ${item.name}?"){
                    op("Yes, please."){
                        item(item.id, "You sold the ${item.name} for $value coins.")
                        exec{
                            player.inventory.removeItems(Item(item.id, 1))
                            player.inventory.addCoins(value)
                        }
                    }
                    op("No, thanks."){
                        simple("You decide to keep the ${item.name} for now.")
                    }
                }
            }
        }
    }
}