package com.rs.game.content.world.generated

import com.rs.game.content.skills.farming.FarmPatch
import com.rs.game.content.skills.farming.PatchLocation
import com.rs.game.model.entity.player.Player
import org.darkan.core.EnvVars
import org.darkan.core.city.City
import org.darkan.core.city.FarmTemplate
import org.darkan.core.map.GeneratedWorld

object CityFarms {
    private const val SHOWN_KEY = "cityFarmShown"
    private const val NO_CITY = ""

    private val cities: List<City> by lazy { GeneratedWorld.cities.filter { FarmTemplate.of(it) != null } }
    private val locations: List<PatchLocation> by lazy { FarmTemplate.patches.mapNotNull(PatchLocation::forObject) }

    @JvmStatic
    fun cityOf(player: Player): String? {
        if (!EnvVars.generateMaps) return null
        return cities.firstOrNull { it.covers(player.x, player.y, FarmTemplate.VIEW_MARGIN) }?.id
    }

    @JvmStatic
    fun refresh(player: Player) {
        val city = cityOf(player) ?: NO_CITY
        if (player.nsv.getO<String>(SHOWN_KEY) == city) return
        player.nsv.setO<String>(SHOWN_KEY, city)
        for (location in locations) (player.getPatch(location) ?: FarmPatch(location)).updateVars(player)
    }
}
