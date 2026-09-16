package ru.poporyadku.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.poporyadku.R
import ru.poporyadku.ui.theme.Elevation
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Spacing

/** Стабильные testTag диалога напоминания. */
object NotificationOptInTestTags {
    const val DIALOG = "notification_opt_in_dialog"
    const val QUESTION = "notification_opt_in_question"
    const val ACCEPT = "notification_opt_in_accept"
    const val DECLINE = "notification_opt_in_decline"
}

/**
 * Предложение включить напоминание (COMPONENTS.md, «NotificationOptInDialog»;
 * ITERATION_6_DESIGN.md, §8.3, I6-D40).
 *
 * **Единственный диалог продукта** и одно из ровно двух состояний с настоящей тенью
 * (второе — `OrderableCard.dragging`): `shape.large`, `surfaceContainerHigh`,
 * `elevation.dialog`. Это исключение зафиксировано явно и на другие контейнеры не
 * распространяется.
 *
 * **Один текстовый узел вопроса**, а не заголовок плюс дублирующий текст: первый фокус
 * TalkBack — сам вопрос, затем два действия в порядке «Да, в 9:00» → «Не нужно».
 *
 * **Касание вне диалога не делает ничего** (`dismissOnClickOutside = false`): закрыть
 * диалог можно только явным выбором или системной «назад», которая равносильна
 * «Не нужно». Случайный промах мимо диалога не считается ответом.
 */
@Composable
fun NotificationOptInDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        // Системная «назад» — это «Не нужно»: отметка ставится, напоминание не включается.
        onDismissRequest = onDecline,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .testTag(NotificationOptInTestTags.DIALOG),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = Elevation.dialog,
            shadowElevation = Elevation.dialog,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.scale500),
                verticalArrangement = Arrangement.spacedBy(Spacing.scale400),
            ) {
                Text(
                    text = stringResource(R.string.reminder_prompt_question),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag(NotificationOptInTestTags.QUESTION),
                )
                // Кнопки во всю ширину и одна под другой: при 200 % и 320 dp
                // горизонтальная пара не помещается, а сокращать цели запрещено.
                PrimaryButton(
                    text = stringResource(R.string.reminder_prompt_accept),
                    onClick = onAccept,
                    modifier = Modifier.testTag(NotificationOptInTestTags.ACCEPT),
                )
                SecondaryButton(
                    text = stringResource(R.string.reminder_prompt_decline),
                    onClick = onDecline,
                    modifier = Modifier.testTag(NotificationOptInTestTags.DECLINE),
                )
            }
        }
    }
}

// --- Preview -----------------------------------------------------------------------------

@Composable
private fun PreviewDialog(darkTheme: Boolean = false) {
    PoPoRyadkuTheme(darkTheme = darkTheme) {
        NotificationOptInDialog(onAccept = {}, onDecline = {})
    }
}

@Preview(name = "NotificationOptInDialog — 390×844", widthDp = 390, heightDp = 844)
@Composable
private fun NotificationOptInPreview() = PreviewDialog()

@Preview(
    name = "NotificationOptInDialog — dark",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun NotificationOptInDarkPreview() = PreviewDialog(darkTheme = true)

@Preview(name = "NotificationOptInDialog — 320×844 @200%", widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun NotificationOptInCompactPreview() = PreviewDialog()
