package ru.poporyadku.ui.recap

import java.time.LocalDate
import ru.poporyadku.domain.usecase.DayRecapResult
import ru.poporyadku.domain.usecase.SlotOutcome

/**
 * Отображение `DayRecapResult → DayRecapState` (ITERATION_3_DESIGN.md, раздел 13).
 *
 * Чистая функция: `today` приходит параметром и используется **только** для выбора
 * заголовка — «Сегодня» либо дата. Ни текущего момента, ни системных часов здесь нет.
 */
fun DayRecapResult.toDayRecapState(today: LocalDate): DayRecapState = when (this) {
    DayRecapResult.NotFound -> DayRecapState.NotFound

    is DayRecapResult.Content -> DayRecapState.Content(
        title = if (localDate == today) {
            DayRecapTitle.Today
        } else {
            DayRecapTitle.Date(localDate)
        },
        totalScore = totalScore,
        slots = slots.map { it.toSlotResultUi() },
        currentStreak = currentStreak,
        bestStreak = bestStreak,
        isRecordUpdated = isRecordUpdated,
    )
}

/**
 * `when` по `SlotOutcome` исчерпывающий, **без `else`**: экран обязан обработать оба
 * варианта sealed-типа в момент мержа, а `else`-заглушку пришлось бы искать и чинить
 * задним числом (I3-D37).
 *
 * Фактический `score` переносится как есть в обоих вариантах: `Unavailable` — это не
 * «ноль», а «показать нечем». Пропуск даёт 0, а отвеченная, но нечитаемая головоломка —
 * свой реальный счёт из 0..6.
 */
private fun SlotOutcome.toSlotResultUi(): SlotResultUi = when (this) {
    is SlotOutcome.Played -> SlotResultUi.Played(
        slotIndex = slotIndex,
        score = score,
        category = category,
    )

    is SlotOutcome.Unavailable -> SlotResultUi.Unavailable(
        slotIndex = slotIndex,
        score = score,
    )
}
