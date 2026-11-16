package fi.rapanen.kellotus.ntp

import fi.rapanen.kellotus.time.Timebase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

class NtpClientTest {
    @Test
    fun picksLowestDelayAndIgnoresWallClockJump() = runBlocking {
        val clock = ManualTimebase(wallUnixNanos = 1_700_000_000_000_000_000L, elapsedRealtimeNanos = 5_000_000_000L)
        val exchange = ScriptedExchange(
            clock,
            listOf(
                Step(rttNanos = 40_000_000L, offsetNanos = 5_000_000L),
                Step(rttNanos = 12_000_000L, offsetNanos = 7_000_000L),
                Step(rttNanos = 30_000_000L, offsetNanos = 9_000_000L, jumpWallNanos = 10_000_000_000L),
            ),
        )
        val outcome = NtpClient(clock, exchange, resolver = { listOf(InetAddress.getByName("192.0.2.1")) }, pause = {})
            .sync("time2.mikes.fi", samples = 3, pauseMs = 0)
        val fix = (outcome as NtpOutcome.Success).fix
        assertEquals(12_000_000L, fix.delayNanos)
        assertNear(7_000_000L, fix.offsetNanos)
        assertEquals(3, fix.acceptedSamples)
        assertNear(clock.wallUnixNanos + 12_000_000L + 7_000_000L - 10_000_000_000L, fix.anchor.unixEpochNanos)
        assertEquals(0x23, exchange.requests.first()[0].toInt() and 0xFF)
    }

    @Test
    fun wallJumpDuringFlightDoesNotMoveTheAnchor() = runBlocking {
        val wall = 1_800_000_000_000_000_000L
        val clock = ManualTimebase(wall, 0L)
        val exchange = ScriptedExchange(
            clock,
            listOf(Step(rttNanos = 20_000_000L, offsetNanos = 1_000_000_000L, jumpWallNanos = 10_000_000_000L)),
        )
        val outcome = NtpClient(clock, exchange, resolver = { listOf(InetAddress.getByName("192.0.2.1")) }, pause = {})
            .sync("time.google.com", samples = 1, pauseMs = 0) as NtpOutcome.Success
        assertNear(1_000_000_000L, outcome.fix.offsetNanos)
        assertNear(wall + 20_000_000L + 1_000_000_000L, outcome.fix.anchor.unixEpochNanos)
        assertEquals(20_000_000L, outcome.fix.anchor.elapsedRealtimeNanos)
    }

    @Test
    fun rejectsOriginMismatchNegativeDelayAndStopsOnRate() = runBlocking {
        val clock = ManualTimebase(1_700_000_000_000_000_000L, 0L)
        val resolver = HostResolver { listOf(InetAddress.getByName("192.0.2.8")) }
        val badOrigin = NtpClient(
            clock,
            ScriptedExchange(clock, listOf(Step(rttNanos = 10_000_000L, corruptOrigin = true))),
            resolver,
            pause = {},
        ).sync("example.com", samples = 1, pauseMs = 0)
        assertEquals(NtpFailure.INVALID, (badOrigin as NtpOutcome.Failure).reason)

        val negative = NtpClient(
            clock,
            ScriptedExchange(clock, listOf(Step(rttNanos = 10_000_000L, serverProcessNanos = 20_000_000L))),
            resolver,
            pause = {},
        ).sync("example.com", samples = 1, pauseMs = 0)
        assertEquals(NtpFailure.INVALID, (negative as NtpOutcome.Failure).reason)

        val rate = NtpClient(
            clock,
            ScriptedExchange(
                clock,
                listOf(
                    Step(rttNanos = 10_000_000L, offsetNanos = 1000L),
                    Step(rttNanos = 10_000_000L, kiss = "RATE"),
                    Step(rttNanos = 10_000_000L, offsetNanos = 9_000_000L),
                ),
            ),
            resolver,
            pause = {},
        ).sync("example.com", samples = 3, pauseMs = 0) as NtpOutcome.Success
        assertEquals(1, rate.fix.acceptedSamples)
        assertNear(1000L, rate.fix.offsetNanos)
    }

    @Test
    fun refusesPrivateAddressWithoutLocalPermission() = runBlocking {
        val clock = ManualTimebase(0L, 0L)
        val outcome = NtpClient(
            clock,
            ScriptedExchange(clock, emptyList()),
            resolver = { listOf(InetAddress.getByName("192.168.1.1")) },
            pause = {},
        ).sync("192.168.1.1", allowLocalNetwork = false)
        assertEquals(NtpFailure.LOCAL_NETWORK, (outcome as NtpOutcome.Failure).reason)
    }

    @Test
    fun liveQueryAgainstMikes() {
        assumeTrue(System.getenv("NTP_LIVE") == "1")
        val outcome = runBlocking {
            NtpClient(timebase = JvmTimebase(), pause = {})
                .sync("time2.mikes.fi", samples = 4, timeoutMs = 2_000, pauseMs = 40)
        }
        assertTrue(outcome.toString(), outcome is NtpOutcome.Success)
        val fix = (outcome as NtpOutcome.Success).fix
        assertTrue(fix.stratum in 1..4)
        assertTrue(fix.delayNanos in 1..2_000_000_000L)
        val skew = kotlin.math.abs(fix.anchor.unixEpochNanos - System.currentTimeMillis() * 1_000_000L)
        assertTrue("skew ns = $skew", skew < 5_000_000_000L)
    }
}

private fun assertNear(expected: Long, actual: Long) {
    assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= 1L)
}

private class ManualTimebase(var wallUnixNanos: Long, var elapsedRealtimeNanos: Long) : Timebase {
    override fun wallUnixNanos(): Long = wallUnixNanos
    override fun elapsedRealtimeNanos(): Long = elapsedRealtimeNanos
}

private class JvmTimebase : Timebase {
    override fun wallUnixNanos(): Long = System.currentTimeMillis() * 1_000_000L
    override fun elapsedRealtimeNanos(): Long = System.nanoTime()
}

private data class Step(
    val rttNanos: Long,
    val offsetNanos: Long = 0L,
    val serverProcessNanos: Long = 0L,
    val jumpWallNanos: Long = 0L,
    val corruptOrigin: Boolean = false,
    val kiss: String? = null,
)

private class ScriptedExchange(
    private val clock: ManualTimebase,
    private val steps: List<Step>,
) : NtpExchange {
    val requests = mutableListOf<ByteArray>()
    private var index = 0

    override fun <T> transact(address: InetAddress, port: Int, timeoutMs: Int, block: (NtpLink) -> T): T {
        val step = steps[kotlin.math.min(index, steps.lastIndex)]
        index++
        var request: ByteArray? = null
        val link = object : NtpLink {
            override fun send(packet: ByteArray) {
                request = packet.copyOf()
                requests += request!!
            }

            override fun receive(): ByteArray {
                clock.wallUnixNanos += step.jumpWallNanos
                clock.elapsedRealtimeNanos += step.rttNanos
                val sent = request ?: error("missing request")
                val originate = readLong(sent, 40)
                if (step.corruptOrigin) {
                    return NtpPacket.buildServerPacket(originate xor 0xFF, 0L, 1_000_000_000L)
                }
                if (step.kiss != null) {
                    return NtpPacket.buildServerPacket(
                        originateNtp = originate,
                        receiveUnixNanos = 0L,
                        transmitUnixNanos = 1_000_000_000L,
                        stratum = 0,
                        referenceId = step.kiss.toByteArray(Charsets.US_ASCII),
                    )
                }
                val t1 = NtpTime.ntpToUnixNanos(originate)
                val t2 = t1 + step.rttNanos / 2 + step.offsetNanos
                return NtpPacket.buildServerPacket(
                    originateNtp = originate,
                    receiveUnixNanos = t2,
                    transmitUnixNanos = t2 + step.serverProcessNanos,
                    stratum = 1,
                    referenceId = byteArrayOf('G'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), 0),
                )
            }
        }
        return block(link)
    }
}
