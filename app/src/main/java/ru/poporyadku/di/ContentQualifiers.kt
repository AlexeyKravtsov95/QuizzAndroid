package ru.poporyadku.di

import javax.inject.Qualifier

/**
 * Разбор пакета из `assets` (ITERATION_4_DESIGN.md, I4-D9, §7.5).
 *
 * ТЕРПИМ к неизвестным ключам, и это требование политики версионирования, а не
 * послабление: `CONTENT_MODEL.md` §7 разрешает добавлять необязательные поля без
 * повышения `schemaVersion`, поэтому существует легальный пакет с неизвестным этой
 * версии приложения полем. Строгость в момент авторинга обеспечивает CI.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AssetJson

/**
 * Разбор JSON-колонок Room (ITERATION_4_DESIGN.md, I4-D17, §7.5).
 *
 * СТРОГ: то, что лежит в `cards_json`/`sources_json`, писали мы сами, и неизвестный
 * ключ там означает повреждение, а не совместимость.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StorageJson

/**
 * Включена ли проверка целостности пакета (ITERATION_4_DESIGN.md, I4-D7, §8.7).
 *
 * Свойство сборки, а не тип сборки в теле класса: так тест включает и выключает её
 * обычным параметром конструктора — без Robolectric и без `BuildConfig`. Отключение
 * снимает только `sha256`, BOM и лишние файлы; отпечаток пакета и защита пути
 * считаются и проверяются всегда, включая release.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VerifyBundleIntegrity
