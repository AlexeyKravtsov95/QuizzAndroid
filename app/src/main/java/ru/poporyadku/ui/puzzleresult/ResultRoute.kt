package ru.poporyadku.ui.puzzleresult

import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.navigation.RouteOrigin
import ru.poporyadku.ui.puzzle.RouteArgError
import ru.poporyadku.ui.puzzle.RouteArgs
import ru.poporyadku.ui.puzzle.readPuzzleRoute

/**
 * Разобранные аргументы маршрута результата (ITERATION_5_DESIGN.md, §4.4).
 *
 * Отдельный тип, а не расширение [RouteArgs]: игровой экран существует только в сессии
 * и `origin` не читает, поэтому его маршрут и разбор не меняются.
 */
sealed interface ResultRouteArgs {
    data class Valid(val slotIndex: Int, val date: LocalDate, val origin: RouteOrigin) : ResultRouteArgs
    data class Invalid(val reason: ResultRouteError) : ResultRouteArgs
}

/** Почему маршрут результата не разобран. Наружу, в состояние экрана, едет один `InvalidRoute`. */
sealed interface ResultRouteError {
    /** `date` или `slotIndex` — тем же строгим разбором, что у `Puzzle` (I3-D39). */
    data class Base(val error: RouteArgError) : ResultRouteError

    /** `origin` есть, но это не [Destinations.ORIGIN_ARCHIVE]. */
    data object OriginMalformed : ResultRouteError
}

/**
 * Сначала существующий [readPuzzleRoute] (I3-D39), затем `origin`: отсутствует →
 * [RouteOrigin.Session], [Destinations.ORIGIN_ARCHIVE] → [RouteOrigin.Archive], любое
 * другое значение — [ResultRouteError.OriginMalformed]. Ничего не бросается и ничего
 * не подменяется.
 */
fun SavedStateHandle.readResultRoute(): ResultRouteArgs {
    val base = when (val puzzleRoute = readPuzzleRoute()) {
        is RouteArgs.Invalid -> return ResultRouteArgs.Invalid(ResultRouteError.Base(puzzleRoute.reason))
        is RouteArgs.Valid -> puzzleRoute
    }
    val origin = RouteOrigin.fromRouteToken(get<String>(Destinations.ARG_ORIGIN))
        ?: return ResultRouteArgs.Invalid(ResultRouteError.OriginMalformed)
    return ResultRouteArgs.Valid(base.slotIndex, base.date, origin)
}
