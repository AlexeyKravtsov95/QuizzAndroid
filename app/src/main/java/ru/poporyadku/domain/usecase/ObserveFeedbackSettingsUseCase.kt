package ru.poporyadku.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.poporyadku.domain.model.FeedbackSettings
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Поток настроек отдачи (ITERATION_5_DESIGN.md, §3.14, §6.10, I5-D22).
 *
 * Тот же источник, что у настроек и темы, — `UserPreferencesRepository.preferences`:
 * второго репозитория и второго хранилища у двух переключателей нет. Только чтение:
 * записывает их единственный владелец — `SettingsViewModel` (I5-D15).
 *
 * `distinctUntilChanged` — потому что `preferences` эмитит на изменение ЛЮБОГО ключа:
 * смена темы или отметки контента не обязана пересобирать настройки отдачи.
 */
class ObserveFeedbackSettingsUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {

    operator fun invoke(): Flow<FeedbackSettings> =
        preferences.preferences
            .map { prefs ->
                FeedbackSettings.Known(
                    soundEnabled = prefs.soundEnabled,
                    vibrationEnabled = prefs.vibrationEnabled,
                )
            }
            .distinctUntilChanged()
}
