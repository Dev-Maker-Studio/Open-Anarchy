package world.gregs.voidps.cache.definition.data

data class InterfaceComponentHook(
    var script: Int = -1,
    var args: Array<Any> = emptyArray()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InterfaceComponentHook

        if (script != other.script) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = script
        result = 31 * result + args.contentHashCode()
        return result
    }
}
