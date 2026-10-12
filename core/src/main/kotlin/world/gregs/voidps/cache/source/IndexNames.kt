package world.gregs.voidps.cache.source

object IndexNames {

    private val names = arrayOf(
        "anims",
        "bases",
        "config",
        "interfaces",
        "synth_sounds",
        "maps",
        "midi_songs",
        "models",
        "graphics",
        "textures",
        "binary",
        "midi_jingles",
        "clientscripts",
        "fontmetrics",
        "vorbis",
        "midi_instruments",
        "config_loc",
        "config_enum",
        "config_npc",
        "config_obj",
        "config_seq",
        "config_spotanim",
        "config_varbit",
        "worldmapdata",
        "quickchat",
        "quickchat_global",
        "materials",
        "config_particle",
        "defaults",
        "config_billboard",
        "dlls",
        "shaders",
        "loading_graphics",
        "loading_screens",
        "loading_graphics_raw",
        "cutscenes",
        "index36"
    )

    private val indices = HashMap<String, Int>(names.size * 2).also { map ->
        for (index in names.indices) {
            map[names[index]] = index
        }
    }

    const val COUNT = 37

    fun name(index: Int): String = names.getOrNull(index) ?: "index$index"

    fun index(name: String): Int = indices[name] ?: fallback(name)

    private fun fallback(name: String): Int {
        if (!name.startsWith("index")) {
            return -1
        }
        return name.substring("index".length).toIntOrNull() ?: -1
    }
}
