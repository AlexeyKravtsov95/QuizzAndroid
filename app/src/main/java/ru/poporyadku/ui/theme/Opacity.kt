package ru.poporyadku.ui.theme

/** DESIGN_TOKENS.md §6.9 — стандартные константы M3 state-layer системы. */
object Opacity {
    /** opacity.disabledContent — иконка/текст внутри disabled-элемента. */
    const val disabledContent = 0.38f

    /** opacity.disabledContainer — заливка disabled-элемента, если она вообще предусмотрена. */
    const val disabledContainer = 0.12f

    /** opacity.pressedStateLayer — оверлей при нажатии. */
    const val pressedStateLayer = 0.08f

    /** opacity.focusStateLayer — оверлей при получении клавиатурного/switch-access фокуса. */
    const val focusStateLayer = 0.12f

    /** opacity.dragStateLayer — дополнительный оверлей primary поверх OrderableCard.dragging. */
    const val dragStateLayer = 0.16f

    /** opacity.moveButtonEnabledBorder — непрозрачность акцентной обводки enabled-MoveButton. */
    const val moveButtonEnabledBorder = 0.50f

    /** opacity.scrim — затемнение фона под NotificationOptInDialog. */
    const val scrim = 0.32f
}
