package ru.poporyadku.domain.usecase

import java.time.LocalTime
import javax.inject.Inject
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Согласие «Да, в 9:00» на итоге дня (ITERATION_6_DESIGN.md, §8.3, I6-D40).
 *
 * **Единственный подтверждаемый переход**: `notificationPromptShown`, время и
 * `reminderEnabled` записываются одним `edit` DataStore и возвращаются только после
 * записи. Три отдельных сеттера дали бы промежуточные состояния («отметку поставили,
 * напоминание не включили»), каждое из которых пришлось бы восстанавливать отдельной
 * веткой после смерти процесса.
 *
 * Долговечное намерение — сам `reminderEnabled = true`; отдельного pending-ключа нет
 * (I6-D2): включённое, но недоступное напоминание уже имеет определённое поведение —
 * переключатель показан выключенным с подсказкой, worker без доступа не показывает
 * уведомлений.
 *
 * Системный запрос разрешения создаётся **только после** успешного завершения этого
 * перехода и никогда до него.
 */
class AcceptReminderPromptUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {

    suspend operator fun invoke(time: LocalTime = DEFAULT_TIME) {
        preferences.acceptReminderPrompt(time)
    }

    companion object {
        /** 9:00 — `UX_FLOW.md` §2, §8, §12; `COMPONENTS.md` («Да, в 9:00»). */
        val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
    }
}
