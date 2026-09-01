package ru.poporyadku.domain.usecase

/**
 * Намерение игрока (ITERATION_3_DESIGN.md, I3-D36).
 *
 * Выражено ТИПОМ, а не формой данных: пустой список — представимое значение, которое
 * может приехать из ошибки ViewModel или из повреждённого восстановленного состояния,
 * и тогда честный ответ молча превратился бы в пропуск с нулём баллов.
 */
sealed interface Submission {

    /** Обычный ответ: порядок из четырёх `cardId`. */
    data class Answer(val order: List<String>) : Submission

    /** Задание недоступно, нажата «Пропустить»: 0 баллов, порядка нет. */
    data object Skip : Submission
}
