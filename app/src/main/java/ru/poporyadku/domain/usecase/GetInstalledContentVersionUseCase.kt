package ru.poporyadku.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Установленная версия контента (ITERATION_5_DESIGN.md, §3.8, §5.4, I5-D12).
 *
 * Отметку пишет только импортёр после commit (I4-D10); здесь она только читается —
 * первая эмиссия настроек, без подписки. Версия признаётся известной, лишь когда рядом
 * записан отпечаток: без него `storedContentVersion` — значение по умолчанию, а не факт
 * установки.
 */
class GetInstalledContentVersionUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {
    suspend operator fun invoke(): InstalledContentVersion {
        val prefs = preferences.preferences.first()
        return if (prefs.storedContentFingerprint != null && prefs.storedContentVersion >= MIN_VERSION) {
            InstalledContentVersion.Known(prefs.storedContentVersion)
        } else {
            InstalledContentVersion.Unknown
        }
    }

    private companion object {
        const val MIN_VERSION = 1
    }
}
