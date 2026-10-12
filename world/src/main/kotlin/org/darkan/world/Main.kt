package org.darkan.world

import com.rs.Launcher
import java.util.TimeZone

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Launcher.main(args)
}
