package ru.poporyadku.data.content

/**
 * Версия формата пакета, которую понимает это приложение (ITERATION_4_DESIGN.md, §4.2).
 *
 * Манифест с большей версией — не ошибка контента, а более новый формат:
 * `ContentInstallException.UnsupportedSchema`, текст «Требуется обновление приложения»
 * (`CONTENT_MODEL.md` §7). Значение то же, что `SUPPORTED_SCHEMA_VERSION` у CLI.
 */
const val SUPPORTED_SCHEMA_VERSION: Int = 1
