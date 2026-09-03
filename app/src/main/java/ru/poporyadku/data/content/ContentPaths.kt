package ru.poporyadku.data.content

/**
 * Адресация файлов пакета внутри `assets` (ITERATION_4_DESIGN.md, I4-D6, §4.5, §8.3).
 *
 * Разделены два независимых контракта, и свести их к одному шаблону нельзя:
 *
 * * имя манифеста задаёт **код** — [MANIFEST] константа, данные им управлять не могут;
 * * имена контентных файлов приходят **из данных** (`manifest.files[].path`) и обязаны
 *   пройти закрытый шаблон [CONTENT_FILE_NAME], в который `manifest.json` не попадает.
 *
 * Шаблон, а не «санитайзер»: список допустимых имён известен заранее, и allow-list
 * не имеет обходов, в отличие от нормализации путей. `/`, `\`, `..`, `~`, абсолютный
 * путь, подкаталог и любое расширение кроме `.json` не проходят по построению.
 */
object ContentPaths {

    /** Каталог пакета внутри assets. */
    const val ROOT = "content"

    /** Имя манифеста — КОНСТАНТА кода, а не значение из данных. */
    const val MANIFEST = "manifest.json"

    /** Имена файлов, объявляемых манифестом. `manifest.json` сюда не подходит. */
    val CONTENT_FILE_NAME = Regex("^(puzzles|daily-sets)-[0-9]{3}\\.json$")

    /** Префиксы двух типов контентных файлов: ровно по одному каждого (**I4-D5**). */
    const val PUZZLES_PREFIX = "puzzles-"
    const val DAILY_SETS_PREFIX = "daily-sets-"

    /** Имя, которое [ContentAssetSource.read] имеет право открыть. */
    fun isReadable(name: String): Boolean =
        name == MANIFEST || CONTENT_FILE_NAME.matches(name)

    /**
     * Единственное место конкатенации `content/` с именем файла во всём проекте.
     *
     * @throws IllegalArgumentException если имя не проходит [isReadable] — до любого
     * обращения к `AssetManager`.
     */
    fun assetPath(name: String): String {
        require(isReadable(name)) { "недопустимое имя файла контента: '$name'" }
        return "$ROOT/$name"
    }
}
