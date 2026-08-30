package ru.poporyadku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.poporyadku.ui.navigation.AppNavHost
import ru.poporyadku.ui.theme.PoPoRyadkuTheme

// Single-activity architecture (ARCHITECTURE.md, раздел 10) — единственная Activity
// приложения, всё остальное — Composable-экраны за NavHost.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PoPoRyadkuTheme {
                AppNavHost()
            }
        }
    }
}
