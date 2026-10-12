package org.darkan.core.net

import io.ktor.network.sockets.SocketAddress

fun SocketAddress.ipString(): String {
    var s = this.toString().trim()
    if (s.isEmpty()) return s
    val slash = s.lastIndexOf('/')
    if (slash >= 0 && slash < s.length - 1) {
        s = s.substring(slash + 1)
    }

    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end > 1) {
            return s.substring(1, end).substringBefore('%')
        }
        return s.removePrefix("[").removeSuffix("]").substringBefore('%')
    }

    val lastColon = s.lastIndexOf(':')
    if (lastColon > 0 && lastColon < s.length - 1) {
        val portPart = s.substring(lastColon + 1)
        if (portPart.all { it.isDigit() }) {
            val candidate = s.substring(0, lastColon)
            if (!candidate.contains(':')) {
                return candidate.substringBefore('%')
            }
        }
    }

    return s.substringBefore('%')
}


