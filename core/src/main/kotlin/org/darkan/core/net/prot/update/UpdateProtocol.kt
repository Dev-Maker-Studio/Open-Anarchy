package org.darkan.core.net.prot.update

interface UpdateProtocol {
    fun encodePlayerUpdate(snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState): ByteArray
    fun encodeNpcUpdate(snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState): ByteArray
}
