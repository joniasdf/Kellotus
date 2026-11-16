package fi.rapanen.kellotus

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import fi.rapanen.kellotus.time.AnchoredTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import fi.rapanen.kellotus.timegrapher.AudioCalibration
import fi.rapanen.kellotus.timegrapher.DetectedTick
import fi.rapanen.kellotus.timegrapher.MicEngine
import fi.rapanen.kellotus.timegrapher.RegulatorAdvice
import fi.rapanen.kellotus.timegrapher.TickDetector
import fi.rapanen.kellotus.timegrapher.TracePoint
import fi.rapanen.kellotus.timegrapher.WatchAnalysis
import kotlin.math.log10
import kotlin.math.sqrt

enum class MicPermission { Unknown, Granted, Denied }

enum class MicLevel { Silent, Noisy, Ticks, Hot }

data class TimegrapherUiState(
    val listening: Boolean = false,
    val permission: MicPermission = MicPermission.Unknown,
    val levelDbfs: Double = -90.0,
    val level: MicLevel = MicLevel.Silent,
    val selectedBph: Int? = null,
    val detectedBph: Int? = null,
    val liftAngleText: String = "52",
    val rateSecondsPerDay: Double? = null,
    val beatErrorMs: Double? = null,
    val amplitudeDegrees: Double? = null,
    val advice: RegulatorAdvice? = null,
    val beatCount: Int = 0,
    val trace: List<TracePoint> = emptyList(),
    val tracePeriodMs: Double = 125.0,
    val calibration: AudioCalibration? = null,
    val ntpScaled: Boolean = false,
    val error: String? = null,
)

class TimegrapherViewModel(app: Application) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var engine: MicEngine? = null
    private var detector = TickDetector(MicEngine.SAMPLE_RATE.toDouble())
    private val ticks = ArrayDeque<DetectedTick>()
    private var levelDbfs = -90.0
    private var recentTicks = 0
    private var recentTickMark = 0L
    private var originSample = 0L
    private var originElapsed = 0L
    private var latestSample = 0L
    private var latestElapsed = 0L
    private var publishPosted = false
    private var previousNtp: AnchoredTime? = null
    private var utcPerMono = 1.0

    private val _state = MutableStateFlow(TimegrapherUiState())
    val state: StateFlow<TimegrapherUiState> = _state.asStateFlow()

    fun setPermission(granted: Boolean) {
        update(_state.value.copy(permission = if (granted) MicPermission.Granted else MicPermission.Denied))
        if (!granted) stop()
    }

    fun selectBph(bph: Int?) {
        update(_state.value.copy(selectedBph = bph))
        publish()
    }

    fun setLiftAngle(text: String) {
        val cleaned = text.filter { it.isDigit() }.take(3)
        update(_state.value.copy(liftAngleText = cleaned))
        publish()
    }

    fun noteNtpAnchor(anchor: AnchoredTime?) {
        if (anchor == null) return
        val previous = previousNtp
        if (previous != null) {
            val scale = WatchAnalysis.monoToUtcScale(
                previous.elapsedRealtimeNanos,
                previous.unixEpochNanos,
                anchor.elapsedRealtimeNanos,
                anchor.unixEpochNanos,
            )
            if (scale != null) utcPerMono = scale
        }
        previousNtp = anchor
        publish()
    }

    fun toggle() {
        if (_state.value.listening) stop() else start()
    }

    fun start() {
        if (_state.value.listening) return
        if (_state.value.permission != MicPermission.Granted) return
        synchronized(lock) {
            detector = TickDetector(MicEngine.SAMPLE_RATE.toDouble())
            ticks.clear()
            originSample = 0L
            originElapsed = 0L
            latestSample = 0L
            latestElapsed = 0L
            recentTicks = 0
        }
        val mic = MicEngine(
            onChunk = ::onChunk,
            onFailure = { message ->
                main.post {
                    stop()
                    update(_state.value.copy(error = message, listening = false))
                }
            },
        )
        engine = mic
        update(_state.value.copy(listening = true, error = null, beatCount = 0, rateSecondsPerDay = null, trace = emptyList()))
        mic.start()
    }

    fun stop() {
        engine?.stop()
        engine = null
        if (_state.value.listening) update(_state.value.copy(listening = false, level = MicLevel.Silent))
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun onChunk(chunk: fi.rapanen.kellotus.timegrapher.MicChunk) {
        var sum = 0.0
        for (sample in chunk.samples) sum += sample * sample
        val rms = sqrt(sum / chunk.samples.size)
        val db = if (rms > 1e-8) 20.0 * log10(rms) else -90.0
        val found = detector.accept(chunk.samples)
        val chunkNanos = chunk.samples.size * 1_000_000_000L / MicEngine.SAMPLE_RATE
        val chunkStart = chunk.elapsedRealtimeNanos - chunkNanos
        synchronized(lock) {
            if (originElapsed == 0L) {
                originElapsed = chunkStart
                originSample = chunk.totalSamples - chunk.samples.size
            }
            latestElapsed = chunk.elapsedRealtimeNanos
            latestSample = chunk.totalSamples
            levelDbfs = db
            for (tick in found) ticks += tick
            val horizon = chunk.totalSamples - MicEngine.SAMPLE_RATE * 40L
            while (ticks.isNotEmpty() && ticks.first().sampleIndex < horizon) ticks.removeFirst()
            val freshAfter = chunk.totalSamples - MicEngine.SAMPLE_RATE * 2L
            if (chunk.totalSamples - recentTickMark > MicEngine.SAMPLE_RATE) {
                recentTicks = ticks.count { it.sampleIndex >= freshAfter }
                recentTickMark = chunk.totalSamples
            } else if (found.isNotEmpty()) {
                recentTicks = ticks.count { it.sampleIndex >= freshAfter }
            }
        }
        schedulePublish()
    }

    private fun schedulePublish() {
        if (publishPosted) return
        publishPosted = true
        main.postDelayed({
            publishPosted = false
            publish()
        }, 90)
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            SessionSnapshot(
                ticks = ticks.toList(),
                levelDbfs = levelDbfs,
                recentTicks = recentTicks,
                samples = (latestSample - originSample).coerceAtLeast(0L),
                elapsed = (latestElapsed - originElapsed).coerceAtLeast(0L),
            )
        }
        val calibration = WatchAnalysis.audioCalibration(
            nominalHz = MicEngine.SAMPLE_RATE.toDouble(),
            sampleCount = snapshot.samples,
            elapsedNanos = snapshot.elapsed,
            utcPerMono = utcPerMono,
        )
        val hz = calibration?.measuredHz ?: MicEngine.SAMPLE_RATE.toDouble()
        val times = DoubleArray(snapshot.ticks.size) { snapshot.ticks[it].sampleIndex / hz }
        val lifts = DoubleArray(snapshot.ticks.size) { snapshot.ticks[it].liftSamples / hz }
        val current = _state.value
        val liftAngle = current.liftAngleText.toDoubleOrNull()?.takeIf { it in 20.0..80.0 }
            ?: WatchAnalysis.DEFAULT_LIFT_ANGLE
        val reading = WatchAnalysis.analyze(times, current.selectedBph, liftAngle, lifts)
        val level = when {
            snapshot.levelDbfs > -6.0 -> MicLevel.Hot
            snapshot.recentTicks >= 4 -> MicLevel.Ticks
            snapshot.levelDbfs < -52.0 -> MicLevel.Silent
            else -> MicLevel.Noisy
        }
        update(
            current.copy(
                levelDbfs = snapshot.levelDbfs,
                level = if (current.listening) level else MicLevel.Silent,
                detectedBph = reading?.bph,
                rateSecondsPerDay = reading?.rateSecondsPerDay,
                beatErrorMs = reading?.beatErrorMs,
                amplitudeDegrees = reading?.amplitudeDegrees,
                advice = reading?.advice,
                beatCount = snapshot.ticks.size,
                trace = reading?.trace ?: emptyList(),
                tracePeriodMs = ((reading?.bph ?: current.selectedBph)?.let { WatchAnalysis.nominalBeatSeconds(it) } ?: 0.125) * 1_000.0,
                calibration = calibration,
                ntpScaled = utcPerMono != 1.0,
            ),
        )
    }

    private fun update(next: TimegrapherUiState) {
        _state.value = next
    }

    private data class SessionSnapshot(
        val ticks: List<DetectedTick>,
        val levelDbfs: Double,
        val recentTicks: Int,
        val samples: Long,
        val elapsed: Long,
    )
}
