package com.rs.rsps.Power

import com.rs.engine.book.Book
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.gameval.gen.Obj

@ServerStartupEvent
fun mapCodex() {
    onItemClick(Obj.obj_6767, options = arrayOf("Read")) { (player, item) ->
        if (!SpecialItems.readCodex(player, item))
            player.openBook(Codex(item.getMetaDataO<String>("Codex") ?: "Introduction"))
    }
}

class Codex(codexType: String) : Book(
    "Codex of $codexType",
    """
    <u>Scaling laws</u>
    For most skills and upgrades your character infinitely scales. This includes weight, run energy, health, prayer and other stats.

    A codex increases these stats permanently in some way. They are typically dropped by a boss influenced by world scale.

    You can increase the scale of the world through Father John outside Lumbridge castle.

    <u>Combining items</u>
    By using a hammer on an item you can increase your characters ability to use a specific item.

    It costs gold but serves as a permanent boost to your equipment.

    Some items boost better and faster than others depending on if is a unique item from a boss.

    <u>Boss Scaling</u>
    As you increase the scale of a boss the amount of items they drop increases.

    The unique drops also increase their ability to boost their bonuses to with a hammer.

    The health, max hit and defensive stats of the boss increase with scale.

    You need to increase all your stats to be able to defeat them at higher scales.

    <u>PVP is on but...</u>
    As you progress you will find your character itself is more important than your bank.

    If you lose torva, for example, you can get it again no problem.

    You keep your bonuses.

    <u>Types of Codex</u>
    Agility Codex: Provides 1 agility xp every 5 blocks ran.

    Health Codex: Permanently boosts your health 1 point.

    Coin Codex: Increases all alchemy by 1 coin.

    Prayer codex: Regenerates prayer by 5 every 20 ticks
    """.trimIndent() + "\n"
)
