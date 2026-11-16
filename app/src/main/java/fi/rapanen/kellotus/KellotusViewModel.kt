package fi.rapanen.kellotus

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.rapanen.kellotus.gps.GpsFix
import fi.rapanen.kellotus.gps.GpsTimeSource
import fi.rapanen.kellotus.ntp.HostNames
import fi.rapanen.kellotus.ntp.NtpClient
import fi.rapanen.kellotus.ntp.NtpFailure
import fi.rapanen.kellotus.ntp.NtpFix
import fi.rapanen.kellotus.ntp.NtpOutcome
import fi.rapanen.kellotus.ntp.NtpServer
import fi.rapanen.kellotus.ntp.NtpServers
import fi.rapanen.kellotus.time.AndroidTimebase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LocationPermission {
    Unknown,
    Granted,
    DeniedCanAsk,
    DeniedForever,
}

sealed interface NtpStatus {
    data object Idle : NtpStatus
    data class Syncing(val done: Int, val total: Int, val bestDelayNanos: Long?) : NtpStatus
    data class Error(val message: String) : NtpStatus
}

data class KellotusUiState(
    val servers: List<NtpServer> = NtpServers.all,
    val selectedId: String = NtpServers.DEFAULT_ID,
    val customHost: String = "",
    val customHostError: String? = null,
    val autoMinutes: Int = 15,
    val ntpStatus: NtpStatus = NtpStatus.Idle,
    val ntp: NtpFix? = null,
    val locationPermission: LocationPermission = LocationPermission.Unknown,
    val localNetworkGranted: Boolean = true,
    val needsLocalPermission: Boolean = false,
    val gpsProviderEnabled: Boolean = true,
    val gpsRawSupported: Boolean? = null,
    val gps: GpsFix? = null,
)

class KellotusViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = KellotusPrefs(app)
    private val ntpClient = NtpClient(AndroidTimebase())
    private val gps = GpsTimeSource(
        context = app,
        onFix = { fix -> _state.update { it.copy(gps = fix) } },
        onLink = { enabled, raw ->
            _state.update { it.copy(gpsProviderEnabled = enabled, gpsRawSupported = raw) }
        },
    )

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<KellotusUiState> = _state.asStateFlow()

    private var hostStarted = false
    private var generation = 0
    private var syncJob: Job? = null
    private var autoJob: Job? = null

    fun onHostStarted(fineGranted: Boolean, shouldShowFineRationale: Boolean, localGranted: Boolean) {
        hostStarted = true
        applyPermissions(fineGranted, shouldShowFineRationale, localGranted)
        if (fineGranted) gps.start() else gps.stop()
        if (shouldSyncOnStart()) syncNow()
        armAuto()
    }

    fun onHostStopped() {
        hostStarted = false
        autoJob?.cancel()
        gps.stop()
    }

    fun refreshPermissions(fineGranted: Boolean, shouldShowFineRationale: Boolean, localGranted: Boolean) {
        applyPermissions(fineGranted, shouldShowFineRationale, localGranted)
        if (!hostStarted) return
        if (fineGranted) gps.start() else gps.stop()
    }

    fun markFineAsked() {
        prefs.askedFineLocation = true
    }

    fun onFinePermissionResult(granted: Boolean) {
        prefs.askedFineLocation = true
        _state.update {
            it.copy(
                locationPermission = if (granted) {
                    LocationPermission.Granted
                } else {
                    LocationPermission.DeniedCanAsk
                },
            )
        }
        if (granted && hostStarted) gps.start()
    }

    fun onLocalNetworkPermissionResult(granted: Boolean) {
        val allowed = Build.VERSION.SDK_INT < 37 || granted
        _state.update { it.copy(localNetworkGranted = allowed, needsLocalPermission = !allowed) }
        if (allowed) syncNow()
    }

    fun selectServer(id: String) {
        if (id != NtpServers.CUSTOM && NtpServers.byId(id) == null) return
        prefs.serverId = id
        _state.update { it.copy(selectedId = id, customHostError = null, needsLocalPermission = false) }
        syncNow()
    }

    fun setCustomHost(value: String) {
        prefs.customHost = value
        _state.update { it.copy(customHost = value, customHostError = null) }
    }

    fun setAutoMinutes(minutes: Int) {
        if (minutes !in AUTO_CHOICES) return
        prefs.autoMinutes = minutes
        _state.update { it.copy(autoMinutes = minutes) }
        armAuto()
    }

    fun syncNow() {
        val host = selectedHost()
        if (host == null) {
            _state.update { it.copy(customHostError = text(R.string.err_host)) }
            return
        }
        val gen = ++generation
        syncJob?.cancel()
        _state.update {
            it.copy(
                customHostError = null,
                needsLocalPermission = false,
                ntpStatus = NtpStatus.Syncing(0, 8, null),
            )
        }
        syncJob = viewModelScope.launch {
            val outcome = try {
                ntpClient.sync(
                    host = host,
                    allowLocalNetwork = _state.value.localNetworkGranted,
                    onProgress = { done, total, best ->
                        if (gen == generation) {
                            _state.update { state ->
                                state.copy(ntpStatus = NtpStatus.Syncing(done, total, best))
                            }
                        }
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "NTP sync failed", error)
                NtpOutcome.Failure(NtpFailure.NETWORK, error.message)
            }
            if (gen != generation) return@launch
            when (outcome) {
                is NtpOutcome.Success -> _state.update {
                    it.copy(ntpStatus = NtpStatus.Idle, ntp = outcome.fix, needsLocalPermission = false)
                }
                is NtpOutcome.Failure -> {
                    Log.w(TAG, "NTP ${outcome.reason} ${outcome.detail.orEmpty()}")
                    _state.update {
                        it.copy(
                            ntpStatus = NtpStatus.Error(messageFor(outcome.reason)),
                            needsLocalPermission = outcome.reason == NtpFailure.LOCAL_NETWORK,
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        gps.stop()
        super.onCleared()
    }

    private fun initialState(): KellotusUiState {
        val storedId = prefs.serverId
        val selected = if (storedId == NtpServers.CUSTOM || NtpServers.byId(storedId) != null) {
            storedId
        } else {
            NtpServers.DEFAULT_ID
        }
        val minutes = prefs.autoMinutes.takeIf { it in AUTO_CHOICES } ?: 15
        return KellotusUiState(
            selectedId = selected,
            customHost = prefs.customHost,
            autoMinutes = minutes,
            localNetworkGranted = Build.VERSION.SDK_INT < 37,
        )
    }

    private fun applyPermissions(fineGranted: Boolean, shouldShowFineRationale: Boolean, localGranted: Boolean) {
        val permission = when {
            fineGranted -> LocationPermission.Granted
            shouldShowFineRationale || !prefs.askedFineLocation -> LocationPermission.DeniedCanAsk
            else -> LocationPermission.DeniedForever
        }
        _state.update {
            it.copy(
                locationPermission = permission,
                localNetworkGranted = Build.VERSION.SDK_INT < 37 || localGranted,
            )
        }
    }

    private fun shouldSyncOnStart(): Boolean {
        if (_state.value.ntpStatus is NtpStatus.Syncing) return false
        val fix = _state.value.ntp ?: return true
        val age = SystemClock.elapsedRealtimeNanos() - fix.anchor.elapsedRealtimeNanos
        val minutes = _state.value.autoMinutes
        val threshold = (if (minutes > 0) minutes else 1) * 60_000_000_000L
        return age >= threshold
    }

    private fun selectedHost(): String? {
        val snapshot = _state.value
        return if (snapshot.selectedId == NtpServers.CUSTOM) {
            snapshot.customHost.trim().takeIf { HostNames.isAcceptable(it) }
        } else {
            NtpServers.byId(snapshot.selectedId)?.host
        }
    }

    private fun armAuto() {
        autoJob?.cancel()
        val minutes = _state.value.autoMinutes
        if (!hostStarted || minutes <= 0) return
        autoJob = viewModelScope.launch {
            while (isActive) {
                delay(minutes * 60_000L)
                syncNow()
            }
        }
    }

    private fun messageFor(reason: NtpFailure): String = when (reason) {
        NtpFailure.UNKNOWN_HOST -> text(R.string.err_unknown_host)
        NtpFailure.TIMEOUT -> text(R.string.err_timeout)
        NtpFailure.RATE_LIMITED -> text(R.string.err_rate)
        NtpFailure.INVALID -> text(R.string.err_invalid)
        NtpFailure.NETWORK -> text(R.string.err_network)
        NtpFailure.NO_SAMPLES -> text(R.string.err_no_samples)
        NtpFailure.LOCAL_NETWORK -> text(R.string.err_local)
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private companion object {
        const val TAG = "Kellotus"
        val AUTO_CHOICES = setOf(0, 5, 15, 60)
    }
}
