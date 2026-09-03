package ru.poporyadku.data.content

/**
 * Байты пакета контента (ITERATION_4_DESIGN.md, §8.3).
 *
 * Единственный продуктовый источник — `AssetManager`; путь строится только из
 * проверенного имени (**I4-D6**). Ничего не разбирает и ничего не проверяет по существу.
 */
interface ContentAssetSource {

    /**
     * @param fileName либо [ContentPaths.MANIFEST], либо имя по
     * [ContentPaths.CONTENT_FILE_NAME].
     * @throws IllegalArgumentException если имя не проходит [ContentPaths.isReadable];
     * бросается ДО любого обращения к источнику байтов.
     * @throws java.io.IOException если файла нет или он не читается.
     */
    suspend fun read(fileName: String): ByteArray

    /** Имена файлов каталога `content/`. Используется только проверкой `M08` (debug/CI). */
    suspend fun list(): List<String>
}
