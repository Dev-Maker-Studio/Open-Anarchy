package com.rs.game.content.world.generated

import com.rs.engine.command.Commands
import com.rs.game.World
import com.rs.game.content.skills.magic.LodestoneAction
import com.rs.game.model.entity.player.Player
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onButtonClick
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Cs2
import org.darkan.core.gameval.gen.Interface
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Spotanim
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import org.darkan.core.model.IFEvents
import world.gregs.voidps.cache.source.codec.WorldGenerator
import org.darkan.core.model.Rights
import world.gregs.voidps.type.Tile

object WildernessLodestones {
    private const val SAVE_KEY = "wildernessLodestones"
    private const val ENTRY_SEPARATOR = ";"
    private const val COORD_SEPARATOR = ","
    private const val ACTIVATE_OP = 1
    private const val LIST_OP = 2

    private val originX get() = WorldWindow.origin(GeneratedWorld.windowX) - WorldGenerator.ORIGIN_X
    private val originY get() = WorldWindow.origin(GeneratedWorld.windowY) - WorldGenerator.ORIGIN_Y

    private val spawnEntry get() = (StartLodestone.tile.x + originX) to (StartLodestone.tile.y + originY)

    fun unlocked(player: Player): List<Pair<Int, Int>> {
        val saved = player.getO<String>(SAVE_KEY).orEmpty().split(ENTRY_SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(COORD_SEPARATOR)
            val x = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val y = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            x to y
        }
        return listOf(spawnEntry) + saved.filter { it != spawnEntry }
    }

    private fun save(player: Player, entries: List<Pair<Int, Int>>) {
        player.set(SAVE_KEY, entries.joinToString(ENTRY_SEPARATOR) { "${it.first}$COORD_SEPARATOR${it.second}" })
    }

    fun activate(player: Player, tile: Tile) {
        val entry = (tile.x + originX) to (tile.y + originY)
        val entries = unlocked(player)
        if (entry in entries) {
            player.sendMessage("You have already activated this lodestone.")
            return
        }
        save(player, entries + entry)
        World.sendSpotAnim(tile, Spotanim.thas_gatestone_activate_spotanim)
        player.sendMessage("You activate the lodestone. Its location is now in your list of lodestones.")
    }

    fun open(player: Player) {
        val entries = unlocked(player)
        if (entries.isEmpty()) {
            player.sendMessage("You have not activated any lodestones yet.")
            return
        }
        if (player.interfaceManager.containsScreenInter()) {
            player.sendMessage("Please close the interface you have open before viewing your lodestones.")
            return
        }
        player.stopAll()
        player.interfaceManager.sendInterface(Interface.wilderness_lodestones)
        player.packets.setIFEvents(IFEvents(Component.wilderness_lodestones.list, 0, entries.size - 1).enableRightClickOption(0))
        entries.forEach { (x, y) -> player.packets.sendRunScript(Cs2.wilderness_lodestones_add, x, y) }
    }

    fun teleport(player: Player, row: Int) {
        val entry = unlocked(player).getOrNull(row) ?: return
        val local = (entry.first - originX) to (entry.second - originY)
        if (local !in GeneratedWorld.lodestoneSites && local != (StartLodestone.tile.x to StartLodestone.tile.y)) {
            player.sendMessage("That lodestone is in another sector.")
            return
        }
        player.interfaceManager.removeCentralInterface()
        player.actionManager.setAction(LodestoneAction(Tile.of(local.first, local.second, 0)))
    }

    @ServerStartupEvent
    @JvmStatic
    fun init() {
        if (EnvVars.generateMaps)
            Thread { GeneratedWorld.lodestoneSites }.apply { isDaemon = true }.start()
        onObjectClick(Loc.wilderness_lodestone) { e ->
            when (e.opNum) {
                ACTIVATE_OP -> activate(e.player, e.obj.tile)
                LIST_OP -> open(e.player)
            }
        }
        onButtonClick(Interface.wilderness_lodestones) { e ->
            when (e.component) {
                Component.wilderness_lodestones.close_button -> e.player.interfaceManager.removeCentralInterface()
                Component.wilderness_lodestones.list -> teleport(e.player, e.slotId)
            }
        }
        Commands.add(Rights.PLAYER, "lodestones", "Lists the wilderness lodestones you have activated.") { p, _ -> open(p) }
    }
}
