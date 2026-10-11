package com.rs.game.content.skills.sailing

import com.rs.game.model.entity.player.Player
import org.darkan.core.Constants
import org.darkan.core.gameval.gen.Interface

private val SPEED_LADDER = listOf(SpeedSetting.REVERSE, SpeedSetting.STOP, SpeedSetting.SLOW, SpeedSetting.FULL)

object VoyageControls {
    fun takeHelm(player: Player, voyage: Voyage) {
        voyage.atHelm = true
        if (voyage.panelDismissed || !player.interfaceManager.topOpen(Interface.sailing_panel)) SailingPanel.open(player, voyage)
        player.sendMessage("You take the helm of the ${voyage.boat.name}.")
    }

    fun leaveHelm(player: Player, voyage: Voyage) {
        voyage.atHelm = false
        player.sendMessage("You step away from the helm.")
    }

    fun toggleHelm(player: Player, voyage: Voyage) {
        if (voyage.atHelm) leaveHelm(player, voyage) else takeHelm(player, voyage)
    }

    fun toggleSails(player: Player, voyage: Voyage) {
        voyage.movement.sailsSet = !voyage.movement.sailsSet
        if (voyage.movement.sailsSet && voyage.movement.setting == SpeedSetting.STOP) voyage.movement.setting = SpeedSetting.FULL
        player.sendMessage(if (voyage.movement.sailsSet) "You set the sails." else "You unset the sails.")
    }

    fun setHeading(player: Player, voyage: Voyage, degrees: Int) {
        voyage.movement.targetHeading = degrees
        player.sendMessage("You steer ${Compass.nameOf(degrees)} ($degrees).")
    }

    fun setSpeed(player: Player, voyage: Voyage, setting: SpeedSetting) {
        voyage.movement.setting = setting
        voyage.movement.sailsSet = setting != SpeedSetting.STOP
        player.sendMessage("Speed set to ${setting.name.lowercase()}.")
    }

    fun changeSpeed(player: Player, voyage: Voyage, step: Int) {
        val index = SPEED_LADDER.indexOf(voyage.movement.setting).coerceAtLeast(0)
        setSpeed(player, voyage, SPEED_LADDER[(index + step).coerceIn(0, SPEED_LADDER.lastIndex)])
    }

    fun toggleAnchor(player: Player, voyage: Voyage) {
        if (Facility.ANCHOR !in voyage.boat.facilities) return player.sendMessage("Your boat has no anchor. A Shipwright can fit one.")
        voyage.movement.anchored = !voyage.movement.anchored
        if (voyage.movement.anchored) voyage.movement.dropSpeed()
        player.sendMessage(if (voyage.movement.anchored) "You drop the anchor." else "You raise the anchor.")
    }

    fun trim(player: Player, voyage: Voyage) {
        val xp = voyage.trim.trim(player.skills.getLevel(Constants.SAILING), true)
        if (xp <= 0.0) return player.sendMessage(if (voyage.boat.hold.crates.isNotEmpty()) "You cannot trim the sails while carrying cargo." else "There is no gust to trim.")
        SailingXp.grant(player, voyage.boat, xp)
        player.sendMessage("You trim the sails, catching the wind for a burst of speed!")
    }

    fun releaseMote(player: Player, voyage: Voyage) {
        val xp = voyage.trim.releaseMote()
        if (xp <= 0.0) return player.sendMessage("Your wind catcher holds no motes.")
        SailingXp.grant(player, voyage.boat, xp)
        player.sendMessage("You release a mote of wind for a burst of speed.")
    }

    fun toggleCannon(player: Player, voyage: Voyage) {
        val cannon = voyage.boat.cannon ?: return player.sendMessage("Your boat has no cannon. A Shipwright can fit one.")
        if (player.skills.getLevel(Constants.RANGE) < cannon.rangedLevel) return player.sendMessage("You need Ranged level ${cannon.rangedLevel} to fire a ${cannon.label} cannon.")
        voyage.cannonAuto = !voyage.cannonAuto
        player.sendMessage(if (voyage.cannonAuto) "You man the ${cannon.label} cannon; it fires at monsters in the front arc." else "You stand down from the cannon.")
    }

    fun useRepairKits(player: Player, voyage: Voyage) {
        val healed = RepairKits.repairFromHold(voyage.boat)
        if (healed <= 0) return player.sendMessage(if (voyage.boat.hitpoints >= voyage.boat.maxHitpoints) "Your boat needs no repairs." else "There are no repair kits in your cargo hold.")
        BoatStore.save(voyage.boat)
        player.sendMessage("Repair kits from the hold mend $healed hit points (${voyage.boat.hitpoints}/${voyage.boat.maxHitpoints}).")
    }
}
