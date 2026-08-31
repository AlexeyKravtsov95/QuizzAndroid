package ru.poporyadku.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AssignmentDao
import ru.poporyadku.data.db.dao.AttemptDao
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.dao.DayResultDao
import ru.poporyadku.data.db.dao.PuzzleDao

// ITERATION_2_DESIGN.md, PR 2A: AppDatabase — @Singleton, DAO — провайдеры без scope
// (один экземпляр на singleton AppDatabase). Ни debug-фикстур, ни автозаполнения (D-9).
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "poporyadku.db").build()

    @Provides
    fun providePuzzleDao(database: AppDatabase): PuzzleDao = database.puzzleDao()

    @Provides
    fun provideDailySetDao(database: AppDatabase): DailySetDao = database.dailySetDao()

    @Provides
    fun provideAssignmentDao(database: AppDatabase): AssignmentDao = database.assignmentDao()

    @Provides
    fun provideAttemptDao(database: AppDatabase): AttemptDao = database.attemptDao()

    @Provides
    fun provideDayResultDao(database: AppDatabase): DayResultDao = database.dayResultDao()
}
