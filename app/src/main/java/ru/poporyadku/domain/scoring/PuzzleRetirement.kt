package ru.poporyadku.domain.scoring

import ru.poporyadku.domain.model.InstalledContentVersion

/**
 * Отозвана ли головоломка (ITERATION_5_DESIGN.md, §3.8, I5-D11).
 *
 * `retiredIn` — «номер `contentVersion`, начиная с которого головоломка не
 * используется» (CONTENT_MODEL.md §4), поэтому отзыв — сравнение с версией, а не
 * наличие поля. Тот же предикат, которым импортёр признаёт законную замену отозванной
 * головоломки в наборе (I4-D4, `retired_in <= :contentVersion`): пометка на экране и
 * законность замены определяются одной семантикой.
 */
object PuzzleRetirement {

    /**
     * @param retiredIn `puzzles.retired_in`; `null` — головоломка не отзывалась.
     * @param rowContentVersion `puzzles.content_version` — отметка поставки строки;
     * запасное значение, когда установленная версия неизвестна.
     * @param installed подтверждённая установленная версия контента.
     */
    fun isRetired(
        retiredIn: Int?,
        rowContentVersion: Int,
        installed: InstalledContentVersion,
    ): Boolean {
        if (retiredIn == null) return false
        val reference = when (installed) {
            is InstalledContentVersion.Known -> installed.version
            // Строку записал импорт версии rowContentVersion, а защитный набор уже проверил
            // R18C (retiredIn ≤ contentVersion пакета): запасное значение не выдумывает
            // отзыв, которого установленная когда-то версия не объявляла.
            InstalledContentVersion.Unknown -> rowContentVersion
        }
        return retiredIn <= reference
    }
}
