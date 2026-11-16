package fi.rapanen.kellotus.time

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object FinnishFormat {
    private val symbols = DecimalFormatSymbols(Locale.forLanguageTag("fi-FI")).apply {
        decimalSeparator = ','
    }

    fun formatSignedDelta(nanos: Long): String {
        if (nanos == 0L) return "0,00 ms"
        val sign = if (nanos > 0) "+" else "−"
        return sign + formatMagnitude(abs(nanos.toDouble()))
    }

    fun formatDuration(nanos: Long): String = formatMagnitude(abs(nanos.toDouble()))

    fun formatUncertainty(nanos: Double): String = "±" + formatMagnitude(abs(nanos))

    /** NTP precision field is an exponent of two, in seconds. */
    fun formatPrecision(exponent: Int): String =
        formatMagnitude(abs(Math.scalb(1.0, exponent)) * 1_000_000_000.0)

    fun formatAge(ageNanos: Long): String {
        val seconds = ageNanos.coerceAtLeast(0L) / 1_000_000_000L
        return when {
            seconds < 1L -> "juuri nyt"
            seconds < 60L -> "$seconds s sitten"
            seconds < 3600L -> "${seconds / 60L} min sitten"
            else -> "${seconds / 3600L} h sitten"
        }
    }

    private fun formatMagnitude(absNanos: Double): String = when {
        absNanos < 1_000.0 -> number(absNanos, 0) + " ns"
        absNanos < 1_000_000.0 -> number(absNanos / 1_000.0, 1) + " µs"
        absNanos < 10_000_000_000.0 -> number(absNanos / 1_000_000.0, 2) + " ms"
        absNanos < 120_000_000_000.0 -> number(absNanos / 1_000_000_000.0, 3) + " s"
        else -> number(absNanos / 60_000_000_000.0, 2) + " min"
    }

    private fun number(value: Double, digits: Int): String {
        val pattern = if (digits == 0) "0" else "0." + "0".repeat(digits)
        return DecimalFormat(pattern, symbols).apply {
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
    }
}
