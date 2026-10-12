package world.gregs.voidps.cache.definition.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.definition.data.MapDefinition
import world.gregs.voidps.cache.definition.data.MapObject

class MapObjectEncoder : DefinitionEncoder<MapDefinition> {

    override fun Writer.encode(definition: MapDefinition) {
        require(definition.id != -1) { "Map definition has no region id, refusing to encode a blank object block." }
        val objects = definition.objects
        for (loc in objects) {
            require(loc.id >= 0) { "Map ${definition.id}: object id ${loc.id} is negative." }
            require(loc.shape in 0..31) {
                "Map ${definition.id}: object ${loc.id} at (${loc.x}, ${loc.y}, ${loc.level}) has shape " +
                    "${loc.shape}, outside 0..31."
            }
            index(definition, loc)
        }
        val groups = if (isOrdered(definition, objects)) runs(objects) else sorted(objects)
        var id = -1
        for (group in groups) {
            val objectId = group.first().id
            writeSum(objectId - id)
            id = objectId
            var location = 0
            for (loc in group) {
                val local = index(definition, loc)
                writeSmart(local - location + 1)
                location = local
                writeByte(loc.rotation or (loc.shape shl 2))
            }
            writeSmart(0)
        }
        writeSmart(0)
        definition.trailing?.let { writeBytes(it) }
    }

    private fun isOrdered(definition: MapDefinition, objects: List<MapObject>): Boolean {
        var id = -1
        var location = -1
        for (loc in objects) {
            if (loc.id != id) {
                if (loc.id < id) {
                    return false
                }
                id = loc.id
                location = -1
            }
            val local = index(definition, loc)
            if (local < location) {
                return false
            }
            location = local
        }
        return true
    }

    private fun runs(objects: List<MapObject>): List<List<MapObject>> {
        val groups = ArrayList<List<MapObject>>()
        var start = 0
        while (start < objects.size) {
            var end = start + 1
            while (end < objects.size && objects[end].id == objects[start].id) {
                end++
            }
            groups.add(objects.subList(start, end))
            start = end
        }
        return groups
    }

    private fun sorted(objects: List<MapObject>): List<List<MapObject>> = objects
        .groupBy { it.id }
        .toList()
        .sortedBy { it.first }
        .map { (_, group) -> group.sortedBy { MapDefinition.index(it.x, it.y, it.level) } }

    private fun index(definition: MapDefinition, loc: MapObject): Int {
        require(loc.x in 0..63 && loc.y in 0..63) {
            "Map ${definition.id}: object ${loc.id} at (${loc.x}, ${loc.y}) is not a region local coordinate, expected 0..63 - world coordinates cannot be encoded."
        }
        return MapDefinition.index(loc.x, loc.y, loc.level)
    }

    private fun Writer.writeSum(value: Int) {
        var remaining = value
        while (remaining >= 32767) {
            writeSmart(32767)
            remaining -= 32767
        }
        writeSmart(remaining)
    }
}
