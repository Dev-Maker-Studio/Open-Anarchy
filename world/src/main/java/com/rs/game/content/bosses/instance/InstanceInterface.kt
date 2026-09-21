package com.rs.game.content.bosses.instance

import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Varc
import org.darkan.core.gameval.gen.Varcstr
import org.darkan.core.model.IFEvents

private class SettingRow(
    val label: String,
    val value: (InstanceSettings, BossInstanceDef) -> String,
    val step: (InstanceSettings, BossInstanceDef, Int) -> InstanceSettings,
)

private val ROWS = listOf(
    SettingRow("Max players:", { s, _ -> s.maxPlayers.toString() }) { s, def, d ->
        s.copy(maxPlayers = (s.maxPlayers + d).coerceIn(1, def.maxPlayers))
    },
    SettingRow("Min combat:", { s, _ -> s.minCombat.toString() }) { s, _, d ->
        s.copy(minCombat = (s.minCombat + d * 5).coerceIn(3, 138))
    },
    SettingRow("Spawn speed:", { s, _ -> s.spawnSpeed.label }) { s, def, d ->
        val speeds = def.spawnSpeeds
        s.copy(spawnSpeed = speeds[(speeds.indexOf(s.spawnSpeed) + d).coerceIn(0, speeds.lastIndex)])
    },
    SettingRow("Protection:", { s, _ -> s.protection.label }) { s, _, d ->
        val all = InstanceProtection.entries
        s.copy(protection = all[(all.indexOf(s.protection) + d).coerceIn(0, all.lastIndex)])
    },
)

private const val PENDING = "instancePending"
private const val PENDING_BOSS = "instancePendingBoss"
private const val REVISION = "instanceRevision"

object InstanceInterface {
    fun open(player: Player, def: BossInstanceDef) {
        val settings = InstanceSettings(
            maxPlayers = 1,
            minCombat = def.minCombatDefault,
            spawnSpeed = def.spawnSpeeds.first(),
            protection = InstanceProtection.FFA,
        )
        player.tempAttribs.setO<InstanceSettings>(PENDING, settings)
        player.tempAttribs.setO<BossInstanceDef>(PENDING_BOSS, def)
        player.interfaceManager.sendInterface(Interface.instance_system)
        player.packets.setIFText(Component.instance_system.boss_name, def.displayName)
        player.packets.setIFText(Component.instance_system.requirements_value, def.entranceRequirements)
        player.packets.setIFNPCHead(Interface.instance_system, Utils.componentIdFromHash(Component.instance_system.portrait), def.bossNpc)
        player.packets.setIFEvents(IFEvents(Component.instance_system.rows, 0, ROWS.size * 2).enableRightClickOptions(*IntArray(ROWS.size * 2) { it }))
        player.packets.setIFEvents(IFEvents(Component.instance_system.buttons, 0, 3).enableRightClickOptions(0, 1, 2))
        refresh(player)
    }

    private fun refresh(player: Player) {
        val settings = pending(player) ?: return
        val def = pendingBoss(player) ?: return
        player.packets.sendVarc(Varc.instance_row_count, ROWS.size)
        player.packets.sendVarc(Varc.instance_practice_mode, if (settings.practiceMode) 1 else 0)
        ROWS.forEachIndexed { row, spec ->
            player.packets.sendVarcString(Varcstr.instance_row_label_0 + row, spec.label)
            player.packets.sendVarcString(Varcstr.instance_row_value_0 + row, spec.value(settings, def))
        }
        val revision = player.tempAttribs.getI(REVISION) + 1
        player.tempAttribs.setI(REVISION, revision)
        player.packets.sendVarc(Varc.instance_revision, revision)
    }

    private fun pending(player: Player): InstanceSettings? = player.tempAttribs.getO<InstanceSettings>(PENDING)

    private fun pendingBoss(player: Player): BossInstanceDef? = player.tempAttribs.getO<BossInstanceDef>(PENDING_BOSS)

    fun step(player: Player, opNum: Int) {
        val settings = pending(player) ?: return
        val def = pendingBoss(player) ?: return
        val row = (opNum - 1) / 2
        val delta = if (opNum % 2 == 1) -1 else 1
        val spec = ROWS.getOrNull(row) ?: return
        player.tempAttribs.setO<InstanceSettings>(PENDING, spec.step(settings, def, delta))
        refresh(player)
    }

    fun togglePractice(player: Player) {
        val settings = pending(player) ?: return
        player.tempAttribs.setO<InstanceSettings>(PENDING, settings.copy(practiceMode = !settings.practiceMode))
        refresh(player)
    }
}

@ServerStartupEvent
fun mapInstanceInterface() {
    onButtonClick(Interface.instance_system) { e ->
        when (e.component) {
            Component.instance_system.rows -> InstanceInterface.step(e.player, e.opNum)
            Component.instance_system.practice_checkbox -> InstanceInterface.togglePractice(e.player)
            Component.instance_system.close_button -> e.player.closeInterfaces()
            Component.instance_system.buttons -> handleAction(e.player, e.opNum)
        }
    }
}

private fun handleAction(player: Player, opNum: Int) {
    val settings = player.tempAttribs.getO<InstanceSettings>(PENDING) ?: return
    val def = player.tempAttribs.getO<BossInstanceDef>(PENDING_BOSS) ?: return
    player.closeInterfaces()
    when (opNum) {
        1 -> BossInstances.start(player, def, settings)
        2 -> BossInstances.join(player, player.username)?.let { player.sendMessage(it) }
        3 -> BossInstances.rejoin(player)?.let { player.sendMessage(it) }
    }
}
