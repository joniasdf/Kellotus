package fi.rapanen.kellotus.timegrapher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class WatchAnalysisTest {
    @Test
    fun nominalBeatIntervals() {
        assertEquals(0.200, WatchAnalysis.nominalBeatSeconds(18_000), 1e-12)
        assertEquals(0.125, WatchAnalysis.nominalBeatSeconds(28_800), 1e-12)
        assertEquals(0.100, WatchAnalysis.nominalBeatSeconds(36_000), 1e-12)
    }

    @Test
    fun detectsCommonRates() {
        assertEquals(28_800, WatchAnalysis.detectBph(0.125))
        assertEquals(21_600, WatchAnalysis.detectBph(3600.0 / 21_600))
        assertNull(WatchAnalysis.detectBph(0.05))
    }

    @Test
    fun fastWatchIsPositiveAndAsksToSlowDown() {
        val times = beatTimes(bph = 28_800, seconds = 8.0, rateSecondsPerDay = 30.0, beatErrorSeconds = 0.0)
        val reading = WatchAnalysis.analyze(times, forcedBph = 28_800, liftAngleDegrees = 52.0)
        assertNotNull(reading)
        assertEquals(30.0, reading!!.rateSecondsPerDay, 0.05)
        assertEquals(RegulatorAdvice.SLOW_DOWN, reading.advice)
        assertEquals(0.0, reading.beatErrorMs, 0.05)
    }

    @Test
    fun slowWatchAsksToSpeedUp() {
        val times = beatTimes(bph = 21_600, seconds = 6.0, rateSecondsPerDay = -20.0, beatErrorSeconds = 0.0)
        val reading = WatchAnalysis.analyze(times, forcedBph = null, liftAngleDegrees = 52.0)
        assertNotNull(reading)
        assertEquals(21_600, reading!!.bph)
        assertEquals(-20.0, reading.rateSecondsPerDay, 0.05)
        assertEquals(RegulatorAdvice.SPEED_UP, reading.advice)
    }

    @Test
    fun beatErrorIsHalfTheAlternatingGap() {
        val error = 0.0004
        val times = beatTimes(bph = 28_800, seconds = 6.0, rateSecondsPerDay = 0.0, beatErrorSeconds = error)
        val reading = WatchAnalysis.analyze(times, 28_800, 52.0)!!
        assertEquals(0.4, reading.beatErrorMs, 0.02)
        assertEquals(RegulatorAdvice.LEAVE, reading.advice)
    }

    @Test
    fun amplitudeFormula() {
        val lift = 0.008
        val period = 0.250
        val angle = 52.0
        val expected = angle / (2.0 * sin(Math.PI * lift / period))
        assertEquals(expected, WatchAnalysis.amplitudeDegrees(lift, period, angle)!!, 1e-9)
        assertTrue(expected in 200.0..320.0)
        assertNull(WatchAnalysis.amplitudeDegrees(0.0, period, angle))
    }

    @Test
    fun audioClockPpmAgainstMonotonicWindow() {
        val calibration = WatchAnalysis.audioCalibration(
            nominalHz = 48_000.0,
            sampleCount = 48_048,
            elapsedNanos = 1_000_000_000L,
        )!!
        assertEquals(48_048.0, calibration.measuredHz, 1e-6)
        assertEquals(1_000.0, calibration.ppm, 1e-6)
        assertEquals(false, calibration.ntpScaled)
    }

    @Test
    fun twoNtpAnchorsScaleTheMonotonicClock() {
        val scale = WatchAnalysis.monoToUtcScale(
            firstElapsedNanos = 0L,
            firstUnixNanos = 1_000L,
            secondElapsedNanos = 10_000_000_000L,
            secondUnixNanos = 1_000L + 10_000_000_000L + 5_000L,
        )
        assertEquals(1.0000005, scale!!, 1e-12)
        val calibration = WatchAnalysis.audioCalibration(48_000.0, 48_000, 1_000_000_000L, scale)!!
        assertTrue(calibration.ntpScaled)
        assertTrue(calibration.measuredHz < 48_000.0)
    }
}

internal fun beatTimes(
    bph: Int,
    seconds: Double,
    rateSecondsPerDay: Double,
    beatErrorSeconds: Double,
): DoubleArray {
    val average = WatchAnalysis.nominalBeatSeconds(bph) * (1.0 - rateSecondsPerDay / 86_400.0)
    val times = ArrayList<Double>()
    var time = 0.0
    var even = true
    while (time < seconds) {
        times += time
        time += average + if (even) beatErrorSeconds else -beatErrorSeconds
        even = !even
    }
    return times.toDoubleArray()
}
