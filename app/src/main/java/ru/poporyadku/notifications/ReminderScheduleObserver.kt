package ru.poporyadku.notifications

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.poporyadku.di.ApplicationScope
import ru.poporyadku.domain.reminder.SyncReminderScheduleUseCase
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Постоянный коллектор настроек напоминания (ITERATION_6_DESIGN.md, §9.4, I6-D29).
 *
 * Единственная связь «настройка изменилась → расписание обновилось» в открытом
 * приложении. `SettingsViewModel` и `ReminderPromptViewModel` про WorkManager не знают
 * вовсе: они пишут DataStore, а работу ставит этот коллектор, увидевший записанное
 * значение. Поэтому выключение и включение подряд, смерть экрана посреди записи и
 * согласие с итога дня приводят к одному и тому же пути.
 *
 * **Своей логики планирования здесь нет** — только вызов той же
 * [SyncReminderScheduleUseCase], что у worker'а и приёмника.
 *
 * **Первая эмиссия — тоже синхронизация**: старт приложения обязан привести расписание в
 * соответствие с настройками, даже если их никто не менял (работа могла пропасть после
 * force stop или очистки).
 *
 * Стартует из `MainActivity.onCreate`, а не из `Application.onCreate`: Robolectric-тесты
 * создают `PoPoRyadkuApp` без инициализатора WorkManager, и обращение к нему из
 * `Application` уронило бы весь `testDebugUnitTest`.
 */
@Singleton
class ReminderScheduleObserver @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val sync: SyncReminderScheduleUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /** Идемпотентен: повторный вызов второго коллектора не создаёт. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            preferences.preferences
                .map { it.reminderEnabled to it.reminderTime }
                // Чужие ключи (звук, тема, кэш серии) расписание не трогают.
                .distinctUntilChanged()
                // Failed не считается успехом и не роняет сбор: следующая эмиссия или
                // следующий старт попробуют снова.
                .collect { sync() }
        }
    }
}
