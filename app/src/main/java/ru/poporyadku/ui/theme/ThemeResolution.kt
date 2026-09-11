package ru.poporyadku.ui.theme

import ru.poporyadku.core.model.ThemeMode

/**
 * Разрешённая тема (ITERATION_5_DESIGN.md, §3.9, §4.8, I5-D16): тёмная ли она при данной
 * системной теме. Чистая функция — проверяется на JVM (`I5-T1`).
 */
fun ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
