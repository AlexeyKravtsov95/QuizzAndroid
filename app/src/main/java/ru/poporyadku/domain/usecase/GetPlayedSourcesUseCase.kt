package ru.poporyadku.domain.usecase

import javax.inject.Inject
import ru.poporyadku.domain.repository.PlayedSourcesRepository

/**
 * Источники сыгранных головоломок одним списком (ITERATION_5_DESIGN.md, §3.11, §5.3,
 * I5-D17): дубликаты слиты, порядок — русская коллация по названию.
 */
class GetPlayedSourcesUseCase @Inject constructor(
    private val repository: PlayedSourcesRepository,
) {
    suspend operator fun invoke(): List<CatalogSource> =
        SourceCatalog.dedupeAndSort(repository.getPlayedPuzzleSources(), russianTitleOrder())
}
