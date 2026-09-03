package ru.poporyadku.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import ru.poporyadku.BuildConfig
import ru.poporyadku.data.content.AssetContentSource
import ru.poporyadku.data.content.ContentAssetSource
import ru.poporyadku.data.content.temporary.TemporaryContentInstaller
import ru.poporyadku.data.content.temporary.TemporaryPuzzleRepository
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.PuzzleRepository

/**
 * Границы контента (ITERATION_3_DESIGN.md, I3-D1, I3-D2; ITERATION_4_DESIGN.md, §8.7).
 *
 * Замена временного источника на настоящий стоит ровно ДВЕ строки в этом файле, и они
 * меняются в PR 4D: у обоих продуктовых `@Binds` меняется правая часть, левая остаётся.
 * Ни один use case, ни один ViewModel и ни один экран не правится.
 *
 * **В PR 4B продуктовые привязки остаются временными.** `ContentImporter` и
 * `PuzzleRepositoryImpl` существуют, собираются и покрыты тестами, но в граф
 * не подключены: поведение приложения этим PR не меняется ни в одном сценарии.
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

    /** Источник байтов пакета. Продуктовой привязкой контента не является: до PR 4D
     *  его единственный потребитель — `ContentPackReader`, который никто не вызывает. */
    @Binds
    abstract fun contentAssetSource(impl: AssetContentSource): ContentAssetSource

    companion object {

        /**
         * Разбор ассетов ТЕРПИМ к неизвестным ключам (**I4-D9**): `CONTENT_MODEL.md` §7
         * разрешает добавлять необязательные поля без повышения `schemaVersion`, и
         * приложение, падающее на неизвестном ключе, сделало бы эту политику
         * неисполнимой. Строгость в момент авторинга обеспечивает CI.
         *
         * `isLenient = false` — иначе разбор принял бы кавычки-одиночки и незакавыченные
         * ключи, то есть «почти JSON». `coerceInputValues = false` — иначе `null` в поле
         * с умолчанием молча превратился бы в умолчание, а это тихая потеря данных.
         */
        @Provides
        @Singleton
        @AssetJson
        fun assetJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            coerceInputValues = false
            explicitNulls = false
        }

        /**
         * Разбор JSON-колонок Room СТРОГ: то, что лежит в `cards_json`, писали мы сами,
         * и неизвестный ключ там означает повреждение, а не совместимость.
         *
         * Строгость здесь — штатное умолчание библиотеки, и оно НЕ выписывается
         * повторно: единственное вхождение имени терпимого флага во всём `src/main`
         * стоит выше, и архитектурная проверка «терпимость ровно в одном месте»
         * перестала бы отличать соблюдение правила от его нарушения, повтори мы его
         * здесь ради симметрии.
         */
        @Provides
        @Singleton
        @StorageJson
        fun storageJson(): Json = Json {
            isLenient = false
            coerceInputValues = false
            explicitNulls = false
        }

        /**
         * Проверка целостности — свойство сборки, а не тип сборки в теле класса: так
         * тест включает и выключает её обычным параметром конструктора, без Robolectric
         * и без `BuildConfig` (**I4-D7**).
         */
        @Provides
        @VerifyBundleIntegrity
        fun verifyBundleIntegrity(): Boolean = BuildConfig.DEBUG
    }
}
