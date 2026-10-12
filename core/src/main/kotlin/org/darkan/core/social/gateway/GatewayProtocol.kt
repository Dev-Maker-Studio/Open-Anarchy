package org.darkan.core.social.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.darkan.core.EnvVars
import org.darkan.core.clan.ClanAction
import org.darkan.core.clan.ClanChannelState
import org.darkan.core.model.AccountView
import org.darkan.core.model.Clan
import org.darkan.core.model.Rights
import org.darkan.core.social.ChatMessage
import org.darkan.core.social.QuickChatMessage
import org.darkan.core.social.SocialAction
import org.darkan.core.social.SocialEvent
import org.darkan.core.social.SocialJson
import org.darkan.core.worldlist.Country
import org.darkan.core.worldlist.WorldMetadata

object SocialGatewayWireJson {
    val serializersModule: SerializersModule = SerializersModule {
        include(SocialJson.serializersModule)
    }

    val json: Json = Json {
        prettyPrint = false
        coerceInputValues = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "_type"
        serializersModule = SocialGatewayWireJson.serializersModule
    }
}

@Serializable
sealed interface SocialGatewayWireMessage

@Serializable
enum class AnnouncementChannel {
    GAME,
    FRIENDS_CHAT,
}

@Serializable
sealed interface AnnouncementTarget {
    @Serializable
    @SerialName("AllWorlds")
    data object AllWorlds : AnnouncementTarget

    @Serializable
    @SerialName("World")
    data class World(val worldId: Int) : AnnouncementTarget

    @Serializable
    @SerialName("User")
    data class User(val username: String) : AnnouncementTarget

    @Serializable
    @SerialName("FriendsChat")
    data class FriendsChat(val ownerUsername: String) : AnnouncementTarget
}

@Serializable
@SerialName("AnnouncementBroadcast")
data class AnnouncementBroadcast(
    val channel: AnnouncementChannel,
    val message: String,
    val target: AnnouncementTarget = AnnouncementTarget.AllWorlds,
) : SocialGatewayWireMessage

@Serializable
@SerialName("WorldInfo")
data class GatewayWorldInfo(
    val worldId: Int,
    val worldName: String,
    val host: String,
    val publicHost: String,
    val port: Int,
    val activity: String = "",
    val members: Boolean = true,
    val quickChat: Boolean = false,
    val botsAllowed: Boolean = false,
    val lootShare: Boolean = true,
    val country: String = "USA",
    val highlighted: Boolean = false,
    val cacheCrcs: List<Int> = emptyList(),
) {
    fun toWorldMetadata(): WorldMetadata = WorldMetadata(
        number = worldId,
        ipAddress = publicHost,
        port = port,
        activity = activity.ifEmpty { worldName },
        country = Country.valueOf(country),
        quickchat = quickChat,
        lootShare = lootShare,
        members = members,
        botsAllowed = botsAllowed,
        highlighted = highlighted,
    )

    companion object {
        fun fromEnv(cacheCrcs: List<Int> = emptyList()): GatewayWorldInfo = GatewayWorldInfo(
            worldId = EnvVars.worldId,
            worldName = EnvVars.worldName,
            host = EnvVars.worldHost,
            publicHost = EnvVars.worldPublicHost,
            port = EnvVars.worldPort,
            activity = EnvVars.worldActivity,
            members = EnvVars.worldMembers,
            quickChat = EnvVars.worldQuickChat,
            botsAllowed = EnvVars.worldBotsAllowed,
            lootShare = EnvVars.worldLootShare,
            country = EnvVars.worldCountry,
            highlighted = EnvVars.worldHighlighted,
            cacheCrcs = cacheCrcs,
        )
    }
}

@Serializable
@SerialName("WorldHello")
data class WorldHello(
    val world: GatewayWorldInfo,
    val token: String,
) : SocialGatewayWireMessage

@Serializable
data class GatewayPlayerPresence(
    val username: String,
    val displayName: String,
    val rightsCrown: Int,
    val privateStatus: Int,
)

@Serializable
enum class PresenceSyncReason {
    CONNECT,

    PERIODIC,
}

@Serializable
@SerialName("WorldPresenceSnapshot")
data class WorldPresenceSnapshot(
    val players: List<GatewayPlayerPresence>,
    val reason: PresenceSyncReason,
) : SocialGatewayWireMessage

@Serializable
@SerialName("WorldHelloAck")
data class WorldHelloAck(
    val ok: Boolean,
    val message: String? = null,
) : SocialGatewayWireMessage

@Serializable
@SerialName("VerifyLoginToken")
data class VerifyLoginToken(
    val requestId: Int,
    val username: String,
    val token: String,
) : SocialGatewayWireMessage

@Serializable
enum class LoginRefusal {
    TOKEN,
    NO_ACCOUNT,
    BANNED,
    NAME_REQUIRED,
}

@Serializable
@SerialName("LoginTokenVerdict")
data class LoginTokenVerdict(
    val requestId: Int,
    val username: String? = null,
    val displayName: String? = null,
    val account: AccountView? = null,
    val refusal: LoginRefusal? = null,
) : SocialGatewayWireMessage

@Serializable
@SerialName("AccountCommand")
data class AccountCommand(
    val username: String,
    val action: AccountAction,
) : SocialGatewayWireMessage

@Serializable
sealed interface AccountAction {
    @Serializable
    @SerialName("SetRights")
    data class SetRights(val target: String, val rights: Rights) : AccountAction

    @Serializable
    @SerialName("Ban")
    data class Ban(val target: String, val until: Long) : AccountAction

    @Serializable
    @SerialName("Mute")
    data class Mute(val target: String, val until: Long) : AccountAction

    @Serializable
    @SerialName("SetFcStatus")
    data class SetFcStatus(val fcStatus: Int) : AccountAction

    companion object {
        const val PERMANENT = Long.MAX_VALUE
        const val LIFTED = 0L
        @JvmStatic
        fun days(days: Int): Long = System.currentTimeMillis() + days * 24L * 60 * 60 * 1000
    }
}

@Serializable
@SerialName("AccountUpdate")
data class AccountUpdate(val account: AccountView) : SocialGatewayWireMessage

@Serializable
@SerialName("DisplayNameLookup")
data class DisplayNameLookup(val requestId: Int, val username: String) : SocialGatewayWireMessage

@Serializable
@SerialName("DisplayNameResult")
data class DisplayNameResult(val requestId: Int, val displayName: String? = null) : SocialGatewayWireMessage

@Serializable
@SerialName("PlayerOnline")
data class PlayerOnline(
    val username: String,
    val displayName: String,
    val rightsCrown: Int,
    val privateStatus: Int,
) : SocialGatewayWireMessage

@Serializable
@SerialName("PlayerOffline")
data class PlayerOffline(
    val username: String,
) : SocialGatewayWireMessage

@Serializable
@SerialName("WindowDemand")
data class WindowDemand(val windowX: Int, val windowY: Int) : SocialGatewayWireMessage

@Serializable
@SerialName("MapDiscovered")
data class MapDiscovered(val regionIds: IntArray) : SocialGatewayWireMessage

@Serializable
@SerialName("SocialCommand")
data class SocialCommand(
    val username: String,
    val action: SocialAction,
) : SocialGatewayWireMessage

@Serializable
@SerialName("SocialNotification")
data class SocialNotification(
    val usernames: List<String>,
    val event: SocialEvent,
) : SocialGatewayWireMessage


@Serializable
@SerialName("ClanCommand")
data class ClanCommand(
    val username: String,
    val requestId: Int,
    val action: ClanAction,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanFetch")
data class ClanFetch(val clanName: String) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanCommandResult")
data class ClanCommandResult(
    val username: String,
    val requestId: Int,
    val action: String,
    val ok: Boolean,
    val message: String? = null,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanSnapshot")
data class ClanSnapshot(val clan: Clan) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanDeleted")
data class ClanDeleted(val clanName: String) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanChannelUpdate")
data class ClanChannelUpdate(val state: ClanChannelState) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanMembership")
data class ClanMembership(
    val username: String,
    val clanName: String? = null,
    val inChannel: Boolean,
    val guestChannel: String? = null,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanChatMessage")
data class ClanChatMessage(
    val clanName: String,
    val sender: String,
    val rights: Int,
    val message: ChatMessage,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ClanQuickChatMessage")
data class ClanQuickChatMessage(
    val clanName: String,
    val sender: String,
    val rights: Int,
    val message: QuickChatMessage,
) : SocialGatewayWireMessage
