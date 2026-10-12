package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

data class MaterialDefinition(
    override var id: Int = -1,
    var defined: Boolean = true,
    var detailsOnly: Boolean = false,
    var isSmall: Boolean = false,
    var skipTriangles: Boolean = false,
    var brightness: Byte = 0,
    var alpha: Byte = 0,
    var effectId: Byte = 0,
    var effectParam1: Byte = 0,
    var colourHsl: Int = 0,
    var textureSpeedU: Byte = 0,
    var textureSpeedV: Byte = 0,
    var unusedBoolean: Boolean = false,
    var isBrickTile: Boolean = false,
    var mipmapping: Byte = 0,
    var repeatS: Boolean = false,
    var repeatT: Boolean = false,
    var hdr: Boolean = false,
    var combineMode: Int = 0,
    var effectParam2: Int = 0,
    var effectCombiner: Int = 0
) : Definition
