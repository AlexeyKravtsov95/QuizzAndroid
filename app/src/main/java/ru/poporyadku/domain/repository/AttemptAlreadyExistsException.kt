package ru.poporyadku.domain.repository

import java.time.LocalDate

/**
 * По слоту уже есть запись (ITERATION_3_DESIGN.md, I3-D42).
 *
 * ДОМЕННЫЙ тип: `domain` не знает про Android SDK, поэтому исключение нарушения
 * ограничения SQLite видит только слой хранения. Он переводит его в это исключение —
 * и только после отката транзакции, и только убедившись отдельным чтением, что попытка
 * по `(localDate, slotIndex)` действительно существует. Любое другое нарушение
 * ограничения пробрасывается как есть: constraint — повод проверить причину, а не
 * доказательство повторной попытки.
 */
class AttemptAlreadyExistsException(
    val localDate: LocalDate,
    val slotIndex: Int,
) : Exception("попытка по слоту $slotIndex за $localDate уже записана")
