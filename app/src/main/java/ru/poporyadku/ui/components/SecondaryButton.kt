package ru.poporyadku.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import ru.poporyadku.ui.theme.Opacity
import ru.poporyadku.ui.theme.Sizing

/**
 * Второстепенное действие (COMPONENTS.md, «SecondaryButton»).
 *
 * В системе SecondaryButton **всегда** outlined: прозрачный фон и обводка `outline`,
 * никогда не tonal-filled и никогда с заливкой `tertiary`/`secondary`. Размеры и
 * поведение — те же, что у [PrimaryButton].
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (enabled) {
        colors.outline
    } else {
        colors.outline.copy(alpha = Opacity.disabledContent)
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.buttonHeight),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(Sizing.dividerThickness, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.onSurface,
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
