package world.gregs.voidps.cache.definition.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index.DEFAULTS
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.data.DefaultsDefinition
import world.gregs.voidps.cache.definition.data.InputSubscriberRecord
import world.gregs.voidps.cache.definition.data.RecolourRecord
import world.gregs.voidps.cache.definition.encoder.DefaultsEncoder

class DefaultsDecoder : DefinitionDecoder<DefaultsDefinition>(DEFAULTS) {

    override fun create(size: Int) = Array(size) { DefaultsDefinition(it) }

    override fun getFile(id: Int) = 0

    override fun getArchive(id: Int) = id

    override fun DefaultsDefinition.read(opcode: Int, buffer: Reader) {
        when (id) {
            DefaultsDefinition.SCENE_CUBE_TEXTURES -> cubeTextures(opcode, buffer)
            DefaultsDefinition.ENTITY -> entity(opcode, buffer)
            DefaultsDefinition.TITLE_SCREEN_MUSIC -> titleScreenMusic(opcode, buffer)
            DefaultsDefinition.INTERACTIONS -> interactions(opcode, buffer)
            DefaultsDefinition.CUTSCENE -> cutscene(opcode, buffer)
            else -> error("Index $index archive $id has no defaults type.")
        }
    }

    private fun DefaultsDefinition.cubeTextures(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> cubeTextures = IntArray(CUBE_FACES) { buffer.readUnsignedShort() }
            else -> error("Unhandled scene cube texture defaults opcode $opcode.")
        }
    }

    private fun DefaultsDefinition.entity(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                val count = maximumHits ?: DefaultsDefinition.DEFAULT_MAXIMUM_HITS
                val x = IntArray(count)
                val y = IntArray(count)
                for (hit in 0 until count) {
                    x[hit] = buffer.readShort()
                    y[hit] = buffer.readShort()
                }
                hitOffsetsX = x
                hitOffsetsY = y
            }
            2 -> profilingMesh = buffer.readBigSmart()
            3 -> maximumHits = buffer.readUnsignedByte()
            4 -> alwaysShowContextMenu = false
            5 -> loginInterface = buffer.readUnsignedMedium()
            6 -> lobbyWindow = buffer.readUnsignedMedium()
            7 -> {
                val entries = ArrayList<RecolourRecord>(
                    DefaultsDefinition.PALETTES * DefaultsDefinition.PALETTE_COLOURS
                )
                for (palette in 0 until DefaultsDefinition.PALETTES) {
                    for (colour in 0 until DefaultsDefinition.PALETTE_COLOURS) {
                        val source = colour(buffer)
                        val destinations = IntArray(buffer.readUnsignedShort()) { colour(buffer) }
                        entries.add(RecolourRecord(source, destinations))
                    }
                }
                recolours = entries
            }
            8 -> npcMessages = false
            9 -> npcMessageDuration = buffer.readUnsignedByte()
            10 -> playerMessages = false
            11 -> playerMessageDuration = buffer.readUnsignedByte()
            12 -> {
                gameWidth = buffer.readUnsignedShort()
                gameHeight = buffer.readUnsignedShort()
            }
            13 -> maxHitbars = buffer.readUnsignedByte()
            14 -> maxVisibleHitbars = buffer.readUnsignedByte()
            15 -> hitbarHeight = buffer.readUnsignedByte()
            else -> error("Unhandled entity defaults opcode $opcode.")
        }
    }

    private fun DefaultsDefinition.titleScreenMusic(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> titleScreenMusic = buffer.readUnsignedShort()
            else -> error("Unhandled title screen music defaults opcode $opcode.")
        }
    }

    private fun DefaultsDefinition.interactions(opcode: Int, buffer: Reader) {
        when (opcode) {
            1, 2, 3, 4, 8, 9, 10 -> input(subscriber(opcode, buffer))
            5, 6, 7 -> input(InputSubscriberRecord(opcode = opcode, keys = keys(buffer)))
            11 -> minimenuCursor = true
            12 -> tooltipColourMembers = buffer.readInt()
            13 -> tooltipColourFree = buffer.readInt()
            else -> error("Unhandled interaction defaults opcode $opcode.")
        }
    }

    private fun DefaultsDefinition.cutscene(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> input(subscriber(opcode, buffer))
            else -> error("Unhandled cutscene defaults opcode $opcode.")
        }
    }

    private fun subscriber(opcode: Int, buffer: Reader): InputSubscriberRecord {
        val type = buffer.readUnsignedByte()
        return when (type) {
            InputSubscriberRecord.COMBINED -> InputSubscriberRecord(
                opcode = opcode,
                type = type,
                clickType = buffer.readUnsignedByte(),
                minimumClicks = buffer.readUnsignedByte(),
                keys = keys(buffer)
            )
            InputSubscriberRecord.KEY_PRESS -> InputSubscriberRecord(
                opcode = opcode,
                type = type,
                code = buffer.readUnsignedByte(),
                flags = buffer.readUnsignedByte()
            )
            InputSubscriberRecord.KEY_HOLD -> InputSubscriberRecord(
                opcode = opcode,
                type = type,
                keys = keys(buffer)
            )
            else -> error("Unknown input subscriber type $type under opcode $opcode.")
        }
    }

    private fun keys(buffer: Reader) = IntArray(buffer.readUnsignedByte()) { buffer.readUnsignedByte() }

    private fun DefaultsDefinition.input(record: InputSubscriberRecord) {
        val records = inputs ?: ArrayList<InputSubscriberRecord>(4).also { inputs = it }
        records.add(record)
    }

    private fun colour(buffer: Reader): Int {
        val value = buffer.readUnsignedShort()
        return if (value == NO_COLOUR) -1 else value
    }

    override fun canonicalOpcodes(definition: DefaultsDefinition): IntArray =
        DefaultsEncoder.opcodesOf(definition)

    private companion object {
        private const val CUBE_FACES = 6

        private const val NO_COLOUR = 65535
    }
}
