package com.rs.game.content.world.generated

import com.rs.Settings
import com.rs.engine.command.Commands
import com.rs.game.World
import com.rs.game.map.Chunk
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onObjectClick
import org.darkan.core.EnvVars
import org.darkan.core.Logger.logInfo
import org.darkan.core.city.Side
import org.darkan.core.dungeon.Dungeon
import org.darkan.core.dungeon.DungeonText
import org.darkan.core.dungeon.DungeonTheme
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import org.darkan.core.model.Rights
import world.gregs.voidps.type.Tile
import java.io.File

private const val CHUNK_SHIFT = 3
private const val GROUND_LEVEL = 0
private const val VIEW_DIRECTORY = "./data/dungeon-views"
private const val BOSS_ARGUMENT = "boss"
private const val BOSS_STANDOFF = 5

object Dungeons {

    @JvmStatic
    fun spawn(chunk: Chunk) {
        if (!EnvVars.generateMaps || chunk.plane != GROUND_LEVEL) return
        for (dungeon in GeneratedWorld.dungeons) {
            val piece = dungeon.entrancePiece()
            if (piece.x shr CHUNK_SHIFT != chunk.x || piece.y shr CHUNK_SHIFT != chunk.y) continue
            val shape = ObjectShape.forId(piece.shape) ?: continue
            chunk.addGeneratedObject(GameObject(piece.loc, shape, piece.rotation, piece.x, piece.y, piece.level))
        }
    }

    fun entranceAt(x: Int, y: Int): Dungeon? = GeneratedWorld.dungeons.firstOrNull { it.entranceTouches(x, y) }

    fun arrival(dungeon: Dungeon): Tile = Tile.of(dungeon.baseX + dungeon.layout.arrivalX, dungeon.baseY + dungeon.layout.arrivalY, GROUND_LEVEL)

    fun surface(dungeon: Dungeon): Tile {
        val style = dungeon.style
        val ring = Side.entries.map { side ->
            Tile.of(
                dungeon.entranceX + if (side.dx > 0) style.entranceWidth else side.dx,
                dungeon.entranceY + if (side.dy > 0) style.entranceDepth else side.dy,
                GROUND_LEVEL
            )
        }
        return ring.firstOrNull { World.floorAndWallsFree(it, 1) } ?: ring.first()
    }

    private fun enter(player: Player, obj: GameObject) {
        val dungeon = entranceAt(obj.x, obj.y) ?: return
        player.useStairs(arrival(dungeon))
        player.sendMessage("You descend into ${dungeon.name}. The creatures grow stronger the deeper you go.")
    }

    private fun leave(player: Player, obj: GameObject) {
        val dungeon = GeneratedWorld.dungeonAt(obj.x, obj.y) ?: return
        player.useStairs(surface(dungeon))
    }

    private fun describe(dungeon: Dungeon): String {
        val boss = DungeonBosses.definitions[dungeon.bossId]
        val level = DungeonMobs.baseLevel(dungeon)
        return "${dungeon.id} (${dungeon.name}, ${dungeon.theme}, level $level, boss ${boss?.name ?: "missing"})"
    }

    @ServerStartupEvent
    @JvmStatic
    fun init() {
        if (!EnvVars.generateMaps) return
        DungeonTheme.entries.forEach { theme ->
            onObjectClick(theme.entrance) { e -> enter(e.player, e.obj) }
            onObjectClick(theme.exit) { e -> leave(e.player, e.obj) }
        }
        onObjectClick(Loc.rd_portal_room2_exit) { e -> leave(e.player, e.obj) }
        Commands.add(Rights.PLAYER, "dungeon [id] [boss]", "Lists the dungeons, or teleports to one or to its boss room. Debug worlds only.") { player, args ->
            if (!Settings.getConfig().isDebug) return@add
            val dungeon = args.firstOrNull()?.let { id -> GeneratedWorld.dungeons.firstOrNull { it.id.equals(id, ignoreCase = true) } }
            when {
                dungeon == null -> player.sendMessage("Dungeons: " + GeneratedWorld.dungeons.joinToString { describe(it) })
                args.getOrNull(1) == BOSS_ARGUMENT -> {
                    val boss = dungeon.layout.spawns.first { it.boss }
                    player.tele(dungeon.baseX + boss.x, dungeon.baseY + boss.y - BOSS_STANDOFF, GROUND_LEVEL)
                }
                else -> player.tele(surface(dungeon))
            }
        }
        export()
    }

    private fun export() {
        val dungeons = GeneratedWorld.dungeons
        val directory = File(VIEW_DIRECTORY).also { it.mkdirs() }
        for (dungeon in dungeons) {
            val mobs = DungeonMobs.slotsOf(dungeon).joinToString("\n") { slot ->
                val point = slot.point
                if (point.boss) {
                    val boss = DungeonBosses.definitions[dungeon.bossId]
                    "boss ${point.x},${point.y} ${boss?.name ?: "missing ${dungeon.bossId}"} level ${boss?.let { DungeonMobs.bossLevel(dungeon, it) } ?: 0}"
                } else {
                    val id = DungeonMobs.mobId(dungeon, point)
                    "mob ${point.x},${point.y} depth ${(point.progress * 100).toInt()}% npc $id native ${DungeonMobs.native(id)} level ${DungeonMobs.mobLevel(dungeon, point)}"
                }
            }
            File(directory, "${dungeon.id}.txt").writeText("${DungeonText.summary(dungeon)}\nbase level ${DungeonMobs.baseLevel(dungeon)}\n\n${DungeonText.render(dungeon)}\n\n$mobs\n")
        }
        DungeonBosses.definitions.values.forEach { boss -> DungeonBosses.problems(boss).forEach { logInfo("Boss ${boss.id}: $it") } }
        logInfo("Dungeons: " + dungeons.joinToString { describe(it) })
    }
}
