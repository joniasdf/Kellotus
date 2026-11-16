package fi.rapanen.kellotus.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF071018)
val Card = Color(0xFF101C28)
val Line = Color(0xFF24384A)
val Brass = Color(0xFFE2B15A)
val Signal = Color(0xFF3CDEC0)
val Paper = Color(0xFFE7EEF4)
val Muted = Color(0xFF8BA0B3)
val Alarm = Color(0xFFFF8A80)

private val scheme = darkColorScheme(
    primary = Brass,
    onPrimary = Color(0xFF1C1404),
    secondary = Signal,
    onSecondary = Color(0xFF04221C),
    background = Ink,
    surface = Card,
    surfaceVariant = Color(0xFF182838),
    onBackground = Paper,
    onSurface = Paper,
    onSurfaceVariant = Muted,
    error = Alarm,
    outline = Line,
)

@Composable
fun KellotusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
