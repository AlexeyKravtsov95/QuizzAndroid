package ru.poporyadku

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ru.poporyadku.notifications.AndroidReminderNotifier
import ru.poporyadku.notifications.ReminderScheduleObserver
import ru.poporyadku.ui.feedback.LocalSoundCues
import ru.poporyadku.ui.feedback.SoundCues
import ru.poporyadku.ui.navigation.AppNavHost
import ru.poporyadku.ui.platform.AndroidExternalApps
import ru.poporyadku.ui.platform.LocalExternalApps
import ru.poporyadku.ui.theme.AppThemeViewModel
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.resolveDark

// Single-activity architecture (ARCHITECTURE.md, раздел 10) — единственная Activity
// приложения, всё остальное — Composable-экраны за NavHost.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Выбранная тема (ITERATION_5_DESIGN.md, §3.9, §4.8, I5-D16). */
    private val appTheme: AppThemeViewModel by viewModels()

    /**
     * Звуки отдачи (ITERATION_5_DESIGN.md, §3.14, §8.2, I5-D22).
     *
     * Полевая инъекция: `SoundCues` — `@ActivityRetainedScoped`, и Activity-компонент
     * видит привязки `ActivityRetainedComponent`. Один экземпляр на Activity, живущий
     * через поворот экрана: звуки не перегружаются, а `release()` вызывается один раз —
     * из `ActivityRetainedLifecycle`, при окончательном уничтожении компонента.
     */
    @Inject
    lateinit var soundCues: SoundCues

    /**
     * Корень композиции напоминания (ITERATION_6_DESIGN.md, §9.1, §9.4, I6-D29).
     *
     * Коллектор стартует отсюда, а не из `Application.onCreate`: Robolectric-тесты
     * создают `PoPoRyadkuApp` без инициализатора WorkManager, и обращение к нему из
     * `Application` уронило бы весь `testDebugUnitTest`.
     */
    @Inject
    lateinit var reminderScheduleObserver: ReminderScheduleObserver

    /** Канал и снятие показанного напоминания (§10.1). */
    @Inject
    lateinit var reminderNotifier: AndroidReminderNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Идемпотентны оба: повторный старт коллектора не создаёт второго, повторное
        // создание канала не перезаписывает пользовательские изменения.
        reminderScheduleObserver.start()
        reminderNotifier.ensureChannel()

        // До первой эмиссии настроек окно и системные панели следуют системной теме —
        // как и фон окна из Theme.PoPoRyadku; после неё стиль панелей переустанавливается
        // по выбранной теме ниже.
        enableEdgeToEdge()

        // Единственная граница внешних действий этой Activity: одна защита от повторного
        // запуска на все экраны (ITERATION_5_DESIGN.md, §8.1).
        val externalApps = AndroidExternalApps(this, SystemClock::elapsedRealtime)

        setContent {
            val themeMode by appTheme.themeMode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            // До первой эмиссии DataStore экраны не компонуются: виден только фон окна,
            // и содержимое ни одного кадра не рисуется в чужой теме. runBlocking не
            // используется — чтение DataStore занимает миллисекунды и главный поток не
            // блокируется.
            val mode = themeMode
            if (mode != null) {
                val resolvedDark = mode.resolveDark(systemDark)

                // enableEdgeToEdge() по умолчанию выбирает цвет иконок по СИСТЕМНОЙ теме:
                // при «Тёмной» на светлой системе иконки стали бы тёмными на тёмном фоне.
                DisposableEffect(resolvedDark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { resolvedDark },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { resolvedDark },
                    )
                    onDispose { }
                }

                // Смена темы — перекомпозиция корня, без пересоздания Activity: граф
                // навигации и его состояние сохраняются.
                PoPoRyadkuTheme(darkTheme = resolvedDark) {
                    CompositionLocalProvider(
                        LocalExternalApps provides externalApps,
                        LocalSoundCues provides soundCues,
                    ) {
                        AppNavHost()
                    }
                }
            }
        }
    }

    /**
     * Пользователь уже в приложении — напоминание о нём же в шторке не нужно
     * (ITERATION_6_DESIGN.md, §8.4, §10.1). Снимается при **любом** старте: и когда
     * приложение открыто нажатием на уведомление, и когда пользователь пришёл сам.
     */
    override fun onStart() {
        super.onStart()
        reminderNotifier.cancelShown()
    }
}
