package com.rs.game.content.world.generated

import com.rs.Settings
import com.rs.engine.command.Commands
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.city.CityJson
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.Rights

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
    Commands.add(Rights.DEVELOPER, "cityverify", "Encodes every city to JSON and back and reports whether each one is unchanged.") { player, _ ->
        player.sendMessage(GeneratedWorld.cities.joinToString { "${it.name}: ${if (CityJson.decode(CityJson.encode(it)) == it) "round trip ok" else "MISMATCH"}" })
    }
}
