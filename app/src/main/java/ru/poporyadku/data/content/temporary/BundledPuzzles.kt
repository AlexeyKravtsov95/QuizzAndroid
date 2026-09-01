package ru.poporyadku.data.content.temporary

import ru.poporyadku.core.model.Card
import ru.poporyadku.core.model.Category
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.SortDirection

/**
 * ВРЕМЕННЫЙ контент итерации 3 (ITERATION_3_DESIGN.md, I3-D1, I3-D3): только литералы.
 *
 * Три головоломки трёх РАЗНЫХ категорий и три набора — три ротации одних и тех же трёх
 * головоломок. Три, а не один: дни 1–3 играются, день 4 честно даёт `ContentExhausted`,
 * и последовательную выдачу можно проверить руками на устройстве.
 *
 * Повторное использование головоломки в разных наборах запрещено CONTENT_MODEL.md §6
 * ДЛЯ НАСТОЯЩЕГО ПАКЕТА. Здесь это свойство временной фикстуры: правило проверяет
 * валидатор итерации 4 по assets, а не рантайм. Образцом ротация не является.
 *
 * Объект не покидает `data/content/temporary`: ни domain, ни ui его не импортируют,
 * поэтому в итерации 4 каталог удаляется целиком (I3-D2).
 */
internal object BundledPuzzles {

    private const val PACK_ID = ContentPack.CORE_RU
    private const val CONTENT_VERSION = 1

    const val GEOGRAPHY_PUZZLE_ID = "tmp-geo-vysota-001"
    const val HISTORY_PUZZLE_ID = "tmp-hist-izobreteniya-002"
    const val SCIENCE_PUZZLE_ID = "tmp-sci-otkrytiya-003"

    /** Высота вершин: ASCENDING, correctOrder = c2, c1, c3, c4. */
    private val geography = Puzzle(
        puzzleId = GEOGRAPHY_PUZZLE_ID,
        packId = PACK_ID,
        category = Category.GEOGRAPHY,
        prompt = "Расположите вершины от самой низкой к самой высокой",
        sortKey = "height",
        sortDirection = SortDirection.ASCENDING,
        directionLabel = "Сверху — самая низкая",
        cards = listOf(
            Card(
                cardId = "c1",
                title = "Эльбрус",
                subtitle = "Кавказ, Россия",
                sortValue = 5642.0,
                displayValue = "5642 м",
                note = null,
                sourceIds = listOf("s1", "s2"),
                disputed = false,
            ),
            Card(
                cardId = "c2",
                title = "Монблан",
                subtitle = "Альпы, Франция и Италия",
                sortValue = 4808.0,
                displayValue = "4808 м",
                note = "Высота снежной вершины уточняется измерениями",
                sourceIds = listOf("s1", "s2"),
                disputed = true,
            ),
            Card(
                cardId = "c3",
                title = "Килиманджаро",
                subtitle = "Танзания",
                sortValue = 5895.0,
                displayValue = "5895 м",
                note = null,
                sourceIds = listOf("s1"),
                disputed = false,
            ),
            Card(
                cardId = "c4",
                title = "Аконкагуа",
                subtitle = "Анды, Аргентина",
                sortValue = 6961.0,
                displayValue = "6961 м",
                note = null,
                sourceIds = listOf("s2"),
                disputed = false,
            ),
        ),
        correctOrder = listOf("c2", "c1", "c3", "c4"),
        explanation = "Монблан — высшая точка Альп, но уступает Эльбрусу. " +
            "Килиманджаро крупнее обоих, а рекорд среди четырёх держит Аконкагуа: " +
            "это высочайшая вершина за пределами Азии.",
        sources = listOf(
            Puzzle.Source(
                sourceId = "s1",
                title = "Encyclopaedia Britannica, статьи о горных вершинах",
                kind = "encyclopedia",
                url = "https://www.britannica.com/",
                reference = null,
                accessedAt = "2026-08-20",
                note = null,
            ),
            Puzzle.Source(
                sourceId = "s2",
                title = "Большая российская энциклопедия",
                kind = "encyclopedia",
                url = null,
                reference = "БРЭ. Т. 35. М., 2017",
                accessedAt = "2026-08-20",
                note = null,
            ),
        ),
        difficulty = 2,
        retiredIn = null,
        contentVersion = CONTENT_VERSION,
    )

    /** Годы изобретений: ASCENDING, correctOrder = c2, c4, c1, c3. */
    private val history = Puzzle(
        puzzleId = HISTORY_PUZZLE_ID,
        packId = PACK_ID,
        category = Category.HISTORY,
        prompt = "Расположите изобретения от самого раннего к самому позднему",
        sortKey = "year",
        sortDirection = SortDirection.ASCENDING,
        directionLabel = "Сверху — самое раннее",
        cards = listOf(
            Card(
                cardId = "c1",
                title = "Телефон Белла",
                subtitle = "США",
                sortValue = 1876.0,
                displayValue = "1876 год",
                note = null,
                sourceIds = listOf("s1"),
                disputed = false,
            ),
            Card(
                cardId = "c2",
                title = "Печатный станок Гутенберга",
                subtitle = "Майнц, Германия",
                sortValue = 1450.0,
                displayValue = "около 1450 года",
                note = "Точный год начала работы станка неизвестен",
                sourceIds = listOf("s1", "s2"),
                disputed = true,
            ),
            Card(
                cardId = "c3",
                title = "Первый полёт братьев Райт",
                subtitle = "Китти-Хок, США",
                sortValue = 1903.0,
                displayValue = "1903 год",
                note = null,
                sourceIds = listOf("s2"),
                disputed = false,
            ),
            Card(
                cardId = "c4",
                title = "Паровая машина Уатта",
                subtitle = "Великобритания",
                sortValue = 1769.0,
                displayValue = "1769 год",
                note = null,
                sourceIds = listOf("s2"),
                disputed = false,
            ),
        ),
        correctOrder = listOf("c2", "c4", "c1", "c3"),
        explanation = "Книгопечатание опередило остальные изобретения на три столетия. " +
            "Патент Уатта на паровую машину отделяет эпоху ремесла от промышленной, " +
            "телефон появился уже в индустриальном XIX веке, а полёт братьев Райт " +
            "открыл XX век.",
        sources = listOf(
            Puzzle.Source(
                sourceId = "s1",
                title = "Encyclopaedia Britannica, статьи по истории техники",
                kind = "encyclopedia",
                url = "https://www.britannica.com/",
                reference = null,
                accessedAt = "2026-08-20",
                note = null,
            ),
            Puzzle.Source(
                sourceId = "s2",
                title = "Большая российская энциклопедия",
                kind = "encyclopedia",
                url = null,
                reference = "БРЭ. М., 2004–2017",
                accessedAt = "2026-08-20",
                note = null,
            ),
        ),
        difficulty = 2,
        retiredIn = null,
        contentVersion = CONTENT_VERSION,
    )

    /** Годы открытий: DESCENDING, correctOrder = c3, c2, c4, c1. */
    private val science = Puzzle(
        puzzleId = SCIENCE_PUZZLE_ID,
        packId = PACK_ID,
        category = Category.SCIENCE,
        prompt = "Расположите научные открытия от самого позднего к самому раннему",
        sortKey = "year",
        sortDirection = SortDirection.DESCENDING,
        directionLabel = "Сверху — самое позднее",
        cards = listOf(
            Card(
                cardId = "c1",
                title = "Рентгеновские лучи",
                subtitle = "Вильгельм Рёнтген",
                sortValue = 1895.0,
                displayValue = "1895 год",
                note = null,
                sourceIds = listOf("s1"),
                disputed = false,
            ),
            Card(
                cardId = "c2",
                title = "Пенициллин",
                subtitle = "Александр Флеминг",
                sortValue = 1928.0,
                displayValue = "1928 год",
                note = null,
                sourceIds = listOf("s1", "s2"),
                disputed = false,
            ),
            Card(
                cardId = "c3",
                title = "Структура ДНК",
                subtitle = "Уотсон, Крик и Франклин",
                sortValue = 1953.0,
                displayValue = "1953 год",
                note = null,
                sourceIds = listOf("s2"),
                disputed = false,
            ),
            Card(
                cardId = "c4",
                title = "Электрон",
                subtitle = "Джозеф Джон Томсон",
                sortValue = 1897.0,
                displayValue = "1897 год",
                note = null,
                sourceIds = listOf("s1"),
                disputed = false,
            ),
        ),
        correctOrder = listOf("c3", "c2", "c4", "c1"),
        explanation = "Рентгеновские лучи и электрон открыты почти одновременно, " +
            "на рубеже XIX и XX веков, причём лучи — двумя годами раньше. " +
            "Пенициллин относится к межвоенному времени, а структура ДНК расшифрована " +
            "позже всех, уже в 1950-е.",
        sources = listOf(
            Puzzle.Source(
                sourceId = "s1",
                title = "Nobel Prize, официальные биографии лауреатов",
                kind = "official",
                url = "https://www.nobelprize.org/",
                reference = null,
                accessedAt = "2026-08-20",
                note = null,
            ),
            Puzzle.Source(
                sourceId = "s2",
                title = "Большая российская энциклопедия",
                kind = "encyclopedia",
                url = null,
                reference = "БРЭ. М., 2004–2017",
                accessedAt = "2026-08-20",
                note = null,
            ),
        ),
        difficulty = 3,
        retiredIn = null,
        contentVersion = CONTENT_VERSION,
    )

    /** Ровно три головоломки, три разные категории. */
    val puzzles: List<Puzzle> = listOf(geography, history, science)

    /**
     * Ровно три набора, setIndex = 0, 1, 2 без дыр — таблица ротации I3-D3.
     *
     * | setIndex | слот 0 | слот 1 | слот 2 |
     * | 0 | geo  | hist | sci  |
     * | 1 | hist | sci  | geo  |
     * | 2 | sci  | geo  | hist |
     */
    val sets: List<DailySet> = listOf(
        DailySet(
            packId = PACK_ID,
            setIndex = 0,
            puzzleId1 = GEOGRAPHY_PUZZLE_ID,
            puzzleId2 = HISTORY_PUZZLE_ID,
            puzzleId3 = SCIENCE_PUZZLE_ID,
        ),
        DailySet(
            packId = PACK_ID,
            setIndex = 1,
            puzzleId1 = HISTORY_PUZZLE_ID,
            puzzleId2 = SCIENCE_PUZZLE_ID,
            puzzleId3 = GEOGRAPHY_PUZZLE_ID,
        ),
        DailySet(
            packId = PACK_ID,
            setIndex = 2,
            puzzleId1 = SCIENCE_PUZZLE_ID,
            puzzleId2 = GEOGRAPHY_PUZZLE_ID,
            puzzleId3 = HISTORY_PUZZLE_ID,
        ),
    )
}
