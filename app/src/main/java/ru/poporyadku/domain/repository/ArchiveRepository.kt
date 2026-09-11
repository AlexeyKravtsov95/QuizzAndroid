package ru.poporyadku.domain.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import ru.poporyadku.domain.model.ArchiveDay

/**
 * Чтение архива сыгранных дней (ITERATION_5_DESIGN.md, §3.3, §3.4, §5.1, I5-D3, I5-D4).
 *
 * Только чтение. Keyset по `local_date DESC`: [probe] двигает нижнюю границу и
 * определяет, есть ли продолжение, а показываемые строки берутся из [observeWindow].
 * Отображение строки проверяет инварианты и бросает на испорченных данных.
 */
interface ArchiveRepository {

    /** До [limit] дней строго раньше [before] (или самые поздние при `null`), по убыванию даты. */
    suspend fun probe(before: LocalDate?, limit: Int): List<ArchiveDay>

    /** Все дни с датой `>=` [lowerBound] (или все дни при `null`), по убыванию даты. */
    fun observeWindow(lowerBound: LocalDate?): Flow<List<ArchiveDay>>
}
