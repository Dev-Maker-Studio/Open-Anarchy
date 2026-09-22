package com.rs.game.content.bosses.godwars.factions.zaros

import com.rs.game.World
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.utils.Ticks
import org.darkan.core.Utils
import org.darkan.core.gameval.gen.Npc
import world.gregs.voidps.type.Tile
import java.util.Collections

/**
 * The war roster for the public Nex arena. Nex herself owns her minions, so this only tracks who is
 * fighting and starts or ends the encounter.
 */
class NexArena {
    private val players: MutableList<Player> = Collections.synchronizedList(ArrayList())
    var nex: Nex? = null
        private set

    val playersCount: Int get() = players.size

    fun getPlayers(): List<Player> = players

    fun addPlayer(player: Player) {
        if (players.contains(player)) return
        players.add(player)
        startWar()
    }

    fun removePlayer(player: Player) {
        players.remove(player)
        if (playersCount == 0) deleteNPCS()
    }

    fun deleteNPCS() {
        nex?.let {
            it.killBloodReavers()
            it.finish()
        }
        nex = null
    }

    val possibleTargets: MutableList<Entity>
        get() = players.filterNotTo(ArrayList()) { it.isDead || it.hasFinished() || !it.isRunning }

    fun moveNextStage() {
        nex?.nextPhase()
    }

    fun endWar() {
        deleteNPCS()
        WorldTasks.schedule(Ticks.fromMinutes(1)) { startWar() }
    }

    private fun startWar() {
        if (playersCount < 1 || nex != null) return
        val summoned = World.spawnNPC(Npc.godwars2_zaros_avatar, HOME, true, true) as? Nex ?: return
        summoned.arena = this
        nex = summoned
        announce(summoned)
    }

    private fun announce(summoned: Nex) {
        WorldTasks.scheduleTimer { count ->
            val live = nex ?: return@scheduleTimer false
            when (count) {
                1 -> {
                    live.forceTalk("AT LAST!")
                    live.anim(6355)
                    live.spotAnim(1217)
                    live.voiceEffect(3295, true)
                }
                in SUMMONS.keys -> summon(live, SUMMONS.getValue(count))
                11 -> {
                    live.forceTalk("Fill my soul with smoke!")
                    live.minion(Npc.godwars2_smokemage)?.let { World.sendProjectile(it, live, 2244, 18 to 18, 60, 10, 0) }
                    live.voiceEffect(3310, true)
                }
                13 -> {
                    live.setCantInteract(false)
                    return@scheduleTimer false
                }
            }
            true
        }
    }

    private fun summon(live: Nex, summon: Summon) {
        val minion = live.minion(summon.npcId) ?: return
        minion.faceAngle = Utils.getAngleTo(summon.faceX, summon.faceY)
        live.setNextFaceTile(Tile.of(minion.getCoordFaceX(minion.size), minion.getCoordFaceY(minion.size), 0))
        live.forceTalk(summon.shout)
        live.anim(6987)
        World.sendProjectile(minion, live, 2244, 18 to 18, 60, 10, 0)
        live.voiceEffect(summon.voice, true)
    }

    private class Summon(val npcId: Int, val shout: String, val voice: Int, val faceX: Int, val faceY: Int)

    companion object {
        @JvmStatic
        val globalInstance = NexArena()

        private val HOME = Tile.of(2924, 5202, 0)

        private val SUMMONS = mapOf(
            3 to Summon(Npc.godwars2_smokemage, "Fumus!", 3325, 1, -1),
            5 to Summon(Npc.godwars2_shadowmage, "Umbra!", 3313, -1, -1),
            7 to Summon(Npc.godwars2_bloodmage, "Cruor!", 3299, -1, 1),
            9 to Summon(Npc.godwars2_icemage, "Glacies!", 3304, 1, 1),
        )
    }
}
