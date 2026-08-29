package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.packet.*
import org.darkan.core.net.prot.handler.PacketHandler
import org.darkan.world.server.WorldServer

class AddFriendHandler : PacketHandler<Player, AddFriend> {
    override suspend fun handle(player: Player, packet: AddFriend) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}

class RemoveFriendHandler : PacketHandler<Player, RemoveFriend> {
    override suspend fun handle(player: Player, packet: RemoveFriend) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}

class AddIgnoreHandler : PacketHandler<Player, AddIgnoreReq> {
    override suspend fun handle(player: Player, packet: AddIgnoreReq) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}

class RemoveIgnoreHandler : PacketHandler<Player, RemoveIgnore> {
    override suspend fun handle(player: Player, packet: RemoveIgnore) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}

class FcJoinHandler : PacketHandler<Player, FcJoin> {
    override suspend fun handle(player: Player, packet: FcJoin) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}

class FcKickHandler : PacketHandler<Player, FcKick> {
    override suspend fun handle(player: Player, packet: FcKick) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}

class FcSetRankHandler : PacketHandler<Player, FcSetRank> {
    override suspend fun handle(player: Player, packet: FcSetRank) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}

class ClanChannelKickHandler : PacketHandler<Player, ClanChannelKickUser> {
    override suspend fun handle(player: Player, packet: ClanChannelKickUser) {
        WorldServer.forwardToLobby(player.username, packet)
    }
}
