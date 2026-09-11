package org.darkan.core.social.gateway

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.darkan.core.EnvVars
import org.darkan.core.clan.ClanAction
import org.darkan.core.clan.ClanChannelState
import org.darkan.core.model.Clan
import org.darkan.core.net.prot.*
import org.darkan.core.net.prot.packet.*
import org.darkan.core.social.ChatMessage
import org.darkan.core.social.PublicChatMessage
import org.darkan.core.social.QuickChatMessage
import org.darkan.core.worldlist.Country
import org.darkan.core.worldlist.WorldMetadata

/**
 * SocialGateway WebSocket wire protocol.
 *
 * Requirement: user-originated communications are forwarded in the same decoded packet format
 * (ClientProt/ServerProt data classes), JSON-serialized over the websocket.
 */
object SocialGatewayWireJson {
    val serializersModule: SerializersModule = SerializersModule {
        polymorphic(ClientProt::class) {
            subclass(AddFriend::class, AddFriend.serializer())
            subclass(RemoveFriend::class, RemoveFriend.serializer())
            subclass(AddIgnoreReq::class, AddIgnoreReq.serializer())
            subclass(RemoveIgnore::class, RemoveIgnore.serializer())
            subclass(ChatSetFilter::class, ChatSetFilter.serializer())
            subclass(ChatType::class, ChatType.serializer())
            subclass(Chat::class, Chat.serializer())
            subclass(PrivateMessage::class, PrivateMessage.serializer())
            subclass(FcJoin::class, FcJoin.serializer())
            subclass(FcKick::class, FcKick.serializer())
            subclass(FcSetRank::class, FcSetRank.serializer())
            subclass(QuickChatPublic::class, QuickChatPublic.serializer())
            subclass(QuickChatPrivate::class, QuickChatPrivate.serializer())
        }
        polymorphic(ServerProt::class) {
            subclass(FriendStatus::class, FriendStatus.serializer())
            subclass(FriendlistLoaded::class, FriendlistLoaded.serializer())
            subclass(UpdateIgnoreList::class, UpdateIgnoreList.serializer())
            subclass(FriendsChatChannel::class, FriendsChatChannel.serializer())
            subclass(MessagePrivate::class, MessagePrivate.serializer())
            subclass(MessagePrivateEcho::class, MessagePrivateEcho.serializer())
            subclass(MessageFriendsChat::class, MessageFriendsChat.serializer())
            subclass(GameMessage::class, GameMessage.serializer())
            subclass(ChatFilterSettingsPrivateChat::class, ChatFilterSettingsPrivateChat.serializer())
            subclass(MessageQuickChatPrivate::class, MessageQuickChatPrivate.serializer())
            subclass(MessageQuickChatPrivateEcho::class, MessageQuickChatPrivateEcho.serializer())
            subclass(MessageQuickChatFriendsChat::class, MessageQuickChatFriendsChat.serializer())
        }
        polymorphic(ChatMessage::class) {
            subclass(PublicChatMessage::class, PublicChatMessage.serializer())
            subclass(QuickChatMessage::class, QuickChatMessage.serializer())
        }
    }

    val json: Json = Json {
        prettyPrint = false
        coerceInputValues = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        // IMPORTANT: cannot use "type" because some decoded packets contain a "type" field (e.g. ClientProt ChatType),
        // which collides with kotlinx-serialization polymorphism discriminator.
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

/** One player a world currently has in game, as the world sees them. */
@Serializable
data class GatewayPlayerPresence(
    val username: String,
    val displayName: String,
    val rightsCrown: Int,
    val privateStatus: Int,
)

/** Why a world is sending its online list; it decides how much the lobby pushes back. */
@Serializable
enum class PresenceSyncReason {
    /**
     * The socket has just come up. Either side may have restarted, so the lobby rebuilds its
     * presence for this world from scratch and re-sends every listed player the social state
     * their client needs - friend list, ignores, chat filter, friends chat and clan channels -
     * without anybody having to relog.
     */
    CONNECT,

    /**
     * A periodic drift check. The lobby only applies the difference: players it did not know
     * about come online, players it still thought were here go offline, and nobody else is
     * disturbed.
     */
    PERIODIC,
}

/**
 * Everyone a world currently has online, which is the authority for that world's presence. Sent
 * immediately after the [WorldHello] handshake and again on a timer, so a lobby restart, a world
 * restart or a dropped socket can never leave the two sides disagreeing about who is where.
 */
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
@SerialName("LoginTokenVerdict")
data class LoginTokenVerdict(
    val requestId: Int,
    val username: String? = null,
    val displayName: String? = null,
) : SocialGatewayWireMessage

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
@SerialName("ClientPacket")
data class ClientPacketEnvelope(
    val username: String,
    @Polymorphic val packet: ClientProt,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ServerPacket")
data class ServerPacketEnvelope(
    val username: String,
    @Polymorphic val packet: ServerProt,
) : SocialGatewayWireMessage

@Serializable
@SerialName("ServerPacketBatch")
data class ServerPacketBatch(
    val usernames: List<String>,
    @Polymorphic val packet: ServerProt,
) : SocialGatewayWireMessage

// ─── FC Settings Update ─────────────────────────────────────────────

@Serializable
@SerialName("FcSettingsUpdate")
data class FcSettingsUpdate(
    val username: String,
    val name: String?,
    val rankToEnter: Int,
    val rankToSpeak: Int,
    val rankToKick: Int,
    val rankToLS: Int,
) : SocialGatewayWireMessage

// ─── Clans (docs/clans.md) ───────────────────────────────────────────

/**
 * World → lobby: the player [username] asks for [action]. [requestId] is world-local and is
 * echoed in the [ClanCommandResult] so the world can run the callback it kept for it.
 */
@Serializable
@SerialName("ClanCommand")
data class ClanCommand(
    val username: String,
    val requestId: Int,
    val action: ClanAction,
) : SocialGatewayWireMessage

/** World → lobby: send me [clanName] as a [ClanSnapshot], or [ClanDeleted] if it does not exist. */
@Serializable
@SerialName("ClanFetch")
data class ClanFetch(val clanName: String) : SocialGatewayWireMessage

/**
 * Lobby → world: the outcome of one [ClanCommand]. [action] is the [ClanAction.serialName] of
 * the request so a world can act without keeping it; [message] is shown to the player verbatim
 * when present.
 */
@Serializable
@SerialName("ClanCommandResult")
data class ClanCommandResult(
    val username: String,
    val requestId: Int,
    val action: String,
    val ok: Boolean,
    val message: String? = null,
) : SocialGatewayWireMessage

/**
 * Lobby → world: the whole clan document. Broadcast to every world on every change and sent in
 * answer to a [ClanFetch]; worlds replace their copy wholesale.
 */
@Serializable
@SerialName("ClanSnapshot")
data class ClanSnapshot(val clan: Clan) : SocialGatewayWireMessage

/** Lobby → world: the clan no longer exists. */
@Serializable
@SerialName("ClanDeleted")
data class ClanDeleted(val clanName: String) : SocialGatewayWireMessage

/** Lobby → world: a channel's chatter list. Broadcast to every world on every change. */
@Serializable
@SerialName("ClanChannelUpdate")
data class ClanChannelUpdate(val state: ClanChannelState) : SocialGatewayWireMessage

/**
 * Lobby → world: the authoritative clan fields of one player's `Social`, sent to their world
 * whenever they change and on world connect, so the world's own account saves never clobber
 * them. [inChannel] is `connectedToClan`, [guestChannel] is `guestedClanChat`.
 */
@Serializable
@SerialName("ClanMembership")
data class ClanMembership(
    val username: String,
    val clanName: String? = null,
    val inChannel: Boolean,
    val guestChannel: String? = null,
) : SocialGatewayWireMessage

/**
 * Lobby → world: one line said in [clanName]'s channel by [sender] (a display name) whose
 * rights crown is [rights]. Sent to every world holding a chatter of that channel; the world
 * delivers it to its local chatters with `guest = false` for members and `guest = true` for
 * guests of that clan.
 */
@Serializable
@SerialName("ClanChatMessage")
data class ClanChatMessage(
    val clanName: String,
    val sender: String,
    val rights: Int,
    val message: ChatMessage,
) : SocialGatewayWireMessage

/** Lobby → world: the quick-chat form of [ClanChatMessage]. */
@Serializable
@SerialName("ClanQuickChatMessage")
data class ClanQuickChatMessage(
    val clanName: String,
    val sender: String,
    val rights: Int,
    val message: QuickChatMessage,
) : SocialGatewayWireMessage
