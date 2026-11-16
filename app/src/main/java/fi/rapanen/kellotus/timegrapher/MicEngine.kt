package fi.rapanen.kellotus.timegrapher

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock

data class MicChunk(
    val samples: FloatArray,
    val totalSamples: Long,
    val elapsedRealtimeNanos: Long,
)

/**
 * Reads the microphone at 48 kHz on its own thread. UNPROCESSED is preferred
 * so gain control does not flatten the escapement shocks.
 */
class MicEngine(
    private val onChunk: (MicChunk) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    val sampleRate: Int get() = recorder?.sampleRate ?: SAMPLE_RATE

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val audio = openRecorder() ?: run {
            onFailure("Mikrofoni ei käynnisty.")
            return
        }
        recorder = audio
        running = true
        audio.startRecording()
        thread = Thread({
            val buffer = ShortArray(audio.sampleRate / 20)
            var total = 0L
            try {
                while (running) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    val floats = FloatArray(count) { buffer[it] / 32768f }
                    total += count
                    onChunk(MicChunk(floats, total, SystemClock.elapsedRealtimeNanos()))
                }
            } catch (error: Exception) {
                if (running) onFailure(error.message ?: "Mikrofoni katkesi.")
            }
        }, "kellotus-mic").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        recorder?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        recorder = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val buffer = maxOf(min, SAMPLE_RATE / 2)
        val sources = intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            val record = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer * 2,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
    }
}
