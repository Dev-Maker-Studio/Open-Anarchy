package world.gregs.voidps.cache.definition

/**
 * One key/value record of a parameter list (opcode 249) or of an enum's entry list.
 *
 * The decoders build a map from those records, which drops a key the file listed twice - 60 objs, 62 npcs and
 * 12 enums in the 727 cache do exactly that. The records are kept in file order alongside the map so the file
 * can be written back with the same number of records.
 */
class KeyedValue(val key: Int, val value: Any) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyedValue) return false
        return key == other.key && value == other.value
    }

    override fun hashCode(): Int = 31 * key + value.hashCode()

    override fun toString(): String = "KeyedValue(key=$key, value=$value)"
}
