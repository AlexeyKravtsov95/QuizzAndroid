package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository

// ITERATION_2_DESIGN.md, D-20: активный пакет в итерации 2 имеет ровно одно продуктовое
// значение, поставляемое DI, — не настраиваемый пользователем пакет в main.
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun dayAssignmentRepository(impl: DayAssignmentRepositoryImpl): DayAssignmentRepository

    @Binds
    abstract fun progressRepository(impl: ProgressRepositoryImpl): ProgressRepository

    @Binds
    abstract fun dailySetRepository(impl: DailySetRepositoryImpl): DailySetRepository

    companion object {
        @Provides
        @ActivePack
        fun activePackId(): String = ContentPack.CORE_RU
    }
}
