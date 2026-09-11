package ru.poporyadku.ui.archive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.poporyadku.R

/**
 * Форматирование архива (ITERATION_5_DESIGN.md, §3.5, §3.6, §6.2). Только ресурсы и
 * русская локаль — локаль устройства на цифры и месяцы не влияет.
 */

/**
 * Средний балл: одна десятичная всегда, десятичная запятая — «13,7 из 18», «15,0 из 18»;
 * `null` (завершённых дней нет) — «—». Постоянная ширина исключает «прыжок» строки между
 * «15» и «14,7».
 */
@Composable
fun averageText(tenths: Int?): String =
    if (tenths == null) {
        stringResource(R.string.archive_stat_average_absent)
    } else {
        stringResource(R.string.archive_stat_average_value, tenths / TENTHS, tenths % TENTHS)
    }

/**
 * Дата строки архива — `d MMMM yyyy` на русском, год всегда: тот же формат, что у
 * заголовка архивного итога («25 августа 2026»).
 */
@Composable
fun rememberArchiveDate(date: LocalDate): String = remember(date) { date.format(ARCHIVE_DATE_FORMATTER) }

private val ARCHIVE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

private const val TENTHS = 10
