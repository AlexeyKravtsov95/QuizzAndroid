package ru.poporyadku.domain.usecase

import java.text.Collator
import java.util.Locale
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.repository.PuzzleSources

/**
 * Ключ дедупликации источника (ITERATION_5_DESIGN.md, §3.11, §6.5, I5-D17) — значение, а
 * не склеенная строка: у пары `(reference, title)` нет разделителя, который можно было бы
 * встретить в данных.
 *
 * [text] — строковая форма ключа для порядка при равных названиях и для ключа элемента
 * `LazyColumn`. Она **инъективна**: у `Reference` длина `reference` записана перед
 * значениями, поэтому два разных ключа никогда не дают одну строку (форма `toString()`
 * data class склеивала бы поля и совпала бы у `reference = "a, title=b"`, `title = "c"`
 * и `reference = "a"`, `title = "b, title=c"`).
 */
sealed interface SourceKey {
    val text: String

    /** Есть `url`: URL как есть уникально адресует страницу. */
    data class Url(val url: String) : SourceKey {
        override val text: String get() = "url:$url"
    }

    /** `url` нет: у печатного источника разные страницы одного тома — разные источники. */
    data class Reference(val reference: String, val title: String) : SourceKey {
        override val text: String get() = "ref:${reference.length}:$reference$title"
    }
}

/** Представитель группы дубликатов и её ключ. */
data class CatalogSource(
    val key: SourceKey,
    val source: Puzzle.Source,
)

/**
 * Единый дедуплицированный список источников сыгранных головоломок (§6.5).
 *
 * Чистая функция: результат не зависит от порядка входа — у представителя группы и у
 * итогового списка полный детерминированный порядок.
 */
object SourceCatalog {

    fun dedupeAndSort(items: List<PuzzleSources>, titleOrder: Comparator<String>): List<CatalogSource> {
        val occurrences = items.flatMap { ps -> ps.sources.map { Occurrence(ps.puzzleId, it) } }
        // Представитель: самая поздняя дата обращения (ISO-даты сравнимы строками), затем
        // меньшее название в русской коллации, затем меньшие puzzleId и sourceId — пара
        // (puzzleId, sourceId) уникальна, поэтому порядок полный.
        val representativeOrder = compareByDescending<Occurrence> { it.source.accessedAt }
            .thenBy(titleOrder) { it.source.title }
            .thenBy { it.puzzleId }
            .thenBy { it.source.sourceId }
        val representatives = occurrences
            .groupBy { keyOf(it.source) }
            .map { (key, group) -> CatalogSource(key, group.minWith(representativeOrder).source) }
        // Алфавит по названию, при равенстве — строковая форма ключа посимвольно: разные
        // ключи дают разные строки, поэтому порядок полный.
        return representatives.sortedWith(
            compareBy(titleOrder) { it: CatalogSource -> it.source.title }.thenBy { it.key.text },
        )
    }

    fun keyOf(source: Puzzle.Source): SourceKey =
        source.url?.let { SourceKey.Url(it) } ?: SourceKey.Reference(source.reference.orEmpty(), source.title)

    private data class Occurrence(val puzzleId: String, val source: Puzzle.Source)
}

/**
 * Порядок названий: русская коллация с силой `SECONDARY` — различия только в регистре
 * (третичные) не учитываются. `java.text` есть и на JVM, и на Android,
 * поэтому `domain` остаётся без Android-импортов. `Collator` не потокобезопасен —
 * экземпляр создаётся на каждый порядок.
 *
 * Коллации JVM и Android (ICU) могут расходиться: например, взаимный порядок кириллицы и
 * латиницы у них разный — ICU для `ru` ставит кириллицу первой. На любой
 * платформе порядок полный: равные названия разводит ключ (ITERATION_5_DESIGN.md, §11.1).
 */
fun russianTitleOrder(): Comparator<String> {
    val collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}
