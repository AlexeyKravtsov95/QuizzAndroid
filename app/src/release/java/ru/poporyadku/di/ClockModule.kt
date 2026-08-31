package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.core.time.SystemClockProvider

// ITERATION_2_DESIGN.md, D-16: release-вариант — единственная связка на системные часы.
@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    abstract fun clockProvider(impl: SystemClockProvider): ClockProvider

    @Binds
    abstract fun dateProvider(impl: SystemClockProvider): DateProvider
}
