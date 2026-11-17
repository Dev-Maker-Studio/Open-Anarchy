package org.darkan.core.dungeon

fun main(arguments: Array<String>) {
    val dungeons = DungeonRegistry.readAll()
    val chosen = arguments.firstOrNull()?.let { id -> dungeons.filter { it.id.equals(id, ignoreCase = true) } } ?: dungeons
    for (dungeon in chosen) {
        println(DungeonText.summary(dungeon))
        if (arguments.isNotEmpty()) println(DungeonText.render(dungeon))
    }
}
