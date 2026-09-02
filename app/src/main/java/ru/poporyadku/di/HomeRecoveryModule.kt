package ru.poporyadku.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import ru.poporyadku.ui.home.HomeErrorRecoveryAction

/**
 * Множественная привязка действий восстановления Home (ITERATION_3_DESIGN.md, I3-D47).
 *
 * Без `@IntoSet`-вкладов Hilt отдаёт **пустой** `Set` — это и есть release-поведение.
 * Ни одного nullable-binding, ни одного `@Provides`, отдающего `null`, и ни одной
 * заглушки в `src/release`: вклад существует только в `src/debug`, который в release
 * не компилируется.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HomeRecoveryModule {

    @Multibinds
    abstract fun recoveryActions(): Set<HomeErrorRecoveryAction>
}
