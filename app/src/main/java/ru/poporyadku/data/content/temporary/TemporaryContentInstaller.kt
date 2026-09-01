package ru.poporyadku.data.content.temporary

import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AssignmentDao
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.di.ActivePack
import ru.poporyadku.domain.content.ContentInstallException
import ru.poporyadku.domain.content.ContentInstaller

/**
 * Установка временных наборов (ITERATION_3_DESIGN.md, I3-D41, I3-D47, I3-D48, I3-D50).
 *
 * Источник истины — база, а не поле в процессе: флага готовности здесь нет намеренно.
 * Кнопка «Очистить базу» на debug-экране опустошает все таблицы Room В ТОМ ЖЕ ПРОЦЕССЕ,
 * и флаг остался бы `true` при пустой `daily_sets` — приложение показывало бы
 * `ContentExhausted` до перезапуска.
 *
 * [Singleton] обязателен и указан явно: без scope Hilt создавал бы экземпляр на каждую
 * инъекцию, и [mutex] перестал бы что-либо сериализовать.
 */
@Singleton
class TemporaryContentInstaller @Inject constructor(
    private val db: AppDatabase,
    private val sets: DailySetDao,
    private val assignments: AssignmentDao,
    @ActivePack private val activePackId: String,
) : ContentInstaller {

    /** Сериализует вызовы внутри процесса. Ничего не запоминает между вызовами. */
    private val mutex = Mutex()

    /**
     * Одна транзакция на весь reconcile: сверка назначений, проверка состава,
     * удаление безопасного излишка и установка.
     *
     * Транзакционная граница закрывает три окна, существовавшие при последовательных
     * шагах: выдачу назначения между проверкой и удалением, отмену корутины между
     * удалением и записью, и вторую сверку по устаревшему снимку. Любой выход по
     * исключению или по отмене откатывает транзакцию целиком, поэтому промежуточного
     * состояния «лишнее удалено, нужное не записано» не существует.
     */
    override suspend fun ensureInstalled() = mutex.withLock {
        db.withTransaction {
            val expected = BundledPuzzles.sets.map { it.setIndex }

            // 1. ПЕРВЫЙ SELECT — назначения на set_index вне expected, независимо от
            //    daily_sets: строки набора может уже не быть, а назначение на неё — есть.
            val danglingIndexes = assignments.setIndexesOutside(activePackId, expected)

            // 2. ВТОРОЙ SELECT — что реально лежит в daily_sets.
            val present = sets.setIndexes(activePackId)

            if (danglingIndexes.isNotEmpty()) {
                // Третий SELECT только в терминальной ветке конфликта.
                val blockedDates = assignments.datesOutside(activePackId, expected)
                    .map(LocalDate::parse)
                val staleIndexes = (danglingIndexes + present.filterNot { it in expected })
                    .distinct().sorted()
                // Бросается ВНУТРИ транзакции, поэтому откат гарантирован формой кода:
                // ни daily_sets, ни day_assignments, ни puzzle_attempts, ни day_results
                // не изменены. Тип доменный (domain/content), а не data-специфичный.
                throw ContentInstallException.Conflict(activePackId, staleIndexes, blockedDates)
            }

            // 3. Ранний выход законен ТОЛЬКО после обеих проверок.
            if (present.toSet() == expected.toSet()) return@withTransaction

            // 4. Безопасный излишек: строки daily_sets, на которые назначений нет.
            //    Единственный DELETE reconcile, и он бьёт строго по daily_sets.
            if (present.any { it !in expected }) sets.deleteOutside(activePackId, expected)
            sets.upsertAll(BundledPuzzles.sets.map { it.toEntity() })
        }
    }
}
