package world.gregs.voidps.cache

object NativeLibrary {

    val operatingSystems = listOf("windows", "linux", "macos")

    val architectures = listOf("x86", "x86_64", "ppc", "universal")

    val libraries = listOf("hw3d", "sw3d", "jaggl", "jaclib", "jagdx", "jagtheora")

    private val byHash: Map<Int, String> = buildMap {
        for (os in operatingSystems) {
            for (architecture in architectures) {
                for (library in libraries) {
                    val name = path(os, architecture, library)
                    put(name.hashCode(), name)
                }
            }
        }
    }

    fun path(os: String, architecture: String, library: String): String =
        "$os/$architecture/${file(os, library)}"

    fun file(os: String, library: String): String = when (os) {
        "windows" -> "$library.dll"
        "linux" -> "lib$library.so"
        "macos" -> "lib$library.dylib"
        else -> throw IllegalArgumentException("No native library naming rule for '$os'.")
    }

    fun name(hash: Int): String? = byHash[hash]

    fun names(): Collection<String> = byHash.values
}
