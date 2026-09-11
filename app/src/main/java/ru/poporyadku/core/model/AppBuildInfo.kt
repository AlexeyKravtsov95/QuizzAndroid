package ru.poporyadku.core.model

/**
 * Версия приложения и код сборки (ITERATION_5_DESIGN.md, §3.10, I5-D13).
 *
 * Единственный продуктовый провайдер — `di/AppModule` из `BuildConfig`; ни `ui`, ни
 * `domain` `BuildConfig` не импортируют, а тесты подставляют собственное значение.
 */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long,
)
