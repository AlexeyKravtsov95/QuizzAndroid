package ru.poporyadku.ui.platform

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Граница внешних действий для экранов (ITERATION_5_DESIGN.md, §8.1).
 *
 * Продукт: `MainActivity` предоставляет единственный [AndroidExternalApps] своей Activity.
 * Тесты: `CompositionLocalProvider(LocalExternalApps provides FakeExternalApps(...))`.
 */
val LocalExternalApps = staticCompositionLocalOf<ExternalApps?> { null }

/**
 * Предоставленная граница, а если её нет (тест или превью без провайдера) — Android-
 * реализация для текущей `ComponentActivity`, запомненная на месте вызова.
 */
@Composable
fun rememberExternalApps(): ExternalApps {
    val provided = LocalExternalApps.current
    val context = LocalContext.current
    return remember(provided, context) {
        provided ?: AndroidExternalApps(context.findComponentActivity(), SystemClock::elapsedRealtime)
    }
}

/** `LocalContext` может быть обёрткой (тема, локаль) — ищется сама Activity. */
private fun Context.findComponentActivity(): ComponentActivity {
    var current: Context? = this
    while (current != null) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    error("ExternalApps требует ComponentActivity в LocalContext")
}
