"""`expectations.json` как единственный источник ожидаемых кодов.

`I4-A34` и сводная проверка `I4-A3`: каждая фикстура каталога прогоняется через CLI,
и полученный список кодов сравнивается с колонкой `cli` — по кодам и **порядку**,
без единого сравнения текста сообщения.
"""

from __future__ import annotations

import json

import pytest

from conftest import EXPECTATIONS, FIXTURES_DIR, diag, run_cli

FIXTURE_NAMES = sorted(EXPECTATIONS["fixtures"])


def _fixture_directories() -> set[str]:
    """Все каталоги фикстур на диске: две позитивные и всё содержимое `invalid/`."""
    names = set()
    for name in ("valid", "valid-minimal"):
        if (FIXTURES_DIR / name).is_dir():
            names.add(name)
    for path in sorted((FIXTURES_DIR / "invalid").iterdir()):
        if path.is_dir():
            names.add(f"invalid/{path.name}")
    return names


# --- I4-A34: полнота ---------------------------------------------------------


def test_i4_a34_every_fixture_has_an_expectation():
    """`I4-A34`: у каждого каталога фикстур есть запись — осиротевших фикстур нет."""
    assert _fixture_directories() - set(EXPECTATIONS["fixtures"]) == set()


def test_i4_a34_every_expectation_has_a_fixture():
    """`I4-A34`: у каждой записи есть каталог — лишних записей нет."""
    assert set(EXPECTATIONS["fixtures"]) - _fixture_directories() == set()


def test_i4_a34_expectations_contain_no_absolute_paths():
    """`I4-A34`: имена фикстур относительные — файл переносится между машинами."""
    raw = (FIXTURES_DIR / "expectations.json").read_text(encoding="utf-8")

    assert "/Users/" not in raw and "/home/" not in raw and "C:\\\\" not in raw
    for name in EXPECTATIONS["fixtures"]:
        assert not name.startswith("/")


def test_i4_a34_expectations_declare_a_fixed_validation_date():
    """`I4-A34`: дата проверки живёт в файле, а не в календаре машины (I4-A8)."""
    from datetime import date

    assert date.fromisoformat(EXPECTATIONS["validationDate"]).isoformat() == (
        EXPECTATIONS["validationDate"]
    )


def test_i4_a34_expected_codes_are_sorted_and_known():
    """`I4-A34`: коды отсортированы и принадлежат объявленному перечню."""
    for name, entry in EXPECTATIONS["fixtures"].items():
        assert entry["cli"] == sorted(entry["cli"]), name
        for code in entry["cli"]:
            assert code in diag.ALL_CODES, f"{name}: неизвестный код {code}"
        for code in entry.get("runtime", []):
            assert code in diag.ALL_CODES, f"{name}: неизвестный код рантайма {code}"


def test_i4_a34_every_negative_fixture_expects_at_least_one_code():
    """`I4-A34`: негативная фикстура, ничего не нарушающая, — не фикстура."""
    for name, entry in EXPECTATIONS["fixtures"].items():
        if name.startswith("invalid/"):
            assert entry["cli"], name
        else:
            assert entry["cli"] == [], name


def test_i4_a34_every_rule_code_has_a_negative_fixture():
    """`I4-A34`: каждый код, кроме `M10`, покрыт хотя бы одной негативной фикстурой.

    `M10` исключён по проекту: его создаёт флаг `--expect-*`, а не испорченный
    пакет, и проверяется он тестом `I4-A37` (ITERATION_4_DESIGN.md §6.3).
    """
    covered = {code for entry in EXPECTATIONS["fixtures"].values() for code in entry["cli"]}

    assert set(diag.ALL_CODES) - covered == {diag.M10_EXPECTED_VOLUME}


def test_i4_a34_fixture_count_meets_the_design_minimum():
    """`I4-A34`: негативных каталогов не меньше 32 (ITERATION_4_DESIGN.md §18.2)."""
    negatives = [name for name in EXPECTATIONS["fixtures"] if name.startswith("invalid/")]

    assert len(negatives) >= 32


# --- I4-A3 сводно: каждая фикстура даёт ровно свой список --------------------


@pytest.mark.parametrize("name", FIXTURE_NAMES)
def test_i4_a3_cli_matches_expectations(name):
    """Каждая фикстура даёт ровно те коды и в том порядке, что записаны в файле."""
    run = run_cli(str(FIXTURES_DIR / name), validation_date=EXPECTATIONS["validationDate"])
    entry = EXPECTATIONS["fixtures"][name]

    assert run.codes == entry["cli"], name
    assert run.code == (1 if entry["cli"] else 0), name


@pytest.mark.parametrize("name", FIXTURE_NAMES)
def test_each_fixture_documents_why_it_exists(name):
    """У каждой негативной фикстуры записано, что именно она нарушает.

    Без этого каталог из полусотни имён вида `r08d-...` через полгода читается
    только вместе с исходником правила.
    """
    entry = EXPECTATIONS["fixtures"][name]
    if name.startswith("invalid/"):
        assert entry.get("why"), name


def test_runtime_column_is_optional_and_well_formed():
    """Колонка `runtime` необязательна; её содержание заполнено в PR 4B.

    Здесь фиксируется форма колонки и два следствия §7.3, которые обязаны пережить
    любую правку рантайма.
    """
    with_runtime = {
        name: entry for name, entry in EXPECTATIONS["fixtures"].items() if "runtime" in entry
    }

    assert with_runtime, "хотя бы одна запись демонстрирует вторую колонку"
    for name, entry in with_runtime.items():
        assert isinstance(entry["runtime"], list), name
        assert entry["runtime"] == sorted(entry["runtime"]), name
        assert entry.get("runtimeWhy"), name

    # §7.3: отсутствующее обязательное поле не даёт DTO построиться, и отказ рантайма
    # уже назван R01 — точного кода CLI (`R02`) он не обещает.
    assert EXPECTATIONS["fixtures"]["invalid/r02-no-correct-order"]["runtime"] == [diag.R01_SCHEMA]

    # §7.3, п. 3: пустой sourceIds защитным инвариантом НЕ является. Фикстура содержит
    # именно пустой массив, а не отсутствующее поле, поэтому DTO строится, и рантайм
    # не находит ничего: приложение не читает источники при выдаче головоломки.
    # (В ревизии PR 4A здесь стояло R01 — предсказание, которому фикстура противоречит.)
    assert EXPECTATIONS["fixtures"]["invalid/r11-empty-source-ids"]["runtime"] == []

    # Ни один код рантайма не принадлежит колонке CLI-специализированных правил.
    for name, entry in with_runtime.items():
        assert diag.R02_CORRECT_ORDER_MISSING not in entry["runtime"], name
        assert diag.R11_SOURCE_IDS_EMPTY not in entry["runtime"], name


# --- Пересборка фикстур воспроизводима --------------------------------------


def test_rebuild_is_reproducible(tmp_path):
    """Пересборка из спецификации даёт тот же состав фикстур, что лежит в репозитории.

    Тест защищает от расхождения «фикстуры поправили руками, а генератор — нет»:
    после такого `rebuild.py` молча вернул бы каталог в прежнее состояние.
    """
    import sys

    sys.path.insert(0, str(FIXTURES_DIR))
    import rebuild

    entries = rebuild.build_all(tmp_path)

    assert sorted(entries) == FIXTURE_NAMES
    for name, entry in entries.items():
        assert entry["cli"] == EXPECTATIONS["fixtures"][name]["cli"], name


def test_rebuild_does_not_repair_deliberately_broken_manifests(tmp_path):
    """Пересборка **не чинит** фикстуры, намеренно ломающие манифест, хеш или счётчики.

    Порча объявлена в спецификации и применяется после автозаполнения производных
    значений, поэтому каждая пересборка воспроизводит нарушение (ITERATION_4_DESIGN.md
    §11, требование к `rebuild.py`).
    """
    import sys

    sys.path.insert(0, str(FIXTURES_DIR))
    import rebuild

    rebuild.build_all(tmp_path)

    for name in sorted(rebuild.DELIBERATE):
        expected = EXPECTATIONS["fixtures"][f"invalid/{name}"]["cli"]
        run = run_cli(
            str(tmp_path / "invalid" / name), validation_date=EXPECTATIONS["validationDate"]
        )
        assert run.codes == expected, name
        assert run.code == 1, name


def test_shuffle_vectors_file_is_normalised():
    """`shuffle-vectors.json` нормализован так же, как остальные файлы фикстур."""
    raw = (FIXTURES_DIR / "shuffle-vectors.json").read_bytes()
    text = raw.decode("utf-8")

    assert not raw.startswith(b"\xef\xbb\xbf")
    assert text.endswith("\n") and not text.endswith("\n\n")
    assert text == json.dumps(json.loads(text), ensure_ascii=False, indent=2) + "\n"
