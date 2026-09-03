"""Правила уровня пакета и вспомогательные режимы CLI.

`I4-A13`–`I4-A22`, `I4-A36`, `I4-A37`.
"""

from __future__ import annotations

import hashlib
import io
import json

import pytest

from conftest import (
    MANIFEST,
    PUZZLES,
    SETS,
    VALIDATION_DATE,
    build_pack,
    cli,
    diag,
    dump,
    fixture,
    run_cli,
)


def codes(path: str, *extra: str) -> list[str]:
    return run_cli(path, *extra).codes


# --- I4-A13 … I4-A21: коды M01–M09 -------------------------------------------


def test_i4_a13_m01_unsupported_schema_version():
    """`I4-A13`: `schemaVersion` больше поддерживаемой — отказ, а не «как-нибудь»."""
    run = run_cli(fixture("invalid/m01-schema-version"))

    assert run.code == 1
    assert run.codes == [diag.M01_SCHEMA_VERSION_UNSUPPORTED]
    assert run.findings[0]["file"] == MANIFEST
    assert run.findings[0]["pointer"] == "/schemaVersion"


def test_i4_a13_supported_schema_version_passes():
    """`I4-A13`: равная поддерживаемой версия проходит."""
    assert codes(fixture("valid")) == []


def test_i4_a14_m02_pack_id_mismatch_between_files():
    """`I4-A14`, первая половина: `packId` файла расходится с манифестом.

    Вторая половина — сверка манифеста с активным пакетом приложения — ниже,
    в `test_i4_a14_manifest_pack_id_must_match_the_active_pack`.
    """
    run = run_cli(fixture("invalid/m02-pack-mismatch"))

    assert run.codes == [diag.M02_PACK_ID_MISMATCH]
    assert run.findings[0]["file"] == PUZZLES
    assert run.findings[0]["pointer"] == "/packId"


M03_FIXTURES = [
    "m03-traversal",
    "m03-absolute-path",
    "m03-subdirectory",
    "m03-uppercase-extension",
    "m03-duplicate-path",
    "m03-three-files",
]


@pytest.mark.parametrize("name", M03_FIXTURES)
def test_i4_a15_m03_file_list(name):
    """`I4-A15`: `../`, абсолютный путь, подкаталог, чужой регистр, дубликат, три файла.

    Все шесть отсекаются одним закрытым шаблоном, а не набором санитайзеров: у
    allow-list не бывает обходов (I4-D6).
    """
    run = run_cli(fixture(f"invalid/{name}"))

    assert run.code == 1
    assert set(run.codes) == {diag.M03_FILE_LIST_INVALID}


def test_i4_a15_m03_suppresses_dependent_checks():
    """`I4-A15`: при недостоверном списке файлов не выдаются M04, M06 и M08.

    Список файлов — единственный источник ответа «что входит в пакет»; сверять с
    испорченным списком каталог значило бы выдавать каскад про уже названную ошибку.
    """
    run = run_cli(fixture("invalid/m03-three-files"))

    assert diag.M04_FILE_MISSING not in run.codes
    assert diag.M06_HASH_MISMATCH not in run.codes
    assert diag.M08_UNEXPECTED_FILE not in run.codes


def test_i4_a16_m04_declared_file_missing():
    """`I4-A16`: объявленный манифестом файл отсутствует."""
    run = run_cli(fixture("invalid/m04-missing-file"))

    assert run.codes == [diag.M04_FILE_MISSING]
    assert run.findings[0]["file"] == SETS


def test_i4_a16_m04_manifest_missing(tmp_path):
    """`I4-A16`: каталог без `manifest.json` — нарушение контента, а не поломка инструмента.

    Каталог прочитан, поэтому это exit 1, а не exit 2: exit 2 зарезервирован за
    «валидатор не смог проверить».
    """
    empty = tmp_path / "empty"
    empty.mkdir()
    run = run_cli(str(empty))

    assert run.code == 1
    assert run.codes == [diag.M04_FILE_MISSING]


def test_i4_a17_m05_malformed_json():
    """`I4-A17`: файл не разбирается как JSON."""
    run = run_cli(fixture("invalid/m05-malformed"))

    assert run.codes == [diag.M05_MALFORMED_JSON]
    assert run.findings[0]["file"] == PUZZLES


@pytest.mark.parametrize(
    "damage",
    [
        lambda text: text.replace('"puzzles": [', '"puzzles": [,', 1),
        lambda text: text.replace('"schemaVersion"', "'schemaVersion'", 1),
        lambda text: text[: len(text) // 2],
    ],
    ids=["лишняя запятая", "одиночные кавычки", "обрыв файла"],
)
def test_i4_a17_m05_three_shapes_of_broken_json(tmp_path, damage):
    """`I4-A17`: обрыв, лишняя запятая и одиночные кавычки — все три дают `M05`."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    path = pack_dir / PUZZLES
    path.write_text(damage(path.read_text(encoding="utf-8")), encoding="utf-8")
    cli.sync_manifest(pack_dir, out=io.StringIO())

    assert diag.M05_MALFORMED_JSON in codes(str(pack_dir))


@pytest.mark.parametrize("name", ["m06-hash-mismatch", "m06-hash-uppercase"])
def test_i4_a18_m06_hash(name):
    """`I4-A18`: несовпадение `sha256` и верхний регистр хеша — оба `M06`.

    Сравнение точное: манифест обязан содержать 64 строчных hex-символа от точных
    байтов файла.
    """
    run = run_cli(fixture(f"invalid/{name}"))

    assert run.codes == [diag.M06_HASH_MISMATCH]
    assert run.findings[0]["file"] == MANIFEST
    assert run.findings[0]["pointer"].startswith("/files/")


def test_i4_a18_hash_is_taken_from_exact_bytes():
    """`I4-A18`: хеш валидной фикстуры совпадает с sha256 её байтов, включая `\\n`."""
    from conftest import FIXTURES_DIR

    root = FIXTURES_DIR / "valid"
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        raw = (root / entry["path"]).read_bytes()
        assert entry["sha256"] == hashlib.sha256(raw).hexdigest()
        assert entry["sha256"] == entry["sha256"].lower()
        assert raw.endswith(b"\n")


def test_i4_a19_m07_schema_version_mismatch_between_files():
    """`I4-A19`: `schemaVersion` расходится между манифестом и файлом."""
    run = run_cli(fixture("invalid/m07-schema-mismatch"))

    assert run.codes == [diag.M07_SCHEMA_VERSION_MISMATCH]
    assert run.findings[0]["pointer"] == "/schemaVersion"


def test_i4_a20_m08_unexpected_file():
    """`I4-A20`: необъявленный файл в каталоге пакета."""
    run = run_cli(fixture("invalid/m08-extra-file"))

    assert run.codes == [diag.M08_UNEXPECTED_FILE]
    assert run.findings[0]["file"] == "puzzles-002.json"


def test_i4_a20_m08_catches_subdirectory(tmp_path):
    """`I4-A20`: подкаталог тоже лишний — формат допускает ровно три файла."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    (pack_dir / "shard").mkdir()

    run = run_cli(str(pack_dir))
    assert run.codes == [diag.M08_UNEXPECTED_FILE]


@pytest.mark.parametrize("name", ["m09-bom", "m09-no-trailing-newline"])
def test_i4_a21_m09_encoding(name):
    """`I4-A21`: BOM и отсутствие завершающего перевода строки.

    Хеши обеих фикстур посчитаны уже от испорченных байтов, поэтому `M06` не
    срабатывает и `M09` проверяется в одиночку.
    """
    run = run_cli(fixture(f"invalid/{name}"))

    assert run.codes == [diag.M09_ENCODING]


def test_i4_a21_m09_double_trailing_newline(tmp_path):
    """`I4-A21`: два перевода строки в конце — тоже нарушение нормализации."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    path = pack_dir / SETS
    path.write_bytes(path.read_bytes() + b"\n")
    cli.sync_manifest(pack_dir, out=io.StringIO())

    assert codes(str(pack_dir)) == [diag.M09_ENCODING]


# --- I4-A22: R21 -------------------------------------------------------------


@pytest.mark.parametrize(
    "field,delta", [("puzzleCount", +1), ("puzzleCount", -1), ("setCount", +1), ("setCount", -1)]
)
def test_i4_a22_r21_counts_in_both_directions(tmp_path, field, delta):
    """`I4-A22`: счётчик больше и меньше фактического — оба `R21`."""
    pack_dir = build_pack(tmp_path, base="valid")
    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    manifest[field] += delta
    (pack_dir / MANIFEST).write_bytes(dump(manifest))

    run = run_cli(str(pack_dir))
    assert run.codes == [diag.R21_MANIFEST_COUNTS]
    assert run.findings[0]["pointer"] == f"/{field}"


# --- I4-A36: --sync-manifest -------------------------------------------------


def test_i4_a36_sync_manifest_repairs_derived_values(tmp_path):
    """`I4-A36`: режим приводит пакет с неверными хешами и счётчиками к валидному.

    И печатает каждое изменение: производные значения, поправленные молча, ничем
    не отличались бы от подмены контента.
    """
    pack_dir = build_pack(tmp_path, base="valid")
    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    manifest["files"][0]["sha256"] = "0" * 64
    manifest["puzzleCount"] = 99
    manifest["setCount"] = 3
    (pack_dir / MANIFEST).write_bytes(dump(manifest))

    assert codes(str(pack_dir)) != []

    report = io.StringIO()
    changes = cli.sync_manifest(pack_dir, out=report)

    assert changes == 3
    printed = report.getvalue()
    assert "sha256" in printed and "puzzleCount" in printed and "setCount" in printed
    assert codes(str(pack_dir)) == []


def test_i4_a36_sync_manifest_touches_nothing_else(tmp_path):
    """`I4-A36`: `contentVersion`, `schemaVersion`, `packId`, имена и контент не меняются."""
    pack_dir = build_pack(tmp_path, base="valid")
    before_manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    before_puzzles = (pack_dir / PUZZLES).read_bytes()
    before_sets = (pack_dir / SETS).read_bytes()

    manifest = json.loads(json.dumps(before_manifest))
    manifest["files"][0]["sha256"] = "1" * 64
    (pack_dir / MANIFEST).write_bytes(dump(manifest))
    cli.sync_manifest(pack_dir, out=io.StringIO())

    after = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    for field in ("schemaVersion", "contentVersion", "packId", "packTitle"):
        assert after[field] == before_manifest[field]
    assert [f["path"] for f in after["files"]] == [f["path"] for f in before_manifest["files"]]
    assert (pack_dir / PUZZLES).read_bytes() == before_puzzles
    assert (pack_dir / SETS).read_bytes() == before_sets


def test_i4_a36_sync_manifest_is_idempotent(tmp_path):
    """`I4-A36`: повторный запуск на синхронном пакете ничего не меняет."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    before = (pack_dir / MANIFEST).read_bytes()

    assert cli.sync_manifest(pack_dir, out=io.StringIO()) == 0
    assert (pack_dir / MANIFEST).read_bytes() == before


def test_i4_a36_sync_manifest_does_not_repair_content(tmp_path):
    """`I4-A36`: режим не «чинит» нарушения контента — только производные значения."""
    pack_dir = build_pack(
        tmp_path,
        base="valid-minimal",
        mutate=lambda d: d[PUZZLES]["puzzles"][0].__setitem__("volatility", "volatile"),
    )
    cli.sync_manifest(pack_dir, out=io.StringIO())

    assert codes(str(pack_dir)) == [diag.R09_VOLATILE_FORBIDDEN]


def test_i4_a36_sync_manifest_on_broken_manifest_is_a_tool_error(tmp_path):
    """`I4-A36`: неразбираемый манифест — exit 2, а не тихая перезапись."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    (pack_dir / MANIFEST).write_text("{ не json", encoding="utf-8")

    run = run_cli(str(pack_dir), "--sync-manifest")
    assert run.code == 2
    assert "Traceback" not in run.stderr


# --- I4-A37: --expect-sets / --expect-puzzles --------------------------------


def test_i4_a37_expected_volume_matches():
    """`I4-A37`: совпадающий объём не даёт находок."""
    assert codes(fixture("valid"), "--expect-sets", "8", "--expect-puzzles", "25") == []


@pytest.mark.parametrize(
    "args,pointers",
    [
        (("--expect-sets", "35"), ["/setCount"]),
        (("--expect-puzzles", "105"), ["/puzzleCount"]),
        (("--expect-sets", "35", "--expect-puzzles", "105"), ["/puzzleCount", "/setCount"]),
    ],
)
def test_i4_a37_expected_volume_mismatch(args, pointers):
    """`I4-A37`: несовпадение даёт exit 1 отдельным кодом `M10`, а не общим `R21`.

    Различие принципиально: `R21` говорит «манифест врёт про свой пакет», `M10` —
    «пакет ещё не дорос до критерия релиза». Первое всегда ошибка, второе — норма
    для батчей 4C-1…4C-4.
    """
    run = run_cli(fixture("valid"), *args)

    assert run.code == 1
    assert run.codes == [diag.M10_EXPECTED_VOLUME] * len(pointers)
    assert [f["pointer"] for f in run.findings] == pointers


def test_i4_a37_expected_volume_is_not_r21():
    """`I4-A37`: `M10` не подменяет `R21` — манифест валидной фикстуры не тронут."""
    run = run_cli(fixture("valid"), "--expect-sets", "35")

    assert diag.R21_MANIFEST_COUNTS not in run.codes


# --- Строгая схема manifest.files действительно действует --------------------
#
# Подавление находок схемы внутри /files точечное: M03 владеет составом списка и
# именем файла, M06 — значением хеша. Форма элемента остаётся за схемой, и три
# случая ниже когда-то проходили с кодом выхода 0, потому что подавлялось всё
# поддерево: M03 смотрит только на path, а M06 сравнивает хеш лишь тогда, когда
# тот уже является строкой.


@pytest.mark.parametrize(
    "name",
    ["r01-files-missing-sha256", "r01-files-sha256-type", "r01-files-unknown-field"],
)
def test_manifest_files_form_is_enforced_by_schema(name):
    """Отсутствующий, нестроковый и лишний ключ внутри `files[]` дают `R01`."""
    run = run_cli(fixture(f"invalid/{name}"))

    assert run.code == 1
    assert run.codes == [diag.R01_SCHEMA]
    assert run.findings[0]["file"] == MANIFEST
    assert run.findings[0]["pointer"].startswith("/files/")


@pytest.mark.parametrize(
    "damage,pointer",
    [
        (lambda m: m["files"][0].pop("sha256"), "/files/0"),
        (lambda m: m["files"][0].__setitem__("sha256", 12345), "/files/0/sha256"),
        (lambda m: m["files"][0].__setitem__("size", 999), "/files/0"),
        (lambda m: m["files"][0].__setitem__("path", 42), "/files"),
    ],
    ids=["нет sha256", "sha256 не строка", "лишнее поле", "path не строка"],
)
def test_manifest_files_form_violations_are_never_silent(tmp_path, damage, pointer):
    """Ни одно нарушение формы элемента `files[]` не проходит с кодом выхода 0."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    damage(manifest)
    (pack_dir / MANIFEST).write_bytes(dump(manifest))

    run = run_cli(str(pack_dir))
    assert run.code == 1, "нарушение схемы манифеста обязано быть замечено"
    assert any(finding["pointer"].startswith(pointer) for finding in run.findings)


def test_manifest_hash_pattern_is_still_owned_by_m06(tmp_path):
    """Регистр и значение хеша остаются за `M06`, а не дублируются `R01`."""
    run = run_cli(fixture("invalid/m06-hash-uppercase"))

    assert run.codes == [diag.M06_HASH_MISMATCH]


# --- I4-A14, вторая половина: сверка с активным пакетом ----------------------


def test_i4_a14_manifest_pack_id_must_match_the_active_pack():
    """`I4-A14`: самосогласованный пакет с чужим `packId` отвергается.

    Он не импортируется приложением ни при каких условиях — все запросы импортёра
    pack-scoped, — поэтому пропускать его в CI значило бы обещать то, чего рантайм
    не выполнит.
    """
    run = run_cli(fixture("invalid/m02-active-pack-mismatch"))

    assert run.code == 1
    assert run.codes == [diag.M02_PACK_ID_MISMATCH]
    assert run.findings[0]["file"] == MANIFEST
    assert run.findings[0]["pointer"] == "/packId"


def test_i4_a14_both_halves_are_independent(tmp_path):
    """`I4-A14`: обе половины правила работают порознь и не подменяют друг друга."""
    from contentval.units import ACTIVE_PACK_ID

    # Только расхождение между файлами: манифест совпадает с активным пакетом.
    between_files = run_cli(fixture("invalid/m02-pack-mismatch"))
    assert between_files.findings[0]["file"] == PUZZLES

    # Только расхождение с активным пакетом: файлы между собой согласованы.
    with_active = run_cli(fixture("invalid/m02-active-pack-mismatch"))
    assert with_active.findings[0]["file"] == MANIFEST

    assert ACTIVE_PACK_ID == "core-ru"


# --- --sync-manifest не обходит защиту путей ---------------------------------


@pytest.mark.parametrize(
    "hostile", ["../secret.json", "/etc/passwd", "sub/puzzles-001.json", "puzzles-001.JSON"]
)
def test_sync_manifest_never_reads_outside_the_pack(tmp_path, hostile):
    """`--sync-manifest` применяет тот же allow-list, что `M03`, **до** чтения диска.

    Обычная валидация объявила бы `M03` уже после чтения, а «прочитали, но потом
    сообщили» защитой не является: инструмент успел бы взять байты чужого файла и
    записать его sha256 в манифест.
    """
    secret = tmp_path / "secret.json"
    secret.write_text('{"тайна": "за пределами пакета"}\n', encoding="utf-8")
    secret_hash = hashlib.sha256(secret.read_bytes()).hexdigest()

    pack_dir = build_pack(tmp_path, base="valid-minimal")
    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    recorded = manifest["files"][0]["sha256"]
    manifest["files"][0]["path"] = hostile
    (pack_dir / MANIFEST).write_bytes(dump(manifest))

    report = io.StringIO()
    cli.sync_manifest(pack_dir, out=report)

    after = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    assert after["files"][0]["sha256"] == recorded, "чужой файл не должен попасть в манифест"
    assert after["files"][0]["sha256"] != secret_hash
    assert "пропущено" in report.getvalue()


def test_sync_manifest_traversal_still_reports_m03(tmp_path):
    """Пропуск в синхронизации не отменяет находку: `M03` объявляется как обычно."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    manifest["files"][0]["path"] = "../secret.json"
    (pack_dir / MANIFEST).write_bytes(dump(manifest))

    run = run_cli(str(pack_dir), "--sync-manifest")
    assert run.code == 1
    assert set(run.codes) == {diag.M03_FILE_LIST_INVALID}


# --- --sync-manifest не зависит от форматирования манифеста ------------------


@pytest.mark.parametrize(
    "separators,indent",
    [((",", ":"), None), ((", ", ": "), 2), ((",", " : "), 4)],
    ids=["минифицированный", "обычный", "широкие пробелы"],
)
def test_sync_manifest_handles_any_json_spacing(tmp_path, separators, indent):
    """Замена значения не привязана к точной форме `"sha256": "…"`.

    Минифицированный манифест — такой же валидный JSON; при подстановке по точной
    строке инструмент не менял бы ничего, но всё равно печатал бы отчёт об
    изменении и увеличивал счётчик — то есть врал бы о том, что сделал.
    """
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    manifest["files"][0]["sha256"] = "0" * 64
    manifest["puzzleCount"] = 99
    (pack_dir / MANIFEST).write_bytes(
        (json.dumps(manifest, ensure_ascii=False, indent=indent, separators=separators) + "\n").encode()
    )

    report = io.StringIO()
    changes = cli.sync_manifest(pack_dir, out=report)

    assert changes == 2
    assert "не удалось обновить" not in report.getvalue()
    after = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    assert after["files"][0]["sha256"] != "0" * 64
    assert after["puzzleCount"] == 3
    assert codes(str(pack_dir)) == []


def test_sync_manifest_reports_only_real_changes(tmp_path):
    """Счётчик изменений считает только те замены, которые действительно произошли."""
    pack_dir = build_pack(tmp_path, base="valid-minimal")
    report = io.StringIO()

    assert cli.sync_manifest(pack_dir, out=report) == 0
    assert "уже синхронизирован" in report.getvalue()
