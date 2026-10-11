package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import com.rs.game.model.entity.player.managers.InterfaceManager
import com.rs.game.model.item.ItemTooltips
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import org.darkan.core.Constants
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Inv
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.gameval.gen.Varbit
import org.darkan.core.model.IFEvents
import org.darkan.core.model.Item

private const val TIER_STEP = 1
private const val PANEL_TOP_TIER = 6
private const val TENTHS = 10
private const val TICK_SECONDS_NUM = 6
private const val TICK_SECONDS_DEN = 10
private const val NAME_RESEND_TICKS = 50
private const val TAB_BUTTON_SLOTS = 6
private const val NAV_BUTTON_SLOTS = 29
private const val NAV_SAILS = 0
private const val NAV_SLOWER = 1
private const val NAV_FASTER = 2
private const val NAV_ANCHOR = 3
private const val NAV_TRIM = 4
private const val NAV_REPAIR = 5
private const val NAV_FIRST_HEADING = 6
private const val HEADING_STEP = 45
private const val TAB_SLOT_WIDTH = 2
private const val NAV_SLOT_WIDTH = 2
private const val TRIM_NONE = 0
private const val TRIM_TIMER = 1
private const val TRIM_READY = 2
private const val TRIM_BOOST = 3

object SailingPanel {
    fun open(player: Player, voyage: Voyage) {
        voyage.panelDismissed = false
        val manager = player.interfaceManager
        manager.sendSub(InterfaceManager.Sub.TAB_COMBAT, Interface.sailing_panel)
        manager.sendOverlay(Interface.sailing_trim_hud)
        player.packets.setIFEvents(IFEvents(Component.sailing_panel.tabs, 0, TAB_BUTTON_SLOTS - 1).enableRightClickOption(0))
        player.packets.setIFEvents(IFEvents(Component.sailing_panel.nav, 0, NAV_BUTTON_SLOTS - 1).enableRightClickOption(0))
        player.packets.setIFText(Component.sailing_panel.boat_name, voyage.boat.name)
        player.vars.setVarBit(Varbit.sailing_panel_tab, 0)
        sendCrew(player, voyage.boat)
        push(player, voyage)
    }

    fun close(player: Player) {
        val manager = player.interfaceManager
        manager.sendSubDefault(InterfaceManager.Sub.TAB_COMBAT)
        manager.removeOverlay()
        clearVars(player)
    }

    fun showCombat(player: Player) {
        Voyages.of(player)?.panelDismissed = true
        player.interfaceManager.sendSubDefault(InterfaceManager.Sub.TAB_COMBAT)
    }

    fun sendCrew(player: Player, boat: Boat) {
        player.packets.sendItems(Inv.sailing_crew_list, boat.crew.map { crewmate ->
            Item(Obj.sailing_boat_kit, 1).addMetaData(ItemTooltips.METADATA_KEY, SailingTexts.crewLine(crewmate))
        }.toTypedArray<Item?>())
    }

    fun sync(player: Player, voyage: Voyage) {
        if (!voyage.panelDismissed && !player.interfaceManager.topOpen(Interface.sailing_panel)) return open(player, voyage)
        if (voyage.ticks % NAME_RESEND_TICKS == 0L) {
            player.packets.setIFText(Component.sailing_panel.boat_name, voyage.boat.name)
            sendCrew(player, voyage.boat)
        }
        push(player, voyage)
    }

    private fun push(player: Player, voyage: Voyage) {
        val boat = voyage.boat
        val movement = voyage.movement
        val vars = player.vars
        vars.setVarBit(Varbit.sailing_hp, boat.hitpoints)
        vars.setVarBit(Varbit.sailing_max_hp, boat.maxHitpoints)
        vars.setVarBit(Varbit.sailing_sails_set, if (movement.sailsSet) 1 else 0)
        vars.setVarBit(Varbit.sailing_anchored, if (movement.anchored) 1 else 0)
        vars.setVarBit(Varbit.sailing_at_helm, if (voyage.atHelm) 1 else 0)
        vars.setVarBit(Varbit.sailing_speed_setting, movement.setting.ordinal)
        vars.setVarBit(Varbit.sailing_speed_tenths, Math.round(Math.abs(movement.speed) * TENTHS).toInt())
        vars.setVarBit(Varbit.sailing_heading, movement.heading)
        vars.setVarBit(Varbit.sailing_target_heading, movement.targetHeading)
        syncTrim(player, voyage)
        vars.setVarBit(Varbit.sailing_base_speed_tenths, Math.round(boat.baseSpeed * TENTHS).toInt())
        vars.setVarBit(Varbit.sailing_speed_cap_tenths, Math.round(boat.speedCap * TENTHS).toInt())
        vars.setVarBit(Varbit.sailing_acceleration_tenths, Math.round(boat.sail.acceleration * TENTHS).toInt())
        vars.setVarBit(Varbit.sailing_boost_duration, boat.sail.boostTicks)
        vars.setVarBit(Varbit.sailing_defence, boat.defence)
        vars.setVarBit(Varbit.sailing_armour, boat.keel?.armour ?: 0)
        vars.setVarBit(Varbit.sailing_repair_hp, RepairKits.healingInHold(boat))
        syncParts(player, boat)
        syncHold(player, boat)
        syncLog(player)
    }

    fun syncParts(player: Player, boat: Boat) {
        val vars = player.vars
        vars.setVarBit(Varbit.sailing_helm_tier, boat.helm.ordinal + TIER_STEP)
        vars.setVarBit(Varbit.sailing_hull_tier, panelTier(boat.hull.ordinal))
        vars.setVarBit(Varbit.sailing_sail_tier, panelTier(boat.sail.ordinal))
        vars.setVarBit(Varbit.sailing_keel_tier, boat.keel?.let { panelTier(it.ordinal) } ?: 0)
        vars.setVarBit(Varbit.sailing_cannon_tier, boat.cannon?.let { it.ordinal + TIER_STEP } ?: 0)
        vars.setVarBit(Varbit.sailing_hook_tier, boat.hook?.let { it.ordinal + TIER_STEP } ?: 0)
        vars.setVarBit(Varbit.sailing_has_anchor, if (Facility.ANCHOR in boat.facilities) 1 else 0)
        vars.setVarBit(Varbit.sailing_has_wind_catcher, if (boat.hasCatcher) 1 else 0)
        vars.setVarBit(Varbit.sailing_has_inoculation, if (boat.hasInoculationStation || Facility.INOCULATION_STATION in boat.facilities) 1 else 0)
        vars.setVarBit(Varbit.sailing_has_brazier, if (boat.hasBrazier || Facility.BRAZIER in boat.facilities) 1 else 0)
        vars.setVarBit(Varbit.sailing_crew_count, boat.crew.size)
        vars.setVarBit(Varbit.sailing_crew_slots, CrewRules.unlockedSlots(player.skills.getLevelForXp(Constants.SAILING), boat.type))
    }

    private fun panelTier(ordinal: Int): Int = minOf(ordinal, PANEL_TOP_TIER) + TIER_STEP

    fun syncHold(player: Player, boat: Boat) {
        player.vars.setVarBit(Varbit.sailing_hold_used, boat.hold.used)
        player.vars.setVarBit(Varbit.sailing_hold_capacity, boat.hold.capacity)
        player.vars.setVarBit(Varbit.sailing_repair_hp, RepairKits.healingInHold(boat))
    }

    fun syncLog(player: Player) {
        val profile = SailorStore.of(player.username)
        val level = player.skills.getLevelForXp(Constants.SAILING)
        player.vars.setVarBit(Varbit.sailing_task_count, profile.tasks.size)
        player.vars.setVarBit(Varbit.sailing_task_slots, PortTasks.slots(level))
        player.vars.setVarBit(Varbit.sailing_charts_done, profile.log.charted.size)
        player.vars.setVarBit(Varbit.sailing_charts_total, SailingScreens.chartTotal(profile))
    }

    private fun syncTrim(player: Player, voyage: Voyage) {
        val movement = voyage.movement
        val trim = voyage.trim
        val (state, ticks) = when {
            !movement.sailsSet -> TRIM_NONE to 0
            trim.gustOpen -> TRIM_READY to trim.windowTicksLeft
            movement.boosted -> TRIM_BOOST to movement.boostTicksLeft
            else -> TRIM_TIMER to trim.ticksUntilGust
        }
        player.vars.setVarBit(Varbit.sailing_trim_state, state)
        player.vars.setVarBit(Varbit.sailing_trim_seconds, (ticks * TICK_SECONDS_NUM + TICK_SECONDS_DEN - 1) / TICK_SECONDS_DEN)
    }

    private fun clearVars(player: Player) {
        listOf(
            Varbit.sailing_hp, Varbit.sailing_max_hp, Varbit.sailing_sails_set, Varbit.sailing_anchored, Varbit.sailing_at_helm,
            Varbit.sailing_speed_setting, Varbit.sailing_speed_tenths, Varbit.sailing_heading, Varbit.sailing_target_heading, Varbit.sailing_trim_state,
            Varbit.sailing_trim_seconds, Varbit.sailing_panel_tab
        ).forEach { player.vars.setVarBit(it, 0) }
    }

    fun navigate(player: Player, voyage: Voyage, button: Int) {
        if (!voyage.atHelm) return VoyageControls.takeHelm(player, voyage)
        when {
            button == NAV_SAILS -> VoyageControls.toggleSails(player, voyage)
            button == NAV_SLOWER -> VoyageControls.changeSpeed(player, voyage, -1)
            button == NAV_FASTER -> VoyageControls.changeSpeed(player, voyage, 1)
            button == NAV_ANCHOR -> VoyageControls.toggleAnchor(player, voyage)
            button == NAV_TRIM -> VoyageControls.trim(player, voyage)
            button == NAV_REPAIR -> VoyageControls.useRepairKits(player, voyage)
            button >= NAV_FIRST_HEADING -> VoyageControls.setHeading(player, voyage, (button - NAV_FIRST_HEADING) * HEADING_STEP)
        }
    }
}

@ServerStartupEvent
fun mapSailingPanel() {
    if (!EnvVars.generateMaps) return
    onButtonClick(Interface.sailing_panel) { e ->
        when (e.component) {
            Component.sailing_panel.swords_button -> SailingPanel.showCombat(e.player)
            Component.sailing_panel.tabs -> e.player.vars.setVarBit(Varbit.sailing_panel_tab, e.slotId / TAB_SLOT_WIDTH)
            Component.sailing_panel.nav -> Voyages.of(e.player)?.let { SailingPanel.navigate(e.player, it, e.slotId / NAV_SLOT_WIDTH) }
        }
    }
}
