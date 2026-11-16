package fi.rapanen.kellotus.gps

import fi.rapanen.kellotus.time.AnchoredTime

enum class GpsMethod {
    /** GnssClock, anchored to the clock's own elapsedRealtimeNanos. */
    GNSS_CLOCK,

    /** GnssClock, anchored when the callback arrived. Used before API 29. */
    GNSS_CLOCK_CALLBACK,

    /** Location.getTime paired with Location.getElapsedRealtimeNanos. */
    LOCATION,
}

data class GpsFix(
    val anchor: AnchoredTime,
    val offsetNanos: Long,
    val method: GpsMethod,
    val leapSeconds: Int,
    val leapFromReceiver: Boolean,
    val uncertaintyNanos: Double?,
    val usedSatellites: Int?,
    val visibleSatellites: Int?,
    val hasFix: Boolean,
)
