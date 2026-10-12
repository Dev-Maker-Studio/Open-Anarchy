package org.darkan.cs2

object Cs2Events {

    val VALUES: Map<String, Int> = linkedMapOf(
        "event_mousex" to -2147483647,
        "event_mousey" to -2147483646,
        "event_com" to -2147483645,
        "event_opindex" to -2147483644,
        "event_comsubid" to -2147483643,
        "event_com2" to -2147483642,
        "event_comsubid2" to -2147483641,
        "event_keycode" to -2147483640,
        "event_keychar" to -2147483639,
    )

    val MEANINGS: Map<String, String> = linkedMapOf(
        "event_mousex" to "mouse x when the event fired",
        "event_mousey" to "mouse y when the event fired",
        "event_com" to "the component the event came from",
        "event_opindex" to "which of the component's options was clicked",
        "event_comsubid" to "the slot within the source component",
        "event_com2" to "the component the event was aimed at",
        "event_comsubid2" to "the slot within that component",
        "event_keycode" to "the key that was pressed",
        "event_keychar" to "the character it typed",
    )

    val COMPONENTS: Set<String> = setOf("event_com", "event_com2")

    val NAMES: Map<Int, String> = VALUES.entries.associate { (name, value) -> value to name }

    fun nameOf(value: Int): String? = NAMES[value]

    fun valueOf(name: String): Int? = VALUES[name]
}
