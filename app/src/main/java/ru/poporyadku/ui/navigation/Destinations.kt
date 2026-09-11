package ru.poporyadku.ui.navigation

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Маршруты — буквально из UX_FLOW.md, раздел 1 («Карта экранов»), с уточнениями
 * ITERATION_3_DESIGN.md (I3-D22, I3-D23, I3-D33) и ITERATION_5_DESIGN.md (§7.1, I5-D8).
 *
 * Аргументы навигации, не игровое состояние: `slotIndex` 0..2, `date` — ISO
 * `yyyy-MM-dd`, `origin` — [ORIGIN_ARCHIVE] или отсутствует. Через `Bundle` не едет
 * ничего, кроме `Int` и строк.
 *
 * **Сентинела `today` больше нет** (I3-D23): `recap/{date}` всегда получает явную
 * ISO-дату. Экран, открытый в 23:59:59 и отрисованный в 00:00:01, при сентинеле
 * показал бы новый, пустой день вместо только что завершённого.
 *
 * Дата у `Puzzle`/`PuzzleResult` и `origin` у `PuzzleResult`/`DayRecap` — query-аргументы,
 * структурно **не** обязательные (`nullable = true`, без `defaultValue`): иначе маршрут
 * без них не сматчился бы вовсе. Отсутствие `origin` означает сессию, а значение по
 * умолчанию было бы тихой подстановкой. Сессионные строители ([puzzleResult], [recap])
 * `origin` не добавляют, поэтому `popUpTo` игрового потока находит те же записи, что
 * и в итерации 3.
 */
object Destinations {
    const val ARG_SLOT_INDEX = "slotIndex"
    const val ARG_DATE = "date"
    const val ARG_ORIGIN = "origin"

    /** Единственное допустимое значение [ARG_ORIGIN]. */
    const val ORIGIN_ARCHIVE = "archive"

    const val HOME = "home"
    const val PUZZLE = "puzzle/{$ARG_SLOT_INDEX}?date={$ARG_DATE}"
    const val PUZZLE_RESULT = "puzzle/{$ARG_SLOT_INDEX}/result?date={$ARG_DATE}&origin={$ARG_ORIGIN}"
    const val RECAP = "recap/{$ARG_DATE}?origin={$ARG_ORIGIN}"
    const val ARCHIVE = "archive"
    const val SETTINGS = "settings"

    /** Источники сыгранных головоломок — единственный подэкран настроек (I5-D14, O5-4). */
    const val SOURCES = "sources"

    fun puzzle(slotIndex: Int, date: LocalDate): String =
        "puzzle/$slotIndex?date=${serialize(date)}"

    /** Сессионный результат: без `origin`. */
    fun puzzleResult(slotIndex: Int, date: LocalDate): String =
        "puzzle/$slotIndex/result?date=${serialize(date)}"

    /** Исторический результат, открытый из архивного итога (I5-D10). */
    fun archivedPuzzleResult(slotIndex: Int, date: LocalDate): String =
        "puzzle/$slotIndex/result?date=${serialize(date)}&origin=$ORIGIN_ARCHIVE"

    /** Сессионный итог: без `origin`. */
    fun recap(date: LocalDate): String = "recap/${serialize(date)}"

    /** Архивный итог, открытый строкой архива (I5-D8). */
    fun archivedRecap(date: LocalDate): String = "recap/${serialize(date)}?origin=$ORIGIN_ARCHIVE"

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
