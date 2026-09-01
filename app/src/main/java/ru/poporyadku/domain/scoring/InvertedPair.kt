package ru.poporyadku.domain.scoring

/**
 * Пара карточек, поставленная пользователем в неверном относительном порядке.
 *
 * ITERATION_3_DESIGN.md, I3-D4: пара нормализована по ПРАВИЛЬНОМУ порядку —
 * [correctlyFirst] обязана стоять перед [correctlySecond] в `correctOrder`.
 * В список попадают только инвертированные пары, поэтому в `submittedOrder`
 * [correctlySecond] всегда раньше [correctlyFirst].
 *
 * Идентичность карточки — только `cardId` (CONTENT_MODEL.md, §4): ни заголовок,
 * ни позиция в списке идентичностью не являются.
 */
data class InvertedPair(
    val correctlyFirst: String,
    val correctlySecond: String,
)
