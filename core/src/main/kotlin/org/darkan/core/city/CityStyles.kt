package org.darkan.core.city

import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Overlay

enum class WallStyle(val wall: Int, val window: Int, val upperWall: Int, val upperWindow: Int, val door: Int) {
    FARMHOUSE(Loc.ahoy_farmhouse_wall, Loc.ahoy_farmhouse_wall_window, Loc.ahoy_farmhouse_wall, Loc.ahoy_farmhouse_wall_window, Loc.poordoor),
    BRICK(Loc.brickwall, Loc.brickwall_window, Loc.paintedbrickwall_1, Loc.brickwall_window, Loc.fai_varrock_door),
    PLASTER(Loc.kr_seers_village_wall, Loc.kr_seers_village_wall_window, Loc.kr_seers_village_wall_lvl2, Loc.kr_seers_village_wall_window_lvl2, Loc.poordoor),
    STONE(Loc.area_ardougne_city_wall, Loc.area_ardougne_city_wall_small_window, Loc.area_ardougne_city_wall_secondlvl, Loc.area_ardougne_city_wall_secondlvll_window, Loc.area_ardougne_door1_closed),
    WHITESTONE(Loc.fai_falador_walls, Loc.fai_falador_wall_window, Loc.fai_falador_interior_lvl2, Loc.fai_falador_interior_window_lvl2, Loc.fai_falador_poor_castle_door),
    LIGHTWOOD(Loc.timberwall, Loc.timberwall_with_window_2, Loc.timberwall_lvl2, Loc.timberwall_with_window_2, Loc.poordoor),
    DARKWOOD(Loc.canafis_wall_var1, Loc.canafis_wall_window, Loc.canafis_wall_lvl1_var1, Loc.canafis_wall_lvl1_window, Loc.fai_varrock_poor_door),
    GREYPLASTER(Loc.fai_varrock_poor_wall, Loc.fai_varrock_poor_wall_window1, Loc.fai_varrock_poor_wall_lvl2, Loc.fai_varrock_poor_wall_window1, Loc.fai_varrock_poor_door),
    ;

    val id = name.lowercase()

    companion object {
        private val byId = entries.associateBy { it.id }

        fun of(id: String): WallStyle = byId[id.lowercase()] ?: FARMHOUSE
    }
}

enum class FloorStyle(val overlay: Int) {
    PLANKS(Overlay.overlay_190),
    DARKWOOD(Overlay.overlay_91),
    SANDSTONE(Overlay.overlay_137),
    COBBLE(Overlay.overlay_171),
    FLAGSTONE(Overlay.overlay_181),
    STONE(Overlay.overlay_75),
    SLATE(Overlay.overlay_11),
    MARBLE(Overlay.overlay_92),
    CREAM(Overlay.overlay_123),
    TERRACOTTA(Overlay.overlay_163),
    GOLDEN(Overlay.overlay_95),
    ;

    val id = name.lowercase()

    companion object {
        private val byId = entries.associateBy { it.id }

        fun of(id: String): FloorStyle = byId[id.lowercase()] ?: PLANKS

        fun overlayOf(id: String): Int =
            byId[id.lowercase()]?.overlay ?: runCatching { Overlay::class.java.getField(id).getInt(null) }.getOrDefault(PLANKS.overlay)
    }
}

enum class RoofStyle(val loc: Int) {
    SLATE(Loc.fai_varrock_roofkit_slate),
    BLUESLATE(Loc.fai_falador_roofkit_slate),
    GREYSLATE(Loc.kr_seers_village_roofkit_slate),
    DARKSLATE(Loc.canafis_roofkit_slate),
    REDSLATE(Loc.edge_roofkit_slate),
    THATCH(Loc.area_ardougne_roof_thatched),
    STRAW(Loc.fai_portsarim_roof_thatched),
    REED(Loc.fai_lumbridge_roof_thatched),
    HAY(Loc.fai_varrock_roof_thatched),
    WOOD(Loc.roof_wooden),
    ;

    val id = name.lowercase()

    companion object {
        const val NONE = "none"
        private val byId = entries.associateBy { it.id }

        fun of(id: String?): RoofStyle? = id?.let { byId[it.lowercase()] }
    }
}

enum class Service(val icons: List<Int>, val keeper: Boolean = true, val floor: FloorStyle = FloorStyle.PLANKS) {
    BANK(listOf(Loc.bank_store_icon), false),
    SMITHY(listOf(Loc.furnace_icon, Loc.anvil_icon), false),
    GENERAL_STORE(listOf(Loc.general_store_icon)),
    BARBER(listOf(Loc.hairdresser_icon), false, FloorStyle.CREAM),
    SWORD_SHOP(listOf(Loc.sword_store_icon), floor = FloorStyle.FLAGSTONE),
    ARCHERY_SHOP(listOf(Loc.archery_store_icon), floor = FloorStyle.DARKWOOD),
    MAGIC_SHOP(listOf(Loc.magic_store_icon), floor = FloorStyle.SLATE),
    CLOTHES_SHOP(listOf(Loc.clothing_store_icon), floor = FloorStyle.TERRACOTTA),
    FOOD_SHOP(listOf(Loc.food_store_icon), floor = FloorStyle.SANDSTONE),
    ARMOUR_SHOP(listOf(Loc.plate_store_icon), floor = FloorStyle.STONE),
    FISHING_SHOP(listOf(Loc.fishing_store_icon)),
    STAFF_SHOP(listOf(Loc.staff_store_icon), floor = FloorStyle.GOLDEN),
    REALTOR(listOf(Loc.poh_estateagent_icon), false, FloorStyle.DARKWOOD),
    CHURCH(listOf(Loc.altar_icon), false, FloorStyle.FLAGSTONE),
    ;

    val kind = name.lowercase()

    companion object {
        private val byKind = entries.associateBy { it.kind }
        val shops = entries.filter { it.keeper || it == BARBER }

        fun of(kind: String): Service? = byKind[kind.lowercase()]
    }
}
