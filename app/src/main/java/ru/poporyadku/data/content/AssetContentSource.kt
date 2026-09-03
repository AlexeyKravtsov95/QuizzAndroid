package ru.poporyadku.data.content

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Чтение пакета из `assets/content/` (ITERATION_4_DESIGN.md, §8.3, I4-D6).
 *
 * Файловой системы этот класс не знает вовсе: ни одного пути в обход `AssetManager`,
 * ни одного потока, открытого мимо него. Единственное обращение к менеджеру ассетов
 * за байтами выполняется ниже, уже после [ContentPaths.assetPath], который сам
 * проверяет имя и строит путь.
 */
class AssetContentSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContentAssetSource {

    /** Единственное во всём проекте место, где байты ассета вообще открываются. */
    override suspend fun read(fileName: String): ByteArray = withContext(Dispatchers.IO) {
        val path = ContentPaths.assetPath(fileName)
        context.assets.open(path).use { it.readBytes() }
    }

    /**
     * Единственное во всём проекте место, где перечисляется каталог. Файлов НЕ
     * открывает: `M08` — вопрос о составе каталога, а не о содержимом, и лишний файл
     * не должен читаться уже потому, что он лишний.
     */
    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        context.assets.list(ContentPaths.ROOT)?.toList().orEmpty()
    }
}
