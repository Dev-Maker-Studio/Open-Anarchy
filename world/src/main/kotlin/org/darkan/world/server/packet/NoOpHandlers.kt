package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import org.darkan.core.net.prot.packet.*
import org.darkan.core.net.prot.handler.PacketHandler

class MoveCameraHandler : PacketHandler<Player, MoveCamera> {
    override suspend fun handle(player: Player, packet: MoveCamera) {
        // Record-only, no gameplay effect
    }
}

class ClientFocusHandler : PacketHandler<Player, ClientFocus> {
    override suspend fun handle(player: Player, packet: ClientFocus) {
        // Record-only, no gameplay effect
    }
}

class MouseClickHandler : PacketHandler<Player, MouseClick> {
    override suspend fun handle(player: Player, packet: MouseClick) {
        // Record-only, no gameplay effect
    }
}

class MouseButtonClickHandler : PacketHandler<Player, MouseButtonClick> {
    override suspend fun handle(player: Player, packet: MouseButtonClick) {
        // Record-only, no gameplay effect
    }
}

class MoveMouseHandler : PacketHandler<Player, MoveMouseData> {
    override suspend fun handle(player: Player, packet: MoveMouseData) {
        // Record-only, no gameplay effect
    }
}

class SendPreferencesHandler : PacketHandler<Player, SendPreferences> {
    override suspend fun handle(player: Player, packet: SendPreferences) {
        // Record-only, no gameplay effect
    }
}

class ReflectionResponseHandler : PacketHandler<Player, ReflectionResponse> {
    override suspend fun handle(player: Player, packet: ReflectionResponse) {
        // Anti-cheat reflection check response - log only for now
    }
}
