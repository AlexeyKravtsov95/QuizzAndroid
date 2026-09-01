package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.scoring.StreakCalculator
import ru.poporyadku.domain.scoring.Streaks

/**
 * Единственный владелец расчёта серии и записи её кэша (ITERATION_3_DESIGN.md, I3-D12).
 *
 * Серию показывают два экрана — Home и DayRecap. Один use case вместо двух вычислителей
 * и двух писателей: кэш не может стать источником истины по построению, потому что его
 * единственный писатель пишет то, что только что посчитал из `day_results`.
 *
 * Возвращается ПОСЧИТАННОЕ значение, а не прочитанный кэш. В итерации 3 кэш только
 * заполняется; чтение кэша ради мгновенного первого кадра Home отложено.
 */
class GetStreaksUseCase @Inject constructor(
    private val progress: ProgressRepository,
    private val preferences: UserPreferencesRepository,
) {
    suspend operator fun invoke(today: LocalDate): Streaks {
        val streaks = StreakCalculator.streaks(progress.getCompletedDates(), today)
        // Одна операция записи: раздельных сеттеров трёх ключей не существует, поэтому
        // промежуточное состояние «дата новая, серия старая» невыразимо (D-18).
        preferences.updateStreakCache(streaks.current, streaks.best, today)
        return streaks
    }
}
