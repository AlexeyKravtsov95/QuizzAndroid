package ru.poporyadku.domain.usecase

import java.time.LocalDate

/**
 * Итог «начать или продолжить день» (ITERATION_3_DESIGN.md, I3-D17).
 *
 * Голого `Decision` недостаточно: он не отвечает на вопрос «какая головоломка
 * открывается», а собирать ответ из трёх вызовов в ViewModel значит завести вторую
 * копию правила «первый неотвеченный слот».
 */
sealed interface SessionStart {

    /** День начат или продолжен: маршрут известен целиком. */
    data class Started(
        val localDate: LocalDate,
        val packId: String,
        val setIndex: Int,
        /** Первый незакрытый слот, 0..2. */
        val slotIndex: Int,
    ) : SessionStart

    /** Все три слота закрыты: играть нечего, показываем итог. */
    data class AlreadyCompleted(val localDate: LocalDate) : SessionStart

    data object AwaitingNextDay : SessionStart

    data object ContentExhausted : SessionStart

    /** Назначение есть, а набора под него нет — дефект контента (раздел 18). */
    data class SetMissing(val packId: String, val setIndex: Int) : SessionStart
}
