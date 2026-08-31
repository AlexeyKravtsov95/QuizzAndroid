package ru.poporyadku.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

// ITERATION_2_DESIGN.md, раздел 6 / D-10: отдельная Activity, собственный launcher
// entry только в app/src/debug/AndroidManifest.xml. Используется существующая
// продуктовая тема — этот экран не отдельная дизайн-система, а рабочий инструмент.
@AndroidEntryPoint
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PoPoRyadkuTheme {
                DebugScreen()
            }
        }
    }
}
