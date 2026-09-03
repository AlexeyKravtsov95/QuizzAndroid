package ru.poporyadku.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.poporyadku.data.db.entity.DayAssignmentEntity

// ITERATION_2_DESIGN.md, §4 и D-20. Запросы разделены на глобальные (инварианты
// пользователя: не более одного отложенного назначения, не более одного назначения
// на дату, "только вперёд") и pack-scoped (последовательность внутри пакета).
// Сборка снимка, политика выдачи и транзакции — PR 2B.
@Dao
interface AssignmentDao {

    /** Проекция свидетельства B (ITERATION_4_DESIGN.md, §3.3). */
    data class AssignedSetRow(val setIndex: Int, val localDate: String)

    /** Проекция свидетельства C (там же). */
    data class PlayedPuzzleRow(
        val setIndex: Int,
        val slotIndex: Int,
        val puzzleId: String,
        val localDate: String,
    )

    // ---------- ГЛОБАЛЬНЫЕ: инварианты пользователя, а не пакета ----------

    /** Отложенное назначение — то, по которому нет ни одной попытки.
     *  БЕЗ фильтра по pack_id: «не более одного отложенного» — глобальный инвариант
     *  (UX_FLOW.md §9). С фильтром две отложенные строки разных пакетов выглядели бы
     *  как одна, и require() в decide() не поймал бы нарушение.
     *  LIMIT 2, а не 1: без второй строки нарушение невозможно обнаружить. */
    @Query(
        """
        SELECT a.* FROM day_assignments a
        WHERE NOT EXISTS (
            SELECT 1 FROM puzzle_attempts t WHERE t.local_date = a.local_date
        )
        ORDER BY a.local_date
        LIMIT 2
        """
    )
    suspend fun pendingAssignments(): List<DayAssignmentEntity>

    /** БЕЗ фильтра по pack_id: local_date — первичный ключ таблицы,
     *  на дату приходится ровно одна строка, какому бы пакету она ни принадлежала. */
    @Query("SELECT * FROM day_assignments WHERE local_date = :date")
    suspend fun byDate(date: String): DayAssignmentEntity?

    /** БЕЗ фильтра по pack_id: защита «только вперёд» не должна обходиться
     *  переключением активного пакета. */
    @Query("SELECT MAX(local_date) FROM day_assignments")
    suspend fun lastAssignedDate(): String?

    // ---------- PACK-SCOPED: последовательность внутри пакета ----------

    /** С фильтром: у каждого пакета собственная последовательность set_index
     *  (CONTENT_MODEL.md §7). */
    @Query("SELECT MAX(set_index) FROM day_assignments WHERE pack_id = :packId")
    suspend fun maxSetIndex(packId: String): Int?

    // ITERATION_3_DESIGN.md, I3-D50 (PR 3A): назначения на set_index вне ожидаемого
    // состава читаются ВСЕГДА и ПЕРВЫМИ, независимо от содержимого daily_sets —
    // строки набора может уже не быть, а назначение на неё есть. Оба запроса read-only.

    /** Индексы для ContentInstallException.Conflict.staleSetIndexes. Непустой
     *  результат — безусловный конфликт, ранний выход установщика после него запрещён. */
    @Query("SELECT set_index FROM day_assignments WHERE pack_id = :packId AND set_index NOT IN (:keep)")
    suspend fun setIndexesOutside(packId: String, keep: List<Int>): List<Int>

    /** Даты, на которых стоит прогресс пользователя, — Conflict.blockedDates.
     *  Читается только в терминальной ветке конфликта. ISO-строка, как и всюду
     *  в этом DAO: TypeConverter'ов у базы нет (ITERATION_2_DESIGN.md, D-8). */
    @Query("SELECT local_date FROM day_assignments WHERE pack_id = :packId AND set_index NOT IN (:keep) ORDER BY local_date")
    suspend fun datesOutside(packId: String, keep: List<Int>): List<String>

    // ITERATION_4_DESIGN.md, §3.3 и §10.3 (PR 4B): диапазонные предикаты и проекции
    // настоящего импортёра. Списочные setIndexesOutside/datesOutside выше остаются:
    // их единственный вызывающий — TemporaryContentInstaller, который живёт до PR 4D.

    /** Свидетельство A: индексы назначений вне диапазона пакета — staleSetIndexes. */
    @Query(
        """
        SELECT set_index FROM day_assignments
         WHERE pack_id = :packId AND (set_index < 0 OR set_index >= :setCount)
         ORDER BY set_index
        """
    )
    suspend fun setIndexesOutsideRange(packId: String, setCount: Int): List<Int>

    /** Предикат (2) быстрого пути: нужен только ФАКТ, поэтому агрегат, а не список. */
    @Query("SELECT COUNT(*) FROM day_assignments WHERE pack_id = :packId AND (set_index < 0 OR set_index >= :setCount)")
    suspend fun countOutsideRange(packId: String, setCount: Int): Int

    /** Даты назначений вне диапазона — часть полезной нагрузки Conflict.
     *  ISO-строка, как и все даты этого DAO: TypeConverter'ов у базы нет (D-8). */
    @Query("SELECT local_date FROM day_assignments WHERE pack_id = :packId AND (set_index < 0 OR set_index >= :setCount) ORDER BY local_date")
    suspend fun datesOutsideRange(packId: String, setCount: Int): List<String>

    /** Свидетельство B: все назначения пакета вместе с датами. Сравнение с ожидаемым
     *  составом выполняется в Kotlin — «ожидаемый состав» приходит из assets, и в SQL
     *  его не передать дешевле, чем сравнить тройки в памяти. */
    @Query("SELECT set_index AS setIndex, local_date AS localDate FROM day_assignments WHERE pack_id = :packId ORDER BY set_index")
    suspend fun assignedSets(packId: String): List<AssignedSetRow>

    /** Свидетельство C: что пользователь РЕАЛЬНО видел. Сильнее B: строки daily_sets
     *  могли быть удалены (I3-D50), а puzzle_attempts.puzzle_id переживает любую
     *  очистку контента. Запрос идёт ОТ назначений, поэтому живёт здесь, а не в AttemptDao. */
    @Query(
        """
        SELECT a.set_index AS setIndex, t.slot_index AS slotIndex,
               t.puzzle_id AS puzzleId, a.local_date AS localDate
          FROM day_assignments a
          JOIN puzzle_attempts t ON t.local_date = a.local_date
         WHERE a.pack_id = :packId
         ORDER BY a.set_index, t.slot_index
        """
    )
    suspend fun playedPuzzles(packId: String): List<PlayedPuzzleRow>

    /**
     * Предикат (3) быстрого пути (ITERATION_4_DESIGN.md, §10.3.1).
     *
     * Считает только БЛОКИРУЮЩИЕ расхождения. Наивная форма «puzzle_id попытки ≠ ID
     * своего слота» неверна после легального отзыва: историческая попытка по отозванной
     * головоломке — расхождение законное и постоянное, и наивный предикат возвращал бы
     * `> 0` навсегда, то есть отключал бы быстрый путь ровно у тех, ради кого механизм
     * отзыва и придуман.
     *
     * `CASE` записан БЕЗ ветки по умолчанию намеренно: такая ветка молча объявляла бы
     * третьим слотом любое значение `slot_index`, включая `7` и `-1`. Теперь такие
     * строки дают `NULL`, сравнение `<> NULL` истинным не бывает, и их ловит первый
     * дизъюнкт запроса — явное отсечение слота вне диапазона.
     *
     * Совпадение сыгранного ID с ID слота состояние ожидаемой строки `puzzles` НЕ
     * проверяет — это вопрос предиката (4) `countSetsWithMissingPuzzles`.
     */
    @Query(
        """
        SELECT COUNT(*)
          FROM day_assignments a
          JOIN puzzle_attempts t ON t.local_date = a.local_date
          JOIN daily_sets s ON s.pack_id = a.pack_id AND s.set_index = a.set_index
         WHERE a.pack_id = :packId
           AND (
             t.slot_index NOT IN (0, 1, 2)
             OR (
               t.puzzle_id <> CASE t.slot_index
                                WHEN 0 THEN s.puzzle_id_1
                                WHEN 1 THEN s.puzzle_id_2
                                WHEN 2 THEN s.puzzle_id_3
                              END
               AND NOT (
                 EXISTS (SELECT 1 FROM puzzles po
                          WHERE po.puzzle_id = t.puzzle_id
                            AND po.pack_id = :packId
                            AND po.retired_in IS NOT NULL
                            AND po.retired_in <= :contentVersion)
                 AND EXISTS (SELECT 1 FROM puzzles pn
                              WHERE pn.puzzle_id = CASE t.slot_index
                                                     WHEN 0 THEN s.puzzle_id_1
                                                     WHEN 1 THEN s.puzzle_id_2
                                                     WHEN 2 THEN s.puzzle_id_3
                                                   END
                                AND pn.pack_id = :packId
                                AND pn.retired_in IS NULL)
               )
             )
           )
        """
    )
    suspend fun countBlockingPlayedPuzzleMismatches(packId: String, contentVersion: Int): Int

    // ---------- запись ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(assignment: DayAssignmentEntity)

    /** Перенос найденного отложенного назначения. Ни одной вставки; set_index и pack_id
     *  не в SET, поэтому переносимая строка сохраняет и пакет, и индекс.
     *  :packId приходит из Decision.CarryOver, то есть из самой строки, а НЕ из активного
     *  пакета: иначе перенос переписал бы pack_id чужой строки.
     *  Условие :today > :pendingDate делает перенос назад невозможным
     *  даже при ошибке в вызывающем коде. */
    @Query(
        """
        UPDATE day_assignments
           SET local_date = :today, assigned_at = :now
         WHERE local_date = :pendingDate
           AND pack_id = :packId
           AND :today > :pendingDate
        """
    )
    suspend fun carryOver(packId: String, pendingDate: String, today: String, now: Long): Int

    @Query("SELECT * FROM day_assignments ORDER BY local_date DESC")
    fun observeAll(): Flow<List<DayAssignmentEntity>>
}
