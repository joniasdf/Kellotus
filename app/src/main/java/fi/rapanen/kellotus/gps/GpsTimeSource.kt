package fi.rapanen.kellotus.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementsEvent
import android.location.GnssMeasurementRequest
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import fi.rapanen.kellotus.time.AnchoredTime
import kotlin.math.sqrt

/**
 * Prefers a GNSS hardware clock (fullBiasNanos) and falls back to the GPS
 * provider's location timestamp. Both are anchored to elapsed realtime so the
 * displayed clock keeps running between fixes.
 */
class GpsTimeSource(
    context: Context,
    private val onFix: (GpsFix) -> Unit,
    private val onLink: (providerEnabled: Boolean, rawSupported: Boolean?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var rawSupported: Boolean? = null
    private var clockFix: GpsFix? = null
    private var locationFix: GpsFix? = null
    private var usedSatellites: Int? = null
    private var visibleSatellites: Int? = null
    private var usedInFix = false

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val clock = event.clock
            if (!clock.hasFullBiasNanos()) return
            val bias = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
            val leap = GpsTimeMath.resolveLeapSeconds(if (clock.hasLeapSecond()) clock.leapSecond else null)
            val unix = try {
                GpsTimeMath.utcUnixNanos(clock.timeNanos, clock.fullBiasNanos, bias, leap.seconds)
            } catch (_: ArithmeticException) {
                return
            }
            val hasClockElapsed = Build.VERSION.SDK_INT >= 29 && clock.hasElapsedRealtimeNanos()
            val elapsed = if (hasClockElapsed) clock.elapsedRealtimeNanos else SystemClock.elapsedRealtimeNanos()
            clockFix = GpsFix(
                anchor = AnchoredTime(unix, elapsed),
                offsetNanos = unix - System.currentTimeMillis() * 1_000_000L,
                method = if (hasClockElapsed) GpsMethod.GNSS_CLOCK else GpsMethod.GNSS_CLOCK_CALLBACK,
                leapSeconds = leap.seconds,
                leapFromReceiver = leap.fromReceiver,
                uncertaintyNanos = uncertaintyOf(clock, hasClockElapsed),
                usedSatellites = usedSatellites,
                visibleSatellites = visibleSatellites,
                hasFix = usedInFix,
            )
            emit()
        }
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val visible = status.satelliteCount
            for (index in 0 until visible) {
                if (status.usedInFix(index)) used++
            }
            usedSatellites = used
            visibleSatellites = visible
            usedInFix = used > 0
            emit()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.time <= 0L) return
            val unix = GpsTimeMath.unixNanosFromLocationMillis(location.time)
            locationFix = GpsFix(
                anchor = AnchoredTime(unix, location.elapsedRealtimeNanos),
                offsetNanos = unix - System.currentTimeMillis() * 1_000_000L,
                method = GpsMethod.LOCATION,
                leapSeconds = GpsTimeMath.DEFAULT_LEAP_SECONDS,
                leapFromReceiver = false,
                uncertaintyNanos = null,
                usedSatellites = usedSatellites,
                visibleSatellites = visibleSatellites,
                hasFix = true,
            )
            emit()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        @Deprecated("Deprecated in LocationListener")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) onLink(true, rawSupported)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) onLink(false, rawSupported)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = locationManager ?: run {
            onLink(false, false)
            return
        }
        if (started) {
            onLink(providerEnabled(manager), rawSupported)
            return
        }
        started = true
        val enabled = providerEnabled(manager)
        rawSupported = registerMeasurements(manager)
        registerStatus(manager)
        if (enabled) {
            try {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
            } catch (_: IllegalArgumentException) {
                onLink(false, rawSupported)
                return
            } catch (_: SecurityException) {
                onLink(enabled, rawSupported)
                return
            }
        }
        onLink(enabled, rawSupported)
    }

    fun stop() {
        if (!started) return
        started = false
        val manager = locationManager ?: return
        try {
            manager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        try {
            manager.unregisterGnssMeasurementsCallback(measurementsCallback)
        } catch (_: Exception) {
        }
        try {
            manager.unregisterGnssStatusCallback(statusCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerMeasurements(manager: LocationManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val request = GnssMeasurementRequest.Builder().setFullTracking(true).build()
                manager.registerGnssMeasurementsCallback(request, mainExecutor, measurementsCallback)
            } else if (Build.VERSION.SDK_INT >= 30) {
                manager.registerGnssMeasurementsCallback(mainExecutor, measurementsCallback)
            } else {
                manager.registerGnssMeasurementsCallback(measurementsCallback, mainHandler)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerStatus(manager: LocationManager) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                manager.registerGnssStatusCallback(mainExecutor, statusCallback)
            } else {
                manager.registerGnssStatusCallback(statusCallback, mainHandler)
            }
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun providerEnabled(manager: LocationManager): Boolean =
        try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }

    private fun emit() {
        val now = SystemClock.elapsedRealtimeNanos()
        val clock = clockFix
        val clockFresh = clock != null && now - clock.anchor.elapsedRealtimeNanos < CLOCK_FRESH_NANOS
        val base = when {
            clockFresh -> clock
            locationFix != null -> locationFix
            else -> clock
        } ?: return
        val fixKnown = visibleSatellites != null
        onFix(
            base.copy(
                usedSatellites = usedSatellites,
                visibleSatellites = visibleSatellites,
                hasFix = if (fixKnown) usedInFix else base.hasFix,
            ),
        )
    }

    private fun uncertaintyOf(clock: android.location.GnssClock, hasClockElapsed: Boolean): Double? {
        val parts = ArrayList<Double>(3)
        if (clock.hasTimeUncertaintyNanos() && clock.timeUncertaintyNanos.isFinite()) {
            parts += clock.timeUncertaintyNanos
        }
        if (clock.hasBiasUncertaintyNanos() && clock.biasUncertaintyNanos.isFinite()) {
            parts += clock.biasUncertaintyNanos
        }
        if (hasClockElapsed && clock.hasElapsedRealtimeUncertaintyNanos() &&
            clock.elapsedRealtimeUncertaintyNanos.isFinite()
        ) {
            parts += clock.elapsedRealtimeUncertaintyNanos
        }
        if (parts.isEmpty()) return null
        return sqrt(parts.sumOf { it * it })
    }

    private companion object {
        const val CLOCK_FRESH_NANOS = 15_000_000_000L
    }
}
