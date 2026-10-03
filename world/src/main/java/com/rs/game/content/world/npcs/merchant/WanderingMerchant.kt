package com.rs.game.content.world.npcs.merchant

import com.rs.game.World
import com.rs.game.content.combat.AttackStyle.Companion.getStyles
import com.rs.game.content.combat.CombatStyle
import com.rs.game.content.combat.getMeleeCombatDelay
import com.rs.game.content.combat.getWeaponAttackEmote
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
import org.darkan.core.gameval.gen.Npc
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

private const val SPAWN_CHANCE = 0.5
private const val LOADED_CHUNK_RADIUS = 6
private const val CHUNK_TILES = 8
private const val SPAWN_ATTEMPTS = 30
private const val MELEE_STYLE_INDEX = 1
private const val MIN_ATTACK_DELAY = 1

private var activeMerchant: MerchantNPC? = null

@ServerStartupEvent
fun mapWanderingMerchant() {
    instantiateNpc(*MERCHANT_IDS) { id, tile -> MerchantNPC(id, tile) }
    onNpcClick(*MERCHANT_IDS, options = arrayOf("Trade")) { (player, npc) -> (npc as? MerchantNPC)?.trade(player) }
    onNpcClick(*MERCHANT_IDS, options = arrayOf("Talk-to")) { (player, npc) -> (npc as? MerchantNPC)?.let { talk(player, it) } }
    npcCombat(*MERCHANT_IDS) { npc, target ->
        val weaponId = (npc as? MerchantNPC)?.weaponId ?: -1
        npc.anim(getWeaponAttackEmote(weaponId, getStyles(weaponId).getValue(MELEE_STYLE_INDEX)))
        val maxHit = CombatScript.getMaxHit(npc, npc.maxHit, npc.highestAttackBonus, CombatStyle.MELEE, target)
        CombatScript.delayHit(npc, 0, target, Hit.melee(npc, maxHit))
        maxOf(getMeleeCombatDelay(weaponId), MIN_ATTACK_DELAY)
    }
    WorldTasks.scheduleHalfHourly { rollMerchantSpawn() }
}

private fun talk(player: Player, merchant: MerchantNPC) {
    merchant.pauseWandering()
    player.startConversation {
        npc(merchant.id, HeadE.CHEERFUL, "Ah, a fellow traveller! I'm always looking to buy items for myself.")
        npc(merchant.id, HeadE.CALM_TALK, "Show me what you've got and I'll pay you the coin price. Anything good enough, I'll even wear.")
    }
}

private fun rollMerchantSpawn() {
    if (activeMerchant?.let { !it.isDead && !it.hasFinished() } == true) return
    if (Math.random() >= SPAWN_CHANCE) return
    val tile = randomLoadedTile() ?: return
    activeMerchant = World.spawnNPC(MERCHANT_IDS.random() as Int, tile, true) as? MerchantNPC
}

private fun randomLoadedTile(): Tile? {
    val chunkIds = World.players
        .filter { !it.isBot && !it.hasFinished() }
        .flatMap { World.getChunkRadius(it.chunkId, LOADED_CHUNK_RADIUS) }
        .distinct()
        .filter { ChunkManager.findChunk(it).let { chunk -> chunk != null && chunk !is InstancedChunk } }
    if (chunkIds.isEmpty()) return null
    repeat(SPAWN_ATTEMPTS) {
        val (chunkX, chunkY, plane) = MapUtils.decode(Structure.CHUNK, chunkIds.random())
        val tile = Tile.of(chunkX * CHUNK_TILES + (0 until CHUNK_TILES).random(), chunkY * CHUNK_TILES + (0 until CHUNK_TILES).random(), plane)
        if (World.floorAndWallsFree(tile, 1)) return tile
    }
    return null
}
