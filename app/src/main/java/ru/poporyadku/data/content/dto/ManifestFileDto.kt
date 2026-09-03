package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/**
 * Элемент `manifest.files[]` (ITERATION_4_DESIGN.md, §4.2, §4.5).
 *
 * `path` — не путь, а **имя из закрытого множества**: проверку выполняет `M03`
 * шаблоном [ru.poporyadku.data.content.ContentPaths.CONTENT_FILE_NAME] до любого
 * обращения к источнику байтов.
 */
@Serializable
data class ManifestFileDto(
    val path: String,
    val sha256: String,
)
