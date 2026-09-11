package ru.poporyadku.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Архив сыгранных дней (ITERATION_5_DESIGN.md, §3.3, §3.4, §5.1, I5-D2, I5-D3, I5-D4).
 *
 * Только чтение и ни одной сущности: схема Room не меняется (I5-D2). Все четыре запроса —
 * `day_results INNER JOIN day_assignments` по первичным ключам `local_date` обеих таблиц,
 * поэтому новых индексов не нужно, а дата не может встретиться дважды.
 *
 * - Включение дня — строка `day_results` (≥ 1 попытки). Отложенное назначение без
 *   попыток строки `day_results` не имеет и в архив не попадает.
 * - Строка `day_results` без назначения — нарушение инварианта записи; внутреннее
 *   соединение её не показывает, а статистика её учитывает (§3.3, `I5-A5`).
 * - Пакет не фильтруется: история — дни пользователя, а не пакета.
 * - Даты сравниваются ISO-строками: `yyyy-MM-dd` сортируется лексикографически так же,
 *   как календарно (D-8). Будущие даты законны и идут первыми.
 * - Keyset, а не `OFFSET` (ADR-017): смещение дублировало бы строку на границе страниц,
 *   как только сверху появляется новый день.
 * - Room-`Flow` инвалидируется записью в любую из двух таблиц запроса: запись попытки
 *   (`day_results`) и перенос отложенного назначения (`day_assignments`).
 */
@Dao
interface ArchiveDao {

    /** Проекция строки архива; дата — ISO-строка, разбор и проверки — в `ArchiveRepositoryImpl`. */
    data class ArchiveDayRow(
        val localDate: String,
        val setIndex: Int,
        val totalScore: Int,
        val completedCount: Int,
        val isComplete: Boolean,
    )

    /** Разведка первой страницы: самые поздние дни. `limit = PAGE_SIZE + 1 = 51`. */
    @Query(
        """
        SELECT r.local_date      AS localDate,
               a.set_index       AS setIndex,
               r.total_score     AS totalScore,
               r.completed_count AS completedCount,
               r.is_complete     AS isComplete
          FROM day_results r
         INNER JOIN day_assignments a ON a.local_date = r.local_date
         ORDER BY r.local_date DESC
         LIMIT :limit
        """
    )
    suspend fun newest(limit: Int): List<ArchiveDayRow>

    /** Разведка следующей страницы: строго раньше курсора [before]. */
    @Query(
        """
        SELECT r.local_date      AS localDate,
               a.set_index       AS setIndex,
               r.total_score     AS totalScore,
               r.completed_count AS completedCount,
               r.is_complete     AS isComplete
          FROM day_results r
         INNER JOIN day_assignments a ON a.local_date = r.local_date
         WHERE r.local_date < :before
         ORDER BY r.local_date DESC
         LIMIT :limit
        """
    )
    suspend fun olderThan(before: String, limit: Int): List<ArchiveDayRow>

    /** Наблюдаемое окно: все дни с датой не раньше нижней границы. */
    @Query(
        """
        SELECT r.local_date      AS localDate,
               a.set_index       AS setIndex,
               r.total_score     AS totalScore,
               r.completed_count AS completedCount,
               r.is_complete     AS isComplete
          FROM day_results r
         INNER JOIN day_assignments a ON a.local_date = r.local_date
         WHERE r.local_date >= :lowerBound
         ORDER BY r.local_date DESC
        """
    )
    fun observeFrom(lowerBound: String): Flow<List<ArchiveDayRow>>

    /** Наблюдаемое окно без нижней границы — когда продолжения не осталось. */
    @Query(
        """
        SELECT r.local_date      AS localDate,
               a.set_index       AS setIndex,
               r.total_score     AS totalScore,
               r.completed_count AS completedCount,
               r.is_complete     AS isComplete
          FROM day_results r
         INNER JOIN day_assignments a ON a.local_date = r.local_date
         ORDER BY r.local_date DESC
        """
    )
    fun observeAll(): Flow<List<ArchiveDayRow>>
}
