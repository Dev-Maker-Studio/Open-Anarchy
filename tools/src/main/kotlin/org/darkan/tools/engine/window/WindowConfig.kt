package org.darkan.tools.engine.window

data class WindowConfig(
    val title: String = "Darkan Tools",
    val width: Int = 1280,
    val height: Int = 720,
    val vsync: Boolean = true,
    val resizable: Boolean = true,
    val samples: Int = 4
)
