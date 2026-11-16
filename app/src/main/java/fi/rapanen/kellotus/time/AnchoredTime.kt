package fi.rapanen.kellotus.time

/**
 * A UTC instant tied to a monotonic elapsed-realtime reading.
 * Later display time is [unixEpochNanos] plus the elapsed delta, so it does
 * not follow jumps of the device wall clock.
 */
data class AnchoredTime(
    val unixEpochNanos: Long,
    val elapsedRealtimeNanos: Long,
) {
    fun unixEpochNanosAt(elapsedNow: Long): Long =
        Math.addExact(unixEpochNanos, Math.subtractExact(elapsedNow, elapsedRealtimeNanos))
}
