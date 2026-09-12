package ru.poporyadku.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.poporyadku.data.prefs.SettingsWriteQueue
import ru.poporyadku.data.prefs.UserPreferencesRepositoryImpl
import ru.poporyadku.domain.repository.SettingsWriter
import ru.poporyadku.domain.repository.UserPreferencesRepository

// ITERATION_2_DESIGN.md, D-18: единственное место в проекте, где встречается имя
// androidx.datastore, кроме data/prefs/*.kt.
@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    abstract fun userPreferencesRepository(
        impl: UserPreferencesRepositoryImpl,
    ): UserPreferencesRepository

    /**
     * Единственный путь записи настроек из UI (ITERATION_5_DESIGN.md, §5.5, I5-D15).
     * Экземпляр — `@Singleton` по аннотации самого класса: одна очередь и один worker
     * на процесс.
     */
    @Binds
    abstract fun settingsWriter(impl: SettingsWriteQueue): SettingsWriter

    companion object {
        private const val PREFERENCES_FILE_NAME = "poporyadku_prefs"

        @Provides
        @Singleton
        fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) {
                context.preferencesDataStoreFile(PREFERENCES_FILE_NAME)
            }
    }
}
