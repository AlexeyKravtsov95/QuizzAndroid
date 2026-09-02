package ru.poporyadku.ui.puzzle

/**
 * Карточка в текущем порядке пользователя (ITERATION_3_DESIGN.md, раздел 11).
 *
 * Идентичность карточки — только [cardId] (CONTENT_MODEL.md §4). [position] —
 * отображаемое значение текущей позиции, а не идентификатор: как `key` списка оно
 * не используется никогда.
 *
 * [canMoveUp]/[canMoveDown] считает ViewModel и только она: UI не выводит доступность
 * действия из индекса в своей коллекции.
 */
data class CardUi(
    val cardId: String,
    val title: String,
    val subtitle: String?,
    /** 1..4 — для `CardIndex` и семантики. */
    val position: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)
