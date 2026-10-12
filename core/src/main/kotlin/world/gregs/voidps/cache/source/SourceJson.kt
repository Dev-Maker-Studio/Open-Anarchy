package world.gregs.voidps.cache.source

internal object SourceJson {

    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character < ' ') {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    fun array(values: IntArray): String = values.joinToString(", ", "[", "]")

    fun strings(values: List<String>): String = values.joinToString(", ", "[", "]") { quote(it) }

    fun intMap(values: Map<Int, Int>): String =
        values.entries.sortedBy { it.key }.joinToString(", ", "{", "}") { "${quote(it.key.toString())}: ${it.value}" }

    fun stringMap(values: Map<Int, String>): String =
        values.entries.sortedBy { it.key }
            .joinToString(", ", "{", "}") { "${quote(it.key.toString())}: ${quote(it.value)}" }
}
