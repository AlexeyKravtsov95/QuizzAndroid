package ru.poporyadku.ui.share

import android.content.res.Resources
import ru.poporyadku.R

/**
 * Тексты карточки из ресурсов (ITERATION_5_DESIGN.md, §3.13, §6.6).
 *
 * Единственное место, где карточка касается `Resources`: их читает route-контейнер
 * `DayRecap` при сборе эффекта `Share`, а ViewModel ни `Context`, ни `Resources` не
 * держит. Копии алгоритма карточки здесь нет — текст строит [ShareCardBuilder].
 */
fun Resources.shareCardStrings(): ShareCardStrings = ShareCardStrings(
    headerTemplate = getString(R.string.share_card_header),
    streakTemplate = getString(R.string.share_card_streak),
    dayOne = getString(R.string.share_card_day_one),
    dayFew = getString(R.string.share_card_day_few),
    dayMany = getString(R.string.share_card_day_many),
)

/**
 * Ссылка на приложение (I5-D21): нейтральный непереводимый ресурс, а не адрес магазина.
 * До релиза — альфа-значение `https://poporyadku.invalid/`; в итерации 7 меняется только
 * значение ресурса — на URL универсальной landing-страницы.
 */
fun Resources.shareAppUrl(): String = getString(R.string.share_app_url)

/** Готовый текст карточки для [input]: шаблоны и ссылка — из ресурсов, сборка — билдером. */
fun Resources.shareCardText(input: ShareCardInput): String =
    ShareCardBuilder.build(input, shareCardStrings(), shareAppUrl())
