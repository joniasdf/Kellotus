package fi.rapanen.kellotus

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import fi.rapanen.kellotus.ui.KellotusScreen
import fi.rapanen.kellotus.ui.KellotusTheme

class MainActivity : ComponentActivity() {
    private val viewModel: KellotusViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            KellotusTheme {
                KellotusScreen(viewModel)
            }
        }
    }
}
