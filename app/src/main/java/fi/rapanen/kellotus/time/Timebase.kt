package fi.rapanen.kellotus.time

/**
 * Wall time is only a label for the NTP originate timestamp.
 * The round trip is measured with [elapsedRealtimeNanos], which must be
 * monotonic and include time spent asleep (CLOCK_BOOTTIME).
 */
interface Timebase {
    fun wallUnixNanos(): Long
    fun elapsedRealtimeNanos(): Long
}
