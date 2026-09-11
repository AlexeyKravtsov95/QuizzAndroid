package ru.poporyadku.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import ru.poporyadku.core.model.Category
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Левая часть строки итога: либо категория, либо нейтральная подпись «Задание N». */
sealed interface DayResultLeading {
    /** `available` (`SlotOutcome.Played`) — головоломка доступна, категория известна. */
    data class CategoryOf(val category: Category) : DayResultLeading

    /**
     * `unavailable` и `notPlayed` — показывать нечем либо попытки нет:
     * `CategoryLabel` не рисуется.
     */
    data class Label(val text: String) : DayResultLeading
}

/**
 * Правая часть строки итога (COMPONENTS.md, «DayResultRow»).
 *
 * Два варианта, а не строка с флагом: у `notPlayed` справа не счёт, а текст «не сыграно»
 * в приглушённом цвете — «0 из 6» у слота без попытки был бы выдумкой.
 */
sealed interface DayResultTrailing {
    val text: String

    /** Фактический счёт попытки «{score} из 6», `onSurface`. */
    data class Score(override val text: String) : DayResultTrailing

    /** Слот без попытки: «не сыграно», `onSurfaceVariant`. */
    data class NotPlayed(override val text: String) : DayResultTrailing
}

/**
 * Одна строка итога дня.
 *
 * [onClick] задан только у интерактивной `available`-строки архивного итога
 * (ITERATION_5_DESIGN.md, §3.7, I5-D10); [onClickLabel] — метка действия для TalkBack.
 */
data class DayResultRowData(
    val leading: DayResultLeading,
    val trailing: DayResultTrailing,
    val onClick: (() -> Unit)? = null,
    val onClickLabel: String? = null,
)

/**
 * Список строк итога дня (COMPONENTS.md, «DayResultRow»).
 *
 * **Раскладка выбирается один раз на весь список.** Если хотя бы одна строка не
 * помещается в `inline` при текущей ширине и текущем масштабе шрифта, в `stacked`
 * уходят **все** строки: смешанной раскладки не существует ни при какой ширине и ни при
 * каком масштабе — это дефект, а не адаптация. Ни `CategoryLabel`, ни результат при
 * переходе не уменьшаются: меняется расположение, а не размеры.
 *
 * Hairline рисуется под каждой строкой, **включая последнюю** — она отделяет список
 * от `StreakRow`.
 */
@Composable
fun DayResultList(
    rows: List<DayResultRowData>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyLarge = MaterialTheme.typography.bodyLarge

    // Ширины считаются один раз, ДО измерения контейнера: строки ресурсов читаются
    // через stringResource (он учитывает конфигурацию), а не через LocalContext.
    val requiredWidths: List<Dp> = rows.map { row ->
        val leadingWidth = when (val leading = row.leading) {
            is DayResultLeading.CategoryOf ->
                Spacing.scale200 + IconSizing.small + Spacing.scale200 +
                    measurer.widthOf(density, stringResource(leading.category.labelRes), labelMedium) +
                    Spacing.scale200

            is DayResultLeading.Label -> measurer.widthOf(density, leading.text, bodyLarge)
        }
        leadingWidth + Spacing.scale400 + measurer.widthOf(density, row.trailing.text, bodyLarge)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available: Dp = maxWidth

        // Проверяемое условие COMPONENTS.md: «ширина левой части + spacing.400 +
        // ширина результата > ширина контентной колонки» хотя бы у одной строки.
        val stacked = requiredWidths.any { it > available }

        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                DayResultRow(row = row, stacked = stacked)
                HorizontalDivider(
                    thickness = Sizing.dividerThickness,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/**
 * Одна строка списка. `stacked` — свойство **всего** списка, а не строки: параметр
 * приходит снаружи именно для того, чтобы строка не могла решить его за себя.
 *
 * Интерактивная строка — одна сенсорная цель на всю ширину с ролью `Button`, меткой
 * действия и минимальной высотой `size.touchTarget.min`; нажимаемость выражается state
 * layer при нажатии и семантикой, без шевронов и новых индикаторов. Неинтерактивная —
 * один составной узел семантики без действия нажатия.
 *
 * Собственного описания у строки нет: TalkBack читает её части по порядку —
 * «Категория: География, 5 из 6», «Задание 2, не сыграно».
 */
@Composable
fun DayResultRow(
    row: DayResultRowData,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val onClick = row.onClick
    val rowModifier = if (onClick != null) {
        // clickable объединяет потомков в один узел сам: «География. 5 из 6» + действие.
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.touchTargetMin)
            .clickable(onClickLabel = row.onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
    }

    if (stacked) {
        Column(
            modifier = rowModifier.padding(vertical = Spacing.scale200),
            verticalArrangement = Arrangement.spacedBy(Spacing.scale300),
        ) {
            Leading(row.leading)
            Trailing(row.trailing)
        }
    } else {
        Row(
            modifier = rowModifier.padding(vertical = Spacing.scale300),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Leading(row.leading)
            Box(modifier = Modifier.weight(WEIGHT_FILL))
            Trailing(row.trailing)
        }
    }
}

@Composable
private fun Leading(leading: DayResultLeading) {
    when (leading) {
        is DayResultLeading.CategoryOf -> CategoryLabel(category = leading.category)
        is DayResultLeading.Label -> Text(
            text = leading.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Trailing(trailing: DayResultTrailing) {
    Text(
        text = trailing.text,
        style = MaterialTheme.typography.bodyLarge,
        color = when (trailing) {
            is DayResultTrailing.Score -> MaterialTheme.colorScheme.onSurface
            is DayResultTrailing.NotPlayed -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

private fun TextMeasurer.widthOf(density: Density, text: String, style: TextStyle): Dp =
    with(density) { measure(text = text, style = style).size.width.toDp() }

/** Растяжка между левой частью и результатом в `inline`-раскладке. */
private const val WEIGHT_FILL = 1f
