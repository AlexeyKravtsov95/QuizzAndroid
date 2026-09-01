package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.data.content.temporary.TemporaryContentInstaller
import ru.poporyadku.data.content.temporary.TemporaryPuzzleRepository
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.PuzzleRepository

/**
 * Границы контента (ITERATION_3_DESIGN.md, I3-D1, I3-D2).
 *
 * Замена временного источника на настоящий в итерации 4 стоит ровно две строки в этом
 * файле: ни один use case, ни один ViewModel и ни один экран не правится, потому что
 * все они видят только [PuzzleRepository] и [ContentInstaller].
 *
 * Обе привязки — в `src/main`: реализация в `src/debug` оставила бы граф Hilt релизной
 * сборки без привязки, и `assembleRelease` падал бы на компиляции Dagger.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ContentModule {

    @Binds
    abstract fun puzzleRepository(impl: TemporaryPuzzleRepository): PuzzleRepository

    /** Экземпляр — @Singleton по аннотации самого класса: один Mutex на процесс. */
    @Binds
    abstract fun contentInstaller(impl: TemporaryContentInstaller): ContentInstaller
}
