package com.rs.utils.music

enum class Biome {
    PLAINS, CITY, FOREST, DESERT, JUNGLE, SWAMP, SNOW, WILDERNESS, DUNGEON, VOLCANIC, OCEAN, MOUNTAIN, RUNE_ESSENCE;

    companion object {
        private val CITY_GENRES = setOf("varrock", "falador", "ardougne", "grand exchange", "edgeville", "taverly & burthorpe", "thieves guild", "dominion lobby")
        private val DUNGEON_WORDS = listOf("dungeon", "cave", "tunnel", "basement", "sewer", "maze", "crypt", "floors", "dorgesh", "stronghold", "mausoleum")
        private val SWAMP_WORDS = listOf("swamp", "morytania", "darkmeyer", "manor", "grim")
        private val SNOW_WORDS = listOf("white wolf", "fremennik", "christmas", "frozen", "trolls")
        private val JUNGLE_WORDS = listOf("jungle", "ogre", "feldip", "ape atoll", "brimhaven")
        private val FOREST_WORDS = listOf("elf", "isafdar", "zanaris", "gnome")

        private const val GENERATED_PREFIX = "generated "

        @JvmStatic
        fun generatedGenreName(biome: Biome): String = GENERATED_PREFIX + biome.name.lowercase()

        @JvmStatic
        fun of(genre: Genre?): Biome {
            genre ?: return PLAINS
            val name = genre.genreName.lowercase()
            if (name.startsWith(GENERATED_PREFIX))
                return valueOf(name.removePrefix(GENERATED_PREFIX).uppercase())
            val parent = genre.parent?.lowercase().orEmpty()
            return when {
                parent == "dungeon" || parent == "dungeoneering" || DUNGEON_WORDS.any { name.contains(it) } -> DUNGEON
                name.contains("wilderness") -> WILDERNESS
                name.contains("desert") || name.contains("duel arena") || parent == "desert" -> DESERT
                parent == "jungle" || JUNGLE_WORDS.any { name.contains(it) } -> JUNGLE
                parent == "swamp" || parent == "spooky" || SWAMP_WORDS.any { name.contains(it) } -> SWAMP
                SNOW_WORDS.any { name.contains(it) } -> SNOW
                name.contains("tzhaar") -> VOLCANIC
                parent == "elf" || FOREST_WORDS.any { name.contains(it) } -> FOREST
                name in CITY_GENRES -> CITY
                else -> PLAINS
            }
        }
    }
}
