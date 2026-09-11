package ru.poporyadku.data.repository

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.data.db.dao.ArchiveDao
import ru.poporyadku.data.db.dao.ArchiveDao.ArchiveDayRow
import ru.poporyadku.domain.model.ArchiveDay
import ru.poporyadku.domain.repository.ArchiveRepository
import ru.poporyadku.domain.scoring.PairwiseScoreCalculator

// ITERATION_5_DESIGN.md, §3.5 (п. 7), §5.1. ISO-строка → LocalDate выполняется здесь:
// TypeConverter'ов у базы нет (D-8), а domain про формат хранения не знает.
class ArchiveRepositoryImpl @Inject constructor(
    private val dao: ArchiveDao,
) : ArchiveRepository {

    override suspend fun probe(before: LocalDate?, limit: Int): List<ArchiveDay> {
        val rows = if (before == null) dao.newest(limit) else dao.olderThan(before.toString(), limit)
        return rows.map { it.toDomain() }
    }

    override fun observeWindow(lowerBound: LocalDate?): Flow<List<ArchiveDay>> {
        val rows = if (lowerBound == null) dao.observeAll() else dao.observeFrom(lowerBound.toString())
        return rows.map { window -> window.map { it.toDomain() } }
    }

    /**
     * Строгое отображение: испорченная строка — нарушение инвариантов записи
     * (`ProgressRepositoryImpl`, политика выдачи), а не состояние экрана. Проверки идут
     * ДО построения модели и бросают; ни одно значение не обрезается и не подменяется.
     */
    private fun ArchiveDayRow.toDomain(): ArchiveDay {
        val date = LocalDate.parse(localDate) // бросает на неразбираемой дате
        check(setIndex >= 0) { "set_index отрицателен у $localDate: $setIndex" }
        // До сложения: set_index + 1 при Int.MAX_VALUE переполнился бы в «День −2147483648».
        check(setIndex < Int.MAX_VALUE) { "set_index у $localDate не даёт номера дня: $setIndex" }
        check(completedCount in 1..SLOTS_PER_DAY) {
            "completed_count вне 1..$SLOTS_PER_DAY у $localDate: $completedCount"
        }
        // Максимум — по фактически закрытым слотам, а не по дню: «7 из 6» при одной попытке невозможен.
        check(totalScore in 0..completedCount * PairwiseScoreCalculator.MAX_PER_PUZZLE) {
            "total_score $totalScore вне 0..${completedCount * PairwiseScoreCalculator.MAX_PER_PUZZLE} " +
                "для $completedCount закрытых слотов у $localDate"
        }
        check(isComplete == (completedCount == SLOTS_PER_DAY)) {
            "is_complete=$isComplete расходится с completed_count=$completedCount у $localDate"
        }
        val dayNumber = setIndex + DAY_NUMBER_OFFSET // только после проверок
        return ArchiveDay(
            localDate = date,
            dayNumber = dayNumber,
            totalScore = totalScore,
            completedCount = completedCount,
            isComplete = isComplete,
        )
    }

    private companion object {
        /** Номер дня, который видит игрок, — `setIndex + 1`. */
        const val DAY_NUMBER_OFFSET = 1
    }
}
