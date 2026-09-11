package ru.poporyadku.ui.recap

import java.time.LocalDate
import ru.poporyadku.core.model.Category
import ru.poporyadku.ui.navigation.RouteOrigin

/**
 * Заголовок экрана (COMPONENTS.md, `AppTopBar`): «Сегодня» бывает только у сессионного
 * итога сегодняшнего дня; архивный итог всегда показывает дату (I5-D8).
 */
sealed interface DayRecapTitle {
    data object Today : DayRecapTitle
    data class Date(val localDate: LocalDate) : DayRecapTitle
}

/**
 * Строка итога в экранной модели (ITERATION_3_DESIGN.md, I3-D37;
 * ITERATION_5_DESIGN.md, §4.3, I5-D9).
 *
 * `score` живёт в вариантах, а не в общем интерфейсе: у [NotPlayed] счёта нет.
 * [Unavailable] при этом не равно «ноль» — счёт из данных.
 */
sealed interface SlotResultUi {
    val slotIndex: Int

    /** Головоломка доступна: показывается `CategoryLabel`. */
    data class Played(
        override val slotIndex: Int,
        /** 0..6. */
        val score: Int,
        val category: Category,
        /** Открывает исторический результат; `== (origin == Archive)` (I5-D10). */
        val isOpenable: Boolean,
    ) : SlotResultUi

    /** Показывать нечем: вместо категории — «Задание N», счёт из данных. */
    data class Unavailable(
        override val slotIndex: Int,
        /** 0..6. */
        val score: Int,
    ) : SlotResultUi

    /** Попытки нет: «Задание N» / «не сыграно», без категории и без «0 из 6». */
    data class NotPlayed(override val slotIndex: Int) : SlotResultUi
}

/** Экранная модель итога дня (ITERATION_3_DESIGN.md, §13; ITERATION_5_DESIGN.md, §4.3). */
sealed interface DayRecapState {

    data object Loading : DayRecapState

    data class Content(
        /** Вариант экрана — только по происхождению маршрута, не по дате (I5-D8). */
        val origin: RouteOrigin,
        val title: DayRecapTitle,
        /** `setIndex + 1`; понадобится карточке шеринга (PR 5D). */
        val dayNumber: Int,
        /** «N из 18». */
        val totalScore: Int,
        val isComplete: Boolean,
        /** Ровно три строки. */
        val slots: List<SlotResultUi>,
        /** Серия ЭТОГО дня (I5-D9, O5-5); `null` → `StreakRow` не показывается. */
        val streakDays: Int?,
        /** «Этот день установил рекорд» — свойство дня, а не момента (I3-D46). */
        val isRecordUpdated: Boolean,
        /** `== isComplete`; сама кнопка «Поделиться» появляется в PR 5D. */
        val canShare: Boolean,
    ) : DayRecapState

    /**
     * `Error.recapMissing`: «Данные за этот день не сохранились», без кнопки.
     * [origin] нужен для кнопки «Назад» в шапке архивного варианта.
     */
    data class NotFound(val origin: RouteOrigin) : DayRecapState
}
