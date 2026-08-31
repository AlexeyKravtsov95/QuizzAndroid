package ru.poporyadku.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ru.poporyadku.data.db.dao.AssignmentDao
import ru.poporyadku.data.db.dao.AttemptDao
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.dao.DayResultDao
import ru.poporyadku.data.db.dao.PuzzleDao
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.data.db.entity.PuzzleEntity

// ITERATION_2_DESIGN.md, §3 и D-8: все пять таблиц создаются в версии схемы 1.
// Ни одного TypeConverter (Entity используют только примитивы и String) и ни одной
// миграции — версия 1 не мигрирует ниоткуда.
@Database(
    entities = [
        PuzzleEntity::class,
        DailySetEntity::class,
        DayAssignmentEntity::class,
        PuzzleAttemptEntity::class,
        DayResultEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun puzzleDao(): PuzzleDao
    abstract fun dailySetDao(): DailySetDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun attemptDao(): AttemptDao
    abstract fun dayResultDao(): DayResultDao
}
