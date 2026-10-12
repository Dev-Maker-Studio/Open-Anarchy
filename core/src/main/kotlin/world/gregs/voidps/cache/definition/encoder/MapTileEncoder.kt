package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapTile
import world.gregs.voidps.cache.definition.encoder.MapEffectEncoder.writeEffects

class MapTileEncoder : DefinitionEncoder<MapDefinition> {

    override fun Writer.encode(definition: MapDefinition) {
        require(definition.id != -1) { "Map definition has no region id, refusing to encode a blank terrain block." }
        require(definition.levels in 0..MapDefinition.LEVELS) {
            "Map ${definition.id} claims ${definition.levels} levels, outside 0..${MapDefinition.LEVELS}."
        }
        for (level in 0 until definition.levels) {
            for (localX in 0 until MapDefinition.SIZE) {
                for (localY in 0 until MapDefinition.SIZE) {
                    val tile = definition.getTile(localX, localY, level)
                    require(tile.underlayId in 0..MAX_UNDERLAY) { describe(definition, localX, localY, level, "underlay ${tile.underlayId} is not in 0..$MAX_UNDERLAY") }
                    require(tile.settings in 0..MAX_SETTINGS) { describe(definition, localX, localY, level, "settings ${tile.settings} is not in 0..$MAX_SETTINGS") }
                    require(consistent(tile)) { describe(definition, localX, localY, level, "overlay opcode ${tile.attrOpcode} disagrees with shape ${tile.overlayPath} rotation ${tile.overlayRotation}") }
                    val opcode = overlayOpcode(tile)
                    if (opcode != 0) {
                        require(opcode in 2..49) { describe(definition, localX, localY, level, "overlay shape ${tile.overlayPath} rotation ${tile.overlayRotation} needs opcode $opcode, outside 2..49") }
                        writeByte(opcode)
                        writeByte(tile.overlayId)
                    }
                    if (tile.settings != 0) {
                        writeByte(tile.settings + 49)
                    }
                    if (tile.underlayId != 0) {
                        writeByte(tile.underlayId + 81)
                    }
                    if (tile.height != 0) {
                        writeByte(1)
                        writeByte(tile.height)
                    } else {
                        writeByte(0)
                    }
                }
            }
        }
        writeEffects(definition.effects)
    }

    private fun overlayOpcode(tile: MapTile): Int = when {
        tile.attrOpcode != 0 -> tile.attrOpcode
        tile.overlayId == 0 && tile.overlayPath == 0 && tile.overlayRotation == 0 -> 0
        else -> 2 + (tile.overlayPath * 4) + tile.overlayRotation
    }

    private fun consistent(tile: MapTile) = tile.attrOpcode == 0 ||
        ((tile.attrOpcode - 2) / 4 == tile.overlayPath && (tile.attrOpcode - 2) and 3 == tile.overlayRotation)

    private fun describe(definition: MapDefinition, localX: Int, localY: Int, level: Int, message: String) =
        "Map ${definition.id} tile ($localX, $localY, $level): $message"

    private companion object {
        private const val MAX_UNDERLAY = 174

        private const val MAX_SETTINGS = 32
    }
}
