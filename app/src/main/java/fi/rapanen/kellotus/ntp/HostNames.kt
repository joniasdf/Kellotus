package fi.rapanen.kellotus.ntp

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object HostNames {
    private val label = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun isAcceptable(raw: String): Boolean {
        val host = raw.trim()
        if (host.length !in 1..253 || host.any { it.isWhitespace() }) return false
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) {
            return parts.all { part ->
                val value = part.toIntOrNull() ?: return false
                value in 0..255
            }
        }
        return parts.all { label.matches(it) }
    }
}

internal fun InetAddress.isNonPublic(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress) return true
    if (this is Inet4Address) {
        val bytes = address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        if (first == 100 && second in 64..127) return true
        if (first == 0) return true
    }
    if (this is Inet6Address) {
        val first = address[0].toInt() and 0xFF
        if (first and 0xFE == 0xFC) return true
    }
    return false
}
