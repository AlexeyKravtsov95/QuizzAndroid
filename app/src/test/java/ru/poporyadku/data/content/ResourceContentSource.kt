package ru.poporyadku.data.content

import java.io.File
import java.io.FileNotFoundException

/**
 * [ContentAssetSource] поверх настоящего каталога на диске — тестовый мост к общим
 * фикстурам (ITERATION_4_DESIGN.md, §8.3, §7.4).
 *
 * `File` здесь законен ровно потому, что это `src/test`: продуктовый `data/content`
 * файловой системы не знает вовсе, и архитектурная проверка это подтверждает.
 * Имя всё равно проходит через [ContentPaths.assetPath] — иначе мост давал бы
 * тестам возможность, которой нет у приложения.
 */
class ResourceContentSource(private val packDir: File) : ContentAssetSource {

    /** Сколько раз читался каждый файл: «тело не читалось» иначе не проверить. */
    val reads: MutableList<String> = mutableListOf()

    override suspend fun read(fileName: String): ByteArray {
        // Тот же порядок, что в AssetContentSource: имя проверяется ДО обращения
        // к байтам, и недопустимое имя не доходит до диска.
        val relative = ContentPaths.assetPath(fileName).removePrefix("${ContentPaths.ROOT}/")
        reads += fileName
        val file = File(packDir, relative)
        if (!file.isFile) throw FileNotFoundException("нет файла фикстуры: ${file.path}")
        return file.readBytes()
    }

    override suspend fun list(): List<String> =
        packDir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    /** Сколько раз читался конкретный файл пакета. */
    fun readCount(fileName: String): Int = reads.count { it == fileName }
}

/** Источник, у которого чтение объявленного файла падает не «нет файла», а вводом-выводом. */
class UnreadableContentSource(
    private val delegate: ContentAssetSource,
    private val failingFileName: String,
) : ContentAssetSource {

    override suspend fun read(fileName: String): ByteArray {
        if (fileName == failingFileName) throw java.io.IOException("поток закрыт")
        return delegate.read(fileName)
    }

    override suspend fun list(): List<String> = delegate.list()
}
