package fi.rapanen.kellotus.ntp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtpPacketTest {
    @Test
    fun requestIsVersion4ClientWithTransmitTimestamp() {
        val packet = NtpPacket.buildRequest(1_000_000_000L)
        assertEquals(48, packet.size)
        assertEquals(0x23, packet[0].toInt() and 0xFF)
        assertEquals(1_000_000_000L, NtpTime.ntpToUnixNanos(readLong(packet, 40)))
    }

    @Test
    fun parsesHandBuiltServerPacket() {
        val packet = ByteArray(48)
        packet[0] = 0x24
        packet[1] = 1
        packet[3] = (-20).toByte()
        byteArrayOf('G'.code.toByte(), 'O'.code.toByte(), 'O'.code.toByte(), 'G'.code.toByte()).copyInto(packet, 12)
        val transmit = NtpTime.unixNanosToNtp(5_000_000_000L)
        writeLong(packet, 32, NtpTime.unixNanosToNtp(4_000_000_000L))
        writeLong(packet, 40, transmit)
        val parsed = NtpPacket.parse(packet) as NtpParse.Ok
        assertEquals(4, parsed.response.version)
        assertEquals(4, parsed.response.mode)
        assertEquals(1, parsed.response.stratum)
        assertEquals(-20, parsed.response.precisionExponent)
        assertEquals("GOOG", parsed.response.referenceId)
        assertEquals(4_000_000_000L, parsed.response.receiveUnixNanos)
        assertEquals(5_000_000_000L, parsed.response.transmitUnixNanos)
    }

    @Test
    fun rejectsClientModeUnsynchronizedKissAndShort() {
        assertEquals(NtpReject.SHORT_PACKET, (NtpPacket.parse(ByteArray(10)) as NtpParse.Reject).reason)
        val client = NtpPacket.buildServerPacket(0, 0, 1_000_000_000L, mode = 3)
        assertEquals(NtpReject.BAD_MODE, (NtpPacket.parse(client) as NtpParse.Reject).reason)
        val alarm = NtpPacket.buildServerPacket(0, 0, 1_000_000_000L, leapIndicator = 3)
        assertEquals(NtpReject.UNSYNCHRONIZED, (NtpPacket.parse(alarm) as NtpParse.Reject).reason)
        val kiss = NtpPacket.buildServerPacket(
            originateNtp = 0,
            receiveUnixNanos = 0,
            transmitUnixNanos = 1_000_000_000L,
            stratum = 0,
            referenceId = "RATE".toByteArray(Charsets.US_ASCII),
        )
        val rejected = NtpPacket.parse(kiss) as NtpParse.Reject
        assertEquals(NtpReject.KISS, rejected.reason)
        assertEquals("RATE", rejected.kissCode)
        assertTrue(NtpPacket.isRateLimit("RATE"))
    }

    @Test
    fun referenceIdFormatting() {
        assertEquals("GPS", NtpPacket.formatReferenceId(1, "GPS\u0000".toByteArray(Charsets.US_ASCII)))
        assertEquals(
            "194.100.49.152",
            NtpPacket.formatReferenceId(2, byteArrayOf(194.toByte(), 100, 49, 152.toByte())),
        )
    }

    @Test
    fun offsetAndDelayFormula() {
        val solution = NtpMath.compute(
            t1UnixNanos = 1_000_000_000L,
            t2UnixNanos = 1_500_000_000L,
            t3UnixNanos = 1_600_000_000L,
            t4UnixNanos = 1_200_000_000L,
        )
        assertEquals(450_000_000L, solution.offsetNanos)
        assertEquals(100_000_000L, solution.delayNanos)
        assertEquals(1_650_000_000L, solution.unixEpochNanosAtT4)
    }

    @Test
    fun symmetricPathHasZeroOffset() {
        val solution = NtpMath.compute(1_000L, 1_050L, 1_050L, 1_100L)
        assertEquals(0L, solution.offsetNanos)
        assertEquals(100L, solution.delayNanos)
    }

    @Test
    fun lowestDelayWins() {
        val samples = listOf(
            sample(delay = 50, offset = 1),
            sample(delay = 10, offset = 9),
            sample(delay = 10, offset = 2),
            sample(delay = 30, offset = 0),
        )
        val best = NtpMath.selectLowestDelay(samples)
        assertEquals(10L, best.delayNanos)
        assertEquals(2L, best.offsetNanos)
    }

    private fun sample(delay: Long, offset: Long) = NtpSample(
        offsetNanos = offset,
        delayNanos = delay,
        anchor = fi.rapanen.kellotus.time.AnchoredTime(0, 0),
        stratum = 1,
        referenceId = "GPS",
        precisionExponent = -20,
        leapIndicator = 0,
        address = "127.0.0.1",
    )
}
