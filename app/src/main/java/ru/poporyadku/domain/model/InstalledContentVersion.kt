package ru.poporyadku.domain.model

/**
 * Установленная версия контента (ITERATION_5_DESIGN.md, §3.8, §4.1, I5-D12).
 *
 * Источник — отметка DataStore, которую импортёр пишет одной операцией с отпечатком и
 * только после commit (ADR-015): она никогда не называет версию, которой не было в базе.
 * Версия из `manifest.json` в assets — поставляемая, а не установленная, поэтому здесь
 * не участвует.
 */
sealed interface InstalledContentVersion {

    /** Отметка есть: отпечаток записан, версия ≥ 1. */
    data class Known(val version: Int) : InstalledContentVersion {
        init {
            require(version >= 1) { "установленная версия контента начинается с 1: $version" }
        }
    }

    /** Отметки нет — чтение DataStore отдало значения по умолчанию или импорта ещё не было. */
    data object Unknown : InstalledContentVersion
}
