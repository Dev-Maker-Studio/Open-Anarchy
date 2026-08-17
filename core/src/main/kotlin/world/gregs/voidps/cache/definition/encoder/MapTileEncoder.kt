package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile

/**
 * Encodes the terrain block of a map archive (`m{regionX}_{regionY}`), the inverse of
 * `MapTileDecoder`.
 *
 * Opcode grammar, identical in the decoder and in the client (darkan-bot `MapLoader.kt`):
 *
 * ```
 * 0             end of tile, no explicit height
 * 1 <byte>      end of tile, explicit height byte
 * 2..49 <byte>  overlay: id byte follows, shape = (opcode - 2) / 4, rotation = (opcode - 2) and 3
 * 50..81        tile settings/flags = opcode - 49
 * 82..255       underlay id = opcode - 81
 * ```
 *
 * Opcode 1 **terminates** the tile record after its height byte - it is not a field opcode followed by a
 * separate 0 terminator. Emitting both desynchronises the whole 4 * 64 * 64 stream, because the following
 * tile consumes the stray 0 as its own terminator, so every later tile decodes as some other tile's data.
 *
 * ## Height, and the one value that is not what it looks like
 *
 * A tile with [MapTile.height] `0` encodes as opcode 0, meaning "no explicit height", and the client derives
 * the height procedurally (level 0) or from the level below (levels 1-3). The client also folds an explicit
 * height byte of `1` to `0` (`MapLoader.kt`: `if (heightDelta == 1) heightDelta = 0`), which is how the
 * shipped cache stores a deliberately flat tile - all 2,441,032 explicit-height tiles that use the value 0
 * are stored as byte `1`, and no shipped tile stores an explicit `0`. So:
 *
 * - `height = 0` means "let the client derive it" and writes no height record.
 * - `height = 1` means "explicitly flat" and is what an editor should write for a flat tile.
 *
 * Consequently a tile that carries an explicit height byte of `0` (which the shipped cache never does) loses
 * its height record here. That is the only tile field this encoder cannot represent.
 *
 * ## Fidelity
 *
 * Output is semantically identical to the input, not byte-identical to the shipped archive: the shipped files
 * order the field opcodes differently (overlay before settings) and contain redundant records, so re-encoding
 * a region rewrites every byte of its terrain block even if nothing was edited. Verified over all 2407 map
 * archives (39,436,288 tiles) by `MapEncoderRoundTripTest`: decoding the output reproduces every tile field
 * for 2407/2407 archives, while only 79/2407 come out byte-identical. If byte-identity is required (to prove
 * an edit touched nothing else), keep a byte-faithful echo of the original block and merge edits into it, the
 * way `tools` does for map environments with `EnvironmentLayout`.
 */
class MapTileEncoder : DefinitionEncoder<MapDefinition> {

    override fun Writer.encode(definition: MapDefinition) {
        // An unset id means the caller never populated the definition; writing the resulting empty block
        // would silently produce a valid looking archive that decodes to a blank region.
        require(definition.id != -1) { "Map definition has no region id, refusing to encode a blank terrain block." }
        for (level in 0 until 4) {
            for (localX in 0 until 64) {
                for (localY in 0 until 64) {
                    val tile = definition.getTile(localX, localY, level)
                    require(tile.underlayId in 0..MAX_UNDERLAY) { describe(definition, localX, localY, level, "underlay ${tile.underlayId} is not in 0..$MAX_UNDERLAY") }
                    require(tile.settings in 0..MAX_SETTINGS) { describe(definition, localX, localY, level, "settings ${tile.settings} is not in 0..$MAX_SETTINGS") }
                    require(consistent(tile)) { describe(definition, localX, localY, level, "overlay opcode ${tile.attrOpcode} disagrees with shape ${tile.overlayPath} rotation ${tile.overlayRotation}") }
                    if (tile.underlayId != 0) {
                        writeByte(tile.underlayId + 81)
                    }
                    if (tile.settings != 0) {
                        writeByte(tile.settings + 49)
                    }
                    val opcode = overlayOpcode(tile)
                    if (opcode != 0) {
                        require(opcode in 2..49) { describe(definition, localX, localY, level, "overlay shape ${tile.overlayPath} rotation ${tile.overlayRotation} needs opcode $opcode, outside 2..49") }
                        writeByte(opcode)
                        writeByte(tile.overlayId)
                    }
                    // Opcode 1 is itself the terminator - no trailing 0 may follow it.
                    if (tile.height != 0) {
                        writeByte(1)
                        writeByte(tile.height)
                    } else {
                        writeByte(0)
                    }
                }
            }
        }
    }

    /**
     * The overlay opcode to emit, or 0 for no overlay record.
     *
     * [MapTile.attrOpcode] is the opcode the tile was decoded from and is preferred so decoded tiles
     * re-encode byte for byte. Tiles built elsewhere (or by hand) only carry the unpacked
     * [MapTile.overlayPath] / [MapTile.overlayRotation], so the opcode is rebuilt from those instead -
     * without this, an overlay authored outside the decoder would be dropped silently.
     *
     * Editing a decoded tile's shape or rotation therefore has to update [MapTile.attrOpcode] too (or clear
     * it and let it be rebuilt); a stale opcode would otherwise win and write back the old shape. Rather
     * than pick a winner, a disagreement is rejected.
     */
    private fun overlayOpcode(tile: MapTile): Int = when {
        tile.attrOpcode != 0 -> tile.attrOpcode
        tile.overlayId == 0 && tile.overlayPath == 0 && tile.overlayRotation == 0 -> 0
        else -> 2 + (tile.overlayPath * 4) + tile.overlayRotation
    }

    /** Whether [MapTile.attrOpcode] still describes the tile's current shape and rotation. */
    private fun consistent(tile: MapTile) = tile.attrOpcode == 0 ||
        ((tile.attrOpcode - 2) / 4 == tile.overlayPath && (tile.attrOpcode - 2) and 3 == tile.overlayRotation)

    private fun describe(definition: MapDefinition, localX: Int, localY: Int, level: Int, message: String) =
        "Map ${definition.id} tile ($localX, $localY, $level): $message"

    private companion object {
        /** Underlay ids are carried by opcodes 82..255. */
        private const val MAX_UNDERLAY = 174

        /** Settings are carried by opcodes 50..81. */
        private const val MAX_SETTINGS = 32
    }
}
