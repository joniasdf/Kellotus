package fi.rapanen.kellotus.time

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ClockReadout(
    val hms: String,
    val millis: String,
    val date: String,
)

object ClockFace {
    val HELSINKI: ZoneId = ZoneId.of("Europe/Helsinki")
    val UTC: ZoneId = ZoneOffset.UTC

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val milliFormat = DateTimeFormatter.ofPattern("SSS")
    private val dateFormat = DateTimeFormatter.ofPattern("EEE d.M.yyyy", Locale.forLanguageTag("fi-FI"))

    fun read(unixEpochNanos: Long, zone: ZoneId): ClockReadout {
        val seconds = Math.floorDiv(unixEpochNanos, 1_000_000_000L)
        val nanos = Math.floorMod(unixEpochNanos, 1_000_000_000L)
        val zoned = Instant.ofEpochSecond(seconds, nanos.toLong()).atZone(zone)
        return ClockReadout(
            hms = timeFormat.format(zoned),
            millis = milliFormat.format(zoned),
            date = dateFormat.format(zoned),
        )
    }
}
