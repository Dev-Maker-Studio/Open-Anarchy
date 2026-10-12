package world.gregs.voidps.cache.store

enum class BZip2Variant {
    LIBBZIP2,
    COMMONS;

    companion object {
        fun of(name: String): BZip2Variant = when (name.lowercase()) {
            "libbzip2" -> LIBBZIP2
            "commons" -> COMMONS
            else -> throw IllegalArgumentException("Unknown bzip2 variant '$name'.")
        }
    }

    val id: String
        get() = name.lowercase()
}
