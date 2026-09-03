package ru.poporyadku.data.db.mapper

import ru.poporyadku.core.model.Category
import ru.poporyadku.core.model.SortDirection

/**
 * Перечисления ↔ токены формата (ITERATION_4_DESIGN.md, **I4-D18**).
 *
 * Хранится ТОКЕН ФОРМАТА (`"geography"`, `"ascending"`), а не имя Kotlin-константы:
 * персистентное значение не должно зависеть от идентификатора в коде — переименование
 * `Category.RUSSIA` не должно требовать миграции данных, а строку в базе можно глазами
 * сравнить с JSON.
 *
 * Преобразование — **явный `when` в обе стороны и только здесь**. Ни `valueOf`, ни
 * `enumValueOf`, ни `uppercase()`: неявное соответствие имени токену — именно тот вид
 * связи, который ломается молча, и ломается он на данных пользователя.
 */
object ContentTokens {

    fun tokenOf(category: Category): String = when (category) {
        Category.HISTORY -> "history"
        Category.GEOGRAPHY -> "geography"
        Category.SCIENCE -> "science"
        Category.NATURE -> "nature"
        Category.CULTURE -> "culture"
        Category.RUSSIA -> "russia"
        Category.MIXED -> "mixed"
    }

    fun tokenOf(direction: SortDirection): String = when (direction) {
        SortDirection.ASCENDING -> "ascending"
        SortDirection.DESCENDING -> "descending"
    }

    /** `null` — токен неизвестен. Используется защитной проверкой `D02` до записи. */
    fun categoryOrNull(token: String): Category? = when (token) {
        "history" -> Category.HISTORY
        "geography" -> Category.GEOGRAPHY
        "science" -> Category.SCIENCE
        "nature" -> Category.NATURE
        "culture" -> Category.CULTURE
        "russia" -> Category.RUSSIA
        "mixed" -> Category.MIXED
        else -> null
    }

    /** `null` — токен неизвестен. Используется защитной проверкой `D02` до записи. */
    fun sortDirectionOrNull(token: String): SortDirection? = when (token) {
        "ascending" -> SortDirection.ASCENDING
        "descending" -> SortDirection.DESCENDING
        else -> null
    }

    /**
     * Неизвестный токен при ЧТЕНИИ строки — повреждение того, что писали мы сами.
     * Мапперу это не состояние экрана, а дефект: `PuzzleNotFound` был бы неправдой
     * («головоломки нет»), а `InvalidPuzzle` требует построить `Puzzle`, которого
     * построить нельзя. Идентификатор в сообщении обязателен: без него непонятно,
     * какую строку чинить.
     */
    fun categoryOf(token: String, puzzleId: String): Category =
        categoryOrNull(token) ?: error("неизвестная категория '$token' у $puzzleId")

    fun sortDirectionOf(token: String, puzzleId: String): SortDirection =
        sortDirectionOrNull(token) ?: error("неизвестное направление '$token' у $puzzleId")
}
