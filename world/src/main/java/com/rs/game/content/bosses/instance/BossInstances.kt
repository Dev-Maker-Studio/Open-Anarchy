package com.rs.game.content.bosses.instance

import com.rs.game.model.entity.player.Player
import org.darkan.core.Utils
import java.util.concurrent.ConcurrentHashMap

object BossInstances {
    private val byOwner = ConcurrentHashMap<String, BossInstance>()

    fun owned(username: String): BossInstance? = byOwner[username]?.takeUnless { it.expired }

    fun start(player: Player, def: BossInstanceDef, settings: InstanceSettings): BossInstance? {
        if (!player.inventory.hasCoins(def.cost)) {
            player.sendMessage("You need ${Utils.formatNumber(def.cost)} coins to start a ${def.displayName} instance.")
            return null
        }
        player.inventory.removeCoins(def.cost)
        owned(player.username)?.destroy()
        val created = BossInstance(def, player.username, settings)
        byOwner[player.username] = created
        enter(player, created)
        return created
    }

    fun join(player: Player, owner: String, enteredPin: Int = 0): String? {
        val instance = owned(owner) ?: return "That instance no longer exists."
        instance.refusalFor(player, enteredPin)?.let { return it }
        enter(player, instance)
        return null
    }

    fun rejoin(player: Player): String? {
        val instance = owned(player.username) ?: return "You do not have an instance to rejoin."
        enter(player, instance)
        return null
    }

    private fun enter(player: Player, instance: BossInstance) {
        player.lock()
        instance.build {
            instance.add(player)
            player.controllerManager.startController(BossInstanceController(instance))
            player.unlock()
        }
    }

    fun forget(instance: BossInstance) {
        byOwner.remove(instance.ownerUsername, instance)
    }
}
