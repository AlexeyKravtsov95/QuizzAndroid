package ru.poporyadku.ui.home

import java.time.Instant
import java.time.LocalDate
import ru.poporyadku.domain.model.CompletedDaySummary
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.domain.model.TodayStats
import ru.poporyadku.domain.scoring.Streaks

/**
 * Экранная модель Home (ITERATION_3_DESIGN.md, I3-D34, I3-D35, I3-D47).
 *
 * Соответствие с доменным `TodayState` **не** взаимно-однозначное: `AwaitingNextDay`
 * домена даёт две разные композиции экрана, а `Loading` в домене отсутствует вовсе —
 * «данные ещё не прочитаны» это свойство подписки экрана, а не факт о дне.
 * Преобразование выполняется единственным маппером — [toHomeState].
 */
sealed interface HomeState {

    /**
     * Видимость иконки «Архив» в `HomeHeader`.
     *
     * COMPONENTS.md требует, чтобы компонент получал **уже вычисленный** булев параметр,
     * а вычисление шло по `completedDayCount` и факту чтения прогресса, а не по имени
     * состояния. Поэтому значение считает [toHomeState], а не сам `HomeHeader`.
     *
     * Правило «неизвестно → скрыта» — то же самое, что COMPONENTS.md задаёт для
     * `Loading` и для `Error` с непрочитанным прогрессом.
     */
    val isArchiveVisible: Boolean

    /** Первый кадр до первой эмиссии расчёта. Иконка «Архив» скрыта. */
    data object Loading : HomeState {
        override val isArchiveVisible: Boolean = false
    }

    /** Не сыграно ни одной головоломки: панель дня без статистики, CTA «Начать». */
    data class FirstRun(
        val today: LocalDate,
        val dayNumber: Int,
    ) : HomeState {
        /** `completedDayCount == 0` по определению состояния. */
        override val isArchiveVisible: Boolean = false
    }

    /** Набор на сегодня доступен: три строки статистики, CTA «Играть». */
    data class Ready(
        val today: LocalDate,
        val dayNumber: Int,
        val stats: TodayStats,
        override val isArchiveVisible: Boolean,
    ) : HomeState

    /** День начат: «Задание N из 3» и `ThreeStepProgress`, **без счёта**. */
    data class InProgress(
        val today: LocalDate,
        val sessionDate: LocalDate,
        val dayNumber: Int,
        /** 0..2. */
        val completedCount: Int,
        override val isArchiveVisible: Boolean,
    ) : HomeState

    /** Все три задания пройдены: «Сегодня: N из 18», серия, обратный отсчёт. */
    data class Completed(
        val today: LocalDate,
        val sessionDate: LocalDate,
        val dayNumber: Int,
        /** 0..18. */
        val totalScore: Int,
        val streaks: Streaks,
        /** Момент начала следующей локальной даты; в состоянии не хранится `Duration`. */
        val nextLocalDateStartsAt: Instant,
        override val isArchiveVisible: Boolean,
    ) : HomeState

    /** Есть последний завершённый день: `DailyIssuePanel` + «Посмотреть итог». */
    data class AwaitingNextDay(
        val today: LocalDate,
        val lastCompleted: CompletedDaySummary,
        val stats: TodayStats,
        override val isArchiveVisible: Boolean,
    ) : HomeState

    /**
     * Истории нет (I3-D35): нейтральное ожидание — ни `DailyIssuePanel`, ни
     * `PrimaryButton`, ни фиктивной даты, ни «День 0», ни «0 из 18».
     * `StatisticsBlock` показывается только при `stats.playedDayCount > 0`.
     */
    data class AwaitingFirstDay(
        val today: LocalDate,
        val stats: TodayStats,
        override val isArchiveVisible: Boolean,
    ) : HomeState

    /** Наборы пакета исчерпаны: текст + `StatisticsBlock` + «Открыть архив». */
    data class ContentExhausted(
        val today: LocalDate,
        val stats: TodayStats,
        override val isArchiveVisible: Boolean,
    ) : HomeState

    /**
     * Отказ расчёта. Статистика показывается, только если прогресс действительно
     * прочитан ([stats] не `null`).
     */
    data class Error(
        val today: LocalDate?,
        val stats: TodayStats?,
        val kind: TodayFailureKind,
        /** Дескрипторы, применимые к [kind]; в release-сборке всегда пуст. */
        val recoveryActions: List<RecoveryActionUi>,
        /** Не `null`, пока действие выполняется: обе кнопки экрана `disabled`. */
        val runningRecoveryId: String?,
        /**
         * Номер пересчёта, породившего ЭТУ ошибку. Едет в
         * [HomeEvent.RecoveryConfirmed] и обратно во ViewModel: подтверждение
         * действительно только для своего поколения. Экран поколение не
         * интерпретирует — только копирует.
         */
        val recomputeGeneration: Long,
        override val isArchiveVisible: Boolean,
    ) : HomeState
}
