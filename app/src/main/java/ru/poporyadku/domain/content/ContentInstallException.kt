package ru.poporyadku.domain.content

import java.time.LocalDate

/**
 * Причина отказа установки — ДОМЕННЫЙ тип (ITERATION_3_DESIGN.md, I3-D47).
 *
 * Лежит рядом с [ContentInstaller], поэтому domain не импортирует ничего из data,
 * а use case классифицирует отказ одной строкой, не зная про Room.
 */
sealed class ContentInstallException(message: String) : Exception(message) {

    /**
     * Остаточные наборы, на которые уже выданы назначения: удалить набор — оставить
     * назначение указывающим в пустоту, удалить и назначение — молча стереть прогресс.
     * Поэтому конфликт объявляется, а не чинится.
     *
     * @param staleSetIndexes индексы вне ожидаемого состава — из `day_assignments`
     * и из `daily_sets`; список непуст даже когда строки набора уже нет (I3-D50).
     * @param blockedDates даты, на которых стоит прогресс пользователя.
     */
    class Conflict(
        val packId: String,
        val staleSetIndexes: List<Int>,
        val blockedDates: List<LocalDate>,
    ) : ContentInstallException(
        "наборы $staleSetIndexes пакета $packId заняты назначениями $blockedDates"
    )
}
