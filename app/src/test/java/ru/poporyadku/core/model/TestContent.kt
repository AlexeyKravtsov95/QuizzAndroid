package ru.poporyadku.core.model

import ru.poporyadku.domain.repository.PuzzleRepository

/**
 * Независимая фикстура контента для тестов домена (ITERATION_4_DESIGN.md, **I4-D22**).
 *
 * Тесты use case проверяют ПРАВИЛА, а не конкретный контент, и опираться в них на
 * временный источник итерации 3 значило бы привязать их срок жизни к сроку жизни
 * фикстуры: `data/content/temporary` удаляется целиком в PR 4D, и каждый такой тест
 * пришлось бы переписывать заодно с переключением DI. Здесь — те же три слота,
 * но собственные значения, за которые не отвечает никакой продуктовый файл.
 *
 * Литералы временного источника вправе использовать только его собственный тест
 * и тест временного установщика — им они и являются предметом проверки.
 */
internal object TestContent {

    const val PACK_ID = ContentPack.CORE_RU

    const val FIRST_PUZZLE_ID = "fix-geo-obrazec-001"
    const val SECOND_PUZZLE_ID = "fix-hist-obrazec-002"
    const val THIRD_PUZZLE_ID = "fix-sci-obrazec-003"

    val cardIds: List<String> = listOf("c1", "c2", "c3", "c4")

    /**
     * Девять головоломок и три набора — столько же, сколько было у временной фикстуры:
     * тесты выдачи проверяют «набор на каждый день, а на четвёртый контент кончился»,
     * и одного набора им мало.
     *
     * `correctOrder` у соседних головоломок разный: тест, перепутавший слоты,
     * обязан упасть, а не совпасть случайно.
     */
    private val CATEGORIES = listOf(Category.GEOGRAPHY, Category.HISTORY, Category.SCIENCE)

    private val ORDERS = listOf(
        listOf("c2", "c1", "c3", "c4"),
        listOf("c1", "c2", "c3", "c4"),
        listOf("c4", "c3", "c2", "c1"),
    )

    val puzzles: List<Puzzle> = (0 until 9).map { index ->
        puzzle(
            puzzleId = when (index) {
                0 -> FIRST_PUZZLE_ID
                1 -> SECOND_PUZZLE_ID
                2 -> THIRD_PUZZLE_ID
                else -> "fix-obrazec-%03d".format(index + 1)
            },
            category = CATEGORIES[index % CATEGORIES.size],
            correctOrder = ORDERS[index % ORDERS.size],
        )
    }

    /** Наборы 0, 1, 2 — по три головоломки подряд. */
    val sets: List<DailySet> = (0 until 3).map { setIndex ->
        DailySet(
            packId = PACK_ID,
            setIndex = setIndex,
            puzzleId1 = puzzles[setIndex * 3].puzzleId,
            puzzleId2 = puzzles[setIndex * 3 + 1].puzzleId,
            puzzleId3 = puzzles[setIndex * 3 + 2].puzzleId,
        )
    }

    /** Набор 0: три головоломки в порядке слотов 0, 1, 2. */
    val set: DailySet = sets.first()

    fun puzzleOf(puzzleId: String): Puzzle = puzzles.first { it.puzzleId == puzzleId }

    fun correctOrderOf(puzzleId: String): List<String> = puzzleOf(puzzleId).correctOrder

    fun puzzle(
        puzzleId: String,
        category: Category = Category.GEOGRAPHY,
        correctOrder: List<String> = cardIds,
        cards: List<Card> = cardIds.mapIndexed { index, cardId ->
            card(cardId, sortValue = 100.0 * (index + 1))
        },
        retiredIn: Int? = null,
        contentVersion: Int = 1,
    ): Puzzle = Puzzle(
        puzzleId = puzzleId,
        packId = PACK_ID,
        category = category,
        prompt = "Расположите образцы по признаку «год»",
        sortKey = "year",
        sortDirection = SortDirection.ASCENDING,
        directionLabel = "Сверху — наименьшее",
        cards = cards,
        correctOrder = correctOrder,
        explanation = "Синтетическая головоломка тестовой фикстуры: порядок следует " +
            "из значений признака, выписанных в поле sortValue каждой карточки.",
        sources = listOf(
            Puzzle.Source(
                sourceId = "s1",
                title = "Синтетический справочник тестовой фикстуры",
                kind = "encyclopedia",
                url = "https://example.invalid/fixture/$puzzleId",
                reference = null,
                accessedAt = "2026-08-20",
                note = null,
            )
        ),
        difficulty = 1,
        retiredIn = retiredIn,
        contentVersion = contentVersion,
    )

    fun card(
        cardId: String,
        title: String = "Образец $cardId",
        sortValue: Double,
    ): Card = Card(
        cardId = cardId,
        title = title,
        subtitle = null,
        sortValue = sortValue,
        displayValue = "${sortValue.toInt()} год",
        note = null,
        sourceIds = listOf("s1"),
        disputed = false,
    )
}

/** Репозиторий поверх списка литералов — ровно та роль, которую играл временный. */
internal class InMemoryPuzzleRepository(
    private val puzzles: List<Puzzle> = TestContent.puzzles,
) : PuzzleRepository {
    override suspend fun getPuzzle(puzzleId: String): Puzzle? =
        puzzles.firstOrNull { it.puzzleId == puzzleId }
}
