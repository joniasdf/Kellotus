package fi.rapanen.kellotus.timegrapher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

enum class RegulatorAdvice {
    /** Watch is fast: move the regulator toward slower. */
    SLOW_DOWN,

    /** Watch is slow: move the regulator toward faster. */
    SPEED_UP,

    /** Within the tolerance, leave the regulator alone. */
    LEAVE,
}

data class AudioCalibration(
    val nominalHz: Double,
    val measuredHz: Double,
    /** (measured − nominal) / nominal × 10⁶. Positive means the mic clock runs fast. */
    val ppm: Double,
    val windowSeconds: Double,
    val sampleCount: Long,
    /** True when two NTP anchors scaled the monotonic window onto UTC. */
    val ntpScaled: Boolean,
)

data class TracePoint(
    val timeSeconds: Double,
    val phaseMs: Double,
    val alternate: Boolean,
)

data class WatchReading(
    val bph: Int,
    /** Positive means the watch is fast (edistää). */
    val rateSecondsPerDay: Double,
    val beatErrorMs: Double,
    val amplitudeDegrees: Double?,
    val beats: Int,
    val trace: List<TracePoint>,
    val advice: RegulatorAdvice,
)

object WatchAnalysis {
    val COMMON_BPH = intArrayOf(18_000, 19_800, 21_600, 25_200, 28_800, 36_000)
    const val DEFAULT_LIFT_ANGLE = 52.0
    const val IN_RANGE_SECONDS_PER_DAY = 5.0

    fun nominalBeatSeconds(bph: Int): Double = 3600.0 / bph

    fun detectBph(medianIntervalSeconds: Double): Int? {
        if (medianIntervalSeconds <= 0.0) return null
        var best: Int? = null
        var bestError = Double.POSITIVE_INFINITY
        for (bph in COMMON_BPH) {
            val nominal = nominalBeatSeconds(bph)
            val relative = abs(medianIntervalSeconds - nominal) / nominal
            if (relative < bestError) {
                bestError = relative
                best = bph
            }
        }
        return if (bestError <= 0.04) best else null
    }

    /**
     * @param liftSeconds duration of each escapement noise (unlock to drop), same order as single-step beats
     */
    fun analyze(
        timesSeconds: DoubleArray,
        forcedBph: Int?,
        liftAngleDegrees: Double,
        liftSeconds: DoubleArray = DoubleArray(0),
    ): WatchReading? {
        if (timesSeconds.size < 8) return null
        val gaps = DoubleArray(timesSeconds.size - 1) { timesSeconds[it + 1] - timesSeconds[it] }
        val plausible = gaps.filter { it in 0.05..0.30 }.toDoubleArray()
        if (plausible.size < 6) return null
        val bph = forcedBph ?: detectBph(median(plausible)) ?: return null
        val nominal = nominalBeatSeconds(bph)
        val indexed = indexBeats(timesSeconds, nominal)
        if (indexed.size < 8) return null
        val slope = slope(indexed.map { it.second }.toDoubleArray(), indexed.map { it.first }.toDoubleArray())
        if (!slope.isFinite() || slope <= 0.0) return null
        val rate = (nominal - slope) / nominal * 86_400.0
        val beatError = beatErrorSeconds(timesSeconds, nominal)
        val oscillation = slope * 2.0
        val amplitude = medianLift(liftSeconds)?.let { lift ->
            amplitudeDegrees(lift, oscillation, liftAngleDegrees)
        }
        return WatchReading(
            bph = bph,
            rateSecondsPerDay = rate,
            beatErrorMs = beatError * 1_000.0,
            amplitudeDegrees = amplitude,
            beats = timesSeconds.size,
            trace = trace(timesSeconds, nominal),
            advice = advice(rate),
        )
    }

    fun advice(rateSecondsPerDay: Double, tolerance: Double = IN_RANGE_SECONDS_PER_DAY): RegulatorAdvice {
        return when {
            rateSecondsPerDay > tolerance -> RegulatorAdvice.SLOW_DOWN
            rateSecondsPerDay < -tolerance -> RegulatorAdvice.SPEED_UP
            else -> RegulatorAdvice.LEAVE
        }
    }

    /**
     * Escapement lift relates to balance amplitude by
     * amplitude = liftAngle / (2 sin(π · liftTime / oscillationPeriod)).
     */
    fun amplitudeDegrees(liftSeconds: Double, oscillationSeconds: Double, liftAngleDegrees: Double): Double? {
        if (liftSeconds <= 0.0 || oscillationSeconds <= 0.0 || liftAngleDegrees <= 0.0) return null
        val argument = PI * liftSeconds / oscillationSeconds
        if (argument <= 0.0 || argument >= PI / 2) return null
        val amplitude = liftAngleDegrees / (2.0 * sin(argument))
        return amplitude.takeIf { it in 80.0..360.0 }
    }

    /**
     * Mic sample clock versus a monotonic window.
     * [utcPerMono] is (UTC nanoseconds) / (elapsedRealtime nanoseconds) from two NTP anchors.
     * A single anchor cancels out, so the caller passes 1 when it cannot yet see drift.
     */
    fun audioCalibration(
        nominalHz: Double,
        sampleCount: Long,
        elapsedNanos: Long,
        utcPerMono: Double = 1.0,
    ): AudioCalibration? {
        if (nominalHz <= 0.0 || sampleCount <= 0L || elapsedNanos <= 0L) return null
        if (!utcPerMono.isFinite() || utcPerMono <= 0.0) return null
        val trueSeconds = elapsedNanos.toDouble() / 1_000_000_000.0 * utcPerMono
        if (trueSeconds < 0.5) return null
        val measuredHz = sampleCount.toDouble() / trueSeconds
        val ppm = (measuredHz - nominalHz) / nominalHz * 1_000_000.0
        return AudioCalibration(
            nominalHz = nominalHz,
            measuredHz = measuredHz,
            ppm = ppm,
            windowSeconds = trueSeconds,
            sampleCount = sampleCount,
            ntpScaled = abs(utcPerMono - 1.0) > 1e-9,
        )
    }

    fun monoToUtcScale(firstElapsedNanos: Long, firstUnixNanos: Long, secondElapsedNanos: Long, secondUnixNanos: Long): Double? {
        val mono = secondElapsedNanos - firstElapsedNanos
        val utc = secondUnixNanos - firstUnixNanos
        if (mono <= 1_000_000_000L || utc <= 0L) return null
        val scale = utc.toDouble() / mono.toDouble()
        return scale.takeIf { it.isFinite() && abs(it - 1.0) < 0.01 }
    }

    fun trace(timesSeconds: DoubleArray, nominalBeatSeconds: Double): List<TracePoint> {
        if (timesSeconds.isEmpty() || nominalBeatSeconds <= 0.0) return emptyList()
        val origin = timesSeconds.first()
        val window = 24.0
        val end = timesSeconds.last()
        val start = end - window
        val points = ArrayList<TracePoint>()
        for (index in timesSeconds.indices) {
            val time = timesSeconds[index]
            if (time < start) continue
            val expected = origin + index * nominalBeatSeconds
            val wrapped = positiveMod(time - expected, nominalBeatSeconds)
            points += TracePoint(
                timeSeconds = time - origin,
                phaseMs = wrapped * 1_000.0,
                alternate = index % 2 == 1,
            )
        }
        return points
    }

    private fun indexBeats(times: DoubleArray, nominal: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(times.size)
        var index = 0.0
        var previous = times.first()
        out += previous to index
        for (i in 1 until times.size) {
            val gap = times[i] - previous
            if (gap <= 0.0) continue
            val steps = (gap / nominal).roundToInt().coerceAtLeast(1)
            index += steps
            out += times[i] to index
            previous = times[i]
        }
        return out
    }

    private fun beatErrorSeconds(times: DoubleArray, nominal: Double): Double {
        val evens = ArrayList<Double>()
        val odds = ArrayList<Double>()
        var index = 0
        var previous = times.first()
        for (i in 1 until times.size) {
            val gap = times[i] - previous
            if (gap <= 0.0) continue
            val steps = (gap / nominal).roundToInt().coerceAtLeast(1)
            if (steps == 1 && gap in nominal * 0.6..nominal * 1.4) {
                if (index % 2 == 0) evens += gap else odds += gap
            }
            index += steps
            previous = times[i]
        }
        if (evens.size < 3 || odds.size < 3) return 0.0
        return abs(mean(evens) - mean(odds)) / 2.0
    }

    private fun medianLift(lifts: DoubleArray): Double? {
        val usable = lifts.filter { it in 0.001..0.030 }.toDoubleArray()
        if (usable.size < 4) return null
        return median(usable)
    }

    private fun slope(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        if (n < 2) return Double.NaN
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sx += x[i]
            sy += y[i]
            sxx += x[i] * x[i]
            sxy += x[i] * y[i]
        }
        val denominator = n * sxx - sx * sx
        if (abs(denominator) < 1e-9) return Double.NaN
        return (n * sxy - sx * sy) / denominator
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun mean(values: List<Double>): Double = values.sum() / values.size

    private fun positiveMod(value: Double, modulus: Double): Double {
        val raw = value % modulus
        return if (raw < 0.0) raw + modulus else raw
    }
}
