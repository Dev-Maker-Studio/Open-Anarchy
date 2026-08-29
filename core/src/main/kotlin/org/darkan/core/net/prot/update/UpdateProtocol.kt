package org.darkan.core.net.prot.update

import org.darkan.core.update.PlayerAppearance
import org.darkan.core.update.PlayerAppearanceData

/**
 * Revision-specific encoder for the entity update schema.
 *
 * Implementations own every on-wire decision (bit widths, mask ordering, byte transforms); callers
 * supply only semantic snapshots.
 */
interface UpdateProtocol {
    val playerBitConfig: PlayerBitConfig
    val npcBitConfig: NpcBitConfig

    fun encodePlayerUpdate(snapshot: PlayerUpdateSnapshot, state: PlayerUpdateTrackingState): ByteArray

    fun encodeNpcUpdate(snapshot: NpcUpdateSnapshot, state: NpcUpdateTrackingState): ByteArray

    /**
     * Encodes a player's appearance block and pairs it with the hash used for per-observer
     * change detection.
     */
    fun encodePlayerAppearance(data: PlayerAppearanceData): PlayerAppearance
}
