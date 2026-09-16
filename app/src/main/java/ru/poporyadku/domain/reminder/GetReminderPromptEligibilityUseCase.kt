package ru.poporyadku.domain.reminder

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Условие предложения включить напоминание на итоге дня (ITERATION_6_DESIGN.md, §8.3,
 * I6-D40).
 *
 * Читается **один раз** при создании `ReminderPromptViewModel`, а не подписками: диалог
 * — разовое событие, и живая подписка на настройки заставила бы его появиться посреди
 * экрана в ответ на чужую запись.
 *
 * Происхождение маршрута (сессия против архива) проверяет вызывающий: оно принадлежит
 * навигации, а не данным.
 *
 * @return `true`, если день [date] полностью завершён, предложение ещё не показывали и
 *  напоминание не включено.
 */
class GetReminderPromptEligibilityUseCase @Inject constructor(
    private val progress: ProgressRepository,
    private val preferences: UserPreferencesRepository,
) {

    suspend operator fun invoke(date: LocalDate): Boolean {
        val prefs = preferences.preferences.first()
        // Уже отвечали или уже включено — предлагать нечего.
        if (prefs.notificationPromptShown || prefs.reminderEnabled) return false
        return progress.getDayResult(date)?.isComplete == true
    }
}
