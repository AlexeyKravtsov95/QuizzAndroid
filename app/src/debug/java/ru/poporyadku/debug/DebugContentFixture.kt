package ru.poporyadku.debug

import javax.inject.Inject
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.entity.DailySetEntity

// ITERATION_2_DESIGN.md, раздел 5 / D-9. Единственная точка вызова — кнопка на
// debug-экране. Пишет только в daily_sets тем же публичным DailySetDao.upsertAll,
// которым будет пользоваться ContentImporter итерации 4 — источник строк для
// остальной системы неотличим. Ни Application.onCreate, ни RoomDatabase.Callback,
// ни createFromAsset/prepopulate не участвуют.
class DebugContentFixture @Inject constructor(private val sets: DailySetDao) {

    suspend fun install(setCount: Int = 5) = sets.upsertAll(
        (0 until setCount).map { i ->
            DailySetEntity(
                packId = ContentPack.CORE_RU,
                setIndex = i,
                puzzleId1 = "debug-fixture-%03d".format(i * 3 + 1),
                puzzleId2 = "debug-fixture-%03d".format(i * 3 + 2),
                puzzleId3 = "debug-fixture-%03d".format(i * 3 + 3),
            )
        }
    )
}
