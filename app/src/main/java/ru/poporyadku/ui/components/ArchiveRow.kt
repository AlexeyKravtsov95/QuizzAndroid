package ru.poporyadku.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import ru.poporyadku.R
import ru.poporyadku.ui.theme.ProjectTextStyles
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag строки архива и её skeleton. */
object ArchiveRowTestTags {
    const val SKELETON_DATE = "archive_row_skeleton_date"
    const val SKELETON_SECOND_LINE = "archive_row_skeleton_second_line"
}

/**
 * Строка архива — один сыгранный день (COMPONENTS.md, state sheet «Archive row»;
 * ITERATION_5_DESIGN.md, §3.6).
 *
 * ```
 * 25 августа 2026                          ← Metadata (mono), onSurface
 * День 12   15 из 18   ● ● ●               ← Metadata · labelMedium · ThreeStepProgress
 * ─────────────────────────────── hairline outlineVariant
 * 24 августа 2026
 * День 11   8 из 18   ● ● ○   не завершён  ← + bodySmall onSurfaceVariant
 * ```
 *
 * Вся строка — одна сенсорная цель (`Role.Button`, метка «Открыть итог дня», высота не
 * меньше `size.touchTarget.min`) и один узел семантики с составным `contentDescription`
 * из утверждённых строк. Видимые тексты и точки из семантики исключены: объединяющий узел
 * с описанием и текстовыми потомками TalkBack прочёл бы дважды — описание, а за ним те же
 * тексты. Вторая строка — `FlowRow`: при 320 dp и 200 % элементы переносятся целиком,
 * ни один не уменьшается. Шевронов и новых индикаторов нет — нажимаемость выражается
 * state layer и семантикой.
 *
 * @param dateText дата уже в формате `d MMMM yyyy` — тот же, что у заголовка архивного итога.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchiveRow(
    dateText: String,
    dayNumber: Int,
    totalScore: Int,
    completedCount: Int,
    isComplete: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = if (isComplete) {
        stringResource(R.string.cd_archive_row_complete, dateText, dayNumber, totalScore)
    } else {
        stringResource(R.string.cd_archive_row_incomplete, dateText, dayNumber, totalScore, completedCount)
    }
    val openLabel = stringResource(R.string.archive_row_open)
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizing.touchTargetMin)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
                // Одно составное описание вместо чтения каждого текста строки.
                .clearAndSetSemantics { contentDescription = description }
                .padding(vertical = Spacing.scale300),
            verticalArrangement = Arrangement.spacedBy(Spacing.scale100),
        ) {
            Text(
                text = dateText,
                style = ProjectTextStyles.metadata,
                color = colors.onSurface,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.scale300),
                verticalArrangement = Arrangement.spacedBy(Spacing.scale100),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.archive_row_day, dayNumber),
                    style = ProjectTextStyles.metadata,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.score_of_day, totalScore),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
                ThreeStepProgress(mode = ThreeStepProgressMode.ClosedDay, completedCount = completedCount)
                if (!isComplete) {
                    Text(
                        text = stringResource(R.string.archive_row_incomplete),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(thickness = Sizing.dividerThickness, color = colors.outlineVariant)
    }
}

/**
 * Skeleton строки архива: полоса даты 45 % и полоса второй строки 60 %, hairline под
 * строкой — тот же силуэт, что у [ArchiveRow], без формы-контейнера вокруг.
 */
@Composable
fun ArchiveRowSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizing.touchTargetMin)
                .padding(vertical = Spacing.scale300),
            verticalArrangement = Arrangement.spacedBy(Spacing.scale200),
        ) {
            SkeletonLine(
                widthFraction = SKELETON_DATE_FRACTION,
                modifier = Modifier.testTag(ArchiveRowTestTags.SKELETON_DATE),
            )
            SkeletonLine(
                widthFraction = SKELETON_SECOND_LINE_FRACTION,
                modifier = Modifier.testTag(ArchiveRowTestTags.SKELETON_SECOND_LINE),
            )
        }
        HorizontalDivider(thickness = Sizing.dividerThickness, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private const val SKELETON_DATE_FRACTION = 0.45f
private const val SKELETON_SECOND_LINE_FRACTION = 0.60f
