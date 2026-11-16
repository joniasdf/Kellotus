package fi.rapanen.kellotus.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GpsTimeMathTest {
    @Test
    fun gpsEpochWithNoLeapIsTheUnixOffset() {
        assertEquals(
            GpsTimeMath.GPS_EPOCH_UNIX_SECONDS * GpsTimeMath.NANOS_PER_SECOND,
            GpsTimeMath.utcUnixNanos(0L, 0L, 0.0, 0),
        )
    }

    @Test
    fun leapSecondsMoveUtcBackFromGpsTime() {
        val without = GpsTimeMath.utcUnixNanos(0L, 0L, 0.0, 0)
        val with = GpsTimeMath.utcUnixNanos(0L, 0L, 0.0, 18)
        assertEquals(18L * GpsTimeMath.NANOS_PER_SECOND, without - with)
    }

    @Test
    fun knownUtcInstantRoundTripsThroughFullBias() {
        val unix = Instant.parse("2026-01-01T00:00:00Z").epochSecond * 1_000_000_000L
        val leap = 18
        val gpsNanos = unix - GpsTimeMath.GPS_EPOCH_UNIX_SECONDS * 1_000_000_000L + leap * 1_000_000_000L
        val result = GpsTimeMath.utcUnixNanos(
            timeNanos = 1_000_000L,
            fullBiasNanos = 1_000_000L - gpsNanos,
            biasNanos = 0.0,
            leapSeconds = leap,
        )
        assertEquals(unix, result)
    }

    @Test
    fun biasRoundsHalfAwayFromZero() {
        val base = GpsTimeMath.utcUnixNanos(10_000L, 0L, 0.0, 0)
        assertEquals(base - 1L, GpsTimeMath.utcUnixNanos(10_000L, 0L, 0.6, 0))
        assertEquals(base, GpsTimeMath.utcUnixNanos(10_000L, 0L, 0.4, 0))
        assertEquals(base + 1L, GpsTimeMath.utcUnixNanos(10_000L, 0L, -0.6, 0))
        assertEquals(base, GpsTimeMath.utcUnixNanos(10_000L, 0L, -0.4, 0))
        assertEquals(0L, GpsTimeMath.roundHalfAwayFromZero(Double.NaN))
    }

    @Test
    fun leapSecondPreference() {
        assertEquals(18, GpsTimeMath.resolveLeapSeconds(null).seconds)
        assertFalse(GpsTimeMath.resolveLeapSeconds(null).fromReceiver)
        assertFalse(GpsTimeMath.resolveLeapSeconds(0).fromReceiver)
        assertEquals(18, GpsTimeMath.resolveLeapSeconds(0).seconds)
        val reported = GpsTimeMath.resolveLeapSeconds(19)
        assertTrue(reported.fromReceiver)
        assertEquals(19, reported.seconds)
        assertFalse(GpsTimeMath.resolveLeapSeconds(99).fromReceiver)
    }

    @Test
    fun locationTimeIsAlreadyUtc() {
        assertEquals(1_700_000_000_123L * 1_000_000L, GpsTimeMath.unixNanosFromLocationMillis(1_700_000_000_123L))
    }
}
