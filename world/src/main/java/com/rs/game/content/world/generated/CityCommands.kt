package com.rs.game.content.world.generated

import com.rs.Settings
import com.rs.engine.command.Commands
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import com.rs.game.tasks.WorldTasks
import org.darkan.core.Logger.logInfo
import org.darkan.core.city.BuildingBuilder
import org.darkan.core.city.City
import org.darkan.core.city.CityPlanner
import org.darkan.core.city.CityJson
import org.darkan.core.city.Side
import world.gregs.voidps.type.Tile
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.Rights

private const val SHOT_OFF = "off"
private const val SHOT_TILT = "tilt"
private const val SHOT_BASE_HEIGHT = 1200
private const val SHOT_HEIGHT_PER_TILE = 160
private const val SHOT_TILT_DISTANCE = 1.2
private const val SHOT_TILT_MARGIN = 3
private const val SHOT_LOOK_HEIGHT = 0
private const val SHOT_SETTLE_TICKS = 3

@ServerStartupEvent
fun loadCityCommands() {
    if (!EnvVars.generateMaps) return
    Commands.add(Rights.PLAYER, "city [name]", "Lists the cities, or teleports to one by name. Debug worlds only.") { player, args ->
        if (!Settings.getConfig().isDebug) return@add
        val cities = GeneratedWorld.cities
        val city = args.firstOrNull()?.let { name -> cities.firstOrNull { it.id.equals(name, ignoreCase = true) } }
        if (city == null) {
            player.sendMessage("Cities: " + cities.joinToString { "${it.name} (${it.centerX}, ${it.centerY})" })
            return@add
        }
        player.tele(city.centerX, city.centerY + 2, 0)
    }
    Commands.add(Rights.PLAYER, "cityshot [city] [building|all|off] [top|tilt] [level] [height] [distance]", "Moves to a city or one of its buildings and fixes the camera above it for a screenshot. Debug worlds only.") { player, args ->
        if (!Settings.getConfig().isDebug) return@add
        val city = args.firstOrNull()?.let { name -> GeneratedWorld.cities.firstOrNull { it.id.equals(name, ignoreCase = true) } }
        if (city == null || args.getOrNull(1) == SHOT_OFF) {
            player.packets.sendResetCamera()
            return@add
        }
        val building = args.getOrNull(1)?.toIntOrNull()?.let { city.buildings.getOrNull(it) }
        val level = (args.getOrNull(3)?.toIntOrNull() ?: 0).coerceIn(0, (building?.levels ?: 1) - 1)
        val span = building?.let { maxOf(it.width, it.depth) } ?: (city.halfSize * 2 + 1)
        val centreX = building?.let { it.x + it.width / 2 } ?: city.centerX
        val centreY = building?.let { it.y + it.depth / 2 } ?: city.centerY
        val tilted = args.getOrNull(2) == SHOT_TILT
        val height = args.getOrNull(4)?.toIntOrNull() ?: (SHOT_BASE_HEIGHT + span * SHOT_HEIGHT_PER_TILE)
        val distance = args.getOrNull(5)?.toIntOrNull() ?: if (tilted) (span * SHOT_TILT_DISTANCE).toInt() + SHOT_TILT_MARGIN else 0
        val stand = building?.let { BuildingBuilder.doorTile(it.x, it.y, it.width, it.depth, Side.valueOf(it.door)) }
            ?.let { (x, y) -> Side.valueOf(building.door).let { side -> Tile.of(x - side.dx, y - side.dy, level) } } ?: Tile.of(city.centerX, city.centerY, 0)
        player.tele(stand)
        WorldTasks.schedule(SHOT_SETTLE_TICKS) {
            player.packets.sendCameraPos(Tile.of(centreX, centreY - distance, level), height)
            player.packets.sendCameraLook(Tile.of(centreX, centreY + if (tilted) 0 else 1, level), SHOT_LOOK_HEIGHT)
        }
        player.sendMessage("Shot of ${city.name} ${building?.let { "building ${city.buildings.indexOf(it)} ${it.kind} at ${it.x},${it.y} ${it.width}x${it.depth} floor $level of ${it.levels}" } ?: "whole city"}, height $height, distance $distance.")
    }
    Commands.add(Rights.DEVELOPER, "cityverify", "Encodes every city to JSON and back and reports whether each one is unchanged.") { player, _ ->
        player.sendMessage(GeneratedWorld.cities.joinToString { "${it.name}: ${if (CityJson.decode(CityJson.encode(it)) == it) "round trip ok" else "MISMATCH"}, ${if (matchesSeed(it)) "matches seed ${it.seed}" else "edited by hand"}" })
    }
    if (Settings.getConfig().isDebug) GeneratedWorld.logInfo("Cities against their seeds: " + GeneratedWorld.cities.joinToString { "${it.name} ${if (matchesSeed(it)) "matches seed ${it.seed}" else "edited by hand"}" })
}

private fun matchesSeed(city: City): Boolean =
    CityPlanner.make(GeneratedWorld.plainGenerator(), city.copy(baseHeight = 0, streets = emptyList(), buildings = emptyList(), residents = emptyList())) == city
