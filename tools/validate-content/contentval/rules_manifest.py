"""Правила уровня пакета: манифест, имена файлов, байты, счётчики.

Коды ``M01``–``M10`` и ``R21``. Ни одно из них не входит в перечень 21 правила
`CONTENT_MODEL.md` §8: §3 описал манифест, но не задал его проверок
(ITERATION_4_DESIGN.md §6.3).
"""

from __future__ import annotations

from dataclasses import dataclass

from . import diagnostics as diag
from . import rules_schema
from .loader import CONTENT_FILE_NAME, FILE_PREFIXES, MANIFEST_NAME, PackDirectory
from .units import ACTIVE_PACK_ID, SUPPORTED_SCHEMA_VERSION


@dataclass
class ManifestFacts:
    """То, что правила остальных уровней берут из манифеста.

    Поля заполняются по мере того, как соответствующая часть манифеста признана
    пригодной. ``None`` означает «нечем пользоваться», и зависящее правило молчит,
    а не выдумывает значение.
    """

    pack_id: str | None = None
    schema_version: int | None = None
    content_version: int | None = None
    set_count: int | None = None
    puzzle_count: int | None = None
    puzzles_file: str | None = None
    daily_sets_file: str | None = None


def check_manifest(pack: PackDirectory) -> tuple[list[diag.Finding], ManifestFacts, bool]:
    """M01–M09 и разбор манифеста.

    Возвращает находки, извлечённые факты и признак «манифест пригоден»: если он
    ложен, кросс-файловые правила не запускаются — им не на что опереться.
    """
    findings: list[diag.Finding] = []
    facts = ManifestFacts()

    manifest_file = pack.get(MANIFEST_NAME)
    if manifest_file is None:
        findings.append(
            diag.Finding(
                diag.M04_FILE_MISSING,
                MANIFEST_NAME,
                "",
                "в каталоге пакета нет manifest.json — читать пакет не с чего",
            )
        )
        # Без манифеста неизвестно, какие файлы объявлены, поэтому M08 объявляет
        # лишним всё, кроме самого манифеста.
        findings.extend(_unexpected_files(pack, declared=set()))
        return findings, facts, False

    if not manifest_file.parsed or not isinstance(manifest_file.data, dict):
        if manifest_file.parsed:
            findings.append(
                diag.Finding(
                    diag.R01_SCHEMA,
                    MANIFEST_NAME,
                    "",
                    "корень манифеста обязан быть объектом",
                )
            )
        # M05/M09 уже объявлены загрузчиком.
        return findings, facts, False

    manifest: dict = manifest_file.data
    findings.extend(
        rules_schema.validate_document(MANIFEST_NAME, rules_schema.MANIFEST_SCHEMA, manifest)
    )

    # --- M01: неподдерживаемая версия формата --------------------------------
    schema_version = manifest.get("schemaVersion")
    if isinstance(schema_version, int) and not isinstance(schema_version, bool):
        facts.schema_version = schema_version
        if schema_version > SUPPORTED_SCHEMA_VERSION:
            findings.append(
                diag.Finding(
                    diag.M01_SCHEMA_VERSION_UNSUPPORTED,
                    MANIFEST_NAME,
                    "/schemaVersion",
                    f"schemaVersion {schema_version} больше поддерживаемой "
                    f"{SUPPORTED_SCHEMA_VERSION}: требуется обновление приложения",
                )
            )

    # --- M02, первая половина: манифест против активного пакета приложения ------
    if isinstance(manifest.get("packId"), str):
        facts.pack_id = manifest["packId"]
        if facts.pack_id != ACTIVE_PACK_ID:
            findings.append(
                diag.Finding(
                    diag.M02_PACK_ID_MISMATCH,
                    MANIFEST_NAME,
                    "/packId",
                    f"packId пакета {facts.pack_id!r} не совпадает с активным пакетом "
                    f"приложения {ACTIVE_PACK_ID!r}: такой пакет не импортируется ни при "
                    "каких условиях, потому что все запросы импортёра pack-scoped",
                )
            )
    for field, attribute in (
        ("contentVersion", "content_version"),
        ("setCount", "set_count"),
        ("puzzleCount", "puzzle_count"),
    ):
        value = manifest.get(field)
        if isinstance(value, int) and not isinstance(value, bool):
            setattr(facts, attribute, value)

    # --- M03: список файлов --------------------------------------------------
    file_findings, declared = _check_file_list(manifest)
    findings.extend(file_findings)

    if file_findings:
        # Список файлов недостоверен, а он — единственный источник ответа на вопрос
        # «что вообще входит в пакет». Сверять с ним каталог (M08), искать объявленные
        # файлы (M04) и считать их хеши (M06) означало бы выдавать каскад находок про
        # ошибку, которая уже названа точным кодом M03.
        return findings, facts, False

    for name in declared:
        for prefix, attribute in zip(FILE_PREFIXES, ("puzzles_file", "daily_sets_file")):
            if name.startswith(prefix):
                setattr(facts, attribute, name)

    # --- M08: лишний физический файл ----------------------------------------
    findings.extend(_unexpected_files(pack, declared=set(declared)))

    # --- M04 и M06: наличие объявленных файлов и их хеши ---------------------
    findings.extend(_check_declared_files(pack, manifest, declared))

    usable = facts.pack_id is not None and facts.puzzles_file is not None and facts.daily_sets_file is not None
    return findings, facts, usable


def _check_file_list(manifest: dict) -> tuple[list[diag.Finding], list[str]]:
    """M03: шаблон имени, ровно два элемента, уникальность, по одному каждого типа.

    ``files[].path`` — **не путь, а имя из закрытого множества** (I4-D6). Полное
    совпадение с шаблоном отсекает ``..``, ``/``, ``\\``, ``~``, абсолютный путь,
    подкаталог, ``manifest.json``, другое расширение и другой регистр по построению —
    санитайзер здесь был бы слабее, потому что у него бывают обходы, а у allow-list нет.
    """
    findings: list[diag.Finding] = []
    files = manifest.get("files")

    if not isinstance(files, list):
        findings.append(
            diag.Finding(
                diag.M03_FILE_LIST_INVALID,
                MANIFEST_NAME,
                "/files",
                "поле files обязано быть массивом из двух объявлений файлов",
            )
        )
        return findings, []

    if len(files) != 2:
        findings.append(
            diag.Finding(
                diag.M03_FILE_LIST_INVALID,
                MANIFEST_NAME,
                "/files",
                f"объявлено файлов: {len(files)}; требуется ровно два — "
                "один puzzles-NNN.json и один daily-sets-NNN.json, шарды не поддерживаются",
            )
        )

    valid_names: list[str] = []
    seen: set[str] = set()
    for index, entry in enumerate(files):
        pointer = f"/files/{index}/path"
        if not isinstance(entry, dict):
            findings.append(
                diag.Finding(
                    diag.M03_FILE_LIST_INVALID,
                    MANIFEST_NAME,
                    f"/files/{index}",
                    "элемент files обязан быть объектом с полями path и sha256",
                )
            )
            continue
        path = entry.get("path")
        if not isinstance(path, str) or not CONTENT_FILE_NAME.match(path):
            findings.append(
                diag.Finding(
                    diag.M03_FILE_LIST_INVALID,
                    MANIFEST_NAME,
                    pointer,
                    f"недопустимое имя файла {path!r}: требуется полное совпадение с "
                    r"^(puzzles|daily-sets)-[0-9]{3}\.json$ — без подкаталогов, «..», "
                    "абсолютных путей, другого расширения и другого регистра",
                )
            )
            continue
        if path in seen:
            findings.append(
                diag.Finding(
                    diag.M03_FILE_LIST_INVALID,
                    MANIFEST_NAME,
                    pointer,
                    f"дубликат объявления файла {path!r}",
                )
            )
            continue
        seen.add(path)
        valid_names.append(path)

    for prefix in FILE_PREFIXES:
        matching = [name for name in valid_names if name.startswith(prefix)]
        if len(matching) != 1:
            findings.append(
                diag.Finding(
                    diag.M03_FILE_LIST_INVALID,
                    MANIFEST_NAME,
                    "/files",
                    f"файлов с префиксом {prefix!r}: {len(matching)}; требуется ровно один",
                )
            )

    return findings, valid_names


def _unexpected_files(pack: PackDirectory, declared: set[str]) -> list[diag.Finding]:
    """M08: в каталоге есть запись, которую манифест не объявлял.

    Проверяются все записи каталога, а не только ``*.json``: подкаталог тоже лишний —
    формат допускает ровно три файла и ни одного подкаталога (§4.1).
    """
    findings: list[diag.Finding] = []
    for name in pack.entries:
        if name == MANIFEST_NAME or name in declared:
            continue
        findings.append(
            diag.Finding(
                diag.M08_UNEXPECTED_FILE,
                name,
                "",
                "запись каталога не объявлена манифестом: пакет состоит ровно из "
                "manifest.json, puzzles-NNN.json и daily-sets-NNN.json",
            )
        )
    return findings


def _check_declared_files(
    pack: PackDirectory, manifest: dict, declared: list[str]
) -> list[diag.Finding]:
    """M04 (объявленный файл отсутствует) и M06 (sha256 не совпал)."""
    findings: list[diag.Finding] = []
    files = manifest.get("files")
    if not isinstance(files, list):
        return findings

    for index, entry in enumerate(files):
        if not isinstance(entry, dict):
            continue
        path = entry.get("path")
        if not isinstance(path, str) or path not in declared:
            continue

        loaded = pack.get(path)
        if loaded is None:
            findings.append(
                diag.Finding(
                    diag.M04_FILE_MISSING,
                    path,
                    "",
                    f"файл объявлен манифестом (/files/{index}), но в каталоге пакета его нет",
                )
            )
            continue

        expected = entry.get("sha256")
        actual = loaded.sha256
        if isinstance(expected, str) and expected != actual:
            findings.append(
                diag.Finding(
                    diag.M06_HASH_MISMATCH,
                    MANIFEST_NAME,
                    f"/files/{index}/sha256",
                    f"sha256 файла {path} не совпал: записан {expected!r}, вычислен "
                    f"{actual!r} (64 строчных hex-символа от точных байтов файла); "
                    "запустите --sync-manifest",
                )
            )
    return findings


def check_envelopes(
    pack: PackDirectory, facts: ManifestFacts
) -> list[diag.Finding]:
    """M02 и M07: ``packId`` и ``schemaVersion`` обязаны совпадать во всех трёх файлах.

    Это **вторая половина** правила ``M02``; первая — сверка манифеста с активным
    пакетом приложения — выполнена в :func:`check_manifest`. Обе требуются
    ITERATION_4_DESIGN.md §6.3 и тестом ``I4-A14``.

    Сверка файлов между собой — единственная защита от «собрали пакет из двух половин
    разных паков»: ссылочная целостность такую ошибку не ловит, если идентификаторы
    случайно совпали (§4.3).
    """
    findings: list[diag.Finding] = []
    for name in (facts.puzzles_file, facts.daily_sets_file):
        if name is None:
            continue
        loaded = pack.get(name)
        if loaded is None or not loaded.parsed or not isinstance(loaded.data, dict):
            continue
        envelope: dict = loaded.data

        pack_id = envelope.get("packId")
        if facts.pack_id is not None and isinstance(pack_id, str) and pack_id != facts.pack_id:
            findings.append(
                diag.Finding(
                    diag.M02_PACK_ID_MISMATCH,
                    name,
                    "/packId",
                    f"packId файла {pack_id!r} расходится с packId манифеста {facts.pack_id!r}",
                )
            )

        schema_version = envelope.get("schemaVersion")
        if (
            facts.schema_version is not None
            and isinstance(schema_version, int)
            and not isinstance(schema_version, bool)
            and schema_version != facts.schema_version
        ):
            findings.append(
                diag.Finding(
                    diag.M07_SCHEMA_VERSION_MISMATCH,
                    name,
                    "/schemaVersion",
                    f"schemaVersion файла {schema_version} расходится с манифестом "
                    f"{facts.schema_version}",
                )
            )
    return findings


def check_counts(
    facts: ManifestFacts, actual_puzzles: int, actual_sets: int
) -> list[diag.Finding]:
    """R21: ``setCount`` и ``puzzleCount`` манифеста против фактических.

    Проверка охраняет **закоммиченный** артефакт: ``--sync-manifest`` пересчитывает
    производные значения, но diff манифеста в PR всё равно читается человеком.
    """
    findings: list[diag.Finding] = []
    if facts.puzzle_count is not None and facts.puzzle_count != actual_puzzles:
        findings.append(
            diag.Finding(
                diag.R21_MANIFEST_COUNTS,
                MANIFEST_NAME,
                "/puzzleCount",
                f"в манифесте {facts.puzzle_count}, фактически головоломок {actual_puzzles}",
            )
        )
    if facts.set_count is not None and facts.set_count != actual_sets:
        findings.append(
            diag.Finding(
                diag.R21_MANIFEST_COUNTS,
                MANIFEST_NAME,
                "/setCount",
                f"в манифесте {facts.set_count}, фактически наборов {actual_sets}",
            )
        )
    return findings


def check_expected_volume(
    actual_sets: int,
    actual_puzzles: int,
    expect_sets: int | None,
    expect_puzzles: int | None,
) -> list[diag.Finding]:
    """M10: фактический объём пакета против флагов ``--expect-*``.

    Критерий релиза, а не правило формата: 35/105 — это то, чем пакет обязан стать
    к PR 4D, а батчи 4C-N законно меньше (ITERATION_4_DESIGN.md §4.6, §12.4).
    Отдельная негативная фикстура правилу не нужна — его создаёт флаг (I4-A37).
    """
    findings: list[diag.Finding] = []
    if expect_sets is not None and expect_sets != actual_sets:
        findings.append(
            diag.Finding(
                diag.M10_EXPECTED_VOLUME,
                MANIFEST_NAME,
                "/setCount",
                f"ожидалось наборов {expect_sets} (--expect-sets), фактически {actual_sets}",
            )
        )
    if expect_puzzles is not None and expect_puzzles != actual_puzzles:
        findings.append(
            diag.Finding(
                diag.M10_EXPECTED_VOLUME,
                MANIFEST_NAME,
                "/puzzleCount",
                f"ожидалось головоломок {expect_puzzles} (--expect-puzzles), "
                f"фактически {actual_puzzles}",
            )
        )
    return findings
