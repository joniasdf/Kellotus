package fi.rapanen.kellotus.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.rapanen.kellotus.KellotusUiState
import fi.rapanen.kellotus.KellotusViewModel
import fi.rapanen.kellotus.LocationPermission
import fi.rapanen.kellotus.NtpStatus
import fi.rapanen.kellotus.R
import fi.rapanen.kellotus.gps.GpsFix
import fi.rapanen.kellotus.gps.GpsMethod
import fi.rapanen.kellotus.ntp.NtpFix
import fi.rapanen.kellotus.ntp.NtpServers
import fi.rapanen.kellotus.time.AnchoredTime
import fi.rapanen.kellotus.time.ClockFace
import fi.rapanen.kellotus.time.FinnishFormat
import kotlinx.coroutines.isActive

const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
fun KellotusScreen(viewModel: KellotusViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val elapsed = rememberElapsedRealtimeNanos()

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onFinePermissionResult(granted)
    }
    val localLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onLocalNetworkPermissionResult(granted)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            val local = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
                context,
                LOCAL_NETWORK_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onHostStarted(fine, rationale, local)
                Lifecycle.Event.ON_RESUME -> viewModel.refreshPermissions(fine, rationale, local)
                Lifecycle.Event.ON_STOP -> viewModel.onHostStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHostStopped()
        }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(containerColor = Ink) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            val wide = maxWidth >= 760.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header()
                    ServerCard(
                        state = state,
                        onSelect = viewModel::selectServer,
                        onCustomHost = viewModel::setCustomHost,
                        onAuto = viewModel::setAutoMinutes,
                        onSync = viewModel::syncNow,
                        onRequestLocal = {
                            if (Build.VERSION.SDK_INT >= 37) localLauncher.launch(LOCAL_NETWORK_PERMISSION)
                        },
                    )
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NtpCard(state, elapsed, Modifier.weight(1f))
                            GpsCard(
                                state = state,
                                elapsed = elapsed,
                                onGrant = {
                                    viewModel.markFineAsked()
                                    fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                },
                                onSettings = { openAppSettings(activity) },
                                onLocationSettings = { openLocationSettings(activity) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        NtpCard(state, elapsed, Modifier.fillMaxWidth())
                        GpsCard(
                            state = state,
                            elapsed = elapsed,
                            onGrant = {
                                viewModel.markFineAsked()
                                fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            },
                            onSettings = { openAppSettings(activity) },
                            onLocationSettings = { openLocationSettings(activity) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    DifferenceCard(state.ntp, state.gps, elapsed)
                    Text(
                        text = stringResource(R.string.footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun rememberElapsedRealtimeNanos(): Long {
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos {
                elapsed = SystemClock.elapsedRealtimeNanos()
            }
        }
    }
    return elapsed
}

@Composable
private fun Header() {
    val device = ClockFace.read(System.currentTimeMillis() * 1_000_000L, ClockFace.HELSINKI)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                color = Paper,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
            Text(
                text = stringResource(R.string.subtitle),
                color = Muted,
                fontSize = 13.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.device_clock), color = Muted, fontSize = 11.sp)
            Text(
                text = "${device.hms}.${device.millis}",
                color = Paper,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerCard(
    state: KellotusUiState,
    onSelect: (String) -> Unit,
    onCustomHost: (String) -> Unit,
    onAuto: (Int) -> Unit,
    onSync: () -> Unit,
    onRequestLocal: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.servers.find { it.id == state.selectedId }
    val fieldValue = if (state.selectedId == NtpServers.CUSTOM) {
        stringResource(R.string.custom_server)
    } else {
        selected?.host.orEmpty()
    }
    val syncing = state.ntpStatus is NtpStatus.Syncing
    Panel {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.server_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.servers.forEach { server ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(server.label)
                                Text(server.host, color = Muted, fontSize = 12.sp)
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(server.id)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.custom_server)) },
                    onClick = {
                        expanded = false
                        onSelect(NtpServers.CUSTOM)
                    },
                )
            }
        }
        if (state.selectedId == NtpServers.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.customHost,
                onValueChange = onCustomHost,
                singleLine = true,
                isError = state.customHostError != null,
                label = { Text(stringResource(R.string.custom_server)) },
                placeholder = { Text(stringResource(R.string.custom_hint)) },
                supportingText = state.customHostError?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSync() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.auto_sync), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoChip(stringResource(R.string.auto_off), state.autoMinutes == 0) { onAuto(0) }
            AutoChip(stringResource(R.string.auto_5), state.autoMinutes == 5) { onAuto(5) }
            AutoChip(stringResource(R.string.auto_15), state.autoMinutes == 15) { onAuto(15) }
            AutoChip(stringResource(R.string.auto_60), state.autoMinutes == 60) { onAuto(60) }
        }
        Text(
            text = stringResource(R.string.auto_help),
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSync, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
            Text(if (syncing) stringResource(R.string.syncing) else stringResource(R.string.sync))
        }
        if (state.ntpStatus is NtpStatus.Syncing) {
            val status = state.ntpStatus
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val best = status.bestDelayNanos?.let { FinnishFormat.formatDuration(it) }
            val line = buildString {
                append(stringResource(R.string.sample_progress, status.done, status.total))
                if (best != null) {
                    append(" · ")
                    append(stringResource(R.string.best_delay, best))
                }
            }
            Text(line, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (state.ntpStatus is NtpStatus.Error) {
            Text(
                text = state.ntpStatus.message,
                color = Alarm,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.needsLocalPermission) {
            Text(
                text = stringResource(R.string.local_perm),
                color = Paper,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onRequestLocal, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.grant_local))
            }
        }
    }
}

@Composable
private fun AutoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun NtpCard(state: KellotusUiState, elapsed: Long, modifier: Modifier) {
    val fix = state.ntp
    Panel(accent = Brass, modifier = modifier) {
        SectionTitle(stringResource(R.string.ntp_title), Brass)
        if (fix == null) {
            BigTime("--:--:--", "---", Brass.copy(alpha = 0.4f))
            Text(stringResource(R.string.no_sync), color = Muted, fontSize = 13.sp)
        } else {
            RunningClock(fix.anchor, elapsed, Brass, "${stringResource(R.string.ntp_title)} ")
            if (fix.leapIndicator == 1) {
                Text(stringResource(R.string.leap_insert), color = Brass, fontSize = 12.sp)
            } else if (fix.leapIndicator == 2) {
                Text(stringResource(R.string.leap_delete), color = Brass, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Stat(stringResource(R.string.offset_device), FinnishFormat.formatSignedDelta(fix.offsetNanos))
            Stat(stringResource(R.string.rtt), FinnishFormat.formatDuration(fix.delayNanos))
            Stat(
                stringResource(R.string.stratum),
                if (fix.stratum == 1) {
                    stringResource(R.string.stratum_primary, fix.stratum)
                } else {
                    fix.stratum.toString()
                },
            )
            Stat(stringResource(R.string.reference), fix.referenceId.ifBlank { stringResource(R.string.dash) })
            Stat(stringResource(R.string.precision), FinnishFormat.formatPrecision(fix.precisionExponent))
            Stat(stringResource(R.string.samples), stringResource(R.string.samples_value, fix.acceptedSamples, fix.attemptCount))
            Stat(stringResource(R.string.address), fix.address)
            Stat(stringResource(R.string.synced), ageLabel(elapsed, fix.anchor.elapsedRealtimeNanos))
        }
    }
}

@Composable
private fun GpsCard(
    state: KellotusUiState,
    elapsed: Long,
    onGrant: () -> Unit,
    onSettings: () -> Unit,
    onLocationSettings: () -> Unit,
    modifier: Modifier,
) {
    Panel(accent = Signal, modifier = modifier) {
        SectionTitle(stringResource(R.string.gps_title), Signal)
        when (state.locationPermission) {
            LocationPermission.Unknown -> {
                BigTime("--:--:--", "---", Signal.copy(alpha = 0.4f))
                Text(stringResource(R.string.checking), color = Muted, fontSize = 13.sp)
            }
            LocationPermission.DeniedCanAsk -> PermissionBlock(
                body = stringResource(R.string.perm_body),
                action = stringResource(R.string.perm_grant),
                onClick = onGrant,
            )
            LocationPermission.DeniedForever -> PermissionBlock(
                body = stringResource(R.string.perm_denied),
                action = stringResource(R.string.open_settings),
                onClick = onSettings,
            )
            LocationPermission.Granted -> GpsGranted(state, elapsed, onLocationSettings)
        }
    }
}

@Composable
private fun GpsGranted(state: KellotusUiState, elapsed: Long, onLocationSettings: () -> Unit) {
    val fix = state.gps
    if (!state.gpsProviderEnabled) {
        Text(stringResource(R.string.gps_off), color = Paper, fontSize = 14.sp)
        OutlinedButton(onClick = onLocationSettings, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.open_location_settings))
        }
    }
    if (fix == null) {
        BigTime("--:--:--", "---", Signal.copy(alpha = 0.4f))
        Text(stringResource(R.string.waiting_gps), color = Muted, fontSize = 13.sp)
    } else {
        RunningClock(fix.anchor, elapsed, Signal, "${stringResource(R.string.gps_title)} ")
        Spacer(Modifier.height(10.dp))
        Stat(stringResource(R.string.method), methodLabel(fix.method))
        Stat(stringResource(R.string.satellites), satelliteLabel(fix))
        Stat(
            stringResource(R.string.fix_label),
            if (fix.hasFix) stringResource(R.string.fix_yes) else stringResource(R.string.fix_no),
        )
        Stat(
            stringResource(R.string.leap),
            if (fix.leapFromReceiver) {
                stringResource(R.string.leap_gnss, fix.leapSeconds)
            } else {
                stringResource(R.string.leap_default, fix.leapSeconds)
            },
        )
        Stat(
            stringResource(R.string.uncertainty),
            fix.uncertaintyNanos?.let { FinnishFormat.formatUncertainty(it) } ?: stringResource(R.string.dash),
        )
        Stat(stringResource(R.string.offset_device), FinnishFormat.formatSignedDelta(fix.offsetNanos))
        Stat(stringResource(R.string.anchor), ageLabel(elapsed, fix.anchor.elapsedRealtimeNanos))
    }
    if (state.gpsRawSupported == false) {
        Text(
            text = stringResource(R.string.raw_unsupported),
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PermissionBlock(body: String, action: String, onClick: () -> Unit) {
    BigTime("--:--:--", "---", Signal.copy(alpha = 0.4f))
    Text(stringResource(R.string.perm_title), color = Paper, fontWeight = FontWeight.Medium)
    Text(body, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    Button(onClick = onClick, modifier = Modifier.padding(top = 10.dp)) {
        Text(action)
    }
}

@Composable
private fun DifferenceCard(ntp: NtpFix?, gps: GpsFix?, elapsed: Long) {
    val delta = if (ntp != null && gps != null) {
        ntp.anchor.unixEpochNanosAt(elapsed) - gps.anchor.unixEpochNanosAt(elapsed)
    } else {
        null
    }
    val color by animateColorAsState(
        targetValue = when {
            delta == null -> Muted
            kotlin.math.abs(delta) < 5_000_000L -> Signal
            kotlin.math.abs(delta) < 50_000_000L -> Brass
            else -> Alarm
        },
        label = "delta",
    )
    Panel(accent = color) {
        Text(stringResource(R.string.diff_title), color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        Text(
            text = delta?.let { FinnishFormat.formatSignedDelta(it) } ?: stringResource(R.string.dash),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 36.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = if (delta == null) stringResource(R.string.diff_missing) else stringResource(R.string.diff_hint),
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RunningClock(anchor: AnchoredTime, elapsed: Long, color: androidx.compose.ui.graphics.Color, prefix: String) {
    val nanos = anchor.unixEpochNanosAt(elapsed)
    val local = ClockFace.read(nanos, ClockFace.HELSINKI)
    val utc = ClockFace.read(nanos, ClockFace.UTC)
    val description = prefix + local.hms + "." + local.millis + ", " + local.date
    Column(modifier = Modifier.semantics { contentDescription = description }) {
        BigTime(local.hms, local.millis, color)
        Text(local.date, color = Muted, fontSize = 13.sp)
        Text(
            text = stringResource(R.string.utc_line, utc.hms, utc.millis),
            color = Paper.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun BigTime(hms: String, millis: String, color: androidx.compose.ui.graphics.Color) {
    BoxWithConstraints {
        val size = when {
            maxWidth < 280.dp -> 32.sp
            maxWidth < 380.dp -> 40.sp
            else -> 48.sp
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = hms,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = size,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
            )
            Text(
                text = ".$millis",
                color = color.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = size * 0.58f,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .size(8.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape),
        )
        Text(title, color = Paper, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, fontSize = 14.sp)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
        Text(
            text = value,
            color = Paper,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Panel(
    accent: androidx.compose.ui.graphics.Color = Line,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .border(1.dp, Line, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .drawBehind {
                drawRect(color = accent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
            }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        content = content,
    )
}

@Composable
private fun methodLabel(method: GpsMethod): String = when (method) {
    GpsMethod.GNSS_CLOCK -> stringResource(R.string.method_gnss)
    GpsMethod.GNSS_CLOCK_CALLBACK -> stringResource(R.string.method_gnss_callback)
    GpsMethod.LOCATION -> stringResource(R.string.method_location)
}

@Composable
private fun satelliteLabel(fix: GpsFix): String {
    val used = fix.usedSatellites
    val visible = fix.visibleSatellites
    return when {
        used != null && visible != null -> stringResource(R.string.satellites_used, used, visible)
        visible != null -> stringResource(R.string.satellites_visible, visible)
        else -> stringResource(R.string.dash)
    }
}

@Composable
private fun ageLabel(elapsedNow: Long, anchorElapsed: Long): String =
    FinnishFormat.formatAge(elapsedNow - anchorElapsed)

private fun openAppSettings(activity: Activity) {
    activity.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        },
    )
}

private fun openLocationSettings(activity: Activity) {
    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}
