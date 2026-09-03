package ru.poporyadku.data.content

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Доступ к ОБЩИМ фикстурам валидатора (ITERATION_4_DESIGN.md, §7.4).
 *
 * Каталог тот же самый, что читает Python-CLI: `tools/validate-content/fixtures`.
 * Копии в `app/src/test/resources` не заводится намеренно — две копии разошлись бы
 * молча, и parity перестал бы что-либо доказывать, оставшись зелёным. Путь приходит
 * системным свойством `content.fixtures.dir` из `app/build.gradle.kts` и абсолютен,
 * поэтому не зависит от рабочего каталога Gradle.
 */
object ContentFixtures {

    private const val PROPERTY = "content.fixtures.dir"

    val root: File by lazy {
        val raw = System.getProperty(PROPERTY)
            ?: error(
                "системное свойство $PROPERTY не задано: его выставляет " +
                    "app/build.gradle.kts (testOptions.unitTests)"
            )
        File(raw).also {
            check(it.isDirectory) { "каталог фикстур не найден: ${it.path}" }
        }
    }

    /** Каталог одной фикстуры, например `valid` или `invalid/m05-malformed`. */
    fun pack(name: String): File = File(root, name).also {
        check(it.isDirectory) { "фикстура '$name' не найдена: ${it.path}" }
    }

    fun source(name: String): ResourceContentSource = ResourceContentSource(pack(name))

    /**
     * Имена всех фикстур: `valid`, `valid-minimal` и каждый подкаталог `invalid`.
     *
     * Перечень позитивных фикстур задан явно, а не «всё, что лежит в корне»: рядом
     * оказываются рабочие каталоги инструментов (`__pycache__`), и обход по факту
     * объявлял бы их осиротевшими фикстурами. Определение то же, что у pytest.
     */
    fun allPackNames(): List<String> {
        val positive = listOf("valid", "valid-minimal").filter { File(root, it).isDirectory }
        val invalid = File(root, "invalid").listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { "invalid/${it.name}" }
        return (positive + invalid).sorted()
    }

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * `expectations.json` — единственный источник ожидаемых кодов для обеих сторон.
     *
     * Колонка `runtime` опускается там, где совпадает с `cli`; здесь это разворачивается
     * обратно, чтобы тест сравнивал полные списки, а не наличие ключа.
     */
    data class Expectation(val name: String, val cli: List<String>, val runtime: List<String>)

    val expectations: List<Expectation> by lazy {
        val tree = lenientJson
            .parseToJsonElement(File(root, "expectations.json").readText())
            .jsonObject["fixtures"]!!
            .jsonObject
        tree.entries.map { (name, value) ->
            val entry = value.jsonObject
            val cli = entry.stringList("cli")
            Expectation(
                name = name,
                cli = cli,
                runtime = if (entry.containsKey("runtime")) entry.stringList("runtime") else cli,
            )
        }.sortedBy { it.name }
    }

    fun expectationOf(name: String): Expectation =
        expectations.first { it.name == name }

    /** Векторы `shuffle-vectors.json` — контракт `I4-P4`. */
    data class ShuffleVector(
        val puzzleId: String,
        val seed: Long,
        val startOrder: List<String>,
        val why: String,
    )

    data class ShuffleVectors(val cardIds: List<String>, val vectors: List<ShuffleVector>)

    val shuffleVectors: ShuffleVectors by lazy {
        val tree = lenientJson
            .parseToJsonElement(File(root, "shuffle-vectors.json").readText())
            .jsonObject
        ShuffleVectors(
            cardIds = tree.stringList("cardIds"),
            vectors = tree["vectors"]!!.jsonArray.map { element ->
                val vector = element.jsonObject
                ShuffleVector(
                    puzzleId = vector["puzzleId"]!!.jsonPrimitive.content,
                    seed = vector["seed"]!!.jsonPrimitive.content.toLong(),
                    startOrder = vector.stringList("startOrder"),
                    why = vector["why"]!!.jsonPrimitive.content,
                )
            },
        )
    }

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]!!.jsonArray.map { it.jsonPrimitive.content }
}
