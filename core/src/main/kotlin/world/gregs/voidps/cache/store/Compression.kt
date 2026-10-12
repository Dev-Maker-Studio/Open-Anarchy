package world.gregs.voidps.cache.store

enum class Compression(val id: Int) {
    NONE(0),
    BZIP2(1),
    GZIP(2);

    companion object {
        private val byId = arrayOfNulls<Compression>(3).also { types ->
            for (type in entries) {
                types[type.id] = type
            }
        }

        fun of(id: Int): Compression = byId.getOrNull(id)
            ?: throw IllegalArgumentException("Unsupported container compression type $id.")
    }
}
