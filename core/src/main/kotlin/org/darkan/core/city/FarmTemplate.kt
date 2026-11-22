package org.darkan.core.city

import com.google.gson.Gson
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc

object FarmTemplate {
    const val KIND = "farm"
    const val LEPRECHAUN = "leprechaun"
    const val SOIL_TILE = "o89/0/0"
    const val VIEW_MARGIN = 16
    private const val RESOURCE = "/city/catherby_farm.json"
    private const val LEPRECHAUN_X = 2
    private const val LEPRECHAUN_Y = 6
    private const val CITIES_PER_FARM = 2L
    private const val GROUND = 0

    val patches = listOf(Loc.loc_8552, Loc.loc_8553, Loc.loc_7848, Loc.loc_8151, Loc.loc_7837)

    private class Stamp(val loc: String, val x: Int, val y: Int, val shape: Int, val rotation: Int)

    private class Template(val width: Int, val depth: Int, val locs: List<Stamp>, val soil: List<List<Int>>)

    private val template = FarmTemplate::class.java.getResourceAsStream(RESOURCE)!!.reader().use { Gson().fromJson(it, Template::class.java) }
    private val soil = template.soil.mapTo(HashSet()) { (x, y) -> x to y }

    val width get() = template.width
    val depth get() = template.depth

    fun inCity(seed: Long): Boolean = Math.floorMod(seed, CITIES_PER_FARM) == 0L

    fun of(city: City): Building? = city.buildings.firstOrNull { it.kind == KIND }

    fun building(x: Int, y: Int) =
        Building(KIND, x, y, width, depth, Side.WEST.name, 1, WallStyle.FARMHOUSE.id, FloorStyle.PLANKS.id, emptyList(), RoofStyle.NONE)

    fun pieces(building: Building): List<Piece> = template.locs.mapNotNull { stamp ->
        BuildingBuilder.locId(stamp.loc)?.let { Piece(it, stamp.shape, stamp.rotation, building.x + stamp.x, building.y + stamp.y, GROUND) }
    }

    fun tile(x: Int, y: Int): String? = SOIL_TILE.takeIf { x to y in soil }

    fun residents(x: Int, y: Int): List<Resident> = listOf(Resident(Npc.farming_tools_leprechaun, x + LEPRECHAUN_X, y + LEPRECHAUN_Y, LEPRECHAUN))
}
