package org.darkan.core.net.prot.update

import org.darkan.core.update.PlayerAppearance
import org.darkan.core.update.PlayerAppearanceData

interface UpdateProtocol {
    val playerBitConfig: PlayerBitConfig
    val npcBitConfig: NpcBitConfig

    fun encodePlayerUpdate(snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState): ByteArray

    fun encodeNpcUpdate(snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState): ByteArray

    fun encodePlayerAppearance(data: PlayerAppearanceData): PlayerAppearance

    fun decodePlayerUpdate(data: ByteArray, state: PlayerUpdateClientState): PlayerUpdateFrame

    fun decodeNpcUpdate(
        data: ByteArray,
        state: NpcUpdateClientState,
        localTileHash30: Int,
        largeSceneView: Boolean,
    ): NpcUpdateFrame

    fun decodePlayerAppearance(data: ByteArray, layout: AppearanceLayout): PlayerAppearanceData
}
