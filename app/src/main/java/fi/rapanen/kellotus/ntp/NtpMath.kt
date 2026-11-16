package fi.rapanen.kellotus.ntp

/**
 * RFC 5905 clock offset and round-trip delay.
 *
 * offset = ((T2 − T1) + (T3 − T4)) / 2
 * delay  = (T4 − T1) − (T3 − T2)
 *
 * All four timestamps are on the same nanosecond scale. T1 and T4 must share
 * one client timebase; T4 − T1 is the monotonic round trip, so a step of the
 * device wall clock between them does not enter the result.
 */
object NtpMath {
    data class Solution(
        val offsetNanos: Long,
        val delayNanos: Long,
        val unixEpochNanosAtT4: Long,
    )

    fun compute(
        t1UnixNanos: Long,
        t2UnixNanos: Long,
        t3UnixNanos: Long,
        t4UnixNanos: Long,
    ): Solution {
        val offset = ((t2UnixNanos - t1UnixNanos) + (t3UnixNanos - t4UnixNanos)) / 2
        val delay = (t4UnixNanos - t1UnixNanos) - (t3UnixNanos - t2UnixNanos)
        return Solution(
            offsetNanos = offset,
            delayNanos = delay,
            unixEpochNanosAtT4 = t4UnixNanos + offset,
        )
    }

    fun selectLowestDelay(samples: List<NtpSample>): NtpSample {
        require(samples.isNotEmpty())
        return samples.minWith(compareBy<NtpSample> { it.delayNanos }.thenBy { kotlin.math.abs(it.offsetNanos) })
    }
}
