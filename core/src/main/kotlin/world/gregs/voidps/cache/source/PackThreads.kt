package world.gregs.voidps.cache.source

object PackThreads {

    const val PROPERTY = "darkan.cache.pack.threads"
    const val DURABLE_PROPERTY = "darkan.cache.pack.durable"

    fun durable(): Boolean = System.getProperty(DURABLE_PROPERTY) != "false"

    fun count(): Int = System.getProperty(PROPERTY)?.toIntOrNull()?.coerceAtLeast(1) ?: Runtime.getRuntime().availableProcessors()
}
