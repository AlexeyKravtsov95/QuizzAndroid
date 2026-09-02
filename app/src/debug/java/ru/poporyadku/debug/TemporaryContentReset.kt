package ru.poporyadku.debug

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.poporyadku.data.db.AppDatabase

/**
 * Debug-only восстановление после конфликта установки контента
 * (ITERATION_3_DESIGN.md, I3-D48). В release не компилируется: живёт в `src/debug`.
 *
 * Действие очищает **весь Room целиком** — `daily_sets`, `puzzles`, `day_assignments`,
 * `puzzle_attempts`, `day_results`, всех пакетов, а не только активного. Обещать
 * избирательность, которой нет, хуже, чем честно очистить всё: точечное удаление
 * потребовало бы четырёх DELETE, два из них — с подзапросом по `day_assignments`.
 *
 * `UserPreferences` (DataStore) не трогается: настройки и флаги обучения — не Room
 * и не часть конфликта.
 *
 * После очистки следующий `ensureInstalled()` видит пустую `daily_sets` и
 * переустанавливает наборы (I3-D41): устройство возвращается в состояние первого запуска.
 */
class TemporaryContentReset @Inject constructor(
    private val db: AppDatabase,
) {

    /**
     * `clearAllTables()` блокирующий и не suspend, Room запрещает вызывать его на
     * главном потоке; собственной внешней транзакции нет — он управляет ею сам.
     *
     * Квалификатор `@IoDispatcher` не используется: его в проекте не существует —
     * ни аннотации, ни модуля.
     */
    suspend fun perform() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }
}
