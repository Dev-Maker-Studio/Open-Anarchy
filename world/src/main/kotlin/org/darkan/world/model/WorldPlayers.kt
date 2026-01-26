package org.darkan.world.model

import java.util.concurrent.ConcurrentHashMap

object WorldPlayers {
    private val playersByUsername = ConcurrentHashMap<String, WorldPlayer>()

    fun get(username: String): WorldPlayer? = playersByUsername[username]

    fun put(player: WorldPlayer) {
        playersByUsername[player.account.username] = player
    }

    fun remove(username: String): WorldPlayer? = playersByUsername.remove(username)

    fun values(): Collection<WorldPlayer> = playersByUsername.values
}


