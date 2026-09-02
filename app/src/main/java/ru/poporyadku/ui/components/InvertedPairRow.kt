package ru.poporyadku.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.poporyadku.R

/**
 * Строка перепутанной пары (COMPONENTS.md, «InvertedPairRow»; UX_FLOW.md §5).
 *
 * **Шаблон один**, не два (I3-D5): якорь — порядок пользователя, первой называется
 * карточка, которую он поставил раньше, и про неё говорится, что она должна
 * располагаться **после** второй. Из нормализованной пары это получается без обращения
 * к `submittedOrder`: `correctlySecond` всегда та, что у пользователя выше. Вариант
 * «перед» не используется ни разу и потому не заводится.
 *
 * Слов «выше»/«ниже» в шаблоне нет физически: в головоломке про высоту гор «выше» —
 * ещё и измеряемая величина, и строка стала бы двусмысленной ровно там, где она важнее
 * всего. Направление сортировки в строку тоже не подставляется.
 *
 * На узкой ширине показывается сокращённая форма — это часть шаблона, а не ручной
 * перенос; TalkBack всегда получает полную.
 *
 * Цвет `tertiary` применяется здесь как **текст** — единственный разрешённый способ
 * применения терракоты к длинной строке; сама карточка не подсвечивается.
 */
@Composable
fun InvertedPairRow(
    laterTitle: String,
    earlierTitle: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val full = stringResource(R.string.inverted_pair, laterTitle, earlierTitle)
    val shown = if (compact) {
        stringResource(R.string.inverted_pair_compact, laterTitle, earlierTitle)
    } else {
        full
    }

    Text(
        text = shown,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.semantics { contentDescription = full },
    )
}
