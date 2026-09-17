package com.rs.game.content.commands.development

import com.rs.engine.command.Commands
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.gameval.gen.Graphic
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item
import org.darkan.core.model.Rights

private const val PLAYER_MOD_ICON = 0
private const val JAGEX_MOD_ICON = 1
private const val PRAYER_HEADICONS = 8

private fun sprite(graphic: Int, frame: Int = 0) = "<sprite=$graphic,$frame>"

private fun obj(id: Int, amount: Int = 1) = "<obj=$id,$amount>"

private val showcaseTooltip = listOf(
    "<col=ffb000><shad=5a1a00>${sprite(Graphic.star_8_point)} Tooltip Showcase ${sprite(Graphic.star_8_point)}</shad></col>",
    "<col=ff3030>Red</col> <col=30ff30>green</col> <col=4090ff>blue</col> <shad=ff00ff>shadowed</shad> <str>struck</str> <u=ffffff>underlined</u>",
    "Staff: <img=$PLAYER_MOD_ICON> player mod  <img=$JAGEX_MOD_ICON> Jagex mod",
    "Head icons: ${sprite(Graphic.headicons_pk)}" + (0 until PRAYER_HEADICONS).joinToString("") { sprite(Graphic.headicons_prayer, it) },
    "Rare drops: ${obj(Obj.red_partyhat)}${obj(Obj.santa_hat)}${obj(Obj.godwars_armadyl_completed_godsword)}${obj(Obj.dragonfire_shield)}${obj(Obj.sum2_spirit_shield_elysian)}",
    "Supplies: ${obj(Obj.shark, 28)} Shark <times> 28  ${obj(Obj.dragon_bones, 5)} Bones <times> 5",
    "Value: ${sprite(Graphic.coins_icon)} ${obj(Obj.coins, 1_337_000)} <col=ffff00>1,337,000 gp</col>",
    "Requires: ${sprite(Graphic.staticons_high_res_0)} <col=00ff80>70 Attack</col>  ${sprite(Graphic.skill_prayer_silver)} <col=00ff80>43 Prayer</col>",
    "${sprite(Graphic.login_tick)} Tradeable  ${sprite(Graphic.cross)} Members only  ${sprite(Graphic.clan_icon_crown)} Clan approved",
    "Escapes: <lt>not a tag<gt>  <euro>5  <copy> Jagex  Darkan<reg>",
    "<col=a0a0a0>\"Whips are ${sprite(Graphic.leftarrow)} fun ${sprite(Graphic.rightarrow)} to swing.\"</col>",
).joinToString("<br>")

@ServerStartupEvent
fun loadTooltipCommands() {
    Commands.add(Rights.DEVELOPER, "tooltipwhip", "Gives an abyssal whip whose tooltip shows off every tooltip text feature.") { p, _ ->
        val whip = Item(Obj.abyssal_whip, 1).addMetaData(ItemTooltips.METADATA_KEY, showcaseTooltip)
        if (!p.inventory.addItem(whip)) {
            p.sendMessage("You need a free inventory slot.")
            return@add
        }
        p.sendMessage("Hover the whip to see its tooltip.")
    }
}
