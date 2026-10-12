package org.darkan.core.clan

import kotlinx.serialization.Serializable
import org.darkan.core.model.ClanRank

@Serializable
data class ClanChatter(
    val username: String,
    val displayName: String,
    val rank: ClanRank,
    val worldId: Int,
) {
    val guest: Boolean get() = rank == ClanRank.NONE

    companion object {
        const val LOBBY_WORLD_ID = 1115
    }
}

@Serializable
data class ClanChannelState(
    val clanName: String,
    val updateNum: Long,
    val talkRank: ClanRank,
    val kickRank: ClanRank,
    val chatters: List<ClanChatter>,
) {
    fun chatter(username: String): ClanChatter? = chatters.firstOrNull { it.username == username }

    operator fun contains(username: String): Boolean = chatter(username) != null
}
