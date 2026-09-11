package ru.poporyadku.ui.archive

import java.time.LocalDate

/**
 * Экранная модель архива (ITERATION_5_DESIGN.md, §3.6, §4.2, I5-D7).
 *
 * `Empty` — защитное состояние: из UI release-сборки недостижимо (иконка архива на Home
 * видна только при `completedDayCount > 0`), возникает при опустевшей базе и в тестах.
 */
sealed interface ArchiveState {
    data object Loading : ArchiveState

    data object Empty : ArchiveState

    data class Content(
        val statistics: ArchiveStatistics,
        val items: List<ArchiveItem>,
        val paging: ArchivePaging,
    ) : ArchiveState

    /** Первая загрузка не удалась: «Не удалось прочитать историю» + «Повторить». */
    data object Error : ArchiveState
}

/** Одна строка архива — один сыгранный день. */
data class ArchiveItem(
    val localDate: LocalDate,
    /** `set_index + 1`, ≥ 1. */
    val dayNumber: Int,
    /** 0..18; у незавершённого дня — частичный счёт. */
    val totalScore: Int,
    /** 1..3 — закрытые слоты. */
    val completedCount: Int,
    val isComplete: Boolean,
) {
    /** Ключ элемента `LazyColumn`: ISO-дата стабильна между перезапросами окна. */
    val key: String get() = localDate.toString()
}

/** Пять значений блока статистики (§3.5). */
data class ArchiveStatistics(
    val playedDays: Int,
    /** `null` → «—»: завершённых дней нет. */
    val averageTenths: Int?,
    val bestDayScore: Int,
    val currentStreak: Int,
    val bestStreak: Int,
)

/** Фаза подгрузки: определяет футер списка. */
sealed interface ArchivePaging {
    /** Есть продолжение: футер — skeleton-строка, её появление просит следующую страницу. */
    data object CanLoadMore : ArchivePaging

    /** Страница читается: та же skeleton-строка, без нового запроса. */
    data object LoadingMore : ArchivePaging

    /** Разведка следующей страницы упала; строки на месте, «Повторить» — с той же границы. */
    data object LoadMoreFailed : ArchivePaging

    /** Перечитывание окна или статистики упало; показаны последние удачные данные. */
    data object RefreshFailed : ArchivePaging

    /** Продолжения нет: футера нет. */
    data object EndReached : ArchivePaging
}
