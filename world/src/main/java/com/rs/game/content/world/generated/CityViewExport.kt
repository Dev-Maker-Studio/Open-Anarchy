package com.rs.game.content.world.generated

import com.google.gson.Gson
import com.rs.Settings
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.city.City
import org.darkan.core.city.DugRoads
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.ObjectShape
import java.io.File

private const val DIRECTORY = "./data/city-views"
private const val REGION_SIZE = 64
private const val SURROUNDINGS = 48
private const val GROUND = 0

private class ViewLoc(val id: Int, val shape: Int, val rotation: Int, val x: Int, val y: Int, val plane: Int)

private class ViewState(val city: String, val locs: List<ViewLoc>)

@ServerStartupEvent(ServerStartupEvent.Priority.POST_PROCESS)
fun exportCityViews() {
    if (!EnvVars.generateMaps || !Settings.getConfig().isDebug) return
    val directory = File(DIRECTORY).also { it.mkdirs() }
    val gson = Gson()
    for (city in GeneratedWorld.cities) File(directory, "${city.id}.state.json").writeText(gson.toJson(ViewState(city.id, locsAround(city))))
}

private fun locsAround(city: City): List<ViewLoc> {
    val reach = city.halfSize + SURROUNDINGS
    val minX = city.centerX - reach
    val minY = city.centerY - reach
    val maxX = city.centerX + reach
    val maxY = city.centerY + reach
    val result = city.pieces().mapTo(ArrayList()) { ViewLoc(it.loc, it.shape, it.rotation, it.x, it.y, it.level) }
    val generator = GeneratedWorld.generator
    for (regionX in minX / REGION_SIZE..maxX / REGION_SIZE) for (regionY in minY / REGION_SIZE..maxY / REGION_SIZE) {
        if (!generator.inArea(regionX, regionY)) continue
        for (scenery in generator.scenery(regionX, regionY)) {
            val x = regionX * REGION_SIZE + scenery.x
            val y = regionY * REGION_SIZE + scenery.y
            if (x in minX..maxX && y in minY..maxY) result += ViewLoc(scenery.id, scenery.shape, scenery.rotation, x, y, scenery.level)
        }
    }
    DugRoads.within(minX, minY, maxX, maxY).mapTo(result) { (x, y) -> ViewLoc(Loc.dug_road, ObjectShape.GROUND_DECORATION.id, 0, x, y, GROUND) }
    return result
}
