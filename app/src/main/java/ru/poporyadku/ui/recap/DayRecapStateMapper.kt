package ru.poporyadku.ui.recap

import java.time.LocalDate
import ru.poporyadku.domain.usecase.DayRecapResult
import ru.poporyadku.domain.usecase.SlotOutcome
import ru.poporyadku.ui.navigation.RouteOrigin

/**
 * Отображение `DayRecapResult → DayRecapState` (ITERATION_3_DESIGN.md, §13;
 * ITERATION_5_DESIGN.md, §3.7, §4.3).
 *
 * Чистая функция: `today` приходит параметром и используется **только** для выбора
 * заголовка сессионного варианта — «Сегодня» либо дата. Архивный вариант показывает
 * дату всегда, в том числе для сегодняшнего дня (I5-D8). Ни текущего момента, ни
 * системных часов здесь нет.
 */
fun DayRecapResult.toDayRecapState(today: LocalDate, origin: RouteOrigin): DayRecapState = when (this) {
    DayRecapResult.NotFound -> DayRecapState.NotFound(origin)

    is DayRecapResult.Content -> DayRecapState.Content(
        origin = origin,
        title = if (origin == RouteOrigin.Session && localDate == today) {
            DayRecapTitle.Today
        } else {
            DayRecapTitle.Date(localDate)
        },
        dayNumber = dayNumber,
        totalScore = totalScore,
        isComplete = isComplete,
        slots = slots.map { it.toSlotResultUi(isOpenable = origin == RouteOrigin.Archive) },
        streakDays = streakAtDay,
        isRecordUpdated = isRecordUpdated,
        canShare = isComplete,
    )
}

/**
 * `when` по `SlotOutcome` исчерпывающий, **без `else`**: экран обязан обработать все
 * варианты sealed-типа в момент мержа, а `else`-заглушку пришлось бы искать и чинить
 * задним числом (I3-D37).
 *
 * Фактический `score` переносится как есть: `Unavailable` — это не «ноль», а «показать
 * нечем». У `NotPlayed` счёта нет вовсе.
 */
private fun SlotOutcome.toSlotResultUi(isOpenable: Boolean): SlotResultUi = when (this) {
    is SlotOutcome.Played -> SlotResultUi.Played(
        slotIndex = slotIndex,
        score = score,
        category = category,
        isOpenable = isOpenable,
    )

    is SlotOutcome.Unavailable -> SlotResultUi.Unavailable(
        slotIndex = slotIndex,
        score = score,
    )

    is SlotOutcome.NotPlayed -> SlotResultUi.NotPlayed(slotIndex = slotIndex)
}
