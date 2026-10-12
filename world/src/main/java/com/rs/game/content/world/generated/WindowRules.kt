package com.rs.game.content.world.generated

import com.rs.Launcher
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.darkan.core.EnvVars
import org.darkan.core.Logger
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.map.WorldWindow
import org.darkan.core.mongo.MongoDB
import org.darkan.core.social.gateway.WindowDemand
import org.darkan.world.server.WorldServer
import world.gregs.voidps.type.Tile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val HANDOVER_CHECK_TICKS = 5
private const val MINUTE_TICKS = 100
private const val PREPARE_DISTANCE = 4096
private const val POSITIONS = "window_positions"
private const val ID = "_id"
private const val X = "x"
private const val Y = "y"
private const val LEVEL = "level"
private const val ANNOUNCED = "windowHandoverAnnounced"

private val positions by lazy { MongoDB.database.getCollection<Document>(POSITIONS) }

@ServerStartupEvent
fun startWindowRules() {
    if (!EnvVars.windowed) return
    onLogin { (player) -> restorePosition(player) }
    WorldTasks.scheduleTimer(HANDOVER_CHECK_TICKS, HANDOVER_CHECK_TICKS) {
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished()) hand(player)
        }
        true
    }
    WorldTasks.scheduleTimer(MINUTE_TICKS, MINUTE_TICKS) {
        val occupied = World.players.any { it.hasStarted() && !it.hasFinished() }
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished()) demandNeighbours(player)
        }
        exitWhenIdle(occupied)
        true
    }
}

private var idleMinutes = 0

private fun exitWhenIdle(occupied: Boolean) {
    if (EnvVars.windowIdleExitMinutes <= 0) return
    idleMinutes = if (occupied) 0 else idleMinutes + 1
    if (idleMinutes >= EnvVars.windowIdleExitMinutes) {
        Logger.info(Launcher::class.java, "exitWhenIdle", "Sector ${GeneratedWorld.sectorName} (window ${GeneratedWorld.windowX},${GeneratedWorld.windowY}) has been empty for $idleMinutes minutes")
        Launcher.shutdown()
    }
}

private fun demandNeighbours(player: Player) {
    val globalX = WorldWindow.toGlobal(player.x, GeneratedWorld.windowX)
    val globalY = WorldWindow.toGlobal(player.y, GeneratedWorld.windowY)
    val xs = setOf(WorldWindow.owner(clampTile(globalX - PREPARE_DISTANCE)), WorldWindow.owner(clampTile(globalX + PREPARE_DISTANCE)))
    val ys = setOf(WorldWindow.owner(clampTile(globalY - PREPARE_DISTANCE)), WorldWindow.owner(clampTile(globalY + PREPARE_DISTANCE)))
    for (x in xs) for (y in ys) {
        if (x == GeneratedWorld.windowX && y == GeneratedWorld.windowY) continue
        if (!WorldWindow.isGenerated(x, y)) continue
        WorldServer.socialClient.send(WindowDemand(x, y))
        launchWindow(x, y)
    }
}

private val launched = ConcurrentHashMap.newKeySet<Int>()

private fun launchWindow(x: Int, y: Int) {
    if (!WorldWindow.isGenerated(x, y)) return
    if (EnvVars.windowLaunchScript.isBlank() || !launched.add(WorldWindow.worldId(x, y))) return
    thread(name = "window-launch-$x-$y", isDaemon = true) {
        runCatching {
            val log = File("logs/window-launch-${x}_$y.log")
            log.parentFile?.mkdirs()
            ProcessBuilder("bash", EnvVars.windowLaunchScript, "ensure", x.toString(), y.toString(), EnvVars.socialGatewayUrl)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
                .waitFor()
        }.onFailure { Logger.handle(Player::class.java, "launchWindow", it) }
    }
}

private fun clampTile(tile: Int) = tile.coerceIn(0, WorldWindow.GRID_TILES - 1)

fun saveWindowPosition(player: Player) {
    if (!EnvVars.windowed) return
    val document = Document(ID, player.username)
        .append(X, WorldWindow.toGlobal(player.x, GeneratedWorld.windowX))
        .append(Y, WorldWindow.toGlobal(player.y, GeneratedWorld.windowY))
        .append(LEVEL, player.plane)
    runCatching { runBlocking { positions.replaceOne(Filters.eq(ID, player.username), document, ReplaceOptions().upsert(true)) } }
        .onFailure { Logger.handle(Player::class.java, "saveWindowPosition", it) }
}

private fun restorePosition(player: Player) {
    val saved = runCatching { runBlocking { positions.find(Filters.eq(ID, player.username)).firstOrNull() } }.getOrNull() ?: return
    val globalX = saved.getInteger(X)
    val globalY = saved.getInteger(Y)
    if (!WorldWindow.contains(GeneratedWorld.windowX, globalX) || !WorldWindow.contains(GeneratedWorld.windowY, globalY)) return
    val local = Tile.of(WorldWindow.toLocal(globalX, GeneratedWorld.windowX), WorldWindow.toLocal(globalY, GeneratedWorld.windowY), saved.getInteger(LEVEL))
    Logger.info(Player::class.java, "restorePosition", "${player.username} restored from global $globalX,$globalY to window ${GeneratedWorld.windowX},${GeneratedWorld.windowY} tile ${local.x},${local.y}")
    player.tele(local)
}

private fun hand(player: Player) {
    val globalX = WorldWindow.toGlobal(player.x, GeneratedWorld.windowX)
    val globalY = WorldWindow.toGlobal(player.y, GeneratedWorld.windowY)
    val targetX = WorldWindow.owner(globalX)
    val targetY = WorldWindow.owner(globalY)
    if (targetX == GeneratedWorld.windowX && targetY == GeneratedWorld.windowY) return
    if (!WorldWindow.isGenerated(targetX, targetY)) return
    if (!player.tempAttribs.getB(ANNOUNCED)) {
        player.tempAttribs.setB(ANNOUNCED, true)
        player.sendMessage("You have reached the edge of this region. Select world ${WorldWindow.worldId(targetX, targetY)} to continue.")
    }
    player.logout(true)
}
