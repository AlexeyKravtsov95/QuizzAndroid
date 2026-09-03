"""Детерминированность вывода и коды выхода.

`I4-A33`, `I4-A35`, `I4-A38`.
"""

from __future__ import annotations

import hashlib
import io
import json

import pytest

from conftest import MANIFEST, PUZZLES, SETS, build_pack, cli, diag, dump, fixture, run_cli


# --- I4-A33: детерминированность --------------------------------------------


def test_i4_a33_repeated_runs_are_byte_identical():
    """`I4-A33`: два запуска на одном пакете дают побайтово одинаковый вывод."""
    first = run_cli(fixture("invalid/r20a-same-category"))
    second = run_cli(fixture("invalid/r20a-same-category"))

    assert first.stdout == second.stdout


def test_i4_a33_key_order_of_input_does_not_change_output(tmp_path):
    """`I4-A33`: перемешивание порядка ключей во входном JSON вывод не меняет.

    Без этой проверки порядок находок мог бы незаметно зависеть от порядка обхода
    словарей — то есть от версии интерпретатора и от того, кто последним правил файл.
    """

    def shuffle_keys(value):
        if isinstance(value, dict):
            return {key: shuffle_keys(value[key]) for key in sorted(value, reverse=True)}
        if isinstance(value, list):
            return [shuffle_keys(item) for item in value]
        return value

    straight = build_pack(tmp_path, base="valid")
    reversed_keys = build_pack(tmp_path, base="valid")
    for name in (MANIFEST, PUZZLES, SETS):
        path = reversed_keys / name
        path.write_bytes(dump(shuffle_keys(json.loads(path.read_text(encoding="utf-8")))))
    cli.sync_manifest(reversed_keys, out=io.StringIO())

    def break_two_puzzles(root):
        path = root / PUZZLES
        document = json.loads(path.read_text(encoding="utf-8"))
        for index in (2, 10):
            document["puzzles"][index]["volatility"] = "volatile"
        path.write_bytes(dump(document))
        cli.sync_manifest(root, out=io.StringIO())

    break_two_puzzles(straight)
    break_two_puzzles(reversed_keys)

    first = run_cli(str(straight))
    second = run_cli(str(reversed_keys))

    assert [f["pointer"] for f in first.findings] == [f["pointer"] for f in second.findings]
    assert first.codes == second.codes


def test_i4_a33_array_indexes_sort_numerically(tmp_path):
    """`I4-A33`: `/puzzles/2` идёт раньше `/puzzles/10`.

    Лексикографическое сравнение поставило бы `/puzzles/10` первым, и диагностика
    большого пакета читалась бы в случайном на вид порядке.
    """
    pack_dir = build_pack(tmp_path, base="valid")
    path = pack_dir / PUZZLES
    document = json.loads(path.read_text(encoding="utf-8"))
    for index in (2, 10, 21):
        document["puzzles"][index]["volatility"] = "volatile"
    path.write_bytes(dump(document))
    cli.sync_manifest(pack_dir, out=io.StringIO())

    pointers = [f["pointer"] for f in run_cli(str(pack_dir)).findings]
    assert pointers == ["/puzzles/2/volatility", "/puzzles/10/volatility", "/puzzles/21/volatility"]


def test_i4_a33_files_are_ordered_for_display(tmp_path):
    """`I4-A33`: порядок файлов — `manifest.json`, головоломки, наборы.

    Он совпадает с направлением ссылок и **не обязан** совпадать с порядком
    ввода-вывода рантайма (`manifest` → `daily-sets`), который здесь ни при чём.
    """
    pack_dir = build_pack(tmp_path, base="valid-minimal")

    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    manifest["puzzleCount"] = 99
    (pack_dir / MANIFEST).write_bytes(dump(manifest))

    puzzles = json.loads((pack_dir / PUZZLES).read_text(encoding="utf-8"))
    puzzles["puzzles"][0]["volatility"] = "volatile"
    (pack_dir / PUZZLES).write_bytes(dump(puzzles))

    sets = json.loads((pack_dir / SETS).read_text(encoding="utf-8"))
    sets["sets"][0]["puzzleIds"][2] = "sci-otsutstvuet-999"
    (pack_dir / SETS).write_bytes(dump(sets))

    # Хеши пересчитываются вручную, а счётчик остаётся неверным намеренно: он и есть
    # находка в манифесте. `--sync-manifest` починил бы её и лишил тест смысла.
    manifest = json.loads((pack_dir / MANIFEST).read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        entry["sha256"] = hashlib.sha256((pack_dir / entry["path"]).read_bytes()).hexdigest()
    (pack_dir / MANIFEST).write_bytes(dump(manifest))

    files = [f["file"] for f in run_cli(str(pack_dir)).findings]
    assert files == [MANIFEST, PUZZLES, SETS]


def test_i4_a33_pointer_rank_is_segment_wise():
    """`I4-A33`: ключ сортировки указателя сравнивает сегменты, а не строку целиком."""
    assert diag.pointer_rank("/puzzles/2") < diag.pointer_rank("/puzzles/10")
    assert diag.pointer_rank("/puzzles/2/cards/10") < diag.pointer_rank("/puzzles/10/cards/2")
    assert diag.pointer_rank("") < diag.pointer_rank("/files")


# --- I4-A35: коды выхода 0 / 1 / 2 ------------------------------------------


def test_i4_a35_exit_zero_on_valid_pack():
    """`I4-A35`: нарушений нет — 0."""
    assert run_cli(fixture("valid")).code == 0


def test_i4_a35_exit_one_on_violation():
    """`I4-A35`: пакет прочитан, нарушения найдены — 1."""
    assert run_cli(fixture("invalid/r06-order-mismatch")).code == 1


def test_i4_a35_exit_two_on_missing_directory():
    """`I4-A35`: каталога нет — 2, и без traceback.

    Без разделения 1 и 2 «контент плохой» и «валидатор сломан» выглядят в CI
    одинаково, и второе чинят как первое.
    """
    run = run_cli("/nonexistent-pack-directory")

    assert run.code == 2
    assert run.stdout == ""
    assert "Traceback" not in run.stderr
    assert run.stderr.strip().startswith("validate.py:")


def test_i4_a35_exit_two_when_path_is_a_file(tmp_path):
    """`I4-A35`: путь указывает на файл, а не на каталог — тоже 2."""
    target = tmp_path / "pack.json"
    target.write_text("{}", encoding="utf-8")

    assert run_cli(str(target)).code == 2


def test_i4_a35_exit_two_on_broken_schema(tmp_path, monkeypatch):
    """`I4-A35`: повреждённая схема — поломка инструмента, а не контента."""
    from contentval import rules_schema

    broken = tmp_path / "schema"
    broken.mkdir()
    (broken / rules_schema.MANIFEST_SCHEMA).write_text(
        '{"type": "не-тип"}', encoding="utf-8"
    )
    monkeypatch.setattr(rules_schema, "SCHEMA_DIR", broken)
    monkeypatch.setattr(rules_schema, "_VALIDATOR_CACHE", {})

    run = run_cli(fixture("valid"))
    assert run.code == 2
    assert "Traceback" not in run.stderr


def test_i4_a35_exit_two_on_unreadable_schema_file(tmp_path, monkeypatch):
    """`I4-A35`: отсутствующий файл схемы — тоже 2."""
    from contentval import rules_schema

    empty = tmp_path / "no-schema"
    empty.mkdir()
    monkeypatch.setattr(rules_schema, "SCHEMA_DIR", empty)
    monkeypatch.setattr(rules_schema, "_VALIDATOR_CACHE", {})

    assert run_cli(fixture("valid")).code == 2


def test_i4_a35_invalid_validation_date_is_rejected():
    """`I4-A35`: неразбираемая `--validation-date` — отказ argparse, а не молчание."""
    with pytest.raises(SystemExit) as error:
        run_cli(fixture("valid"), validation_date="2026-13-99")
    assert error.value.code == 2


# --- I4-A38: два формата, один порядок --------------------------------------


@pytest.mark.parametrize(
    "name", ["invalid/m03-traversal", "invalid/r15-only-other", "invalid/r19-index-gap"]
)
def test_i4_a38_human_and_json_carry_the_same_findings_in_the_same_order(name):
    """`I4-A38`: человекочитаемый вывод и JSON содержат одно и то же и в одном порядке."""
    findings = run_cli(fixture(name)).findings
    lines = run_cli(fixture(name), json_format=False).stdout.strip().split("\n")

    assert len(lines) == len(findings)
    for line, finding in zip(lines, findings):
        assert line.startswith(finding["code"] + " ")
        location = (
            f"{finding['file']}#{finding['pointer']}" if finding["pointer"] else finding["file"]
        )
        assert location in line


def test_i4_a38_counts_match_the_findings():
    """`I4-A38`: блок `counts` — та же выборка, что `findings`, а не отдельный подсчёт."""
    payload = json.loads(run_cli(fixture("invalid/m03-traversal")).stdout)

    tally: dict[str, int] = {}
    for finding in payload["findings"]:
        tally[finding["code"]] = tally.get(finding["code"], 0) + 1
    assert payload["counts"] == tally


def test_i4_a38_valid_pack_json_output_is_empty_and_well_formed():
    """`I4-A38`: на валидном пакете JSON-вывод — пустые `findings` и `counts`."""
    payload = json.loads(run_cli(fixture("valid")).stdout)

    assert payload == {"findings": [], "counts": {}}


def test_i4_a38_findings_expose_the_four_contract_fields():
    """`I4-A38`: у каждой находки ровно четыре поля — код, файл, указатель, сообщение."""
    for finding in run_cli(fixture("invalid/r15-only-other")).findings:
        assert set(finding) == {"code", "file", "pointer", "message"}
        assert finding["code"] in diag.ALL_CODES
