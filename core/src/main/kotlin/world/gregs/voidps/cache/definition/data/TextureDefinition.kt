package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

data class TextureDefinition(
    override var id: Int = -1,
    var operations: List<TextureOp> = emptyList(),
    var opaque: Int = 0,
    var opacity: Int = 0,
    var hdr: Int = 0,
    var trailer: String? = null
) : Definition
