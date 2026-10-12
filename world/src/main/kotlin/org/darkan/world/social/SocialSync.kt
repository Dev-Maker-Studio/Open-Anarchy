package org.darkan.world.social

import com.rs.game.model.entity.player.Player
import org.darkan.core.social.FriendsChatState
import org.darkan.core.social.SocialEvent
import java.util.concurrent.ConcurrentHashMap

object SocialSync {
    private val channels = ConcurrentHashMap<String, FriendsChatState>()

    fun channelOf(username: String): FriendsChatState? = channels[username]

    private fun remember(username: String, state: FriendsChatState?) {
        if (state == null) channels.remove(username) else channels[username] = state
    }

    fun apply(player: Player, event: SocialEvent) {
        val social = player.account.social
        val username = player.account.username
        when (event) {
            is SocialEvent.Snapshot -> remember(username, event.friendsChat)
            is SocialEvent.FriendsChatChanged -> remember(username, event.channel)
            else -> Unit
        }
        when (event) {
            is SocialEvent.Snapshot -> {
                social.friends = event.friends.mapTo(HashSet()) { it.username }
                social.ignores = event.ignores.mapTo(HashSet()) { it.username }
                social.status = event.privateStatus.toByte()
                social.currentFriendsChat = event.friendsChat?.ownerDisplayName
            }
            is SocialEvent.FriendUpdated -> social.friends.add(event.friend.username)
            is SocialEvent.FriendRemoved -> social.friends.remove(event.username)
            is SocialEvent.IgnoresChanged -> social.ignores = event.ignores.mapTo(HashSet()) { it.username }
            is SocialEvent.PrivateStatusChanged -> social.status = event.status.toByte()
            is SocialEvent.FriendsChatChanged -> social.currentFriendsChat = event.channel?.ownerDisplayName
            is SocialEvent.FriendsChatLine,
            is SocialEvent.PrivateMessageReceived,
            is SocialEvent.PrivateMessageSent,
            is SocialEvent.Notice -> Unit
        }
    }
}
