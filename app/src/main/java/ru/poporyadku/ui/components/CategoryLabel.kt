package ru.poporyadku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import ru.poporyadku.R
import ru.poporyadku.core.model.Category
import ru.poporyadku.ui.theme.IconSizing
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing

/**
 * Каталожная метка категории (COMPONENTS.md, «CategoryLabel»).
 *
 * Используется ровно в двух местах: правый слот `AppTopBar` на `Puzzle` и внутри
 * [DayResultRow] на `DayRecap`. На `Home` категория не раскрывается ни в каком виде.
 *
 * Пиктограмма и текст всегда вместе — варианта «только иконка» не существует. Весь
 * компонент — один узел семантики «Категория: {название}»: иконка декоративна
 * относительно текста и отдельным фокус-стопом не становится.
 */
@Composable
fun CategoryLabel(
    category: Category,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(category.labelRes)
    val description = stringResource(R.string.cd_category, name)

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = Sizing.categoryLabelMinHeight)
            .background(colors.primaryContainer, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = Spacing.scale200, vertical = Spacing.scale100)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.scale200),
    ) {
        Icon(
            imageVector = rememberCategoryIcon(category),
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            // icon.size.small — в dp, поэтому системный масштаб шрифта его не растит.
            modifier = Modifier.size(IconSizing.small),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onPrimaryContainer,
        )
    }
}

/** Название категории в UI — таблица «Таблица иконок категорий» COMPONENTS.md. */
internal val Category.labelRes: Int
    get() = when (this) {
        Category.HISTORY -> R.string.category_history
        Category.GEOGRAPHY -> R.string.category_geography
        Category.SCIENCE -> R.string.category_science
        Category.NATURE -> R.string.category_nature
        Category.CULTURE -> R.string.category_culture
        Category.RUSSIA -> R.string.category_russia
        Category.MIXED -> R.string.category_mixed
    }

@Composable
private fun rememberCategoryIcon(category: Category): ImageVector {
    // Заливка приходит из роли темы: литеральных цветов в компонентах нет.
    val tint = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(category, tint) { category.glyph.toImageVector(tint) }
}

private val Category.glyph: CategoryGlyph
    get() = when (this) {
        Category.HISTORY -> CategoryGlyph.HISTORY
        Category.GEOGRAPHY -> CategoryGlyph.GEOGRAPHY
        Category.SCIENCE -> CategoryGlyph.SCIENCE
        Category.NATURE -> CategoryGlyph.NATURE
        Category.CULTURE -> CategoryGlyph.CULTURE
        Category.RUSSIA -> CategoryGlyph.RUSSIA
        Category.MIXED -> CategoryGlyph.MIXED
    }

/**
 * Семь пиктограмм — геометрия, а не иконочный шрифт: ни один глиф не адресуется кодом
 * символа, поэтому дополнительная библиотека иконок не подключается.
 *
 * Источник — Material Symbols Outlined (Apache License 2.0), все семь инстанцированы с
 * одинаковыми осями `FILL 0`, `GRAD 0`, `opsz 20`, `wght 600`: единый визуальный вес
 * набора — свойство построения, а не результат подбора на глаз (COMPONENTS.md).
 * Пути перенесены дословно из `tools/design-b2/category_icons.py` — единственного места,
 * где эта геометрия зафиксирована; коробка 24 × 24 с осью Y вниз.
 */
private enum class CategoryGlyph(val pathData: String) {
    /** Material Symbol `account_balance` — История. */
    HISTORY(
        pathData =
            "M4.36 17.56V9.76H6.6V17.56ZM10.89 17.56V9.76H13.11V17.56ZM1.92 20.77V18.32H22.08V20.77ZM17.4" +
            " 17.56V9.76H19.64V17.56ZM1.92 8.98V5.8L12 0.76L22.08 5.8V8.98ZM5.96 6.53H12H18.04ZM5.96 6.53" +
            "H18.04L12 3.52Z",
    ),
    /** Material Symbol `public` — География. */
    GEOGRAPHY(
        pathData =
            "M12.03 22.08Q9.94 22.08 8.11 21.3Q6.27 20.51 4.89 19.13Q3.5 17.74 2.72 15.91Q1.93 14.07 1.93" +
            " 11.99Q1.93 9.9 2.72 8.08Q3.5 6.26 4.89 4.87Q6.27 3.49 8.11 2.7Q9.94 1.92 12.03 1.92Q14.11 1" +
            ".92 15.93 2.7Q17.74 3.49 19.13 4.87Q20.51 6.26 21.3 8.08Q22.08 9.9 22.08 11.99Q22.08 14.07 2" +
            "1.3 15.91Q20.51 17.74 19.13 19.13Q17.74 20.51 15.93 21.3Q14.11 22.08 12.03 22.08ZM10.83 19.5" +
            "3V17.84Q10.34 17.84 10.01 17.51Q9.67 17.17 9.67 16.69V15.52L4.56 10.4Q4.46 10.86 4.42 11.25Q" +
            "4.38 11.64 4.38 11.98Q4.38 14.8 6.2 16.96Q8.02 19.12 10.83 19.53ZM17.89 16.86Q18.76 15.84 19" +
            ".19 14.58Q19.63 13.33 19.63 11.97Q19.63 9.66 18.37 7.75Q17.12 5.85 14.96 4.96V5.53Q14.96 6.2" +
            "6 14.43 6.77Q13.91 7.28 13.18 7.28H10.83V8.44Q10.83 8.93 10.48 9.28Q10.13 9.63 9.64 9.63H8.4" +
            "8V11.97H14.37Q14.86 11.97 15.21 12.32Q15.56 12.67 15.56 13.16V15.49H16.54Q17.12 15.49 17.5 1" +
            "5.89Q17.89 16.29 17.89 16.86Z",
    ),
    /** Material Symbol `science` — Наука. */
    SCIENCE(
        pathData =
            "M5.4 20.88Q3.95 20.88 3.32 19.59Q2.68 18.3 3.61 17.2L8.52 11.23V5.73H8.06Q7.56 5.73 7.19 5.3" +
            "7Q6.83 5.01 6.83 4.51Q6.83 4 7.19 3.64Q7.55 3.27 8.06 3.27H15.94Q16.45 3.27 16.81 3.63Q17.17" +
            " 3.99 17.17 4.49Q17.17 5 16.81 5.36Q16.45 5.73 15.94 5.73H15.48V11.23L20.39 17.2Q21.29 18.3 " +
            "20.66 19.59Q20.02 20.88 18.6 20.88ZM5.77 18.43H18.23L13.03 12.07V5.73H10.97V12.07ZM12.03 12." +
            "07Z",
    ),
    /** Material Symbol `park` — Природа. */
    NATURE(
        pathData =
            "M14.04 22.08H9.96V18.48H2.71L6.53 12.48H5.1L12 1.58L18.9 12.48H17.47L21.29 18.48H14.04ZM7.2 " +
            "16.03H11.15H9.33H12H14.67H12.85H16.77ZM7.2 16.03H16.77L12.97 10.03H14.42L12 6.1L9.58 10.03H1" +
            "1.03Z",
    ),
    /** Material Symbol `palette` — Культура. */
    CULTURE(
        pathData =
            "M12 22.08Q9.93 22.08 8.09 21.29Q6.26 20.5 4.88 19.12Q3.5 17.74 2.71 15.91Q1.92 14.07 1.92 12" +
            "Q1.92 9.9 2.71 8.08Q3.5 6.26 4.9 4.87Q6.3 3.49 8.17 2.7Q10.05 1.92 12.17 1.92Q14.21 1.92 16." +
            "01 2.63Q17.82 3.34 19.17 4.58Q20.52 5.82 21.3 7.47Q22.08 9.13 22.08 11.02Q22.08 13.51 20.38 " +
            "15.39Q18.69 17.28 16.1 17.28H14.73Q14.59 17.28 14.44 17.38Q14.3 17.47 14.3 17.66Q14.3 18.01 " +
            "14.59 18.09Q14.88 18.17 14.88 19.17Q14.88 20.21 14.05 21.15Q13.22 22.08 12 22.08ZM12 12Q12 1" +
            "2 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12Q" +
            "12 12 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12Q12 12 12 12ZM6.77 12.9Q7.39 12.9" +
            " 7.83 12.46Q8.27 12.02 8.27 11.4Q8.27 10.77 7.83 10.34Q7.39 9.9 6.77 9.9Q6.14 9.9 5.71 10.34" +
            "Q5.27 10.77 5.27 11.4Q5.27 12.02 5.71 12.46Q6.14 12.9 6.77 12.9ZM9.68 9.43Q10.31 9.43 10.75 " +
            "8.99Q11.18 8.55 11.18 7.93Q11.18 7.3 10.75 6.86Q10.31 6.43 9.68 6.43Q9.06 6.43 8.62 6.86Q8.1" +
            "8 7.3 8.18 7.93Q8.18 8.55 8.62 8.99Q9.06 9.43 9.68 9.43ZM14.32 9.43Q14.94 9.43 15.38 8.99Q15" +
            ".82 8.55 15.82 7.93Q15.82 7.3 15.38 6.86Q14.94 6.43 14.32 6.43Q13.69 6.43 13.25 6.86Q12.82 7" +
            ".3 12.82 7.93Q12.82 8.55 13.25 8.99Q13.69 9.43 14.32 9.43ZM17.19 12.9Q17.81 12.9 18.25 12.46" +
            "Q18.69 12.02 18.69 11.4Q18.69 10.77 18.25 10.34Q17.81 9.9 17.19 9.9Q16.56 9.9 16.13 10.34Q15" +
            ".69 10.77 15.69 11.4Q15.69 12.02 16.13 12.46Q16.56 12.9 17.19 12.9ZM11.92 19.63Q12.16 19.63 " +
            "12.3 19.43Q12.44 19.24 12.44 19.09Q12.44 18.69 12.06 18.44Q11.68 18.19 11.68 17.29Q11.68 16." +
            "27 12.39 15.55Q13.1 14.83 14.12 14.83H16.1Q17.73 14.83 18.68 13.69Q19.63 12.56 19.63 11.12Q1" +
            "9.63 8.25 17.45 6.31Q15.26 4.37 12.17 4.37Q8.9 4.37 6.63 6.6Q4.37 8.83 4.37 12.01Q4.37 15.18" +
            " 6.57 17.4Q8.78 19.63 11.92 19.63Z",
    ),
    /** Material Symbol `flag` — Россия. */
    RUSSIA(
        pathData =
            "M4.32 20.88V3.12H13.6L14.2 5.52H19.68V16.08H12.8L12.2 13.68H6.77V20.88ZM12.3 9.62ZM14.76 13." +
            "63H17.23V7.97H12.24L11.64 5.57H6.77V11.23H14.16Z",
    ),
    /** Material Symbol `category` — Смешанное. */
    MIXED(
        pathData =
            "M6.05 11.32 12 1.54 17.95 11.32ZM17.79 22.46Q15.85 22.46 14.48 21.1Q13.12 19.74 13.12 17.79Q" +
            "13.12 15.85 14.48 14.48Q15.84 13.12 17.79 13.12Q19.74 13.12 21.1 14.48Q22.46 15.84 22.46 17." +
            "79Q22.46 19.74 21.1 21.1Q19.74 22.46 17.79 22.46ZM2.51 21.86V13.72H10.67V21.86ZM17.79 20.01Q" +
            "18.71 20.01 19.36 19.37Q20.01 18.72 20.01 17.8Q20.01 16.88 19.37 16.22Q18.72 15.57 17.8 15.5" +
            "7Q16.88 15.57 16.22 16.22Q15.57 16.87 15.57 17.79Q15.57 18.71 16.22 19.36Q16.87 20.01 17.79 " +
            "20.01ZM4.96 19.41H8.22V16.17H4.96ZM10.43 8.87H13.57L12 6.24ZM12 8.87ZM8.22 16.17ZM17.67 17.6" +
            "7Q17.67 17.67 17.67 17.67Q17.67 17.67 17.67 17.67Q17.67 17.67 17.67 17.67Q17.67 17.67 17.67 " +
            "17.67Q17.67 17.67 17.67 17.67Q17.67 17.67 17.67 17.67Q17.67 17.67 17.67 17.67Q17.67 17.67 17" +
            ".67 17.67Z",
    ),    ;

    companion object {
        /** Коробка Material Symbols — 24 юнита; масштабируется до `icon.size.small`. */
        const val VIEWPORT = 24f
    }
}

private fun CategoryGlyph.toImageVector(tint: Color): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = IconSizing.small,
        defaultHeight = IconSizing.small,
        viewportWidth = CategoryGlyph.VIEWPORT,
        viewportHeight = CategoryGlyph.VIEWPORT,
    )
        .addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(tint),
        )
        .build()
