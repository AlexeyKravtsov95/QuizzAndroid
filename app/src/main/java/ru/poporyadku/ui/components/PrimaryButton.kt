package ru.poporyadku.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import ru.poporyadku.ui.theme.Opacity
import ru.poporyadku.ui.theme.Sizing

/**
 * Единственное основное действие экрана (COMPONENTS.md, «PrimaryButton»).
 *
 * `disabled` — без вариантов: заливки нет вовсе (ни `primary`, ни `surfaceContainer`),
 * вместо неё приглушённая обводка `outline` при `opacity.disabledContent`, текст
 * `onSurface` при той же непрозрачности. Состояние различимо не только цветом —
 * заливка присутствует/отсутствует (DESIGN_PRINCIPLES.md §7). Disabled-семантику
 * для TalkBack и switch access выставляет сам Material-`Button` по `enabled = false`.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.buttonHeight),
        shape = MaterialTheme.shapes.small,
        border = if (enabled) {
            null
        } else {
            BorderStroke(Sizing.dividerThickness, colors.outline.copy(alpha = Opacity.disabledContent))
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurface.copy(alpha = Opacity.disabledContent),
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}
