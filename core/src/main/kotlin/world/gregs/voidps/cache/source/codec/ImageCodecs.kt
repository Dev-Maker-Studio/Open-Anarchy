package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.FontDefinition
import world.gregs.voidps.cache.definition.data.TextureDefinition
import world.gregs.voidps.cache.definition.decoder.FontDecoder
import world.gregs.voidps.cache.definition.decoder.TextureDecoder
import world.gregs.voidps.cache.definition.encoder.FontEncoder
import world.gregs.voidps.cache.definition.encoder.TextureEncoder
import world.gregs.voidps.cache.source.IndexNames

object ImageCodecs {

    val indices: IntArray = intArrayOf(
        Index.GRAPHICS,
        Index.TEXTURES,
        Index.FONT_METRICS,
        Index.TEXTURE_DEFINITIONS,
        Index.LOADING_GRAPHICS,
        Index.LOADING_GRAPHICS_RAW
    )

    val textures: DefinitionJsonCodec<TextureDefinition> = definition("json-texture", TextureDecoder(), TextureEncoder())

    val fonts: DefinitionJsonCodec<FontDefinition> = definition("json-fontmetrics", FontDecoder(), FontEncoder())

    fun register() {
        for (index in intArrayOf(Index.GRAPHICS, Index.LOADING_GRAPHICS, Index.LOADING_GRAPHICS_RAW)) {
            SourceCodecs.builtin(index, GraphicCodec)
        }
        SourceCodecs.builtin(Index.TEXTURES, textures)
        SourceCodecs.builtin(Index.FONT_METRICS, fonts)
        SourceCodecs.builtin(Index.TEXTURE_DEFINITIONS, MaterialCodec)
    }

    fun directory(index: Int): String = IndexNames.name(index)

    private fun definition(
        id: String,
        decoder: TextureDecoder,
        encoder: TextureEncoder
    ): DefinitionJsonCodec<TextureDefinition> = DefinitionJsonCodec(
        id = id,
        layout = DefinitionLayout.Packed(0),
        create = { TextureDefinition(it) },
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )

    private fun definition(
        id: String,
        decoder: FontDecoder,
        encoder: FontEncoder
    ): DefinitionJsonCodec<FontDefinition> = DefinitionJsonCodec(
        id = id,
        layout = DefinitionLayout.Packed(0),
        create = { FontDefinition(it) },
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )
}
