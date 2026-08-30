package ru.poporyadku.ui.theme

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** DESIGN_TOKENS.md §6.5 — размеры иконок и толщина их линий. */
object IconSizing {
    /** icon.size.default */
    val default = 24.dp

    /** icon.size.small — CategoryLabel, SourceRow (иконка перехода). */
    val small = 16.dp

    /** icon.strokeWidth.default — толщина линии иконки 24 dp. */
    val strokeWidthDefault = 2.dp

    /** icon.strokeWidth.small — толщина линии иконки 16 dp. */
    val strokeWidthSmall = 1.6.dp

    /** icon.size.dragHandleGlyph — визуальная сетка точек DragHandle. */
    val dragHandleGlyph = DpSize(16.dp, 24.dp)
}
