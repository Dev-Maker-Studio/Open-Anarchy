package org.darkan.world.server.packet

import com.rs.game.model.entity.player.Player
import com.rs.utils.record.impl.Click
import com.rs.utils.record.impl.ClickHW
import com.rs.utils.record.impl.Focus
import org.darkan.core.net.prot.packet.*
import org.darkan.core.net.prot.handler.PacketHandler

class MoveCameraHandler : PacketHandler<Player, MoveCamera> {
    override suspend fun handle(player: Player, packet: MoveCamera) {
    }
}

class ClientFocusHandler : PacketHandler<Player, ClientFocus> {
    override suspend fun handle(player: Player, packet: ClientFocus) {
        player.recorder.record(Focus(System.currentTimeMillis(), packet.focused))
    }
}

class MouseClickHandler : PacketHandler<Player, MouseClick> {
    override suspend fun handle(player: Player, packet: MouseClick) {
        player.recorder.record(Click(System.currentTimeMillis(), packet.time, packet.x, packet.y, packet.mouseButton))
    }
}

class MouseButtonClickHandler : PacketHandler<Player, MouseButtonClick> {
    override suspend fun handle(player: Player, packet: MouseButtonClick) {
        player.recorder.record(ClickHW(System.currentTimeMillis(), packet.time, packet.x, packet.y, packet.mouseButton, packet.hw))
    }
}

class MoveMouseHandler : PacketHandler<Player, MoveMouseData> {
    override suspend fun handle(player: Player, packet: MoveMouseData) {
        player.refreshIdleTime()
    }
}

class SendPreferencesHandler : PacketHandler<Player, SendPreferences> {
    override suspend fun handle(player: Player, packet: SendPreferences) {
    }
}

class ReflectionResponseHandler : PacketHandler<Player, ReflectionResponse> {
    override suspend fun handle(player: Player, packet: ReflectionResponse) {
    }
}

class TransmitvarVerifyIdHandler : PacketHandler<Player, TransmitvarVerifyId> {
    override suspend fun handle(player: Player, packet: TransmitvarVerifyId) {
    }
}

class SoundEffectMusicEndedHandler : PacketHandler<Player, SoundEffectMusicEnded> {
    override suspend fun handle(player: Player, packet: SoundEffectMusicEnded) {
    }
}
