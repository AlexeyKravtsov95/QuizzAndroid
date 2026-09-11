package ru.poporyadku.ui.sources

/** Одноразовые эффекты экрана источников (ITERATION_5_DESIGN.md, §4.6, I5-D24). */
sealed interface SourcesEffect {
    data object NavigateBack : SourcesEffect
}
