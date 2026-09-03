package ru.poporyadku.data.content

import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.data.content.dto.ParsedPack
import ru.poporyadku.data.content.validation.ContentValidator
import ru.poporyadku.data.content.validation.ContentViolation
import ru.poporyadku.di.ContentModule
import ru.poporyadku.domain.content.ContentInstallException

/**
 * Общий harness parity (ITERATION_4_DESIGN.md, §7.4).
 *
 * Прогоняет фикстуру через **полный** runtime-путь `ContentPackReader → ContentValidator`,
 * а не через один валидатор: `R01` возникает в читателе, и требовать его от валидатора
 * было бы требованием невыполнимого. Разделение обязанностей внутри рантайма — деталь
 * реализации; контракт «какие коды даёт приложение на этой фикстуре» один.
 *
 * `Json` берётся у продуктового провайдера `ContentModule.assetJson()`, а не собирается
 * заново: parity на копии настроек доказывал бы parity копии.
 */
object RuntimeParityHarness {

    /**
     * @param codes все коды, выданные обоими слоями, в порядке диагностик.
     * @param validatorCalls сколько раз вызывался валидатор — контракт `I4-P6`.
     */
    data class Result(val codes: List<String>, val validatorCalls: Int)

    suspend fun run(
        packName: String,
        verifyIntegrity: Boolean = true,
        activePackId: String = ContentPack.CORE_RU,
        validate: (ParsedPack) -> List<ContentViolation> = ContentValidator()::findings,
    ): Result {
        var calls = 0
        val reader = ContentPackReader(
            source = ContentFixtures.source(packName),
            json = ContentModule.assetJson(),
            verifyIntegrity = verifyIntegrity,
        )
        return try {
            val header = reader.readHeader(activePackId)
            val pack = reader.readBody(header)
            calls++
            Result(validate(pack).map { it.code }, calls)
        } catch (e: ContentInstallException.BundleInvalid) {
            // Читатель бросает первым же нарушением: он владеет формой и целостностью,
            // и продолжать разбор документа, форма которого не подтверждена, нечем.
            Result(listOf(e.code), calls)
        } catch (e: ContentInstallException.UnsupportedSchema) {
            // Отдельный тип отказа, но код правила у него тот же, что у CLI.
            Result(listOf(ContentViolation.M01_SCHEMA_VERSION_UNSUPPORTED), calls)
        }
    }
}
