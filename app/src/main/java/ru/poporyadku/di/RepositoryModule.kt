package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.ArchiveRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.data.repository.PlayedSourcesRepositoryImpl
import ru.poporyadku.domain.repository.ArchiveRepository
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.PlayedSourcesRepository
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

    // ITERATION_5_DESIGN.md, §5.1 (PR 5A): keyset-разведка и наблюдаемое окно архива.
    @Binds
    abstract fun archiveRepository(impl: ArchiveRepositoryImpl): ArchiveRepository

    // ITERATION_5_DESIGN.md, §5.3 (PR 5C): источники сыгранных головоломок, только Room.
    @Binds
    abstract fun playedSourcesRepository(impl: PlayedSourcesRepositoryImpl): PlayedSourcesRepository

    companion object {
        @Provides
        @ActivePack
        fun activePackId(): String = ContentPack.CORE_RU
    }
}
