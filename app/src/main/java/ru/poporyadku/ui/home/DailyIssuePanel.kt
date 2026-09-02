package ru.poporyadku.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import ru.poporyadku.R
import ru.poporyadku.ui.components.StreakRow
import ru.poporyadku.ui.components.ThreeStepProgress
import ru.poporyadku.ui.components.ThreeStepProgressMode
import ru.poporyadku.ui.theme.ProjectTextStyles
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Композитный блок «сегодняшний выпуск» (COMPONENTS.md, «DailyIssuePanel»).
 *
 * Номер дня и относящееся к нему содержимое — **одна** редакционная единица за общей
 * spine-меткой, а не раздельные поверхности. Дата внутри не дублируется: она
 * принадлежит [HomeHeader] и показывается один раз на весь экран. Категория дня не
 * раскрывается здесь ни при каких условиях.
 *
 * Компонент присутствует в `FirstRun`/`Ready`/`InProgress`/`Completed`/`AwaitingNextDay`
 * и **отсутствует вовсе** в `ContentExhausted`, `AwaitingFirstDay` и `Error`.
 *
 * Весь блок — **один** составной узел семантики: иначе TalkBack-пользователь проходил
 * бы Home дольше зрячего без причины.
 *
 * @param contentDescription готовое составное описание, соответствующее содержимому
 * состояния — не более и не менее.
 */
@Composable
fun DailyIssuePanel(
    dayNumber: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        // Spine проходит на всю высоту блока, который помечает, и не становится толще
        // при масштабе шрифта 200%: spacing.spine.width — константа.
        Box(
            modifier = Modifier
                .width(Spacing.spineWidth)
                .fillMaxHeight()
                .background(colors.primary),
        )
        Column(
            modifier = Modifier
                .padding(start = Spacing.spineContentIndent)
                .fillMaxWidth(),
        ) {
            IssueNumber(dayNumber)
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.scale400),
                thickness = Sizing.dividerThickness,
                color = colors.outlineVariant,
            )
            content()
            HorizontalDivider(
                modifier = Modifier.padding(top = Spacing.scale400),
                thickness = Sizing.dividerThickness,
                color = colors.outlineVariant,
            )
        }
    }
}

/**
 * Блок «День»: метка `IssueLabel` («ВЫПУСК», mono, `tertiary`) над `IssueNumber`.
 *
 * `IssueNumber` — составная роль: слово набрано `headlineMedium` (serif), число —
 * JetBrains Mono Bold. Единственное место в системе, где serif и mono стоят на одной
 * строке.
 */
@Composable
private fun IssueNumber(dayNumber: Int) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(
            text = stringResource(R.string.home_issue_label).uppercase(),
            style = ProjectTextStyles.issueLabel,
            color = colors.tertiary,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.scale200),
        ) {
            Text(
                text = stringResource(R.string.home_issue_number_word),
                style = ProjectTextStyles.issueNumberWord,
                color = colors.onSurface,
            )
            Text(
                text = dayNumber.toString(),
                style = ProjectTextStyles.issueNumberDigits,
                color = colors.onSurface,
            )
        }
    }
}

/** Одна строка «метка → значение» внутри панели. */
@Composable
internal fun IssueStatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    StreakRow(label = label, value = value, modifier = modifier)
}

/**
 * Вариант `InProgress`: «Задание N из 3» и `ThreeStepProgress` под ним. Ни счёта, ни
 * баллов — ни промежуточных, ни скрытых-но-отрендеренных.
 */
@Composable
internal fun InProgressContent(completedCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.scale300)) {
        Text(
            text = stringResource(R.string.home_task_of_three, completedCount + 1),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ThreeStepProgress(
            mode = ThreeStepProgressMode.ActiveDay,
            completedCount = completedCount,
        )
    }
}
