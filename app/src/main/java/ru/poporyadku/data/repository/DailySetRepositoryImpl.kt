package ru.poporyadku.data.repository

import javax.inject.Inject
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.mapper.toDomain
import ru.poporyadku.domain.repository.DailySetRepository

class DailySetRepositoryImpl @Inject constructor(
    private val dao: DailySetDao,
) : DailySetRepository {

    override suspend fun getSet(packId: String, setIndex: Int): DailySet? =
        dao.getSet(packId, setIndex)?.toDomain()
}
