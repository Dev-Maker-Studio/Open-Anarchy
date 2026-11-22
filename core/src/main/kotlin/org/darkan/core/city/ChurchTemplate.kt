package org.darkan.core.city

import com.google.gson.Gson
import org.darkan.core.gameval.gen.Npc

object ChurchTemplate {
    const val KIND = "church"
    const val PRIEST = "priest"
    const val GREETER = "greeter"
    const val CONGREGANT = "congregant"
    const val LEVELS = 4
    const val ENTRANCE_X = 1
    const val ENTRANCE_Y = 6
    const val ENTRANCE_ROWS = 2
    const val SERVICE_X = 8
    const val SERVICE_Y = 4
    private const val RESOURCE = "/city/lumbridge_church.json"
    private const val VOID = "-"

    private class Stamp(val loc: String, val x: Int, val y: Int, val level: Int, val shape: Int, val rotation: Int)

    private class Template(val width: Int, val depth: Int, val locs: List<Stamp>, val tiles: List<List<String>>)

    private val template = ChurchTemplate::class.java.getResourceAsStream(RESOURCE)!!.reader().use { Gson().fromJson(it, Template::class.java) }
    private val tiles = template.tiles.map { level -> level.map { it.split(' ') } }

    val width get() = template.width
    val depth get() = template.depth

    fun building(x: Int, y: Int) =
        Building(KIND, x, y, width, depth, Side.WEST.name, Building.MAX_LEVELS, WallStyle.STONE.id, FloorStyle.FLAGSTONE.id, emptyList(), RoofStyle.NONE)

    fun pieces(building: Building): List<Piece> = template.locs.mapNotNull { stamp ->
        BuildingBuilder.locId(stamp.loc)?.let { Piece(it, stamp.shape, stamp.rotation, building.x + stamp.x, building.y + stamp.y, stamp.level) }
    }

    fun tile(level: Int, x: Int, y: Int): String? = tiles[level][x][y].takeIf { it != VOID }

    fun residents(x: Int, y: Int): List<Resident> = listOf(
        Resident(Npc.father_aereck, x + 7, y + 2, PRIEST),
        Resident(Npc.agrith_reen, x, y + 5, GREETER),
        Resident(Npc.hans, x + 10, y + 4, CONGREGANT),
        Resident(Npc.johnathon_fitzharmon, x + 4, y + 6, CONGREGANT),
        Resident(Npc.atlum_explorer_jack, x + 8, y + 8, CONGREGANT),
        Resident(Npc.lum_fish_shopkeeper, x + 5, y + 8, CONGREGANT),
        Resident(Npc.fred_the_farmer, x + 8, y + 6, CONGREGANT),
        Resident(Npc.build_imp_draynor_man, x + 4, y + 4, CONGREGANT),
    )
}
