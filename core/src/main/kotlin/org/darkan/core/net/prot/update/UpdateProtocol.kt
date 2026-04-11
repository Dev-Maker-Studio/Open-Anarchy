package org.darkan.core.net.prot.update

interface UpdateProtocol {
    val playerBitConfig: PlayerBitConfig
    val npcBitConfig: NpcBitConfig

    fun encodePlayerUpdate(snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState): ByteArray
    fun encodeNpcUpdate(snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState): ByteArray
}
