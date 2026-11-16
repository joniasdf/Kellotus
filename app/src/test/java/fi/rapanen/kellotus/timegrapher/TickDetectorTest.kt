package fi.rapanen.kellotus.timegrapher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TickDetectorTest {
    @Test
    fun silenceProducesNoTicks() {
        val detector = TickDetector(48_000.0)
        val ticks = detector.accept(FloatArray(48_000))
        assertTrue(ticks.isEmpty())
    }

    @Test
    fun syntheticEscapementGivesRateBeatErrorAndBph() {
        val sampleRate = 48_000
        val signal = synthesize(
            sampleRate = sampleRate,
            seconds = 5.0,
            bph = 28_800,
            rateSecondsPerDay = 25.0,
            beatErrorSeconds = 0.0004,
        )
        val ticks = TickDetector(sampleRate.toDouble()).accept(signal)
        assertTrue("ticks=${ticks.size}", ticks.size >= 30)
        val times = DoubleArray(ticks.size) { ticks[it].sampleIndex / sampleRate.toDouble() }
        val reading = WatchAnalysis.analyze(times, forcedBph = null, liftAngleDegrees = 52.0)!!
        assertEquals(28_800, reading.bph)
        assertEquals(25.0, reading.rateSecondsPerDay, 2.0)
        assertEquals(0.4, reading.beatErrorMs, 0.15)
        assertEquals(RegulatorAdvice.SLOW_DOWN, reading.advice)
    }

    @Test
    fun anotherCommonRateIsRecognized() {
        val sampleRate = 48_000
        val signal = synthesize(
            sampleRate = sampleRate,
            seconds = 4.0,
            bph = 21_600,
            rateSecondsPerDay = -15.0,
            beatErrorSeconds = 0.0,
        )
        val ticks = TickDetector(sampleRate.toDouble()).accept(signal)
        val times = DoubleArray(ticks.size) { ticks[it].sampleIndex / sampleRate.toDouble() }
        val reading = WatchAnalysis.analyze(times, null, 52.0)!!
        assertEquals(21_600, reading.bph)
        assertEquals(-15.0, reading.rateSecondsPerDay, 2.0)
        assertEquals(RegulatorAdvice.SPEED_UP, reading.advice)
    }
}

private fun synthesize(
    sampleRate: Int,
    seconds: Double,
    bph: Int,
    rateSecondsPerDay: Double,
    beatErrorSeconds: Double,
): FloatArray {
    val total = (seconds * sampleRate).toInt()
    val output = FloatArray(total)
    val average = WatchAnalysis.nominalBeatSeconds(bph) * (1.0 - rateSecondsPerDay / 86_400.0)
    var time = 0.03
    var even = true
    while (time < seconds - 0.02) {
        paintBurst(output, sampleRate, time)
        time += average + if (even) beatErrorSeconds else -beatErrorSeconds
        even = !even
    }
    return output
}

private fun paintBurst(output: FloatArray, sampleRate: Int, startSeconds: Double) {
    val start = (startSeconds * sampleRate).toInt()
    val length = (0.007 * sampleRate).toInt()
    val tone = 4_000.0
    for (i in 0 until length) {
        val index = start + i
        if (index !in output.indices) continue
        val t = i.toDouble() / sampleRate
        val decay = exp(-t / 0.0016)
        output[index] = (0.7 * sin(2.0 * PI * tone * t) * decay).toFloat()
    }
}
