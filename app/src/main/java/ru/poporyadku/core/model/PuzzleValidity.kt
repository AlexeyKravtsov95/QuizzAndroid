package ru.poporyadku.core.model

// ITERATION_3_DESIGN.md, §8: проверка ФОРМЫ головоломки, не содержания. Нужна и
// итерации 3 (временный источник может быть испорчен правкой), и итерации 4
// (импортированный контент). Расширение, а не метод data-класса, — чтобы не выглядеть
// частью контракта хранения.
//
// Литерал 4 повторяет PairwiseScoreCalculator.CARDS_PER_PUZZLE намеренно: core.model —
// лист в графе зависимостей (ARCHITECTURE.md, §1, `domain ──▶ core.model`), и импорт
// domain отсюда развернул бы стрелку. Единственная договорённость — C(4,2) = 6 баллов.
private const val CARDS_PER_PUZZLE = 4

/**
 * Головоломку можно показать игроку: ровно четыре карточки с уникальными `cardId`,
 * `correctOrder` — перестановка тех же `cardId`, тексты непусты.
 *
 * Содержание (значения, источники, направление сортировки) здесь не проверяется:
 * это работа валидатора контента в CI (CONTENT_MODEL.md, §8).
 */
fun Puzzle.isPlayable(): Boolean {
    if (cards.size != CARDS_PER_PUZZLE) return false
    val cardIds = cards.map { it.cardId }
    if (cardIds.toSet().size != CARDS_PER_PUZZLE) return false
    if (correctOrder.size != CARDS_PER_PUZZLE) return false
    if (correctOrder.toSet() != cardIds.toSet()) return false
    if (prompt.isBlank()) return false
    if (explanation.isBlank()) return false
    if (directionLabel.isBlank()) return false
    return true
}
