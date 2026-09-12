package ru.poporyadku.domain.usecase

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.repository.PlayedSourcesRepository
import ru.poporyadku.domain.repository.PuzzleSources

/**
 * `SourceCatalog` — `I5-Q1` (ITERATION_5_DESIGN.md, §3.11, §6.5, I5-D17): дедупликация по
 * ключу, выбор представителя, полный детерминированный порядок, независимость от
 * порядка входа.
 *
 * Порядок проверяется внутри одной письменности: взаимный порядок кириллицы и латиницы у
 * коллации JVM и ICU Android разный (ICU для `ru` ставит кириллицу первой, JVM — латиницу;
 * на эмуляторе API 35 это видно на экране источников), и утверждение о нём проверяло бы
 * платформу, а не каталог (§11.1).
 */
class SourceCatalogTest {

    private val order = russianTitleOrder()

    /** `I5-Q1`. Дубли по URL сливаются в одну строку; ключ — URL как есть. */
    @Test
    fun `I5-Q1 duplicates by url collapse into one entry`() {
        val catalog = SourceCatalog.dedupeAndSort(
            listOf(
                PuzzleSources("p-001", listOf(source("s1", "Британника", url = URL_A))),
                PuzzleSources("p-002", listOf(source("s3", "Британника", url = URL_A))),
                PuzzleSources("p-003", listOf(source("s1", "Атлас", url = URL_B))),
            ),
            order,
        )

        assertEquals(listOf(SourceKey.Url(URL_B), SourceKey.Url(URL_A)), catalog.map { it.key })
        // URL не нормализуется: другой регистр или слеш — другой ключ.
        val distinct = SourceCatalog.dedupeAndSort(
            listOf(PuzzleSources("p-001", listOf(source("s1", "А", url = URL_A), source("s2", "А", url = "$URL_A/")))),
            order,
        )
        assertEquals(2, distinct.size)
    }

    /**
     * `I5-Q1`. Представитель дубликатов: наибольший `accessedAt`, затем меньшее название в
     * русской коллации, затем меньший `puzzleId`, затем меньший `sourceId`.
     */
    @Test
    fun `I5-Q1 representative follows accessedAt then title then puzzle id then source id`() {
        // accessedAt решает первым.
        assertEquals(
            "2026-08-21",
            representativeOf(
                PuzzleSources("p-001", listOf(source("s1", "Альфа", url = URL_A, accessedAt = "2026-08-20"))),
                PuzzleSources("p-002", listOf(source("s1", "Бета", url = URL_A, accessedAt = "2026-08-21"))),
            ).accessedAt,
        )
        // Равные даты — меньшее название («Альфа» < «Бета»).
        assertEquals(
            "Альфа",
            representativeOf(
                PuzzleSources("p-001", listOf(source("s1", "Бета", url = URL_A))),
                PuzzleSources("p-002", listOf(source("s1", "Альфа", url = URL_A))),
            ).title,
        )
        // Равные дата и название — меньший puzzleId (виден по kind: разный у кандидатов).
        assertEquals(
            "official",
            representativeOf(
                PuzzleSources("p-002", listOf(source("s1", "Альфа", url = URL_A, kind = "other"))),
                PuzzleSources("p-001", listOf(source("s9", "Альфа", url = URL_A, kind = "official"))),
            ).kind,
        )
        // Та же головоломка — меньший sourceId.
        assertEquals(
            "s1",
            representativeOf(
                PuzzleSources("p-001", listOf(source("s2", "Альфа", url = URL_A), source("s1", "Альфа", url = URL_A))),
            ).sourceId,
        )
    }

    /**
     * `I5-Q1`. `referenceOnly` различаются ПАРОЙ `(reference, title)`: разные страницы одного
     * тома — разные источники; тот же том с тем же названием — один. Склейки нет: пары,
     * которые совпали бы в склеенной строке, остаются разными.
     */
    @Test
    fun `I5-Q1 reference only sources are keyed by the reference and title pair`() {
        val catalog = SourceCatalog.dedupeAndSort(
            listOf(
                PuzzleSources(
                    "p-001",
                    listOf(
                        source("s1", "БРЭ", reference = "Т. 35, с. 10"),
                        source("s2", "БРЭ", reference = "Т. 35, с. 11"),
                    ),
                ),
                PuzzleSources("p-002", listOf(source("s1", "БРЭ", reference = "Т. 35, с. 10"))),
                PuzzleSources("p-003", listOf(source("s1", "Другое", reference = "Т. 35, с. 10"))),
                // Склеенные «a, title=b» + «c» и «a» + «b, title=c» совпали бы в toString().
                PuzzleSources(
                    "p-004",
                    listOf(source("s1", "c", reference = "a, title=b"), source("s2", "b, title=c", reference = "a")),
                ),
            ),
            order,
        )

        val keys = catalog.map { it.key }
        assertEquals(
            setOf(
                SourceKey.Reference("Т. 35, с. 10", "БРЭ"),
                SourceKey.Reference("Т. 35, с. 11", "БРЭ"),
                SourceKey.Reference("Т. 35, с. 10", "Другое"),
                SourceKey.Reference("a, title=b", "c"),
                SourceKey.Reference("a", "b, title=c"),
            ),
            keys.toSet(),
        )
        assertEquals("одна строка на ключ", 5, keys.size)
        assertEquals("строковые формы ключей различны", keys.size, keys.map { it.text }.toSet().size)
    }

    /**
     * `I5-Q1`. Порядок — русская коллация по названию без учёта регистра, при равенстве —
     * строковая форма ключа посимвольно; порядок полный.
     */
    @Test
    fun `I5-Q1 order is russian collation by title then the key`() {
        val catalog = SourceCatalog.dedupeAndSort(
            listOf(
                PuzzleSources(
                    "p-001",
                    listOf(
                        source("s1", "яблоко", url = "https://e.test/1"),
                        source("s2", "Арбуз", url = "https://e.test/2"),
                        source("s3", "ёлка", url = "https://e.test/3"),
                        source("s4", "Банан", url = "https://e.test/4"),
                        source("s6", "банан", url = "https://e.test/0"),
                        source("s7", "Банан", reference = "Т. 1"),
                    ),
                ),
            ),
            order,
        )

        assertEquals(
            listOf(
                "Арбуз",
                // Три «банана» равны без учёта регистра — порядок по ключу: ref: < url:…/0 < url:…/4.
                "Банан",
                "банан",
                "Банан",
                "ёлка",
                "яблоко",
            ),
            catalog.map { it.source.title },
        )
        assertEquals(
            listOf(SourceKey.Reference("Т. 1", "Банан"), SourceKey.Url("https://e.test/0"), SourceKey.Url("https://e.test/4")),
            catalog.subList(1, 4).map { it.key },
        )
    }

    /** `I5-Q1`. Перемешанный вход — и головоломки, и источники внутри — даёт тот же выход. */
    @Test
    fun `I5-Q1 shuffled input gives the same output`() {
        val input = (1..12).map { puzzle ->
            PuzzleSources(
                puzzleId = "p-%03d".format(puzzle),
                sources = (1..4).map { index ->
                    val shared = (puzzle + index) % 5
                    if (index == 4) {
                        source("s$index", "Том ${shared % 2}", reference = "Т. $shared", accessedAt = "2026-08-${10 + puzzle}")
                    } else {
                        source(
                            "s$index",
                            listOf("Альфа", "бета", "Бета", "Гамма", "дельта")[shared],
                            url = "https://e.test/$shared",
                            accessedAt = "2026-08-${10 + (puzzle * index) % 7}",
                            kind = if (puzzle % 2 == 0) "official" else "other",
                        )
                    }
                },
            )
        }
        val expected = SourceCatalog.dedupeAndSort(input, order)

        val random = Random(SEED)
        repeat(SHUFFLES) {
            val shuffled = input.shuffled(random).map { it.copy(sources = it.sources.shuffled(random)) }
            assertEquals(expected, SourceCatalog.dedupeAndSort(shuffled, russianTitleOrder()))
        }
        assertNotEquals("вход действительно содержит дубли", input.sumOf { it.sources.size }, expected.size)
    }

    /** Use case — тот же каталог поверх репозитория, с русской коллацией. */
    @Test
    fun `use case dedupes and sorts what the repository returns`() = runBlocking {
        val repository = object : PlayedSourcesRepository {
            override suspend fun getPlayedPuzzleSources() = listOf(
                PuzzleSources("p-001", listOf(source("s1", "Бета", url = URL_A), source("s2", "Альфа", url = URL_B))),
                PuzzleSources("p-002", listOf(source("s1", "Бета", url = URL_A))),
            )
        }

        val catalog = GetPlayedSourcesUseCase(repository)()

        assertEquals(listOf("Альфа", "Бета"), catalog.map { it.source.title })
    }

    // --- Инфраструктура -----------------------------------------------------------------

    private fun representativeOf(vararg items: PuzzleSources): Puzzle.Source {
        val catalog = SourceCatalog.dedupeAndSort(items.toList(), order)
        assertEquals("все кандидаты — один ключ", 1, catalog.size)
        return catalog.single().source
    }

    private fun source(
        sourceId: String,
        title: String,
        url: String? = null,
        reference: String? = null,
        accessedAt: String = "2026-08-20",
        kind: String = "encyclopedia",
    ) = Puzzle.Source(
        sourceId = sourceId,
        title = title,
        kind = kind,
        url = url,
        reference = reference,
        accessedAt = accessedAt,
        note = null,
    )

    private companion object {
        const val URL_A = "https://www.britannica.com/place/Mont-Blanc"
        const val URL_B = "https://example.test/atlas"
        const val SEED = 20260911L
        const val SHUFFLES = 50
    }
}
