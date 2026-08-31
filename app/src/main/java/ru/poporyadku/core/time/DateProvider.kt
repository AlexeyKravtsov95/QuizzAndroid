package ru.poporyadku.core.time

import java.time.LocalDate

// ITERATION_2_DESIGN.md, D-16: узкий интерфейс для UI итерации 3.
interface DateProvider {
    fun today(): LocalDate
}
