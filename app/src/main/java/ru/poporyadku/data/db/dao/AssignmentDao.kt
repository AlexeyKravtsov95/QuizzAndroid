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
