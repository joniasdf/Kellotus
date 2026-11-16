package fi.rapanen.kellotus.ntp

/**
 * NTP timestamp conversion. The 64-bit wire format is unsigned seconds since
 * 1900-01-01 plus a 32-bit fraction of a second. Seconds are treated as
 * unsigned because the high bit is set until the 2036 era rollover.
 */
object NtpTime {
    const val NTP_UNIX_DELTA_SECONDS = 2_208_988_800L
    const val NANOS_PER_SECOND = 1_000_000_000L

    fun unixNanosToNtp(unixEpochNanos: Long): Long {
        val seconds = Math.floorDiv(unixEpochNanos, NANOS_PER_SECOND)
        val nanos = Math.floorMod(unixEpochNanos, NANOS_PER_SECOND)
        val ntpSeconds = seconds + NTP_UNIX_DELTA_SECONDS
        require(ntpSeconds in 0L..0xFFFF_FFFFL) { "NTP seconds out of range: $ntpSeconds" }
        val fraction = (nanos.toULong() shl 32) / NANOS_PER_SECOND.toULong()
        return (ntpSeconds shl 32) or fraction.toLong()
    }

    fun ntpToUnixNanos(ntp: Long): Long {
        val seconds = (ntp ushr 32) and 0xFFFF_FFFFL
        val fraction = (ntp and 0xFFFF_FFFFL).toULong()
        val nanos = ((fraction * NANOS_PER_SECOND.toULong()) shr 32).toLong()
        val unixSeconds = seconds - NTP_UNIX_DELTA_SECONDS
        return unixSeconds * NANOS_PER_SECOND + nanos
    }
}
