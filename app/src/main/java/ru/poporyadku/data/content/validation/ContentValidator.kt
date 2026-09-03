package ru.poporyadku.data.content.validation

import javax.inject.Inject
import ru.poporyadku.data.content.ContentPaths
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.dto.PuzzleDto
import ru.poporyadku.data.db.mapper.ContentTokens
import ru.poporyadku.domain.content.ContentInstallException

/** Ровно четыре карточки в головоломке (`core.model.isPlayable`). */
private const val CARDS_PER_PUZZLE = 4

/**
 * Защитный набор рантайма (ITERATION_4_DESIGN.md, §7.2, §8.5).
 *
 * Работает ТОЛЬКО над успешно построенным [ParsedPack]: всё, что мешает построить DTO,
 * отсекается раньше — `M05` для неразбираемого JSON и `R01` для несобираемого
 * документа, — и до валидатора управление не доходит. Поэтому `D01` проверяет
 * ЗНАЧЕНИЯ, а не наличие полей, а кодов `R02` и `R11` он не возвращает никогда:
 * отсутствующий `correctOrder` уже назван `R01`, а пустой `sourceIds` защитным
 * инвариантом не является — приложение не читает источники при выдаче головоломки,
 * и его ловит CI (§7.3).
 *
 * Двадцать одно правило здесь НЕ дублируется. Граница проведена по одному критерию:
 * защитный набор — то, нарушение чего ломает приложение или портит базу; остальное —
 * то, нарушение чего делает контент неверным. Порядок, разрывы, качество источников
 * и компоновка наборов остаются за CLI.
 */
class ContentValidator @Inject constructor() {

    /**
     * ВСЕ нарушения защитного набора, а не первое: «нарушений 7, первое — R19» отличает
     * опечатку от подложенного пакета, а parity-тесты сравнивают полные списки.
     * Порядок — тот же, что у диагностик CLI (§7.6).
     */
    fun findings(pack: ParsedPack): List<ContentViolation> {
        val puzzlesFile = fileName(pack, ContentPaths.PUZZLES_PREFIX)
        val setsFile = fileName(pack, ContentPaths.DAILY_SETS_PREFIX)
        val found = mutableListOf<ContentViolation>()

        checkManifestCounts(pack, found)
        checkPuzzles(pack, puzzlesFile, found)
        checkSets(pack, setsFile, found)

        return found.sortedAsDiagnostics()
    }

    /**
     * @throws ContentInstallException.BundleInvalid при непустом [findings]: код ПЕРВОГО
     * нарушения, общее число нарушений и полезная деталь первого.
     */
    fun validate(pack: ParsedPack) {
        val violations = findings(pack)
        if (violations.isEmpty()) return
        val first = violations.first()
        throw ContentInstallException.BundleInvalid(
            code = first.code,
            violations = violations.size,
            detail = "${first.file}#${first.pointer} — ${first.message}",
        )
    }

    // ---------- R21 ----------

    private fun checkManifestCounts(pack: ParsedPack, found: MutableList<ContentViolation>) {
        val manifest = pack.manifest
        if (manifest.setCount != pack.sets.size) {
            found += ContentViolation(
                code = ContentViolation.R21_MANIFEST_COUNTS,
                file = ContentPaths.MANIFEST,
                pointer = "/setCount",
                message = "setCount ${manifest.setCount} не совпадает " +
                    "с фактическим числом наборов ${pack.sets.size}",
            )
        }
        if (manifest.puzzleCount != pack.puzzles.size) {
            found += ContentViolation(
                code = ContentViolation.R21_MANIFEST_COUNTS,
                file = ContentPaths.MANIFEST,
                pointer = "/puzzleCount",
                message = "puzzleCount ${manifest.puzzleCount} не совпадает " +
                    "с фактическим числом головоломок ${pack.puzzles.size}",
            )
        }
    }

    // ---------- R05, R18C, D01, D02 ----------

    private fun checkPuzzles(
        pack: ParsedPack,
        file: String,
        found: MutableList<ContentViolation>,
    ) {
        val seen = mutableSetOf<String>()
        pack.puzzles.forEachIndexed { index, puzzle ->
            if (!seen.add(puzzle.puzzleId)) {
                found += ContentViolation(
                    code = ContentViolation.R05_DUPLICATE_PUZZLE_ID,
                    file = file,
                    pointer = "/puzzles/$index/puzzleId",
                    message = "идентификатор '${puzzle.puzzleId}' встречается в файле не впервые",
                )
            }
            checkRetiredIn(pack, puzzle, index, file, found)
            checkPuzzleForm(puzzle, index, file, found)
            checkEnumTokens(puzzle, index, file, found)
        }
    }

    /**
     * `R18C`. «Отозвана в будущей версии» — не отзыв, а опечатка, и она опасна: такая
     * головоломка считалась бы отозванной и открывала бы дорогу подмене состава
     * назначенного набора (§10.3, правило замены).
     */
    private fun checkRetiredIn(
        pack: ParsedPack,
        puzzle: PuzzleDto,
        index: Int,
        file: String,
        found: MutableList<ContentViolation>,
    ) {
        val retiredIn = puzzle.retiredIn ?: return
        if (retiredIn > pack.manifest.contentVersion) {
            found += ContentViolation(
                code = ContentViolation.R18C_RETIRED_IN_FUTURE,
                file = file,
                pointer = "/puzzles/$index/retiredIn",
                message = "retiredIn $retiredIn больше contentVersion " +
                    "${pack.manifest.contentVersion} у ${puzzle.puzzleId}",
            )
        }
    }

    /** `D01` — то же, что `core.model.isPlayable()`, но над asset-DTO и по значениям. */
    private fun checkPuzzleForm(
        puzzle: PuzzleDto,
        index: Int,
        file: String,
        found: MutableList<ContentViolation>,
    ) {
        val pointer = "/puzzles/$index"
        val cardIds = puzzle.cards.map { it.cardId }
        val problem = when {
            puzzle.cards.size != CARDS_PER_PUZZLE ->
                "карточек ${puzzle.cards.size} вместо $CARDS_PER_PUZZLE"

            cardIds.toSet().size != cardIds.size -> "cardId не уникальны: $cardIds"
            puzzle.correctOrder.size != cardIds.size ||
                puzzle.correctOrder.toSet() != cardIds.toSet() ->
                "correctOrder ${puzzle.correctOrder} не является перестановкой $cardIds"

            puzzle.prompt.isBlank() -> "prompt пуст"
            puzzle.explanation.isBlank() -> "explanation пусто"
            puzzle.directionLabel.isBlank() -> "directionLabel пуст"
            else -> null
        }
        if (problem != null) {
            found += ContentViolation(
                code = ContentViolation.D01_PUZZLE_FORM,
                file = file,
                pointer = pointer,
                message = "${puzzle.puzzleId}: $problem",
            )
        }
    }

    /** `D02` — токен обязан разбираться в доменное перечисление (**I4-D18**). */
    private fun checkEnumTokens(
        puzzle: PuzzleDto,
        index: Int,
        file: String,
        found: MutableList<ContentViolation>,
    ) {
        if (ContentTokens.categoryOrNull(puzzle.category) == null) {
            found += ContentViolation(
                code = ContentViolation.D02_ENUM_UNKNOWN,
                file = file,
                pointer = "/puzzles/$index/category",
                message = "неизвестная категория '${puzzle.category}' у ${puzzle.puzzleId}",
            )
        }
        if (ContentTokens.sortDirectionOrNull(puzzle.sortDirection) == null) {
            found += ContentViolation(
                code = ContentViolation.D02_ENUM_UNKNOWN,
                file = file,
                pointer = "/puzzles/$index/sortDirection",
                message = "неизвестное направление '${puzzle.sortDirection}' у ${puzzle.puzzleId}",
            )
        }
    }

    // ---------- R18, R18A, R18B, R19 ----------

    private fun checkSets(pack: ParsedPack, file: String, found: MutableList<ContentViolation>) {
        checkSetIndexes(pack, file, found)

        val usage = mutableMapOf<String, Int>()
        pack.sets.forEachIndexed { index, set ->
            set.puzzleIds.forEachIndexed { slot, puzzleId ->
                val pointer = "/sets/$index/puzzleIds/$slot"
                val puzzle = pack.puzzleById(puzzleId)
                when {
                    puzzle == null -> found += ContentViolation(
                        code = ContentViolation.R18_SET_REFERENCE_MISSING,
                        file = file,
                        pointer = pointer,
                        message = "набор ${set.setIndex} ссылается на отсутствующую '$puzzleId'",
                    )

                    puzzle.retiredIn != null -> found += ContentViolation(
                        code = ContentViolation.R18A_SET_REFERENCE_RETIRED,
                        file = file,
                        pointer = pointer,
                        message = "набор ${set.setIndex} ссылается на отозванную '$puzzleId'",
                    )
                }
                val used = usage.getOrDefault(puzzleId, 0) + 1
                usage[puzzleId] = used
                // Ровно одна находка на переиспользованный идентификатор — на втором
                // вхождении: третье и четвёртое ничего нового не сообщают.
                if (used == 2) {
                    found += ContentViolation(
                        code = ContentViolation.R18B_PUZZLE_REUSED,
                        file = file,
                        pointer = pointer,
                        message = "головоломка '$puzzleId' используется в пакете не впервые",
                    )
                }
            }
        }
    }

    /**
     * `R19` — множество `setIndex` обязано быть ровно `0..N−1`, где `N` — ФАКТИЧЕСКОЕ
     * число наборов. Именно фактическое: расхождение с `manifest.setCount` — вопрос
     * `R21`, и брать оттуда границу значило бы отвечать одним кодом на два вопроса.
     */
    private fun checkSetIndexes(
        pack: ParsedPack,
        file: String,
        found: MutableList<ContentViolation>,
    ) {
        val actual = pack.sets.map { it.setIndex }
        val expected = pack.sets.indices.toSet()
        if (actual.toSet() != expected || actual.size != expected.size) {
            found += ContentViolation(
                code = ContentViolation.R19_SET_INDEX_SEQUENCE,
                file = file,
                pointer = "/sets",
                message = "последовательность setIndex $actual " +
                    "не равна 0..${pack.sets.size - 1}",
            )
        }
    }

    private fun fileName(pack: ParsedPack, prefix: String): String =
        pack.manifest.files.firstOrNull { it.path.startsWith(prefix) }?.path
            ?: "$prefix???.json"

    companion object {

        /**
         * Коды, которые объявляет валидатор (таблица §7.2). Сверяется тестом `I4-P5`:
         * с набором читателя не пересекается, и вместе они дают весь защитный набор.
         *
         * `R02` и `R11` здесь отсутствуют намеренно и навсегда (§7.3).
         */
        val OWNED_CODES: Set<String> = setOf(
            ContentViolation.R05_DUPLICATE_PUZZLE_ID,
            ContentViolation.R18_SET_REFERENCE_MISSING,
            ContentViolation.R18A_SET_REFERENCE_RETIRED,
            ContentViolation.R18B_PUZZLE_REUSED,
            ContentViolation.R18C_RETIRED_IN_FUTURE,
            ContentViolation.R19_SET_INDEX_SEQUENCE,
            ContentViolation.R21_MANIFEST_COUNTS,
            ContentViolation.D01_PUZZLE_FORM,
            ContentViolation.D02_ENUM_UNKNOWN,
        )
    }
}
