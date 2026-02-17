package org.darkan.core.social.gateway

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.darkan.core.EnvVars
import org.darkan.core.net.prot.*

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
            subclass(ClanChannelKickUser::class, ClanChannelKickUser.serializer())
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
            subclass(ClanChannelFull::class, ClanChannelFull.serializer())
            subclass(ClanSettingsFull::class, ClanSettingsFull.serializer())
            subclass(MessageClanChannel::class, MessageClanChannel.serializer())
            subclass(VarclanEnable::class, VarclanEnable.serializer())
            subclass(VarclanDisable::class, VarclanDisable.serializer())
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
    val pvp: Boolean = false,
    val lootShare: Boolean = true,
) {
    companion object {
        fun fromEnv(): GatewayWorldInfo = GatewayWorldInfo(
            worldId = EnvVars.worldId,
            worldName = EnvVars.worldName,
            host = EnvVars.worldHost,
            publicHost = EnvVars.worldPublicHost,
            port = EnvVars.worldPort,
            activity = EnvVars.worldActivity,
            members = EnvVars.worldMembers,
            quickChat = EnvVars.worldQuickChat,
            pvp = EnvVars.worldPvp,
            lootShare = EnvVars.worldLootShare,
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
@SerialName("WorldHelloAck")
data class WorldHelloAck(
    val ok: Boolean,
    val message: String? = null,
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
