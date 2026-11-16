package fi.rapanen.kellotus.ntp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtpTimeTest {
    @Test
    fun unixEpochIsNtpDelta() {
        val ntp = NtpTime.unixNanosToNtp(0L)
        assertEquals(NtpTime.NTP_UNIX_DELTA_SECONDS shl 32, ntp)
        assertEquals(0L, NtpTime.ntpToUnixNanos(ntp))
    }

    @Test
    fun oneSecondRoundTrips() {
        val nanos = 1_000_000_000L
        assertEquals(nanos, NtpTime.ntpToUnixNanos(NtpTime.unixNanosToNtp(nanos)))
    }

    @Test
    fun halfSecondIsExactFraction() {
        val nanos = 500_000_000L
        val ntp = NtpTime.unixNanosToNtp(nanos)
        assertEquals(0x8000_0000L, ntp and 0xFFFF_FFFFL)
        assertEquals(nanos, NtpTime.ntpToUnixNanos(ntp))
    }

    @Test
    fun highBitSecondsStayUnsigned() {
        val unix = 1_763_000_000_000_000_000L
        val ntp = NtpTime.unixNanosToNtp(unix)
        assertTrue(ntp < 0L)
        val back = NtpTime.ntpToUnixNanos(ntp)
        assertTrue(kotlin.math.abs(back - unix) <= 1L)
    }
}
