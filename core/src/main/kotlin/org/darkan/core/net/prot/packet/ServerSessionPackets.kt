package org.darkan.core.net.prot.packet

import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.dto.WorldListSnapshot

@JvmInline value class LogoutLobby(val dummy: Int = 0) : ServerProt

@JvmInline value class LogoutFull(val dummy: Int = 0) : ServerProt

data class OpenUrl(val url: String, val displayUrl: String? = null) : ServerProt

@JvmInline value class OpenSocialNetworkUrl(val url: String) : ServerProt

@JvmInline value class WindowCallWithArgs(val args: String) : ServerProt

data class WorldListPacket(
    val worlds: WorldListSnapshot,
    val refreshOnClient: Boolean,
    val refreshOnlyPlayerCounts: Boolean,
) : ServerProt

@JvmInline value class IdentifyHostName(val ip: Int) : ServerProt

@JvmInline value class UpdateRebootTimer(val seconds: Int) : ServerProt

data class RequestFps(val key: Int, val flags: Int) : ServerProt

@JvmInline value class UpdateSitesettingsCookie(val dummy: Int = 0) : ServerProt

class Pong : ServerProt

@JvmInline value class KeepAlive(val dummy: Int = 0) : ServerProt

data class QuickHopWorlds(val host: String, val port: Int, val reconnecting: Boolean) : ServerProt
