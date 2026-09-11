package ru.poporyadku.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import ru.poporyadku.domain.model.ArchiveDay
import ru.poporyadku.domain.repository.ArchiveRepository

/**
 * Постраничное чтение архива без Paging 3 (ITERATION_5_DESIGN.md, §3.4, §5.1, I5-D4;
 * ARCHITECTURE.md, ADR-017).
 *
 * Страница — [PAGE_SIZE] дней. [probe] читает на одну строку больше страницы: 51-я
 * строка — единственный признак продолжения, поэтому пустого запроса в конце списка
 * не бывает. Показываемые строки берутся не из разведки, а из наблюдаемого окна
 * `local_date >= нижняя граница`: окно ограничено датой, а не числом строк, и новый
 * день сверху ничего не выталкивает за его предел.
 */
class GetArchiveUseCase @Inject constructor(
    private val archive: ArchiveRepository,
) {

    /**
     * Разведка страницы строго раньше [before] (первой страницы — при `null`).
     * Граница продолжения — дата [PAGE_SIZE]-й строки: окно `>=` неё показывает ровно
     * прочитанные страницы, а следующая разведка начнётся строго раньше неё.
     */
    suspend fun probe(before: LocalDate?): ArchivePage {
        val rows = archive.probe(before, PAGE_SIZE + 1)
        val hasMore = rows.size > PAGE_SIZE
        return ArchivePage(
            nextLowerBound = if (hasMore) rows[PAGE_SIZE - 1].localDate else null,
            hasMore = hasMore,
        )
    }

    /** Окно показанных дней; `null` — продолжения нет, наблюдаются все дни. */
    fun observeWindow(lowerBound: LocalDate?): Flow<List<ArchiveDay>> =
        archive.observeWindow(lowerBound)

    companion object {
        const val PAGE_SIZE = 50
    }
}

/**
 * Итог разведки страницы.
 *
 * `nextLowerBound == null` означает «продолжения нет, наблюдать все дни»;
 * при `hasMore` граница есть всегда.
 */
data class ArchivePage(
    val nextLowerBound: LocalDate?,
    val hasMore: Boolean,
)
