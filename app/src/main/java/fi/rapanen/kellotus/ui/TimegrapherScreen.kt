package fi.rapanen.kellotus.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.rapanen.kellotus.MicLevel
import fi.rapanen.kellotus.MicPermission
import fi.rapanen.kellotus.R
import fi.rapanen.kellotus.TimegrapherUiState
import fi.rapanen.kellotus.TimegrapherViewModel
import fi.rapanen.kellotus.ntp.NtpFix
import fi.rapanen.kellotus.time.FinnishFormat
import fi.rapanen.kellotus.timegrapher.RegulatorAdvice
import fi.rapanen.kellotus.timegrapher.TracePoint
import fi.rapanen.kellotus.timegrapher.WatchAnalysis
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun TimegrapherScreen(ntp: NtpFix?, elapsedRealtimeNanos: Long) {
    val viewModel: TimegrapherViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setPermission(granted)
        if (granted) viewModel.start()
    }

    LaunchedEffect(ntp?.anchor) {
        viewModel.noteNtpAnchor(ntp?.anchor)
    }
    DisposableEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.setPermission(granted)
        onDispose { viewModel.stop() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MeterCard {
            Text(
                text = stringResource(R.string.tg_lead),
                color = Muted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            LevelMeter(state)
            Text(levelHint(state), color = Paper, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            if (state.permission != MicPermission.Granted) {
                Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tg_grant))
                }
            } else if (state.listening) {
                OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tg_stop))
                }
            } else {
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tg_listen))
                }
            }
            state.error?.let { Text(it, color = Alarm, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }

        MeterCard {
            Text(stringResource(R.string.tg_bph), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.selectedBph == null,
                    onClick = { viewModel.selectBph(null) },
                    label = { Text(stringResource(R.string.tg_auto)) },
                )
                WatchAnalysis.COMMON_BPH.forEach { bph ->
                    FilterChip(
                        selected = state.selectedBph == bph,
                        onClick = { viewModel.selectBph(bph) },
                        label = { Text(bph.toString()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.liftAngleText,
                onValueChange = viewModel::setLiftAngle,
                label = { Text(stringResource(R.string.tg_lift)) },
                suffix = { Text("°") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MeterCard {
            val rate = state.rateSecondsPerDay
            Text(
                text = if (state.detectedBph != null) {
                    stringResource(R.string.tg_rate_bph, state.detectedBph!!)
                } else {
                    stringResource(R.string.tg_rate)
                },
                color = Muted,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = rate?.let { formatRate(it) } ?: stringResource(R.string.dash),
                color = adviceColor(state.advice),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 40.sp,
            )
            Text(
                text = rateWord(rate),
                color = Paper,
                fontSize = 16.sp,
            )
            state.advice?.let { advice ->
                Text(
                    text = adviceText(advice),
                    color = adviceColor(advice),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (state.beatCount < 12 && state.listening) {
                Text(
                    text = stringResource(R.string.tg_collecting, state.beatCount),
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            StatLine(
                stringResource(R.string.tg_beat_error),
                state.beatErrorMs?.let { formatFixed(it, 2) + " ms" } ?: stringResource(R.string.dash),
            )
            StatLine(
                stringResource(R.string.tg_amplitude),
                state.amplitudeDegrees?.let { formatFixed(it, 0) + "°" } ?: stringResource(R.string.dash),
            )
        }

        MeterCard {
            Text(stringResource(R.string.tg_trace), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            TracePlot(state.trace, state.tracePeriodMs, Modifier.fillMaxWidth().height(220.dp))
            Text(
                text = stringResource(R.string.tg_trace_hint),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        MeterCard {
            Text(stringResource(R.string.tg_cal_title), color = Muted, fontSize = 12.sp)
            val calibration = state.calibration
            if (calibration == null) {
                Text(stringResource(R.string.tg_cal_wait), color = Paper, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text(
                    text = formatFixed(calibration.measuredHz, 1) + " Hz · " + formatSigned(calibration.ppm, 1) + " ppm",
                    color = Paper,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.tg_cal_window, formatFixed(calibration.windowSeconds, 1)),
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
            val ntpLine = if (ntp == null) {
                stringResource(R.string.tg_cal_no_ntp)
            } else if (state.ntpScaled) {
                stringResource(R.string.tg_cal_ntp_scaled)
            } else {
                stringResource(R.string.tg_cal_ntp_anchor, FinnishFormat.formatAge(elapsedRealtimeNanos - ntp.anchor.elapsedRealtimeNanos))
            }
            Text(ntpLine, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                text = stringResource(R.string.tg_cal_note),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LevelMeter(state: TimegrapherUiState) {
    val fraction = ((state.levelDbfs + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
    val color = when (state.level) {
        MicLevel.Ticks -> Signal
        MicLevel.Hot -> Alarm
        MicLevel.Noisy -> Brass
        MicLevel.Silent -> Muted
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(Line, RoundedCornerShape(7.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(14.dp)
                .background(color, RoundedCornerShape(7.dp)),
        )
    }
}

@Composable
private fun TracePlot(points: List<TracePoint>, periodMs: Double, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(Ink, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp)),
    ) {
        Canvas(modifier = Modifier.matchParentSize().padding(8.dp)) {
            val period = periodMs.coerceAtLeast(1.0).toFloat()
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
                val y = size.height * (1f - fraction)
                drawLine(Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = dash)
            }
            if (points.size < 2) return@Canvas
            val end = points.last().timeSeconds
            val start = (end - 20.0).coerceAtLeast(points.first().timeSeconds)
            val span = (end - start).coerceAtLeast(0.5)
            points.forEach { point ->
                val x = ((point.timeSeconds - start) / span).toFloat() * size.width
                val y = size.height * (1f - (point.phaseMs / period).toFloat().coerceIn(0f, 1f))
                drawCircle(
                    color = if (point.alternate) Signal else Brass,
                    radius = 3.2f,
                    center = Offset(x, y),
                )
            }
        }
        if (points.isEmpty()) {
            Text(
                text = stringResource(R.string.tg_trace_empty),
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
    }
}

@Composable
private fun MeterCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(20.dp))
            .border(1.dp, Line, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun levelHint(state: TimegrapherUiState): String = when {
    !state.listening && state.permission == MicPermission.Granted -> stringResource(R.string.tg_place)
    state.level == MicLevel.Hot -> stringResource(R.string.tg_hot)
    state.level == MicLevel.Ticks -> stringResource(R.string.tg_ticks)
    state.level == MicLevel.Noisy -> stringResource(R.string.tg_noisy)
    state.listening -> stringResource(R.string.tg_quiet)
    else -> stringResource(R.string.tg_place)
}

@Composable
private fun rateWord(rate: Double?): String = when {
    rate == null -> stringResource(R.string.tg_no_rate)
    rate > 0.5 -> stringResource(R.string.tg_fast)
    rate < -0.5 -> stringResource(R.string.tg_slow)
    else -> stringResource(R.string.tg_on_time)
}

@Composable
private fun adviceText(advice: RegulatorAdvice): String = when (advice) {
    RegulatorAdvice.SLOW_DOWN -> stringResource(R.string.tg_slow_down)
    RegulatorAdvice.SPEED_UP -> stringResource(R.string.tg_speed_up)
    RegulatorAdvice.LEAVE -> stringResource(R.string.tg_leave)
}

private fun adviceColor(advice: RegulatorAdvice?) = when (advice) {
    RegulatorAdvice.LEAVE -> Signal
    RegulatorAdvice.SLOW_DOWN, RegulatorAdvice.SPEED_UP -> Brass
    null -> Muted
}

private val symbols = DecimalFormatSymbols(Locale.forLanguageTag("fi-FI")).apply { decimalSeparator = ',' }

private fun formatFixed(value: Double, digits: Int): String {
    val pattern = if (digits == 0) "0" else "0." + "0".repeat(digits)
    return DecimalFormat(pattern, symbols).apply { isGroupingUsed = false }.format(value)
}

private fun formatSigned(value: Double, digits: Int): String {
    if (value == 0.0) return formatFixed(0.0, digits)
    val sign = if (value > 0) "+" else "−"
    return sign + formatFixed(kotlin.math.abs(value), digits)
}

private fun formatRate(secondsPerDay: Double): String = formatSigned(secondsPerDay, 1) + " s/vrk"
