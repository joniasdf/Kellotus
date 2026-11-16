package fi.rapanen.kellotus.timegrapher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Direct-form I biquad. Coefficients are already normalized by a0. */
internal class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(sample: Double): Double {
        val output = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = output
        return output
    }

    companion object {
        fun highPass(sampleRate: Double, frequency: Double, q: Double = 0.707): Biquad {
            val w0 = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosine = cos(w0)
            val b0 = (1.0 + cosine) / 2.0
            val b1 = -(1.0 + cosine)
            val b2 = (1.0 + cosine) / 2.0
            val a0 = 1.0 + alpha
            return Biquad(b0 / a0, b1 / a0, b2 / a0, -2.0 * cosine / a0, (1.0 - alpha) / a0)
        }

        fun lowPass(sampleRate: Double, frequency: Double, q: Double = 0.707): Biquad {
            val w0 = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosine = cos(w0)
            val b0 = (1.0 - cosine) / 2.0
            val b1 = 1.0 - cosine
            val b2 = (1.0 - cosine) / 2.0
            val a0 = 1.0 + alpha
            return Biquad(b0 / a0, b1 / a0, b2 / a0, -2.0 * cosine / a0, (1.0 - alpha) / a0)
        }
    }
}

data class DetectedTick(
    val sampleIndex: Long,
    /** Width of the escapement noise above 35% of its peak, in samples. */
    val liftSamples: Int,
)

/**
 * Band-pass, envelope and onset detector for a mechanical escapement.
 * One onset per tick/tock. The three shocks inside a single tick stay inside
 * the refractory window and are measured only as lift width.
 */
class TickDetector(
    private val sampleRate: Double,
    refractorySeconds: Double = 0.045,
) {
    private val highPass = Biquad.highPass(sampleRate, 1_500.0)
    private val lowPass = Biquad.lowPass(sampleRate, 8_000.0)
    private val envelopeAlpha = 1.0 - exp(-2.0 * PI * 350.0 / sampleRate)
    private val refractorySamples = (refractorySeconds * sampleRate).toInt().coerceAtLeast(1)
    private var envelope = 0.0
    private var noise = 0.002
    private var inBurst = false
    private var burstStart = 0L
    private var peakEnvelope = 0.0
    private var peakIndex = 0L
    private var lastOnset = Long.MIN_VALUE / 4
    private var cursor = 0L

    fun accept(samples: FloatArray): List<DetectedTick> {
        if (samples.isEmpty()) return emptyList()
        val found = ArrayList<DetectedTick>(4)
        for (sample in samples) {
            val filtered = lowPass.process(highPass.process(sample.toDouble()))
            envelope += envelopeAlpha * (abs(filtered) - envelope)
            val index = cursor++
            val threshold = maxOf(noise * 6.0, ABSOLUTE_FLOOR)
            if (!inBurst) {
                noise = noise * 0.997 + envelope * 0.003
                if (envelope > threshold) {
                    inBurst = true
                    burstStart = index
                    peakEnvelope = envelope
                    peakIndex = index
                }
            } else if (envelope >= peakEnvelope) {
                peakEnvelope = envelope
                peakIndex = index
            } else if (envelope < peakEnvelope * 0.35 || index - burstStart > MAX_BURST_SAMPLES) {
                closeBurst(index, found)
            }
        }
        return found
    }

    fun reset() {
        envelope = 0.0
        noise = 0.002
        inBurst = false
        peakEnvelope = 0.0
        lastOnset = Long.MIN_VALUE / 4
        cursor = 0L
    }

    private fun closeBurst(index: Long, found: MutableList<DetectedTick>) {
        val lift = (index - burstStart).toInt().coerceAtLeast(1)
        if (peakIndex - lastOnset >= refractorySamples && peakEnvelope >= ABSOLUTE_FLOOR) {
            found += DetectedTick(peakIndex, lift)
            lastOnset = peakIndex
        }
        inBurst = false
        peakEnvelope = 0.0
    }

    private companion object {
        const val ABSOLUTE_FLOOR = 0.02
        const val MAX_BURST_SAMPLES = 48_000 / 20
    }
}
