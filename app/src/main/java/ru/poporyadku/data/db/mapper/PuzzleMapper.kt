package ru.poporyadku.data.db.mapper

import kotlinx.serialization.json.Json
import ru.poporyadku.core.model.Card
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.data.db.entity.PuzzleEntity
import ru.poporyadku.data.db.json.StoredCard
import ru.poporyadku.data.db.json.StoredSource

/**
 * Строка `puzzles` → доменная [Puzzle] (ITERATION_4_DESIGN.md, §9.1, §9.3).
 *
 * @param json ДОЛЖЕН быть `@StorageJson` — строгий: то, что лежит в `cards_json`
 * и `sources_json`, писали мы сами, и неизвестный ключ там означает повреждение,
 * а не совместимость.
 *
 * Неизвестный токен перечисления даёт исключение с `puzzleId` в сообщении (`ContentTokens`):
 * повреждение собственных данных — дефект, а не состояние экрана.
 */
fun PuzzleEntity.toDomain(json: Json): Puzzle = Puzzle(
    puzzleId = puzzleId,
    packId = packId,
    category = ContentTokens.categoryOf(category, puzzleId),
    prompt = prompt,
    sortKey = sortKey,
    sortDirection = ContentTokens.sortDirectionOf(sortDirection, puzzleId),
    directionLabel = directionLabel,
    cards = json.decodeFromString<List<StoredCard>>(cardsJson).map { it.toDomain() },
    // Пустой строки здесь не бывает (ровно четыре карточки), но "".split(",") дал бы
    // список из одной пустой позиции — форма проверки та же, что у submittedOrder.
    correctOrder = if (correctOrder.isEmpty()) emptyList() else correctOrder.split(","),
    explanation = explanation,
    sources = json.decodeFromString<List<StoredSource>>(sourcesJson).map { it.toDomain() },
    difficulty = difficulty,
    retiredIn = retiredIn,
    contentVersion = contentVersion,
)

private fun StoredCard.toDomain(): Card = Card(
    cardId = cardId,
    title = title,
    subtitle = subtitle,
    sortValue = sortValue,
    displayValue = displayValue,
    note = note,
    sourceIds = sourceIds,
    disputed = disputed,
)

private fun StoredSource.toDomain(): Puzzle.Source = Puzzle.Source(
    sourceId = sourceId,
    title = title,
    kind = kind,
    url = url,
    reference = reference,
    accessedAt = accessedAt,
    note = note,
)
