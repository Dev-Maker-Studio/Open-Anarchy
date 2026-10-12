package org.darkan.world.model

import com.rs.game.model.entity.player.Player
import java.util.concurrent.ConcurrentHashMap

object WorldPlayers {
    private val playersByUsername = ConcurrentHashMap<String, Player>()

    fun get(username: String): Player? = playersByUsername[username]

    fun put(player: Player) {
        playersByUsername[player.account.username] = player
    }

    fun remove(username: String): Player? = playersByUsername.remove(username)

    fun values(): Collection<Player> = playersByUsername.values
}
