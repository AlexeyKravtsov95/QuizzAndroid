package ru.poporyadku.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.poporyadku.R

// DESIGN_TOKENS.md §6.3 — три утверждённых семейства, статические инстансы (не вариативные
// файлы), кириллица и «ё» подтверждены на Design Gate B2.
val NotoSerifFamily = FontFamily(
    Font(R.font.noto_serif_regular, FontWeight.Normal),
    Font(R.font.noto_serif_medium, FontWeight.Medium),
    Font(R.font.noto_serif_semibold, FontWeight.SemiBold),
)

val GolosTextFamily = FontFamily(
    Font(R.font.golos_text_regular, FontWeight.Normal),
    Font(R.font.golos_text_medium, FontWeight.Medium),
    Font(R.font.golos_text_semibold, FontWeight.SemiBold),
    Font(R.font.golos_text_bold, FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

// DESIGN_TOKENS.md §6.4 — полная шкала ролей Material 3. Размер/высота строки/трекинг
// перенесены буквально; Serif = NotoSerifFamily, Sans = GolosTextFamily.
val PoPoRyadkuTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)

/**
 * DESIGN_TOKENS.md §6.4 «Проектные роли» — ограниченный список, ссылающийся на базовую
 * шкалу M3 там, где роль является псевдонимом, и определяющий буквальные значения там,
 * где это самостоятельный композит (сам композит — это и есть определение токена, а не
 * захардкоженное значение в компоненте).
 */
object ProjectTextStyles {

    /**
     * EditorialTitle — псевдоним headlineSmall. Формулировка задания и заголовки
     * PuzzleResult (`COMPONENTS.md`, «Puzzle», «PuzzleResult»).
     */
    val editorialTitle: TextStyle = PoPoRyadkuTypography.headlineSmall

    /**
     * IssueNumber — составная роль: слово («День») набрано headlineMedium, число
     * («24») — JetBrains Mono Bold 40/44/0. Единственное место в системе, где serif
     * и mono стоят на одной строке.
     */
    val issueNumberWord: TextStyle = PoPoRyadkuTypography.headlineMedium
    val issueNumberDigits: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    )

    /** CardIndex — «№1…№4» в индексной зоне OrderableCard. */
    val cardIndex: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    )

    /** Metadata — дата на Home и любые другие полные mono-строки. */
    val metadata: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    )

    /**
     * Masthead — мастхед «ПО ПОРЯДКУ!» в HomeHeader, единственное использование.
     * `textTransform = uppercase` обязателен и применяется на месте вызова
     * (`text.uppercase()`), так как TextStyle не несёт трансформации регистра.
     */
    val masthead: TextStyle = TextStyle(
        fontFamily = NotoSerifFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 2.sp,
    )

    /**
     * IssueLabel — метка «ВЫПУСК» в DailyIssuePanel, единственное использование.
     * `textTransform = uppercase` применяется на месте вызова.
     */
    val issueLabel: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.5.sp,
    )

    /**
     * CapsLabel — метки строк статистики («СЕРИЯ», «ЛУЧШИЙ ДЕНЬ», «СЫГРАНО ДНЕЙ»).
     * Sans (Golos Text), не mono — не путать с IssueLabel несмотря на совпадение
     * размера/трекинга. `textTransform = uppercase` применяется на месте вызова.
     */
    val capsLabel: TextStyle = TextStyle(
        fontFamily = GolosTextFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.5.sp,
    )
}
