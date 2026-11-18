package com.rs.game.content.world.npcs.merchant

import com.rs.engine.command.Commands
import com.rs.game.World
import com.rs.game.content.combat.AttackStyle.Companion.getStyles
import com.rs.game.content.combat.CombatStyle
import com.rs.game.content.combat.getMeleeCombatDelay
import com.rs.game.content.combat.getWeaponAttackEmote
import com.rs.game.content.world.generated.CityShopkeeper
import com.rs.game.map.ChunkManager
import com.rs.game.map.instance.InstancedChunk
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.npc.combat.CombatScript
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.engine.dialogue.HeadE
import com.rs.engine.dialogue.startConversation
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.instantiateNpc
import com.rs.plugin.kts.npcCombat
import com.rs.plugin.kts.onNpcClick
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Npc
import org.darkan.core.map.GeneratedWorld
import org.darkan.core.model.Rights
import org.darkan.core.util.MapUtils
import org.darkan.core.util.Structure
import world.gregs.voidps.type.Tile

private val MERCHANT_IDS = arrayOf<Any>(
    Npc.wandering_merchant_01,
    Npc.wandering_merchant_02,
    Npc.wandering_merchant_03,
    Npc.wandering_merchant_04,
    Npc.wandering_merchant_05,
    Npc.wandering_merchant_06,
    Npc.wandering_merchant_07,
    Npc.wandering_merchant_08,
    Npc.wandering_merchant_09,
    Npc.wandering_merchant_10,
    Npc.wandering_merchant_11,
    Npc.wandering_merchant_12,
    Npc.wandering_merchant_13,
    Npc.wandering_merchant_14,
    Npc.wandering_merchant_15,
    Npc.wandering_merchant_16,
    Npc.wandering_merchant_17,
    Npc.wandering_merchant_18,
    Npc.wandering_merchant_19,
    Npc.wandering_merchant_20,
    Npc.wandering_merchant_21,
    Npc.wandering_merchant_22,
    Npc.wandering_merchant_23,
    Npc.wandering_merchant_24,
    Npc.wandering_merchant_25,
    Npc.wandering_merchant_26,
    Npc.wandering_merchant_27,
    Npc.wandering_merchant_28,
    Npc.wandering_merchant_29,
    Npc.wandering_merchant_30,
    Npc.wandering_merchant_31,
    Npc.wandering_merchant_32,
    Npc.wandering_merchant_33,
    Npc.wandering_merchant_34,
    Npc.wandering_merchant_35,
    Npc.wandering_merchant_36,
    Npc.wandering_merchant_37,
    Npc.wandering_merchant_38,
    Npc.wandering_merchant_39,
    Npc.wandering_merchant_40,
    Npc.wandering_merchant_41,
    Npc.wandering_merchant_42,
    Npc.wandering_merchant_43,
    Npc.wandering_merchant_44,
    Npc.wandering_merchant_45,
    Npc.wandering_merchant_46,
    Npc.wandering_merchant_47,
    Npc.wandering_merchant_48,
    Npc.wandering_merchant_49,
    Npc.wandering_merchant_50
)

private const val SPAWN_CHANCE_PERCENT = 1.0
private const val SPAWN_INTERVAL_TICKS = 100
private const val NEAR_PLAYER_CHUNK_RADIUS = 3
private const val NEAR_PLAYER_TILES = 18
private const val DESPAWN_TILES = 96
private const val PERCENT = 100.0
private const val CHUNK_TILES = 8
private const val SPAWN_ATTEMPTS = 8
private const val MERCHANT_COMMAND_RADIUS = 3
private const val AREA_CAP_TILES = 150
private const val MAX_MERCHANTS_PER_AREA = 6
private const val MELEE_STYLE_INDEX = 1
private const val MIN_ATTACK_DELAY = 1

private val activeMerchants = java.util.concurrent.ConcurrentHashMap.newKeySet<MerchantNPC>()

@ServerStartupEvent
fun mapWanderingMerchant() {
    instantiateNpc(*MERCHANT_IDS) { id, tile -> MerchantNPC(id, tile) }
    onNpcClick(*MERCHANT_IDS, options = arrayOf("Trade")) { (player, npc) ->
        when (npc) {
            is MerchantNPC -> npc.trade(player)
            is CityShopkeeper -> npc.trade(player)
        }
    }
    onNpcClick(*MERCHANT_IDS, options = arrayOf("Talk-to")) { (player, npc) ->
        when (npc) {
            is MerchantNPC -> talk(player, npc)
            is CityShopkeeper -> npc.talk(player)
        }
    }
    npcCombat(*MERCHANT_IDS) { npc, target ->
        val weaponId = (npc as? MerchantNPC)?.weaponId ?: -1
        npc.anim(getWeaponAttackEmote(weaponId, getStyles(weaponId).getValue(MELEE_STYLE_INDEX)))
        val maxHit = CombatScript.getMaxHit(npc, npc.maxHit, npc.highestAttackBonus, CombatStyle.MELEE, target)
        CombatScript.delayHit(npc, 0, target, Hit.melee(npc, maxHit))
        maxOf(getMeleeCombatDelay(weaponId), MIN_ATTACK_DELAY)
    }
    WorldTasks.scheduleTimer(SPAWN_INTERVAL_TICKS, SPAWN_INTERVAL_TICKS) {
        despawnAbandonedMerchants()
        nearPlayerChunks().forEach { if (Math.random() * PERCENT < SPAWN_CHANCE_PERCENT) spawnInChunk(it) }
        true
    }
    Commands.add(Rights.OWNER, "merchant", "Spawns a wandering merchant next to you.") { player, _ -> spawnMerchantNear(player) }
}

private fun spawnMerchantNear(player: Player) {
    val tile = (0..MERCHANT_COMMAND_RADIUS).firstNotNullOfOrNull { radius ->
        (-radius..radius).flatMap { dx -> (-radius..radius).map { dy -> player.tile.transform(dx, dy, 0) } }
            .filter { it != player.tile && World.floorAndWallsFree(it, 1) }
            .randomOrNull()
    }
    if (tile == null) {
        player.sendMessage("There's no room for a merchant next to you.")
        return
    }
    (World.spawnNPC(MERCHANT_IDS.random() as Int, tile, true) as? MerchantNPC)?.let { activeMerchants.add(it) }
    player.sendMessage("A wandering merchant appears.")
}

private fun talk(player: Player, merchant: MerchantNPC) {
    merchant.pauseWandering()
    player.startConversation {
        npc(merchant.id, HeadE.CHEERFUL, "Ah, a fellow traveller! I'm always looking to buy items for myself.")
        npc(merchant.id, HeadE.CALM_TALK, "Show me what you've got and I'll pay you the coin price. Anything good enough, I'll even wear.")
    }
}

private fun nearPlayerChunks(): List<Int> = World.players
    .filter { !it.isBot && it.hasStarted() && !it.hasFinished() }
    .flatMap { World.getChunkRadius(it.chunkId, NEAR_PLAYER_CHUNK_RADIUS) }
    .distinct()
    .filter { ChunkManager.findChunk(it).let { chunk -> chunk != null && chunk !is InstancedChunk } }
    .filter { !inDungeonChunk(it) }

private fun inDungeonChunk(chunkId: Int): Boolean {
    val (chunkX, chunkY, _) = MapUtils.decode(Structure.CHUNK, chunkId)
    return GeneratedWorld.inDungeon(chunkX * CHUNK_TILES, chunkY * CHUNK_TILES)
}

private fun spawnInChunk(chunkId: Int) {
    val (chunkX, chunkY, plane) = MapUtils.decode(Structure.CHUNK, chunkId)
    repeat(SPAWN_ATTEMPTS) {
        val tile = Tile.of(chunkX * CHUNK_TILES + (0 until CHUNK_TILES).random(), chunkY * CHUNK_TILES + (0 until CHUNK_TILES).random(), plane)
        val nearPlayer = World.players.any { !it.hasFinished() && it.plane == plane && Utils.getDistance(it.tile, tile) <= NEAR_PLAYER_TILES }
        if (!nearPlayer || GeneratedWorld.inDungeon(tile.x, tile.y) || !World.floorAndWallsFree(tile, 1)) return@repeat
        if (activeMerchants.count { !it.hasFinished() && it.plane == plane && Utils.getDistance(it.tile, tile) <= AREA_CAP_TILES } >= MAX_MERCHANTS_PER_AREA) return
        (World.spawnNPC(MERCHANT_IDS.random() as Int, tile, true) as? MerchantNPC)?.let { activeMerchants.add(it) }
        return
    }
}

private fun despawnAbandonedMerchants() {
    activeMerchants.removeIf { merchant ->
        val gone = merchant.hasFinished() || merchant.isDead
        val abandoned = !gone && World.players.none { !it.hasFinished() && it.plane == merchant.plane && Utils.getDistance(it.tile, merchant.tile) < DESPAWN_TILES }
        if (abandoned) merchant.finish()
        gone || abandoned
    }
}
