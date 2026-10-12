package com.rs.game.content

import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import org.darkan.core.social.FriendsChatState
import org.darkan.core.social.SocialEvent
import org.darkan.core.social.SocialPackets
import org.darkan.world.social.SocialSync

private const val NAMES_VARCSTR = 139
private const val WORLDS_VARCSTR = 141
private const val MAX_OFFICIAL = 60
private const val RESEND_DELAY = 4

object HelpChat {
    private val prefix = SocialPackets.OFFICIAL_PREFIX

    fun sync(player: Player, event: SocialEvent) {
        when (event) {
            is SocialEvent.Snapshot -> send(player, event.friendsChat)
            is SocialEvent.FriendsChatChanged -> send(player, event.channel)
            else -> Unit
        }
    }

    fun send(player: Player, state: FriendsChatState?) {
        val official = state?.members.orEmpty().filter { it.username.startsWith(prefix) }.take(MAX_OFFICIAL)
        player.packets.sendVarcString(NAMES_VARCSTR, official.joinToString("|") { it.displayName })
        player.packets.sendVarcString(WORLDS_VARCSTR, official.joinToString("|") { label(it.worldName) })
    }

    private fun label(worldName: String): String {
        val text = worldName.removePrefix("Darkan ")
        val number = text.takeLastWhile { it.isDigit() }
        return when {
            text.startsWith("Lobby") -> "L$number"
            text.startsWith("World") -> "W$number"
            else -> text.take(6)
        }
    }
}

@ServerStartupEvent
fun mapHelpChatRefresh() {
    onLogin { (player) ->
        WorldTasks.schedule(RESEND_DELAY) {
            if (player.hasFinished()) return@schedule
            val state = SocialSync.channelOf(player.account.username) ?: return@schedule
            HelpChat.send(player, state)
            player.session.queuePacket(SocialPackets.friendsChatChannel(player.account.username, state))
        }
    }
}
