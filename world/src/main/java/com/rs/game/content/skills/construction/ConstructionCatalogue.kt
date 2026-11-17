package com.rs.game.content.skills.construction

import com.rs.game.content.skills.construction.playerOwnedHouse.HouseObjects
import com.rs.game.model.entity.player.Player
import com.rs.game.model.gameobject.GameObject
import org.darkan.core.cache.ItemDefinitions
import org.darkan.core.cache.ObjectDefinitions
import org.darkan.core.gameval.Gameval
import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.ObjectShape

enum class ConstructionCategory(val label: String) {
    FURNISHINGS("Furnishings"),
    FUNCTIONAL("Functional"),
    TELEPORTS("Teleports"),
    DECOR("Decor"),
    LIGHTING("Lighting"),
    TROPHIES("Trophies"),
    GARDEN("Garden"),
    MISC("Misc"),
}

enum class PieceKind { STANDARD, ROOF, FLOOR, LADDER }

enum class Material(val xpPerDamage: Double) {
    LIGHT_WOOD(0.5),
    TIMBER(0.75),
    HEAVY_TIMBER(1.0),
    ;

    companion object {
        private const val LIGHT_LIMIT = 60
        private const val TIMBER_LIMIT = 200

        fun forHitPoints(hitPoints: Int): Material = when {
            hitPoints < LIGHT_LIMIT -> LIGHT_WOOD
            hitPoints < TIMBER_LIMIT -> TIMBER
            else -> HEAVY_TIMBER
        }
    }
}

object ConstructionMaterials {
    val NAIL_TYPES = intArrayOf(Obj.nails, Obj.nails_bronze, Obj.nails_iron, Obj.nails_black, Obj.nails_mithril, Obj.obj_4823, Obj.nails_rune)

    val BARS = intArrayOf(Obj.bronze_bar, Obj.iron_bar, Obj.steel_bar, Obj.silver_bar, Obj.gold_bar, Obj.mithril_bar, Obj.adamantite_bar, Obj.runite_bar)

    val PLANKS = intArrayOf(Obj.woodplank, Obj.plank_oak, Obj.plank_teak, Obj.plank_mahogany)

    val CANDLES = intArrayOf(Obj.obj_36, Obj.unlit_black_candle)

    private fun alternatives(item: Int): IntArray = if (item == Obj.nails) NAIL_TYPES else intArrayOf(item)

    fun owned(player: Player, item: Int): Int = alternatives(item).sumOf { player.inventory.getNumberOf(it) }

    fun has(player: Player, item: Int, amount: Int): Boolean = owned(player, item) >= amount

    fun take(player: Player, item: Int, amount: Int) {
        var remaining = amount
        for (option in alternatives(item)) {
            val used = minOf(remaining, player.inventory.getNumberOf(option))
            if (used > 0) player.inventory.deleteItem(option, used)
            remaining -= used
            if (remaining <= 0) return
        }
    }
}

class ConstructionPiece(
    val item: Int,
    val loc: Int,
    val ghost: Int,
    val shape: ObjectShape,
    val rotation: Int,
    val xp: Double,
    val width: Int,
    val depth: Int,
    val category: ConstructionCategory,
    val cost: List<Pair<Int, Int>>,
    val openLoc: Int = -1,
    val onOpen: ((Player, GameObject) -> Unit)? = null,
    val lockedLoc: Int = -1,
    val kind: PieceKind = PieceKind.STANDARD,
    val topLoc: Int = -1,
    val hasCorner: Boolean = false,
) {
    val name: String get() = ItemDefinitions.getDefs(item).name
    val placedLocs: IntArray get() = intArrayOf(loc, openLoc, lockedLoc, topLoc).filter { it != -1 }.toIntArray()
    val materialValue: Int = cost.sumOf { (material, amount) -> ItemDefinitions.getDefs(material).getValue() * amount }
    val hitPoints: Int = (materialValue / ConstructionCatalogue.VALUE_PER_HIT_POINT).coerceIn(ConstructionCatalogue.MIN_HIT_POINTS, ConstructionCatalogue.MAX_HIT_POINTS)
    val material: Material get() = Material.forHitPoints(hitPoints)

    fun materials(): String = cost.joinToString(", ") { (material, amount) -> "$amount x ${ItemDefinitions.getDefs(material).name.lowercase()}" }

    fun canMake(player: Player): Boolean = cost.all { (material, amount) -> ConstructionMaterials.has(player, material, amount) }

    fun take(player: Player) = cost.forEach { (material, amount) -> ConstructionMaterials.take(player, material, amount) }
}

object ConstructionCatalogue {
    const val VALUE_PER_HIT_POINT = 4
    const val MIN_HIT_POINTS = 20
    const val MAX_HIT_POINTS = 2000
    private const val XP_PER_MATERIAL = 5.0
    private const val PLANKS_REQUIRED = 3
    private const val CHEST_PLANKS = 5
    private const val WALL_MATERIALS = 4
    private const val WALL_QUARTER_TURN = 3
    private const val DEFAULT_MODEL_TYPE = 10
    private const val DOOR_XP = 100.0
    private const val WALL_XP = 100.0
    private const val CHAIR_XP = 66.0
    private const val FURNACE_XP = 60.0
    private const val FURNACE_WIDTH = 2
    private const val FURNACE_DEPTH = 3
    private const val FURNACE_ORE = 10
    private const val FURNACE_COAL = 2
    private const val ANVIL_XP = 40.0
    private const val ANVIL_WIDTH = 1
    private const val ANVIL_DEPTH = 1
    private const val ANVIL_BARS = 5
    private const val ROOF_MATERIALS = 2
    private const val FLOOR_MATERIALS = 2
    private const val LADDER_PLANKS = 4
    private const val LADDER_NAILS = 4

    private val STARTS = linkedMapOf(
        HouseObjects.EXIT_PORTAL to ConstructionCategory.TELEPORTS,
        HouseObjects.DECORATIVE_ROCK to ConstructionCategory.GARDEN,
        HouseObjects.DUNGEON_ENTRACE to ConstructionCategory.TELEPORTS,
        HouseObjects.BIG_DEATH_TREE to ConstructionCategory.GARDEN,
        HouseObjects.CRUDE_WOODEN_CHAIR to ConstructionCategory.FURNISHINGS,
        HouseObjects.CLAY_FIREPLACE to ConstructionCategory.LIGHTING,
        HouseObjects.TORN_CURTAINS to ConstructionCategory.DECOR,
        HouseObjects.WOODEN_BOOKCASE to ConstructionCategory.FURNISHINGS,
        HouseObjects.BROWN_RUG to ConstructionCategory.DECOR,
        HouseObjects.OAK_STAIRCASE to ConstructionCategory.FUNCTIONAL,
        HouseObjects.CRAWLING_HAND to ConstructionCategory.TROPHIES,
        HouseObjects.SKELETON to ConstructionCategory.MISC,
        HouseObjects.OAK_LADDER to ConstructionCategory.FUNCTIONAL,
        HouseObjects.OAKTREASURECHEST to ConstructionCategory.FUNCTIONAL,
        HouseObjects.SMALLOBELISK to ConstructionCategory.MISC,
        HouseObjects.OAKTHRONE to ConstructionCategory.FURNISHINGS,
        HouseObjects.OAKLEVER to ConstructionCategory.FUNCTIONAL,
        HouseObjects.FLOORDECORATION to ConstructionCategory.DECOR,
        HouseObjects.STEELCAGE to ConstructionCategory.MISC,
        HouseObjects.CARVEDTEAKBENCH to ConstructionCategory.FURNISHINGS,
        HouseObjects.OAKTRAPDOOR to ConstructionCategory.FUNCTIONAL,
        HouseObjects.WOODEN_BED to ConstructionCategory.FURNISHINGS,
        HouseObjects.TELEPORT_FOCUS to ConstructionCategory.TELEPORTS,
        HouseObjects.WOODEN_LARDER to ConstructionCategory.FURNISHINGS,
        HouseObjects.PUMP_AND_DRAIN to ConstructionCategory.FUNCTIONAL,
        HouseObjects.WOODEN_KITCHEN_TABLE to ConstructionCategory.FURNISHINGS,
        HouseObjects.CAT_BLANKET to ConstructionCategory.FURNISHINGS,
        HouseObjects.FIREPIT to ConstructionCategory.FUNCTIONAL,
        HouseObjects.WOODEN_SHELVES_1 to ConstructionCategory.FURNISHINGS,
        HouseObjects.BEER_BARREL to ConstructionCategory.FUNCTIONAL,
        HouseObjects.WOOD_DINING to ConstructionCategory.FURNISHINGS,
        HouseObjects.ROPE_BELL_PULL to ConstructionCategory.FUNCTIONAL,
        HouseObjects.OAK_DECORATION to ConstructionCategory.DECOR,
        HouseObjects.REPAIR_BENCH to ConstructionCategory.FUNCTIONAL,
        HouseObjects.SILVERLIGHT_SWORD to ConstructionCategory.TROPHIES,
        HouseObjects.KING_ARTHUR to ConstructionCategory.DECOR,
        HouseObjects.DECORATIVEBLOOD to ConstructionCategory.DECOR,
        HouseObjects.CANDLE to ConstructionCategory.LIGHTING,
        HouseObjects.WOODENCRATE to ConstructionCategory.MISC,
        HouseObjects.OAKCHEST to ConstructionCategory.FUNCTIONAL,
        HouseObjects.DEMON to ConstructionCategory.MISC,
        HouseObjects.OAKDOOR to ConstructionCategory.FUNCTIONAL,
        HouseObjects.ALCHEMICAL_CHART to ConstructionCategory.DECOR,
        HouseObjects.WOODEN_TELESCOPE to ConstructionCategory.FUNCTIONAL,
        HouseObjects.CRYSTAL_BALL to ConstructionCategory.FUNCTIONAL,
        HouseObjects.GLOBE to ConstructionCategory.DECOR,
        HouseObjects.OAK_LECTURN to ConstructionCategory.FURNISHINGS,
        HouseObjects.LECTURN_STATUE to ConstructionCategory.DECOR,
        HouseObjects.EXITPORTAL to ConstructionCategory.TELEPORTS,
        HouseObjects.GAZEBO to ConstructionCategory.GARDEN,
        HouseObjects.DUNGEONENTRANCE to ConstructionCategory.TELEPORTS,
        HouseObjects.SMALLFOUNTAIN to ConstructionCategory.GARDEN,
        HouseObjects.OAK_TREASURE_CHEST to ConstructionCategory.FUNCTIONAL,
        HouseObjects.OAK_ALTAR to ConstructionCategory.FUNCTIONAL,
        HouseObjects.WOODEN_TORCHES to ConstructionCategory.LIGHTING,
        HouseObjects.GUTHIX_SYMBOL to ConstructionCategory.DECOR,
        HouseObjects.WINCHIMES to ConstructionCategory.FUNCTIONAL,
        HouseObjects.SMALL_STATUE to ConstructionCategory.DECOR,
        HouseObjects.SHUTTERED_WINDOW to ConstructionCategory.DECOR,
    )

    private data class Wall(val item: Int, val built: Int, val ghost: Int, val material: Int)

    private val WALLS = listOf(
        Wall(Obj.placeable_wall_1902, Loc.placeable_wall_1902_built, Loc.placeable_wall_1902_ghost, Obj.clay),
        Wall(Obj.placeable_wall_13091, Loc.placeable_wall_13091_built, Loc.placeable_wall_13091_ghost, Obj.clay),
        Wall(Obj.placeable_wall_25890, Loc.placeable_wall_25890_built, Loc.placeable_wall_25890_ghost, Obj.clay),
        Wall(Obj.placeable_wall_25899, Loc.placeable_wall_25899_built, Loc.placeable_wall_25899_ghost, Obj.clay),
        Wall(Obj.placeable_wall_36040, Loc.placeable_wall_36040_built, Loc.placeable_wall_36040_ghost, Obj.clay),
        Wall(Obj.placeable_wall_36050, Loc.placeable_wall_36050_built, Loc.placeable_wall_36050_ghost, Obj.clay),
        Wall(Obj.placeable_wall_11652, Loc.placeable_wall_11652_built, Loc.placeable_wall_11652_ghost, Obj.clay),
        Wall(Obj.placeable_wall_11653, Loc.placeable_wall_11653_built, Loc.placeable_wall_11653_ghost, Obj.clay),
        Wall(Obj.placeable_wall_1602, Loc.placeable_wall_1602_built, Loc.placeable_wall_1602_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_10816, Loc.placeable_wall_10816_built, Loc.placeable_wall_10816_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_24946, Loc.placeable_wall_24946_built, Loc.placeable_wall_24946_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_24953, Loc.placeable_wall_24953_built, Loc.placeable_wall_24953_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_23638, Loc.placeable_wall_23638_built, Loc.placeable_wall_23638_ghost, Obj.clay),
        Wall(Obj.placeable_wall_23641, Loc.placeable_wall_23641_built, Loc.placeable_wall_23641_ghost, Obj.clay),
        Wall(Obj.placeable_wall_5240, Loc.placeable_wall_5240_built, Loc.placeable_wall_5240_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_5241, Loc.placeable_wall_5241_built, Loc.placeable_wall_5241_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_1904, Loc.placeable_wall_1904_built, Loc.placeable_wall_1904_ghost, Obj.clay),
        Wall(Obj.placeable_wall_33995, Loc.placeable_wall_33995_built, Loc.placeable_wall_33995_ghost, Obj.clay),
        Wall(Obj.placeable_wall_33997, Loc.placeable_wall_33997_built, Loc.placeable_wall_33997_ghost, Obj.clay),
        Wall(Obj.placeable_wall_36059, Loc.placeable_wall_36059_built, Loc.placeable_wall_36059_ghost, Obj.clay),
        Wall(Obj.placeable_wall_36065, Loc.placeable_wall_36065_built, Loc.placeable_wall_36065_ghost, Obj.clay),
        Wall(Obj.placeable_wall_35814, Loc.placeable_wall_35814_built, Loc.placeable_wall_35814_ghost, Obj.clay),
        Wall(Obj.placeable_wall_35818, Loc.placeable_wall_35818_built, Loc.placeable_wall_35818_ghost, Obj.clay),
        Wall(Obj.placeable_wall_33950, Loc.placeable_wall_33950_built, Loc.placeable_wall_33950_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_24938, Loc.placeable_wall_24938_built, Loc.placeable_wall_24938_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_24943, Loc.placeable_wall_24943_built, Loc.placeable_wall_24943_ghost, Obj.woodplank),
        Wall(Obj.placeable_wall_23650, Loc.placeable_wall_23650_built, Loc.placeable_wall_23650_ghost, Obj.clay),
    )

    private data class Cover(val item: Int, val built: Int, val ghost: Int, val material: Int)

    private val ROOFS = listOf(
        Cover(Obj.placeable_roof_slate, Loc.placeable_roof_slate_built, Loc.placeable_roof_slate_ghost, Obj.clay),
        Cover(Obj.placeable_roof_blueslate, Loc.placeable_roof_blueslate_built, Loc.placeable_roof_blueslate_ghost, Obj.clay),
        Cover(Obj.placeable_roof_greyslate, Loc.placeable_roof_greyslate_built, Loc.placeable_roof_greyslate_ghost, Obj.clay),
        Cover(Obj.placeable_roof_darkslate, Loc.placeable_roof_darkslate_built, Loc.placeable_roof_darkslate_ghost, Obj.clay),
        Cover(Obj.placeable_roof_redslate, Loc.placeable_roof_redslate_built, Loc.placeable_roof_redslate_ghost, Obj.clay),
        Cover(Obj.placeable_roof_thatch, Loc.placeable_roof_thatch_built, Loc.placeable_roof_thatch_ghost, Obj.logs),
        Cover(Obj.placeable_roof_straw, Loc.placeable_roof_straw_built, Loc.placeable_roof_straw_ghost, Obj.logs),
        Cover(Obj.placeable_roof_reed, Loc.placeable_roof_reed_built, Loc.placeable_roof_reed_ghost, Obj.logs),
        Cover(Obj.placeable_roof_hay, Loc.placeable_roof_hay_built, Loc.placeable_roof_hay_ghost, Obj.logs),
        Cover(Obj.placeable_roof_wood, Loc.placeable_roof_wood_built, Loc.placeable_roof_wood_ghost, Obj.woodplank),
    )

    private val FLOORS = listOf(
        Cover(Obj.placeable_floor_wood, Loc.placeable_floor_wood_built, Loc.placeable_floor_wood_ghost, Obj.woodplank),
        Cover(Obj.placeable_floor_slate, Loc.placeable_floor_slate_built, Loc.placeable_floor_slate_ghost, Obj.clay),
    )

    val pieces: List<ConstructionPiece> by lazy { crafted() + vanilla() }

    private val byItem: Map<Int, ConstructionPiece> by lazy { pieces.associateBy { it.item } }
    private val byLoc: Map<Int, ConstructionPiece> by lazy { pieces.flatMap { piece -> piece.placedLocs.map { it to piece } }.toMap() }
    private val byCategory: Map<ConstructionCategory, List<ConstructionPiece>> by lazy { pieces.groupBy { it.category } }

    val largestCategory: Int by lazy { ConstructionCategory.entries.maxOf { forCategory(it).size } }

    fun forItem(item: Int): ConstructionPiece? = byItem[item]

    @JvmStatic
    fun isBuildItem(item: Int): Boolean = item == Obj.estate_deed || byItem.containsKey(item)

    fun forLoc(loc: Int): ConstructionPiece? = byLoc[loc]

    fun forCategory(category: ConstructionCategory): List<ConstructionPiece> = byCategory[category] ?: emptyList()

    private fun sized(item: Int, built: Int, ghost: Int, source: Int, category: ConstructionCategory, cost: List<Pair<Int, Int>>, xp: Double, openLoc: Int = -1, onOpen: ((Player, GameObject) -> Unit)? = null, type: Int? = null, lockedLoc: Int = -1, kind: PieceKind = PieceKind.STANDARD, topLoc: Int = -1): ConstructionPiece {
        val def = ObjectDefinitions.getDefs(source)
        val types = def.modelTypes?.map { it.toInt() }.orEmpty()
        val shape = ObjectShape.forId(type ?: types.firstOrNull() ?: DEFAULT_MODEL_TYPE) ?: ObjectShape.SCENERY_INTERACT
        val quarterTurn = if (shape == ObjectShape.WALL_STRAIGHT) WALL_QUARTER_TURN else 0
        val hasCorner = shape == ObjectShape.WALL_STRAIGHT && ObjectShape.WALL_WHOLE_CORNER.id in types
        return ConstructionPiece(item, built, ghost, shape, quarterTurn, xp, def.sizeX, def.sizeY, category, cost, openLoc, onOpen, lockedLoc, kind, topLoc, hasCorner)
    }

    private fun cover(cover: Cover, kind: PieceKind, shape: ObjectShape, amount: Int): ConstructionPiece =
        sized(cover.item, cover.built, cover.ghost, cover.built, ConstructionCategory.DECOR, listOf(cover.material to amount), amount * XP_PER_MATERIAL, type = shape.id, kind = kind)

    private fun piece(item: Int, built: Int, ghost: Int, category: ConstructionCategory, vararg cost: Pair<Int, Int>, xp: Double = cost.sumOf { it.second } * XP_PER_MATERIAL, openLoc: Int = -1, onOpen: ((Player, GameObject) -> Unit)? = null, lockedLoc: Int = -1): ConstructionPiece =
        sized(item, built, ghost, built, category, cost.toList(), xp, openLoc, onOpen, lockedLoc = lockedLoc)

    private fun planks(amount: Int = PLANKS_REQUIRED) = Obj.woodplank to amount

    private fun crafted(): List<ConstructionPiece> = listOf(
        piece(Obj.poh_armchair_1, Loc.built_wooden_chair, Loc.poh_chair1_placement_ghost, ConstructionCategory.FURNISHINGS, planks(), xp = CHAIR_XP),
        piece(Obj.wooden_door_furniture_item, Loc.built_wooden_door, Loc.timberwall_doorl_placement_ghost, ConstructionCategory.FUNCTIONAL, planks(), xp = DOOR_XP, openLoc = Loc.built_wooden_door_open, lockedLoc = Loc.built_wooden_door_locked),
        piece(Obj.wooden_wall_furniture_item, Loc.built_wooden_wall, Loc.placeable_wooden_wall_ghost, ConstructionCategory.DECOR, planks(), xp = WALL_XP),
        ConstructionPiece(Obj.furnace_item, Loc.built_furnace, Loc.poh_furnace_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, FURNACE_XP, FURNACE_WIDTH, FURNACE_DEPTH, ConstructionCategory.FUNCTIONAL, listOf(Obj.iron_ore to FURNACE_ORE, Obj.coal to FURNACE_COAL)),
        ConstructionPiece(Obj.anvil_item, Loc.loc_2783, Loc.anvil_placement_ghost, ObjectShape.SCENERY_INTERACT, 0, ANVIL_XP, ANVIL_WIDTH, ANVIL_DEPTH, ConstructionCategory.FUNCTIONAL, listOf(Obj.iron_bar to ANVIL_BARS)),
        piece(Obj.placeable_door_3, Loc.placeable_door_3_built, Loc.placeable_door_3_ghost, ConstructionCategory.FUNCTIONAL, Obj.woodplank to 3, openLoc = Loc.placeable_door_3_built_open, lockedLoc = Loc.placeable_door_3_built_locked),
        piece(Obj.placeable_iron_gate_37, Loc.placeable_iron_gate_37_built, Loc.placeable_iron_gate_37_ghost, ConstructionCategory.FUNCTIONAL, Obj.iron_bar to 3, Obj.nails to 10, openLoc = Loc.placeable_iron_gate_37_built_open, lockedLoc = Loc.placeable_iron_gate_37_built_locked),
        piece(Obj.placeable_steel_door_59, Loc.placeable_steel_door_59_built, Loc.placeable_steel_door_59_ghost, ConstructionCategory.FUNCTIONAL, Obj.steel_bar to 3, Obj.nails to 10, openLoc = Loc.placeable_steel_door_59_built_open, lockedLoc = Loc.placeable_steel_door_59_built_locked),
        piece(Obj.placeable_wooden_gate_37, Loc.placeable_wooden_gate_37_built, Loc.placeable_wooden_gate_37_ghost, ConstructionCategory.GARDEN, Obj.woodplank to 4, openLoc = Loc.placeable_wooden_gate_37_built_open, lockedLoc = Loc.placeable_wooden_gate_37_built_locked),
        piece(Obj.placeable_lever_33, Loc.placeable_lever_33_built, Loc.placeable_lever_33_ghost, ConstructionCategory.FUNCTIONAL, Obj.iron_bar to 1),
        piece(Obj.placeable_chest_375, Loc.placeable_chest_375_built, Loc.placeable_chest_375_ghost, ConstructionCategory.FUNCTIONAL, Obj.woodplank to CHEST_PLANKS, onOpen = StorageChest::open),
        piece(Obj.placeable_bar_131, Loc.placeable_bar_131_built, Loc.placeable_bar_131_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 5),
        piece(Obj.placeable_bar_132, Loc.placeable_bar_132_built, Loc.placeable_bar_132_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 5),
        piece(Obj.placeable_bar_136, Loc.placeable_bar_136_built, Loc.placeable_bar_136_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 5),
        piece(Obj.placeable_drawers_350, Loc.placeable_drawers_350_built, Loc.placeable_drawers_350_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 4),
        piece(Obj.placeable_cupboard_367, Loc.placeable_cupboard_367_built, Loc.placeable_cupboard_367_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 3),
        piece(Obj.placeable_dresser_373, Loc.placeable_dresser_373_built, Loc.placeable_dresser_373_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 4),
        piece(Obj.placeable_bookcase_380, Loc.placeable_bookcase_380_built, Loc.placeable_bookcase_380_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 4),
        piece(Obj.placeable_bookcase_381, Loc.placeable_bookcase_381_built, Loc.placeable_bookcase_381_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 4),
        piece(Obj.placeable_bed_419, Loc.placeable_bed_419_built, Loc.placeable_bed_419_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 4),
        piece(Obj.placeable_bed_424, Loc.placeable_bed_424_built, Loc.placeable_bed_424_ghost, ConstructionCategory.FURNISHINGS, Obj.gold_bar to 3, Obj.nails to 8),
        piece(Obj.placeable_workbench_540, Loc.placeable_workbench_540_built, Loc.placeable_workbench_540_ghost, ConstructionCategory.FUNCTIONAL, Obj.woodplank to 5, Obj.nails to 5),
        piece(Obj.placeable_bank_table_591, Loc.placeable_bank_table_591_built, Loc.placeable_bank_table_591_ghost, ConstructionCategory.FUNCTIONAL, Obj.woodplank to 5, Obj.nails to 5),
        piece(Obj.placeable_table_593, Loc.placeable_table_593_built, Loc.placeable_table_593_ghost, ConstructionCategory.FURNISHINGS, Obj.woodplank to 5),
        piece(Obj.placeable_wall_36073, Loc.placeable_wall_36073_built, Loc.placeable_wall_36073_ghost, ConstructionCategory.DECOR, Obj.woodplank to 4),
        *WALLS.map { (item, built, ghost, material) -> piece(item, built, ghost, ConstructionCategory.DECOR, material to WALL_MATERIALS) }.toTypedArray(),
        *ROOFS.map { cover(it, PieceKind.ROOF, ObjectShape.STRAIGHT_FLAT_ROOF, ROOF_MATERIALS) }.toTypedArray(),
        *FLOORS.map { cover(it, PieceKind.FLOOR, ObjectShape.GROUND_DECORATION, FLOOR_MATERIALS) }.toTypedArray(),
        sized(Obj.placeable_ladder, Loc.placeable_ladder_built, Loc.placeable_ladder_ghost, Loc.placeable_ladder_built, ConstructionCategory.FUNCTIONAL, listOf(Obj.woodplank to LADDER_PLANKS, Obj.nails to LADDER_NAILS), (LADDER_PLANKS + LADDER_NAILS) * XP_PER_MATERIAL, kind = PieceKind.LADDER, topLoc = Loc.placeable_ladder_top_built),
        piece(Obj.placeable_candles_202, Loc.placeable_candles_202_built, Loc.placeable_candles_202_ghost, ConstructionCategory.LIGHTING, Obj.obj_36 to 3, Obj.woodplank to 1),
    )

    private fun vanilla(): List<ConstructionPiece> {
        val taken = crafted().mapTo(HashSet()) { it.item }
        var category = ConstructionCategory.MISC
        val result = ArrayList<ConstructionPiece>()
        for (entry in HouseObjects.entries) {
            category = STARTS[entry] ?: category
            val key = entry.name.lowercase()
            val built = Gameval.id(Gameval.LOC, "placeable_${key}_built") ?: continue
            val ghost = Gameval.id(Gameval.LOC, "placeable_${key}_ghost") ?: continue
            if (!taken.add(entry.itemId)) continue
            result += sized(entry.itemId, built, ghost, built, category, entry.reqs.map { it.id to it.amount }, entry.xp)
        }
        return result
    }
}
