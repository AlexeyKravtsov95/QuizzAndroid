package ru.poporyadku.domain.usecase

import javax.inject.Inject
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository

/**
 * Начать или продолжить день (ITERATION_3_DESIGN.md, I3-D17).
 *
 * Единственный владелец ответственности «начать день»: второго use case не заводится.
 * Набор загружает он сам — «начать день» это не только записать назначение, но и
 * ответить, какая головоломка открывается; репозиторий назначений остаётся не знающим
 * о головоломках, а ViewModel не собирает маршрут из трёх вызовов.
 *
 * API даты не принимает (D-16): она приходит наружу в результате, но внутрь — никогда.
 */
class StartDailySessionUseCase @Inject constructor(
    private val content: ContentInstaller,
    private val assignments: DayAssignmentRepository,
    private val sets: DailySetRepository,
    private val progress: ProgressRepository,
) {
    suspend operator fun invoke(): SessionStart {
        content.ensureInstalled()

        // Одна транзакция: снимок и запись. Повторный вызов в тот же день уходит в ветку
        // Assigned и не создаёт второго назначения даже при двойном нажатии.
        val context = assignments.startSession()
        val localDate = context.localDate

        val (packId, setIndex) = when (val decision = context.decision) {
            is Decision.NewSet -> decision.packId to decision.setIndex
            is Decision.CarryOver -> decision.packId to decision.setIndex
            is Decision.Assigned -> decision.packId to decision.setIndex
            Decision.AwaitingNextDay -> return SessionStart.AwaitingNextDay
            Decision.ContentExhausted -> return SessionStart.ContentExhausted
        }

        sets.getSet(packId, setIndex) ?: return SessionStart.SetMissing(packId, setIndex)

        // Первый НЕЗАКРЫТЫЙ слот, а не «число попыток»: число не отвечает на вопрос при
        // дырке в последовательности (слот 1 закрыт, слот 0 — нет). Дырка в итерации 3
        // недостижима, но правило верно и после появления пропусков.
        val closed = progress.getAttempts(localDate).mapTo(HashSet()) { it.slotIndex }
        val first = (0 until SLOTS_PER_DAY).firstOrNull { it !in closed }
            ?: return SessionStart.AlreadyCompleted(localDate)

        return SessionStart.Started(
            localDate = localDate,
            packId = packId,
            setIndex = setIndex,
            slotIndex = first,
        )
    }
}
