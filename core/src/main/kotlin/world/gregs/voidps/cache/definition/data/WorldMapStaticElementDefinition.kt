package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition

data class WorldMapStaticElementDefinition(
    override var id: Int = -1,
    var position: Int = 0,
    var mapElement: Int = -1,
    var members: Int = 0
) : Definition
