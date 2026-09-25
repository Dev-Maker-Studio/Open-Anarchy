package com.rs.game.content.minigames.pest

import com.rs.Settings
import com.rs.game.World
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.utils.Ticks
import org.darkan.core.gameval.gen.Component
import org.darkan.core.gameval.gen.Interface
import kotlin.math.ceil

private const val MAX_PLAYERS = 25
private const val MAX_PRIORITY = 4
private val MIN_PLAYERS get() = if (Settings.getConfig().isDebug) 1 else 5
private val DEPARTURE_WAIT get() = if (Settings.getConfig().isDebug) Ticks.fromSeconds(6) else Ticks.fromMinutes(5)
private val HUD_REFRESH = Ticks.fromSeconds(30)

class Lander private constructor(val tier: LanderTier) {
    private val queue = LinkedHashSet<Player>()
    private val priority = HashMap<Player, Int>()
    private var departureTick = 0L
    private var generation = 0

    private val ticksLeft get() = (departureTick - World.getServerTicks()).coerceAtLeast(0L).toInt()

    fun board(player: Player) {
        when {
            player.skills.combatLevelWithSummoning < tier.combatLevel ->
                player.simpleDialogue("You need a combat level of ${tier.combatLevel} or higher to board the ${tier.displayName.lowercase()} lander.")
            player.familiar != null || player.pet != null ->
                player.sendMessage("You can't take a follower into the lander, there isn't enough room!")
            else -> {
                if (player.commendations >= COMMENDATION_CAP)
                    player.sendMessage("You already hold the maximum of $COMMENDATION_CAP commendations; spend some or you will not be awarded any more.")
                player.useStairs(-1, tier.boardTile, 1, 2, "You board the lander.")
                player.controllerManager.startController(PestControlLobbyController(tier))
                queue += player
                if (queue.size == 1) startClock()
                refreshAll()
            }
        }
    }

    fun remove(player: Player) {
        player.interfaceManager.removeOverlay()
        if (!queue.remove(player)) return
        priority.remove(player)
        if (queue.isEmpty()) generation++
        refreshAll()
    }

    private fun startClock() {
        departureTick = World.getServerTicks() + DEPARTURE_WAIT
        val clock = ++generation
        WorldTasks.scheduleTimer(1, 0) { tick ->
            if (clock != generation) return@scheduleTimer false
            if (queue.size >= MAX_PLAYERS || (ticksLeft == 0 && queue.size >= MIN_PLAYERS)) depart()
            else if (ticksLeft == 0) departureTick = World.getServerTicks() + DEPARTURE_WAIT
            if (queue.isEmpty()) {
                generation++
                return@scheduleTimer false
            }
            if (tick % HUD_REFRESH == 0) refreshAll()
            true
        }
    }

    private fun depart() {
        val selected = queue.sortedByDescending { priority[it] ?: 0 }.take(MAX_PLAYERS)
        queue.removeAll(selected.toSet())
        selected.forEach { priority.remove(it) }
        for (player in queue) {
            val raised = ((priority[player] ?: 0) + 1).coerceAtMost(MAX_PRIORITY)
            priority[player] = raised
            player.sendMessage("You have been given priority level $raised over other players in joining the next game.")
        }
        departureTick = World.getServerTicks() + DEPARTURE_WAIT
        PestControlGame.launch(tier, selected)
        refreshAll()
    }

    fun sendHud(player: Player) {
        player.interfaceManager.sendOverlay(Interface.interface_407)
        refresh(player)
    }

    private fun refresh(player: Player) {
        val minutes = ceil(ticksLeft / Ticks.fromMinutes(1).toDouble()).toInt()
        player.packets.setIFText(Component.interface_407.component_407_3, tier.displayName)
        player.packets.setIFText(Component.interface_407.component_407_13, "Next Departure: $minutes min")
        player.packets.setIFText(Component.interface_407.component_407_14, "Players Ready: ${queue.size}")
        player.packets.setIFText(Component.interface_407.component_407_15, "(Need $MIN_PLAYERS to $MAX_PLAYERS players)")
        player.packets.setIFText(Component.interface_407.component_407_16, "Commendations: ${player.commendations}")
    }

    private fun refreshAll() = queue.forEach(::refresh)

    companion object {
        private val landers = LanderTier.entries.associateWith(::Lander)

        fun of(tier: LanderTier) = landers.getValue(tier)
    }
}
