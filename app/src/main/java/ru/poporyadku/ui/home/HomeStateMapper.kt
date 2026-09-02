package ru.poporyadku.ui.home

import ru.poporyadku.domain.model.TodayState
import ru.poporyadku.domain.model.TodayStats

/**
 * Единственное место отображения `TodayState → HomeState` (ITERATION_3_DESIGN.md,
 * I3-D34). Чистая функция: ни базы, ни времени, ни ресурсов — только перекладывание
 * уже посчитанных фактов в композицию экрана.
 *
 * Соответствие не взаимно-однозначное: `AwaitingNextDay` домена даёт **две** разные
 * композиции — [HomeState.AwaitingNextDay] со сводкой и [HomeState.AwaitingFirstDay]
 * без неё (I3-D35). `when` исчерпывающий, `else`-ветки нет.
 *
 * @param recoveryActions дескрипторы, уже отфильтрованные по причине отказа: сама
 * фильтрация выполняется во ViewModel, потому что в дескрипторе причины уже нет.
 * @param recomputeGeneration переносится **как есть** и только в вариант [HomeState.Error];
 * остальным композициям поколение не нужно.
 */
fun TodayState.toHomeState(
    recoveryActions: List<RecoveryActionUi>,
    runningRecoveryId: String?,
    recomputeGeneration: Long,
): HomeState = when (this) {
    is TodayState.FirstRun -> HomeState.FirstRun(
        today = today,
        dayNumber = dayNumber,
    )

    is TodayState.Ready -> HomeState.Ready(
        today = today,
        dayNumber = dayNumber,
        stats = stats,
        isArchiveVisible = stats.hasArchive,
    )

    is TodayState.InProgress -> HomeState.InProgress(
        today = today,
        sessionDate = sessionDate,
        dayNumber = dayNumber,
        completedCount = completedCount,
        // Число завершённых дней в этом доменном состоянии не читается: правило
        // COMPONENTS.md для неизвестного completedDayCount — иконка скрыта.
        isArchiveVisible = false,
    )

    is TodayState.Completed -> HomeState.Completed(
        today = today,
        sessionDate = sessionDate,
        dayNumber = dayNumber,
        totalScore = totalScore,
        streaks = streaks,
        nextLocalDateStartsAt = nextLocalDateStartsAt,
        // Сегодняшний день завершён, значит completedDayCount >= 1 — это факт
        // состояния, а не подставленное значение.
        isArchiveVisible = true,
    )

    is TodayState.AwaitingNextDay -> {
        val summary = lastCompleted
        if (summary == null) {
            HomeState.AwaitingFirstDay(
                today = today,
                stats = stats,
                isArchiveVisible = stats.hasArchive,
            )
        } else {
            HomeState.AwaitingNextDay(
                today = today,
                lastCompleted = summary,
                stats = stats,
                isArchiveVisible = stats.hasArchive,
            )
        }
    }

    is TodayState.ContentExhausted -> HomeState.ContentExhausted(
        today = today,
        stats = stats,
        isArchiveVisible = stats.hasArchive,
    )

    is TodayState.Failure -> HomeState.Error(
        today = today,
        stats = stats,
        kind = kind,
        recoveryActions = recoveryActions,
        runningRecoveryId = runningRecoveryId,
        recomputeGeneration = recomputeGeneration,
        // Прогресс не прочитан — счёт завершённых дней неизвестен, иконка скрыта.
        isArchiveVisible = stats?.hasArchive == true,
    )
}

/** Видимость «Архива» — по данным, а не по имени состояния (COMPONENTS.md). */
private val TodayStats.hasArchive: Boolean get() = completedDayCount > 0
