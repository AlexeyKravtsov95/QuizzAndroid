package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.core.time.DebugClockProvider

// ITERATION_2_DESIGN.md, D-16: debug-вариант — управляемые часы. Тот же пакет и то же
// имя, что и release-версия; разные source sets в один продукт не попадают одновременно.
@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    abstract fun clockProvider(impl: DebugClockProvider): ClockProvider

    @Binds
    abstract fun dateProvider(impl: DebugClockProvider): DateProvider
}
