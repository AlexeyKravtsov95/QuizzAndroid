package ru.poporyadku.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.poporyadku.BuildConfig
import ru.poporyadku.core.model.AppBuildInfo

/**
 * Базовый модуль Hilt (ARCHITECTURE.md, раздел 1): зависимости уровня процесса, не
 * принадлежащие ни данным, ни контенту.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Scope процесса (ITERATION_5_DESIGN.md, §5.5, I5-D15): живёт до смерти процесса и не
     * зависит ни от Activity, ни от ViewModel. Единственный потребитель — worker очереди
     * записи настроек.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Версия приложения (I5-D13). `BuildConfig` читается только здесь: `ui` и `domain`
     * получают готовое значение.
     */
    @Provides
    fun appBuildInfo(): AppBuildInfo = AppBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
    )
}
