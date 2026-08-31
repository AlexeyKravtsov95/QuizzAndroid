package ru.poporyadku.core.model

// UX_FLOW.md, §8: «Как в системе» / «Светлая» / «Тёмная».
// ITERATION_2_DESIGN.md, D-18: неизвестное имя в хранилище читается как SYSTEM.
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}
