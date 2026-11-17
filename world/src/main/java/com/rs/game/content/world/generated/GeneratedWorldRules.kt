package com.rs.game.content.world.generated

import com.rs.game.content.claims.BedRespawn
import com.rs.game.World
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.player.Player
import com.rs.game.content.skills.summoning.Familiar
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import org.darkan.core.EnvVars
import org.darkan.core.model.Item
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap

private const val PVP_REFRESH_TICKS = 5
private const val PRUNE_TICKS = 100
private const val DAMAGE_WINDOW_MILLIS = 30_000L
private const val INVENTORY_SLOTS = 28
private const val MAJORITY_DIVISOR = 2

private class DamageRecord(val source: Entity?, val amount: Int, val time: Long)

private val recentDamage = ConcurrentHashMap<Player, MutableList<DamageRecord>>()

@ServerStartupEvent
fun startGeneratedWorldRules() {
    if (!EnvVars.generateMaps) return
    WorldTasks.scheduleTimer(PVP_REFRESH_TICKS, PVP_REFRESH_TICKS) {
        World.players.forEach { player ->
            if (player.hasStarted() && !player.hasFinished() && !player.isCanPvp) player.setCanPvp(true)
        }
        true
    }
    WorldTasks.scheduleTimer(PRUNE_TICKS, PRUNE_TICKS) {
        recentDamage.keys.removeIf { it.hasFinished() }
        true
    }
}

fun recordDamage(victim: Player, source: Entity?, amount: Int) {
    if (!EnvVars.generateMaps || amount <= 0) return
    val now = System.currentTimeMillis()
    val records = recentDamage.computeIfAbsent(victim) { mutableListOf() }
    synchronized(records) {
        records.removeIf { now - it.time > DAMAGE_WINDOW_MILLIS }
        records.add(DamageRecord(source, amount, now))
    }
}

fun sendGeneratedWorldDeath(player: Player, source: Entity?): Boolean {
    if (!EnvVars.generateMaps) return false
    val killer = pvpKiller(player)
    recentDamage.remove(player)
    if (killer != null) dropInventory(player)
    player.dangerousDeath(source, BedRespawn::wake)
    return true
}

private fun pvpKiller(victim: Player): Player? {
    val records = recentDamage[victim] ?: return null
    val now = System.currentTimeMillis()
    val recent = synchronized(records) { records.filter { now - it.time <= DAMAGE_WINDOW_MILLIS } }
    val total = recent.sumOf { it.amount }
    val byPlayer = recent
        .mapNotNull { record -> owner(record.source)?.takeIf { it !== victim }?.let { it to record.amount } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.sum() }
    val top = byPlayer.maxByOrNull { it.value } ?: return null
    return top.key.takeIf { top.value * MAJORITY_DIVISOR > total }
}

private fun owner(source: Entity?): Player? = when (source) {
    is Player -> source
    is Familiar -> source.owner
    else -> null
}

private fun dropInventory(player: Player) {
    val tile = Tile.of(player.tile)
    for (slot in 0 until INVENTORY_SLOTS) {
        val item = player.inventory.getItem(slot) ?: continue
        if (item.id == -1 || item.amount <= 0) continue
        World.addGroundItem(Item(item.id, item.amount, item.metadata), tile, invisible = false)
    }
    player.inventory.reset()
    player.inventory.init()
}
