package ru.poporyadku.domain.model

import java.time.Instant
import java.time.LocalDate
import ru.poporyadku.domain.scoring.Streaks

/**
 * Что известно про сегодняшний день (ITERATION_3_DESIGN.md, I3-D13, I3-D34).
 *
 * ДОМЕННЫЙ тип: ни одного UI-понятия и ни одного импорта из слоя экранов. Экранная
 * модель Home и маппер к ней живут рядом с самим экраном и появляются вместе с ним;
 * направление зависимостей `ui → domain → core.model` (ARCHITECTURE.md §1) этим
 * сохранено.
 *
 * `Loading` здесь ОТСУТСТВУЕТ намеренно: «данные ещё не прочитаны» — свойство подписки
 * экрана, а не факт о дне, поэтому это состояние живёт только в экранной модели.
 *
 * Состояние определяется РЕШЕНИЕМ политики выдачи, а не числом попыток (I3-D13):
 * назначение на сегодня с нулём попыток даёт [InProgress] и «Продолжить», а не [Ready].
 */
sealed interface TodayState {

    /** Не сыграно ни одной головоломки за всю историю и набор на сегодня ещё не выдан. */
    data class FirstRun(
        val today: LocalDate,
        /** `setIndex + 1` доступного набора. */
        val dayNumber: Int,
    ) : TodayState

    /** История есть, набор на сегодня ещё не выдан. */
    data class Ready(
        val today: LocalDate,
        val dayNumber: Int,
        val stats: TodayStats,
    ) : TodayState

    /** Набор выдан, закрыто 0..2 слота. Ноль попыток — тоже InProgress (I3-D13). */
    data class InProgress(
        val today: LocalDate,
        /** Дата назначения; равна [today] по определению `Decision.Assigned`. */
        val sessionDate: LocalDate,
        val dayNumber: Int,
        /** 0..2. */
        val completedCount: Int,
    ) : TodayState

    /** Все три слота закрыты. */
    data class Completed(
        val today: LocalDate,
        val sessionDate: LocalDate,
        val dayNumber: Int,
        /** 0..18. */
        val totalScore: Int,
        val streaks: Streaks,
        /** Начало следующей локальной даты — из [ru.poporyadku.domain.assignment.DecisionContext] (I3-D40). */
        val nextLocalDateStartsAt: Instant,
    ) : TodayState

    /**
     * Новый набор сегодня не положен.
     *
     * `lastCompleted == null` — ШТАТНЫЙ случай (I3-D35): назначение создано, попыток нет,
     * часы переведены назад. Ни фиктивной даты, ни «День 0», ни «0 из 18».
     */
    data class AwaitingNextDay(
        val today: LocalDate,
        val lastCompleted: CompletedDaySummary?,
        val stats: TodayStats,
    ) : TodayState

    /** Наборы активного пакета закончились. */
    data class ContentExhausted(
        val today: LocalDate,
        val stats: TodayStats,
    ) : TodayState

    /**
     * Расчёт не удался. Поля nullable: прогресс мог прочитаться, а решение выдачи —
     * нет, и наоборот. Ничего не выдумывается: не прочитано — значит `null`.
     */
    data class Failure(
        val today: LocalDate?,
        val stats: TodayStats?,
        val kind: TodayFailureKind,
    ) : TodayState
}

/**
 * Причина отказа (I3-D47; ITERATION_4_DESIGN.md, **I4-D19**). Живёт рядом с [TodayState]
 * и объявляется вместе с ним.
 *
 * Отличает конфликт установки контента, у которого есть собственные действия
 * восстановления, от непригодного пакета, где прогресс ни при чём, и от любой другой
 * ошибки чтения.
 *
 * `ContentUnusable` — «пакет внутри приложения непригоден; прогресс цел; помочь может
 * только обновление приложения». Полный сброс базы для него не предлагается: связь
 * «пакет сломан → сотрём ваш прогресс» ложна.
 */
enum class TodayFailureKind { Generic, ContentConflict, ContentUnusable }
