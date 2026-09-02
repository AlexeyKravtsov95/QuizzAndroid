package ru.poporyadku.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.poporyadku.R
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Одна строка источника (COMPONENTS.md, «SourceRow»).
 *
 * Ровно **три** состояния, не два: наличия `url` недостаточно, чтобы решить, кликабельна
 * ли строка, потому что обработчик `ACTION_VIEW` на устройстве может отсутствовать.
 *
 * - `link` — есть `url` и найден обработчик: строка кликабельна целиком, иконка перехода
 *   справа, `contentDescription` содержит название источника;
 * - `urlPlainText` — есть `url`, обработчика нет: `url` показывается обычным читаемым
 *   текстом, строка некликабельна, без иконки и без ошибки;
 * - `referenceOnly` — `url` отсутствует: показывается обязательный `reference`.
 *
 * Название, вид источника и дата обращения показываются **всегда**, в любом из трёх
 * состояний; `reference` не скрывается из-за наличия `url`.
 *
 * Собственного сетевого запроса не выполняется: переход отдаётся внешнему браузеру, и
 * разрешение `INTERNET` для этого не требуется.
 *
 * Видимость обработчика на API 30+ обеспечена объявлением `<queries>` в манифесте:
 * без него `resolveActivity` не увидел бы установленный браузер и строка деградировала
 * бы до `urlPlainText` даже там, где переход возможен.
 */
@Composable
fun SourceRow(
    source: Puzzle.Source,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    val intent = remember(source.url) {
        source.url?.let { Intent(Intent.ACTION_VIEW, it.toUri()) }
    }
    // resolveActivity, а не «есть url»: без обработчика строка обязана деградировать
    // до читаемого текста, а не вести в никуда.
    val isLink = remember(intent) {
        intent != null && intent.resolveActivity(context.packageManager) != null
    }

    val linkDescription = stringResource(R.string.cd_source_link, source.title)
    val rowModifier = if (isLink) {
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizing.touchTargetMin)
            // Роль объявляется ЯВНО, а не выводится из наличия обработчика нажатия:
            // TalkBack обязан назвать строку кнопкой, а не прочитать её как текст,
            // за которым почему-то есть действие.
            .clickable(role = Role.Button) { context.startActivity(intent) }
            // Описание обязательно включает НАЗВАНИЕ источника: «Открыть источник в
            // браузере» без него не говорит, какой именно источник откроется.
            .semantics(mergeDescendants = true) { contentDescription = linkDescription }
    } else {
        // Некликабельная строка не должна быть достижима фокусом как интерактивный
        // элемент: это обычный текст, без роли и без действия нажатия.
        modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier.padding(vertical = Spacing.scale200),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale200),
    ) {
        Column(
            modifier = Modifier.weight(WEIGHT_FILL),
            verticalArrangement = Arrangement.spacedBy(Spacing.scale100),
        ) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
            )

            // Raw-значение enum пользователю не показывается ни при каких условиях;
            // неизвестное значение — ошибка данных, а не повод для произвольного текста.
            source.kindLabelRes?.let { labelRes ->
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            if (source.url != null && !isLink) {
                Text(
                    text = source.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            source.reference?.let { reference ->
                Text(
                    text = reference,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.source_accessed_at, source.accessedAt),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        if (isLink) {
            Icon(
                imageVector = rememberExternalLinkIcon(colors.onSurfaceVariant),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(IconSizing.small),
            )
        }
    }
}

/** Русская подпись вида источника; `null` — значение вне модели контента. */
private val Puzzle.Source.kindLabelRes: Int?
    get() = when (kind) {
        "official" -> R.string.source_kind_official
        "encyclopedia" -> R.string.source_kind_encyclopedia
        "academic" -> R.string.source_kind_academic
        "other" -> R.string.source_kind_other
        else -> null
    }

/** Иконка внешнего перехода — геометрия в коробке 16 юнитов, ровно `icon.size.small`. */
@Composable
private fun rememberExternalLinkIcon(tint: Color): ImageVector = remember(tint) {
    ImageVector.Builder(
        name = "external_link",
        defaultWidth = IconSizing.small,
        defaultHeight = IconSizing.small,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    )
        .addPath(
            pathData = PathParser()
                .parsePathString("M12.5 9.5 V13 H3 V3.5 H6.5 M9.5 2.5 H13.5 V6.5 M13.5 2.5 L7.5 8.5")
                .toNodes(),
            fill = null,
            stroke = SolidColor(tint),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}

private const val ICON_VIEWPORT = 16f

/** `icon.strokeWidth.small` = 1.6 dp, выраженная в единицах вьюпорта 1:1. */
private const val STROKE_WIDTH = 1.6f
private const val WEIGHT_FILL = 1f
