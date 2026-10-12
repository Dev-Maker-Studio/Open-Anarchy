package org.darkan.core.clan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.darkan.core.model.ClanRank
import org.darkan.core.model.ClanSettingValueSerializer
import org.darkan.core.social.ChatMessage
import org.darkan.core.social.QuickChatMessage

@Serializable
sealed interface ClanAction {

    val serialName: String
        get() = when (this) {
            is CheckName -> "CheckName"
            is Create -> "Create"
            is Leave -> "Leave"
            is AcceptInvite -> "AcceptInvite"
            is RemoveMember -> "RemoveMember"
            is UpdateMember -> "UpdateMember"
            is Ban -> "Ban"
            is Unban -> "Unban"
            is UpdateSettings -> "UpdateSettings"
            is SetChannelRanks -> "SetChannelRanks"
            is SetVar -> "SetVar"
            is JoinChannel -> "JoinChannel"
            is LeaveChannel -> "LeaveChannel"
            is JoinGuestChannel -> "JoinGuestChannel"
            is LeaveGuestChannel -> "LeaveGuestChannel"
            is KickFromChannel -> "KickFromChannel"
            is Chat -> "Chat"
            is QuickChat -> "QuickChat"
        }

    @Serializable
    @SerialName("CheckName")
    data class CheckName(val name: String) : ClanAction

    @Serializable
    @SerialName("Create")
    data class Create(val name: String) : ClanAction

    @Serializable
    @SerialName("Leave")
    data object Leave : ClanAction

    @Serializable
    @SerialName("AcceptInvite")
    data class AcceptInvite(val clanName: String, val inviter: String) : ClanAction

    @Serializable
    @SerialName("RemoveMember")
    data class RemoveMember(val target: String) : ClanAction

    @Serializable
    @SerialName("UpdateMember")
    data class UpdateMember(
        val target: String,
        val rank: ClanRank? = null,
        val job: Int? = null,
        val banFromKeep: Boolean? = null,
        val banFromCitadel: Boolean? = null,
        val banFromIsland: Boolean? = null,
    ) : ClanAction

    @Serializable
    @SerialName("Ban")
    data class Ban(val target: String) : ClanAction

    @Serializable
    @SerialName("Unban")
    data class Unban(val target: String) : ClanAction

    @Serializable
    @SerialName("UpdateSettings")
    data class UpdateSettings(
        val settings: Map<String, @Serializable(with = ClanSettingValueSerializer::class) Any>,
    ) : ClanAction

    @Serializable
    @SerialName("SetChannelRanks")
    data class SetChannelRanks(val talkRank: ClanRank? = null, val kickRank: ClanRank? = null) : ClanAction

    @Serializable
    @SerialName("SetVar")
    data class SetVar(val id: Int, val value: @Serializable(with = ClanSettingValueSerializer::class) Any) : ClanAction

    @Serializable
    @SerialName("JoinChannel")
    data object JoinChannel : ClanAction

    @Serializable
    @SerialName("LeaveChannel")
    data object LeaveChannel : ClanAction

    @Serializable
    @SerialName("JoinGuestChannel")
    data class JoinGuestChannel(val clanName: String) : ClanAction

    @Serializable
    @SerialName("LeaveGuestChannel")
    data object LeaveGuestChannel : ClanAction

    @Serializable
    @SerialName("KickFromChannel")
    data class KickFromChannel(val target: String, val guest: Boolean) : ClanAction

    @Serializable
    @SerialName("Chat")
    data class Chat(val guest: Boolean, val message: ChatMessage) : ClanAction

    @Serializable
    @SerialName("QuickChat")
    data class QuickChat(val guest: Boolean, val message: QuickChatMessage) : ClanAction
}
