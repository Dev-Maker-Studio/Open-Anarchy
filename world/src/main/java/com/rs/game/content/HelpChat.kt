package com.rs.game.content

import com.rs.game.model.entity.player.Player
import com.rs.game.tasks.WorldTasks
import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onLogin
import org.darkan.core.social.SocialPackets
import org.darkan.world.social.SocialSync

private val RESEND_DELAYS = intArrayOf(2, 6, 14)

@ServerStartupEvent
fun mapHelpChatRefresh() {
    onLogin { (player) -> RESEND_DELAYS.forEach { delay -> WorldTasks.schedule(delay) { player.resendHelpChat() } } }
}

private fun Player.resendHelpChat() {
    if (hasFinished()) return
    val username = account.username
    val state = SocialSync.channelOf(username) ?: return
    session.queuePacket(SocialPackets.friendsChatChannel(username, state))
}
