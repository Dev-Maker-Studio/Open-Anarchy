package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.GAME_TIPS
import world.gregs.voidps.cache.definition.data.GameTipDefinition
import world.gregs.voidps.cache.definition.data.GameTipElementRecord
import world.gregs.voidps.cache.definition.data.GameTipStageRecord
import world.gregs.voidps.cache.definition.data.GameTipUpdateRecord

class GameTipDecoder : DefinitionDecoder<GameTipDefinition>(GAME_TIPS) {

    override fun create(size: Int) = Array(size) { GameTipDefinition(it) }

    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun getArchive(id: Int) = id

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: GameTipDefinition, buffer: Reader) {
        definition.read(-1, buffer)
    }

    override fun GameTipDefinition.read(opcode: Int, buffer: Reader) {
        if (id == MASTER) {
            readStageTable(buffer)
        } else {
            readScreen(buffer)
        }
    }

    private fun GameTipDefinition.readStageTable(buffer: Reader) {
        version = buffer.readUnsignedByte()
        val renderTypeCount = buffer.readUnsignedByte()
        renderTypes = IntArray(renderTypeCount) { buffer.readUnsignedByte() }
        val definedStages = buffer.readUnsignedByte()
        definedStageCount = definedStages
        totalStageCount = buffer.readUnsignedByte()
        tipsFileId = buffer.readShort()
        displayDuration = buffer.readUnsignedMedium()
        timeBetweenUpdates = buffer.readUnsignedShort()
        stages = List(definedStages) {
            val stage = buffer.readUnsignedByte()
            val hasUpdates = buffer.readUnsignedByte()
            val updateCount = buffer.readUnsignedShort()
            GameTipStageRecord(
                stage,
                hasUpdates,
                List(updateCount) {
                    GameTipUpdateRecord(
                        buffer.readUnsignedShort(),
                        buffer.readUnsignedMedium(),
                        buffer.readUnsignedShort()
                    )
                }
            )
        }
    }

    private fun GameTipDefinition.readScreen(buffer: Reader) {
        val count = buffer.readUnsignedByte()
        elements = List(count) { readElement(buffer) }
    }

    private fun readElement(buffer: Reader): GameTipElementRecord {
        val element = GameTipElementRecord(buffer.readUnsignedByte())
        val ids = BigSmarts(buffer)
        when (element.type) {
            BACKGROUND -> element.colour = buffer.readInt()
            COLOR_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.fillColour = buffer.readInt()
                element.outlineColour = buffer.readInt()
            }
            GRAPHIC_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.fillColour = buffer.readInt()
                element.backgroundColour = buffer.readInt()
                element.graphicFileId = ids.read()
            }
            MULTI_GRAPHIC_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.readBarGraphics(ids)
            }
            OUTLINED -> {
                element.newsItemId = buffer.readUnsignedByte()
                element.anchorX = buffer.readUnsignedByte()
                element.anchorY = buffer.readUnsignedByte()
                element.offsetX = buffer.readShort()
                element.offsetY = buffer.readShort()
                element.width = buffer.readUnsignedShort()
                element.height = buffer.readUnsignedShort()
                element.textColour = buffer.readInt()
                element.shadeColour = buffer.readInt()
                element.backgroundColour = buffer.readInt()
                element.drawOutline = buffer.readUnsignedByte()
            }
            GRAPHIC -> element.readGraphic(buffer, ids)
            ROTATED_GRAPHIC -> {
                element.readGraphic(buffer, ids)
                element.angle = buffer.readShort()
            }
            ANCHORED_TEXT -> {
                element.tipText = buffer.readString()
                element.anchorX = buffer.readUnsignedByte()
                element.anchorY = buffer.readUnsignedByte()
                element.offsetX = buffer.readShort()
                element.offsetY = buffer.readShort()
                element.textAlign = buffer.readUnsignedByte()
                element.textVAlign = buffer.readUnsignedByte()
                element.lineSpacing = buffer.readUnsignedByte()
                element.width = buffer.readUnsignedShort()
                element.height = buffer.readUnsignedShort()
                element.fileId = ids.read()
                element.textColour = buffer.readInt()
                element.textShadowColour = buffer.readInt()
            }
            TIPS_GRAPHIC -> element.graphicFileId = ids.read()
            ANIMATED_LOADING_BAR -> {
                element.readAnchored(buffer, ids)
                element.readBarGraphics(ids)
                element.scrollSpeed = buffer.readShort()
            }
            else -> throw IllegalArgumentException("Unknown game tip element type ${element.type}.")
        }
        element.wideFileIds = ids.wide()
        return element
    }

    private fun GameTipElementRecord.readAnchored(buffer: Reader, ids: BigSmarts) {
        anchorX = buffer.readUnsignedByte()
        anchorY = buffer.readUnsignedByte()
        offsetX = buffer.readShort()
        offsetY = buffer.readShort()
        width = buffer.readUnsignedShort()
        height = buffer.readUnsignedShort()
        textOffsetY = buffer.readShort()
        fileId = ids.read()
        textColour = buffer.readInt()
    }

    private fun GameTipElementRecord.readBarGraphics(ids: BigSmarts) {
        fillGraphic = ids.read()
        emptyGraphic = ids.read()
        leftCapGraphic = ids.read()
        rightCapGraphic = ids.read()
        topBorderGraphic = ids.read()
        bottomBorderGraphic = ids.read()
    }

    private fun GameTipElementRecord.readGraphic(buffer: Reader, ids: BigSmarts) {
        graphicId = ids.read()
        anchorX = buffer.readUnsignedByte()
        anchorY = buffer.readUnsignedByte()
        offsetX = buffer.readShort()
        offsetY = buffer.readShort()
    }

    private class BigSmarts(private val buffer: Reader) {

        private var mask = 0

        private var index = 0

        fun read(): Int {
            val start = buffer.position()
            val value = buffer.readBigSmart()
            if (buffer.position() - start == WIDE_BYTES && value in 0..NARROW_MAX) {
                mask = mask or (1 shl index)
            }
            index++
            return value
        }

        fun wide() = if (mask == 0) null else mask
    }

    private companion object {
        const val MASTER = 0

        const val BACKGROUND = 0
        const val COLOR_LOADING_BAR = 1
        const val GRAPHIC_LOADING_BAR = 2
        const val MULTI_GRAPHIC_LOADING_BAR = 3
        const val OUTLINED = 4
        const val GRAPHIC = 5
        const val ROTATED_GRAPHIC = 6
        const val ANCHORED_TEXT = 7
        const val TIPS_GRAPHIC = 8
        const val ANIMATED_LOADING_BAR = 9

        const val WIDE_BYTES = 4
        const val NARROW_MAX = 32766
    }
}
