package com.rs.game.content.bosses.instance

import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.gameval.gen.Npc
import world.gregs.voidps.type.Tile

@ServerStartupEvent
fun mapBossInstances() {
    listOf(
        BossInstanceDef(
            key = "king_black_dragon",
            displayName = "King Black Dragon",
            bossNpc = Npc.king_dragon,
            home = Tile.of(2270, 4701, 0),
            returnTo = Tile.of(3017, 3849, 0),
            minCombatDefault = 140,
        ),
        BossInstanceDef(
            key = "kalphite_queen",
            displayName = "Kalphite Queen",
            bossNpc = Npc.kalphite_queen,
            home = Tile.of(3484, 9491, 0),
            returnTo = Tile.of(3226, 3108, 0),
            minCombatDefault = 120,
        ),
        BossInstanceDef(
            key = "giant_mole",
            displayName = "Giant Mole",
            bossNpc = Npc.kalphite_flyingqueen,
            home = Tile.of(1760, 5186, 0),
            returnTo = Tile.of(2997, 3376, 0),
            regions = listOf(Tile.of(1760, 5186, 0).getRegionId(), Tile.of(1760, 5250, 0).getRegionId()),
            minCombatDefault = 80,
        ),
        BossInstanceDef(
            key = "corporeal_beast",
            displayName = "Corporeal Beast",
            bossNpc = Npc.sum2_spirit_beast_corporeal,
            home = Tile.of(2986, 4383, 2),
            returnTo = Tile.of(2885, 4372, 2),
            minCombatDefault = 130,
        ),
        BossInstanceDef(
            key = "general_graardor",
            displayName = "General Graardor",
            bossNpc = Npc.godwars_bandos_avatar,
            home = Tile.of(2872, 5358, 2),
            returnTo = Tile.of(2864, 5354, 2),
            minCombatDefault = 100,
        ),
        BossInstanceDef(
            key = "kree_arra",
            displayName = "Kree'arra",
            bossNpc = Npc.godwars_armadyl_avatar,
            home = Tile.of(2832, 5302, 2),
            returnTo = Tile.of(2839, 5296, 2),
            minCombatDefault = 100,
        ),
        BossInstanceDef(
            key = "commander_zilyana",
            displayName = "Commander Zilyana",
            bossNpc = Npc.godwars_saradomin_avatar,
            home = Tile.of(2900, 5268, 0),
            returnTo = Tile.of(2907, 5265, 0),
            minCombatDefault = 100,
        ),
        BossInstanceDef(
            key = "kril_tsutsaroth",
            displayName = "K'ril Tsutsaroth",
            bossNpc = Npc.godwars_zamorak_avatar,
            home = Tile.of(2926, 5322, 2),
            returnTo = Tile.of(2925, 5331, 2),
            minCombatDefault = 100,
        ),
        BossInstanceDef(
            key = "nex",
            displayName = "Nex",
            bossNpc = Npc.godwars2_zaros_avatar,
            home = Tile.of(2924, 5202, 0),
            returnTo = Tile.of(2900, 5204, 0),
            entrance = Tile.of(2911, 5204, 0),
            minCombatDefault = 138,
            entranceRequirements = "40 Zarosian kills",
        ),
        BossInstanceDef(
            key = "dagannoth_kings",
            displayName = "Dagannoth Kings",
            bossNpc = Npc.dagcave_melee_boss,
            home = Tile.of(2924, 4441, 0),
            returnTo = Tile.of(2900, 4449, 0),
            minCombatDefault = 90,
        ),
    ).forEach(BossInstanceDefs::register)
}
