package ru.poporyadku.ui.puzzle

import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate
import java.time.format.DateTimeParseException
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.ui.navigation.Destinations

/**
 * Разобранные аргументы игрового маршрута (ITERATION_3_DESIGN.md, I3-D39).
 *
 * Общий тип для `Puzzle` и `PuzzleResult`: оба маршрута несут одну и ту же пару
 * аргументов и обязаны одинаково реагировать на её порчу.
 */
sealed interface RouteArgs {
    data class Valid(val slotIndex: Int, val date: LocalDate) : RouteArgs
    data class Invalid(val reason: RouteArgError) : RouteArgs
}

/** Почему маршрут не разобран. Наружу, в состояние экрана, едет один `InvalidRoute`. */
enum class RouteArgError {
    DateMissing,
    DateMalformed,
    SlotMissing,
    SlotOutOfRange,
}

/**
 * Разбор аргументов маршрута.
 *
 * Правила, каждое из которых закрывает конкретный отказ:
 * - `date` обязана быть валидной ISO-датой; ничего другого не принимается;
 * - отсутствующая или неразбираемая `date` **не подменяется** сегодняшней и не берётся
 *   из системных часов ни в каком виде — тихая подмена сессионной даты увела бы попытку
 *   в чужой день, а обнаружилось бы это как «задание пропало»;
 * - ничего не бросается: повреждённый `Bundle` — представимый случай, а не дефект кода.
 */
fun SavedStateHandle.readPuzzleRoute(): RouteArgs {
    val slot = get<Int>(Destinations.ARG_SLOT_INDEX)
        ?: return RouteArgs.Invalid(RouteArgError.SlotMissing)
    if (slot !in 0 until SLOTS_PER_DAY) {
        return RouteArgs.Invalid(RouteArgError.SlotOutOfRange)
    }

    val raw = get<String>(Destinations.ARG_DATE)
        ?: return RouteArgs.Invalid(RouteArgError.DateMissing)
    val date = try {
        Destinations.parseDate(raw)
    } catch (e: DateTimeParseException) {
        return RouteArgs.Invalid(RouteArgError.DateMalformed)
    }

    return RouteArgs.Valid(slot, date)
}
