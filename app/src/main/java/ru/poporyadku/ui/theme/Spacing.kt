package ru.poporyadku.ui.theme

import androidx.compose.ui.unit.dp

// DESIGN_TOKENS.md §6.5 — примитивная шкала отступов, шаг кратен 4 dp.
// Уровень primitive: используется только для определения семантических токенов ниже.
private object SpacingScale {
    val s100 = 4.dp
    val s200 = 8.dp
    val s300 = 12.dp
    val s400 = 16.dp
    val s500 = 20.dp
    val s600 = 24.dp
    val s700 = 32.dp
    val s800 = 40.dp
    val s900 = 48.dp
    val s1000 = 64.dp
}

/**
 * DESIGN_TOKENS.md §6.5 — семантические токены отступов. Компоненты и экраны обращаются
 * только сюда, никогда к [SpacingScale] напрямую.
 */
object Spacing {
    /** spacing.margin.default — горизонтальное поле экрана, ширина ≥ 360 dp. */
    val marginDefault = SpacingScale.s600

    /** spacing.margin.compact — горизонтальное поле экрана, ширина < 360 dp (включая 320 dp). */
    val marginCompact = SpacingScale.s400

    /** spacing.section — вертикальный зазор между независимыми секциями экрана. */
    val section = SpacingScale.s600

    /** spacing.listGap — вертикальный зазор между однородными элементами списка. */
    val listGap = SpacingScale.s300

    /** spacing.statRow.inner — внутренний отступ строки статистики (метка → значение). */
    val statRowInner = SpacingScale.s100

    /** spacing.spine.width — ширина редакционной spine-метки Home (константа, не отступ). */
    val spineWidth = 3.dp

    /** spacing.spine.contentIndent — отступ содержимого DailyIssuePanel от spine. */
    val spineContentIndent = SpacingScale.s500

    // Примитивы шкалы, оставленные доступными по имени для будущих семантических токенов
    // (итерации 3/5/6), не для прямого использования компонентами.
    val scale100 = SpacingScale.s100
    val scale200 = SpacingScale.s200
    val scale300 = SpacingScale.s300
    val scale400 = SpacingScale.s400
    val scale500 = SpacingScale.s500
    val scale600 = SpacingScale.s600
    val scale700 = SpacingScale.s700
    val scale800 = SpacingScale.s800
    val scale900 = SpacingScale.s900
    val scale1000 = SpacingScale.s1000
}
