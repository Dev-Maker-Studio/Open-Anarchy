package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject

/**
 * Encodes the object block of a map archive (`l{regionX}_{regionY}`), the inverse of `MapObjectDecoder`.
 *
 * The block is a list of object id groups, each group a list of positions:
 *
 * ```
 * <sum>                     id delta from the previous group, 0 ends the block
 *   <smart>                 position delta + 1 within the region, 0 ends the group
 *   <byte>                  shape shl 2 or rotation
 * ```
 *
 * `sum` is the client's `Packet.readSum`: unsigned smarts accumulated for as long as each part is 32767, so
 * deltas above 32766 are written as repeated 32767 parts followed by the remainder. Positions are the packed
 * `(level shl 12) or (localX shl 6) or localY` index and reset to 0 at the start of every group.
 *
 * ## Coordinates are region local
 *
 * [MapObject.x] and [MapObject.y] must be **local** 0..63 tile coordinates, not world coordinates. Several
 * callers in this repository (`RegionLoader`) hand back [MapObject] carrying world coordinates; encoding
 * those would pack garbage into the position index, so they are rejected here rather than silently written.
 *
 * ## Bridges
 *
 * The decoder stores objects at their original level, so an unmodified decode round trips. Anything that
 * shifts objects between levels for rendering (bridge / "link below" handling) must be undone before
 * encoding, or the shifted level is what gets written.
 *
 * ## Fidelity
 *
 * Output is semantically identical to the input, not necessarily byte-identical: objects are regrouped and
 * sorted, and the shipped files do not always use that order. Verified over every decryptable object archive
 * by `MapEncoderRoundTripTest`: the decoded object set is reproduced for 2399/2399 archives, 2351 of which
 * are also byte-identical.
 */
class MapObjectEncoder : DefinitionEncoder<MapDefinition> {

    override fun Writer.encode(definition: MapDefinition) {
        // An unset id means the caller never populated the definition; an empty block is a valid looking
        // archive that decodes to a region with no objects at all.
        require(definition.id != -1) { "Map definition has no region id, refusing to encode a blank object block." }
        var id = -1
        definition.objects.groupBy { it.id }.toList().sortedBy { it.first }.forEach { (objectId, objects) ->
            require(objectId >= 0) { "Map ${definition.id}: object id $objectId is negative." }
            writeSum(objectId - id)
            id = objectId
            var location = 0
            objects.sortedBy { index(definition, it) }.forEach { loc ->
                val local = index(definition, loc)
                writeSmart(local - location + 1)
                location = local
                require(loc.shape in 0..31) { "Map ${definition.id}: object $objectId at (${loc.x}, ${loc.y}, ${loc.level}) has shape ${loc.shape}, outside 0..31." }
                writeByte(loc.rotation or (loc.shape shl 2))
            }
            writeSmart(0)
        }
        writeSmart(0)
    }

    private fun index(definition: MapDefinition, loc: MapObject): Int {
        require(loc.x in 0..63 && loc.y in 0..63) {
            "Map ${definition.id}: object ${loc.id} at (${loc.x}, ${loc.y}) is not a region local coordinate, expected 0..63 - world coordinates cannot be encoded."
        }
        return MapDefinition.index(loc.x, loc.y, loc.level)
    }

    /**
     * Writes [value] the way the client's `Packet.readSum` reads it: 32767 parts until the remainder is
     * below 32767. A value that is an exact multiple of 32767 still needs the trailing remainder part,
     * otherwise the reader keeps chaining into the next field.
     */
    private fun Writer.writeSum(value: Int) {
        var remaining = value
        while (remaining >= 32767) {
            writeSmart(32767)
            remaining -= 32767
        }
        writeSmart(remaining)
    }
}
