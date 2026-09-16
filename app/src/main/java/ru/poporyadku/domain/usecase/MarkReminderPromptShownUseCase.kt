package ru.poporyadku.domain.usecase

import javax.inject.Inject
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Отметка «предложение показано» для «Не нужно» и системной «назад»
 * (ITERATION_6_DESIGN.md, §8.3, I6-D40).
 *
 * Подтверждаемая запись (`suspend`, не команда очереди): отказ отметки честно означает,
 * что предложение может появиться снова, и это лучше, чем считать несохранённый ответ
 * полученным. Напоминание при этом **не включается** и разрешение не запрашивается.
 */
class MarkReminderPromptShownUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {

    suspend operator fun invoke() {
        preferences.setNotificationPromptShown(true)
    }
}
