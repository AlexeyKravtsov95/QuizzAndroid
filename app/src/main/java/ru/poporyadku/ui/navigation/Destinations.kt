package ru.poporyadku.ui.navigation

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Маршруты — буквально из UX_FLOW.md, раздел 1 («Карта экранов»), с уточнениями
 * ITERATION_3_DESIGN.md (I3-D22, I3-D23, I3-D33).
 *
 * Аргументы навигации, не игровое состояние: `slotIndex` 0..2 и `date` — ISO
 * `yyyy-MM-dd`. Через `Bundle` не едет ничего, кроме `Int` и ISO-строки.
 *
 * **Сентинела `today` больше нет** (I3-D23): `recap/{date}` всегда получает явную
 * ISO-дату. Экран, открытый в 23:59:59 и отрисованный в 00:00:01, при сентинеле
 * показал бы новый, пустой день вместо только что завершённого.
 *
 * Дата у `Puzzle`/`PuzzleResult` — query-аргумент, структурно **не** обязательный
 * (`nullable = true`, без `defaultValue`): иначе маршрут без него не сматчился бы
 * вовсе, и обработать «даты нет» было бы негде. Семантическая обязательность
 * (`RouteArgs`, редирект на Home) — задача PR 3D.
 */
object Destinations {
    const val ARG_SLOT_INDEX = "slotIndex"
    const val ARG_DATE = "date"

    const val HOME = "home"
    const val PUZZLE = "puzzle/{$ARG_SLOT_INDEX}?date={$ARG_DATE}"
    const val PUZZLE_RESULT = "puzzle/{$ARG_SLOT_INDEX}/result?date={$ARG_DATE}"
    const val RECAP = "recap/{$ARG_DATE}"
    const val ARCHIVE = "archive"
    const val SETTINGS = "settings"

    fun puzzle(slotIndex: Int, date: LocalDate): String =
        "puzzle/$slotIndex?date=${serialize(date)}"

    fun puzzleResult(slotIndex: Int, date: LocalDate): String =
        "puzzle/$slotIndex/result?date=${serialize(date)}"

    fun recap(date: LocalDate): String = "recap/${serialize(date)}"

    /** Единственное место сериализации даты маршрута. */
    fun serialize(date: LocalDate): String = date.format(ISO)

    /**
     * Единственное место разбора даты маршрута.
     *
     * @throws java.time.format.DateTimeParseException если строка не ISO `yyyy-MM-dd`;
     * подмены на «сегодня» нет ни на одном уровне.
     */
    fun parseDate(raw: String): LocalDate = LocalDate.parse(raw, ISO)

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
}
