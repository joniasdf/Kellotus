package fi.rapanen.kellotus.gps

/**
 * GPS time to POSIX UTC.
 *
 * Android's [android.location.GnssClock] contract:
 * GPS time = timeNanos − (fullBiasNanos + biasNanos)
 * UtcTimeNanos = timeNanos − (fullBiasNanos + biasNanos) − leapSecond × 10^9
 *
 * That UTC value is nanoseconds since the GPS epoch (1980-01-06). POSIX time
 * does not count leap seconds, and GPS time started equal to UTC, so adding
 * the GPS epoch offset yields Unix UTC. Leap seconds since the GPS epoch are
 * subtracted. The IERS Bulletin C 72 (6 July 2026) keeps GPS−UTC at 18 s
 * through the end of 2026; a receiver-reported leap second in 1..40 wins.
 */
object GpsTimeMath {
    const val GPS_EPOCH_UNIX_SECONDS = 315964800L
    const val DEFAULT_LEAP_SECONDS = 18
    const val NANOS_PER_SECOND = 1_000_000_000L

    data class ResolvedLeap(val seconds: Int, val fromReceiver: Boolean)

    fun resolveLeapSeconds(reported: Int?): ResolvedLeap {
        if (reported != null && reported in 1..40) {
            return ResolvedLeap(reported, fromReceiver = true)
        }
        return ResolvedLeap(DEFAULT_LEAP_SECONDS, fromReceiver = false)
    }

    fun utcUnixNanos(
        timeNanos: Long,
        fullBiasNanos: Long,
        biasNanos: Double,
        leapSeconds: Int,
    ): Long {
        val bias = roundHalfAwayFromZero(biasNanos)
        val gpsNanos = Math.subtractExact(Math.subtractExact(timeNanos, fullBiasNanos), bias)
        val utcSinceGpsEpoch = Math.subtractExact(
            gpsNanos,
            Math.multiplyExact(leapSeconds.toLong(), NANOS_PER_SECOND),
        )
        return Math.addExact(
            utcSinceGpsEpoch,
            Math.multiplyExact(GPS_EPOCH_UNIX_SECONDS, NANOS_PER_SECOND),
        )
    }

    /** [android.location.Location.getTime] is already UTC milliseconds. */
    fun unixNanosFromLocationMillis(timeMillis: Long): Long =
        Math.multiplyExact(timeMillis, 1_000_000L)

    fun roundHalfAwayFromZero(value: Double): Long {
        if (value.isNaN() || value.isInfinite()) return 0L
        return if (value >= 0.0) (value + 0.5).toLong() else (value - 0.5).toLong()
    }
}
