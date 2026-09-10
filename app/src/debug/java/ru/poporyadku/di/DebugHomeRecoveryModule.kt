package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.poporyadku.debug.ContentResetAction
import ru.poporyadku.ui.home.HomeErrorRecoveryAction

/**
 * Единственный вклад в набор действий восстановления (ITERATION_3_DESIGN.md, I3-D47;
 * ITERATION_4_DESIGN.md, **I4-D20**).
 *
 * Модуль существует только в `src/debug`: в release-графе набор остаётся пустым, и
 * заглушка для этого не требуется.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DebugHomeRecoveryModule {

    @Binds
    @IntoSet
    abstract fun contentResetAction(
        impl: ContentResetAction,
    ): HomeErrorRecoveryAction
}
