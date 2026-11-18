package org.darkan.core.dungeon

import org.darkan.core.gameval.gen.Loc
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.gameval.gen.Overlay
import org.darkan.core.gameval.gen.Underlay

enum class DungeonTheme(
    val entrance: Int,
    val entranceWidth: Int,
    val entranceDepth: Int,
    val exit: Int,
    val underlay: Int,
    val overlay: Int?,
    val arenaOverlay: Int,
    val channelOverlay: Int?,
    val wall: Int?,
    val organic: Boolean,
    val corridor: Int,
    val decor: List<Int>,
    val arenaDecor: Int,
    val mobs: List<Int>,
    val boss: String,
) {
    CAVE(
        Loc.troll_mad_eadgar_entrance, 3, 2, Loc.godwars_rock_rope_bottom1,
        Underlay.underlay_55, null, Overlay.overlay_11, null, null, true, 3,
        listOf(
            Loc.stagamite_small_grey, Loc.stalagmite_twin_grey, Loc.boulder3, Loc.boulder4, Loc.harmless_dungeon_mushrooms_coldtop,
            Loc.dorgesh_mushroom1, Loc.wild4_bones_small_01, Loc.chickenquest_bones_small_01
        ),
        Loc.stalagmite_twin_grey,
        listOf(
            Npc.spider, Npc.rand_bat_1, Npc.small_bat, Npc.cave_goblin, Npc.rand_earth_warrior_1, Npc.giantspider1, Npc.darkbear,
            Npc.rand_cave_crawler, Npc.giantspider2, Npc.bat, Npc.rand_cave_slime, Npc.rand_hobgoblin_3, Npc.mossgiant,
            Npc.babybluedragon, Npc.ice_spider, Npc.troll
        ),
        "broodmother"
    ),
    FORTRESS(
        Loc.win05_trapdoor, 1, 1, Loc.elid_ladder_up,
        Underlay.underlay_55, Overlay.overlay_181, Overlay.overlay_92, null, Loc.area_ardougne_city_wall, false, 2,
        listOf(
            Loc.royal_torch, Loc.luc2_bf_barrel_empty, Loc.di2_boxes1, Loc.sos_death_skeleton_2, Loc.dragon_slayer_qip_corpse_1,
            Loc.varrock_castle_banner, Loc.stonepillar_small_waterfall_quest
        ),
        Loc.rand_abnd_furn_religious_decor_short_lit,
        listOf(
            Npc.rand_zombie_ranged_1, Npc.rand_ghost_1, Npc.mugger, Npc.rand_guard_dog_2, Npc.chaos_druid, Npc.rand_hill_giant_1,
            Npc.rand_skeleton_melee_lvl1_1, Npc.rogue, Npc.ghost, Npc.skeleton_unarmed, Npc.zombie_armed, Npc.skeleton_armed,
            Npc.black_knight, Npc.rand_fire_giant_1, Npc.rand_lesser_demon_1, Npc.rand_greater_demon_1, Npc.hellhound
        ),
        "warden"
    ),
    SEWER(
        Loc.manholeclosed, 1, 1, Loc.contact_ladder,
        Underlay.underlay_55, Overlay.overlay_171, Overlay.overlay_11, Overlay.overlay_112, Loc.brickwall, false, 3,
        listOf(
            Loc.slug2_rotten_barrel_broken, Loc.luc2_bf_barrel_empty, Loc.di2_boxes1, Loc.chickenquest_bones_small_01,
            Loc.wild4_bones_small_01, Loc.harmless_dungeon_mushrooms_coldtop
        ),
        Loc.mom2_dungeon_torch,
        listOf(
            Npc.rat, Npc.spider, Npc.rand_giant_rat_1, Npc.giantspider1, Npc.snake, Npc.small_bat, Npc.rand_crawling_hand,
            Npc.rand_giant_rat_3, Npc.rogue, Npc.zombie_armed_sewer4, Npc.giantspider2, Npc.rand_cave_slime, Npc.kingscorpion,
            Npc.mossgiant, Npc.rand_pyrefiend, Npc.rand_jelly
        ),
        "rat_king"
    ),
    ;

    val id = name.lowercase()

    companion object {
        private val byId = entries.associateBy { it.id }

        fun of(id: String): DungeonTheme = byId[id.lowercase()] ?: CAVE
    }
}
