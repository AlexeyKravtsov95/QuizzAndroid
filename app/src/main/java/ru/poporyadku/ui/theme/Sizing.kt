package ru.poporyadku.ui.theme

import androidx.compose.ui.unit.dp

/**
 * DESIGN_TOKENS.md §6.5 — размерные токены (не отступы, не иконки — см. [Spacing]/[IconSizing]).
 */
object Sizing {
    /** size.orderableCard.indexZoneWidth */
    val orderableCardIndexZoneWidth = 56.dp

    /** size.divider.thickness — толщина hairline-разделителя. */
    val dividerThickness = 1.dp

    /** size.cutCorner — размер срезанного угла OrderableCardCutCorner, обе оси. */
    val cutCorner = 18.dp

    /** size.orderableCard.minHeight — минимальная высота OrderableCard при 100% шрифте. */
    val orderableCardMinHeight = 112.dp

    /** size.button.height — высота PrimaryButton/SecondaryButton, минимум. */
    val buttonHeight = 56.dp

    /** size.touchTarget.min — минимальный touch target любого интерактивного элемента. */
    val touchTargetMin = 48.dp

    /** size.moveButton — кнопка перемещения MoveButton. */
    val moveButton = 48.dp

    /** size.dragHandle.touchTarget — зона захвата DragHandle. */
    val dragHandleTouchTarget = 48.dp

    /** size.backButton — кнопка «назад» в AppTopBar. */
    val backButton = 48.dp

    /** size.headerIcon — иконки шапки Home (архив, настройки), область нажатия. */
    val headerIcon = 48.dp

    /** size.categoryLabel.minHeight — минимальная высота CategoryLabel. */
    val categoryLabelMinHeight = 26.dp

    /** size.progressDot.diameter — диаметр точки ThreeStepProgress. */
    val progressDotDiameter = 8.dp

    /**
     * DESIGN_TOKENS.md §6.10 — контентная колонка на ширине ≥ 600 dp и в landscape
     * ограничивается этой шириной и центрируется.
     */
    val contentMaxWidth = 480.dp

    /** DESIGN_TOKENS.md §6.10 — граница между compact и обычным горизонтальным полем. */
    val compactWidthBreakpoint = 360.dp

    /** DESIGN_TOKENS.md §6.10 — граница среднего/расширенного класса окна (планшеты). */
    val mediumWidthBreakpoint = 600.dp
}
