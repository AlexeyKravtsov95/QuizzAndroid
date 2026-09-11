package ru.poporyadku.ui.navigation

/**
 * Откуда открыт итог дня или результат головоломки (ITERATION_5_DESIGN.md, §3.7, §4.3,
 * I5-D8). Один тип для обоих экранов.
 *
 * Вариант экрана выбирается ТОЛЬКО по происхождению, а не сравнением даты с сегодняшней:
 * архивная строка сегодняшнего дня обязана открыть архивный вариант, а итог последнего
 * завершённого дня, открытый с Home, — сессионный (закрывает O-7 итерации 3).
 */
enum class RouteOrigin {
    /** Игровая цепочка и Home; токена в маршруте нет. */
    Session,

    /** Строка архива; токен [Destinations.ORIGIN_ARCHIVE]. */
    Archive;

    companion object {

        /**
         * Разбор аргумента маршрута: отсутствует → [Session], [Destinations.ORIGIN_ARCHIVE]
         * → [Archive]. Любое другое значение не подменяется ни одним вариантом — `null`,
         * то есть маршрут невалиден.
         */
        fun fromRouteToken(token: String?): RouteOrigin? = when (token) {
            null -> Session
            Destinations.ORIGIN_ARCHIVE -> Archive
            else -> null
        }
    }
}
