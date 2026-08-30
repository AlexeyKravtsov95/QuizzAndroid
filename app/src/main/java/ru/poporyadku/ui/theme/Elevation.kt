package ru.poporyadku.ui.theme

import androidx.compose.ui.unit.dp

/**
 * DESIGN_TOKENS.md §6.7 — elevation ровно для двух функциональных исключений
 * (OrderableCard.dragging, NotificationOptInDialog); всё остальное — resting.
 */
object Elevation {
    /** elevation.resting — состояние по умолчанию для всех компонентов. */
    val resting = 0.dp

    /** elevation.pressed — обратная связь только через state layer, не тень. */
    val pressed = 0.dp

    /** elevation.dragged — OrderableCard в состоянии перетаскивания. */
    val dragged = 6.dp

    /** elevation.dialog — NotificationOptInDialog, единственный диалог MVP. */
    val dialog = 6.dp

    /** elevation.overlay — резерв (меню в MVP нет). */
    val overlay = 3.dp
}
