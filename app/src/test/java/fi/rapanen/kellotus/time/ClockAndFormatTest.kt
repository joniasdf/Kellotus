package fi.rapanen.kellotus.time

import fi.rapanen.kellotus.ntp.HostNames
import fi.rapanen.kellotus.ntp.NtpServers
import fi.rapanen.kellotus.ntp.isNonPublic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.time.Instant

class ClockAndFormatTest {
    @Test
    fun helsinkiWinterAndSummer() {
        val winter = ClockFace.read(nanos("2026-11-16T12:00:00.123Z"), ClockFace.HELSINKI)
        assertEquals("14:00:00", winter.hms)
        assertEquals("123", winter.millis)
        assertEquals("ma 16.11.2026", winter.date)
        val summer = ClockFace.read(nanos("2026-07-16T12:00:00.000Z"), ClockFace.HELSINKI)
        assertEquals("15:00:00", summer.hms)
        assertEquals("000", summer.millis)
        val utc = ClockFace.read(nanos("2026-11-16T12:00:00.123Z"), ClockFace.UTC)
        assertEquals("12:00:00", utc.hms)
    }

    @Test
    fun anchorExtrapolatesFromElapsedTime() {
        val anchor = AnchoredTime(unixEpochNanos = 1_000L, elapsedRealtimeNanos = 500L)
        assertEquals(1_025L, anchor.unixEpochNanosAt(525L))
    }

    @Test
    fun finnishDurations() {
        assertEquals("0,00 ms", FinnishFormat.formatSignedDelta(0))
        assertEquals("+1,50 ms", FinnishFormat.formatSignedDelta(1_500_000L))
        assertEquals("−2,00 ms", FinnishFormat.formatSignedDelta(-2_000_000L))
        assertEquals("+500 ns", FinnishFormat.formatSignedDelta(500L))
        assertEquals("20,00 ms", FinnishFormat.formatDuration(20_000_000L))
        assertEquals("juuri nyt", FinnishFormat.formatAge(0))
        assertEquals("12 s sitten", FinnishFormat.formatAge(12_000_000_000L))
        assertEquals("954 ns", FinnishFormat.formatPrecision(-20))
    }

    @Test
    fun hostNamesAndPrivateAddresses() {
        assertTrue(HostNames.isAcceptable("time2.mikes.fi"))
        assertTrue(HostNames.isAcceptable("localhost"))
        assertTrue(HostNames.isAcceptable("192.168.1.1"))
        assertFalse(HostNames.isAcceptable(""))
        assertFalse(HostNames.isAcceptable("192.168.1.256"))
        assertFalse(HostNames.isAcceptable("-bad.example"))
        assertFalse(HostNames.isAcceptable("a..b"))
        assertTrue(InetAddress.getByName("10.1.2.3").isNonPublic())
        assertTrue(InetAddress.getByName("192.168.0.1").isNonPublic())
        assertTrue(InetAddress.getByName("172.16.5.5").isNonPublic())
        assertFalse(InetAddress.getByName("172.15.5.5").isNonPublic())
        assertFalse(InetAddress.getByName("194.100.49.152").isNonPublic())
        assertTrue(InetAddress.getByName("127.0.0.1").isNonPublic())
        assertTrue(InetAddress.getByName("fd00::1").isNonPublic())
        assertFalse(InetAddress.getByName("2001:708::123:1").isNonPublic())
    }

    @Test
    fun serverListKeepsVerifiedFinnishHosts() {
        val hosts = NtpServers.all.map { it.host }.toSet()
        assertEquals("time2.mikes.fi", NtpServers.all.first().host)
        listOf(
            "time1.mikes.fi",
            "time2.mikes.fi",
            "ntp1.aalto.fi",
            "ntp2.aalto.fi",
            "ntp.funet.fi",
            "fi.pool.ntp.org",
            "europe.pool.ntp.org",
            "time.cloudflare.com",
            "time.google.com",
        ).forEach { assertTrue(it, hosts.contains(it)) }
        assertTrue(NtpServers.all.all { HostNames.isAcceptable(it.host) })
    }

    private fun nanos(iso: String): Long {
        val instant = Instant.parse(iso)
        return instant.epochSecond * 1_000_000_000L + instant.nano
    }
}
