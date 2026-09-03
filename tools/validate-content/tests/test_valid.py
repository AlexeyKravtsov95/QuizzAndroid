"""Позитивные фикстуры: `I4-A1`, `I4-A2`.

Зелёный валидатор на полной фикстуре — необходимое условие всего остального: если
он ложно ругается на корректный пакет, ни одна негативная проверка ничего не значит.
"""

from __future__ import annotations

import json

from conftest import FIXTURES_DIR, PUZZLES, SETS, fixture, run_cli


def _document(name: str, file_name: str) -> dict:
    return json.loads((FIXTURES_DIR / name / file_name).read_text(encoding="utf-8"))


def _manifest(name: str) -> dict:
    return _document(name, "manifest.json")


def test_i4_a1_full_valid_pack_has_no_findings():
    """`I4-A1`: полная фикстура — exit 0 и ноль находок."""
    run = run_cli(fixture("valid"))

    assert run.code == 0
    assert run.codes == []


def test_i4_a1_valid_pack_composition():
    """`I4-A1`: состав фикстуры — 8 наборов и 25 головоломок, 24 из них активные.

    Двадцать пять, а не двадцать четыре: `R18B` требует не более одного вхождения
    головоломки в пакет, а `R18A` запрещает ссылаться на отозванную — значит
    отозванная обязана лежать в пуле лишней (ITERATION_4_DESIGN.md §6.1).
    """
    manifest = _manifest("valid")
    puzzles = _document("valid", PUZZLES)["puzzles"]
    sets = _document("valid", SETS)["sets"]

    assert manifest["setCount"] == 8
    assert manifest["puzzleCount"] == 25
    assert len(sets) == 8
    assert len(puzzles) == 25

    retired = [p for p in puzzles if p["retiredIn"] is not None]
    assert len(retired) == 1
    assert retired[0]["retiredIn"] == manifest["contentVersion"]

    used = [pid for item in sets for pid in item["puzzleIds"]]
    assert len(used) == 24
    assert len(set(used)) == 24, "каждая активная головоломка используется ровно один раз"
    assert retired[0]["puzzleId"] not in used, "отозванная головоломка не используется"


def test_i4_a1_valid_pack_covers_required_features():
    """`I4-A1`: в фикстуре есть `slow`, `disputed`, обе сортировки, четыре `sortKey`.

    Позитив обязан покрывать те же ветки, что и негативы, иначе «зелёный на валидном»
    ничего не говорит о правилах 16, 6 и 8.
    """
    puzzles = _document("valid", PUZZLES)["puzzles"]

    assert any(p["volatility"] == "slow" for p in puzzles)
    assert any(card.get("disputed") for p in puzzles for card in p["cards"])
    assert {p["sortDirection"] for p in puzzles} == {"ascending", "descending"}
    assert len({p["sortKey"] for p in puzzles}) >= 4

    profiles = set()
    sets = _document("valid", SETS)["sets"]
    by_id = {p["puzzleId"]: p for p in puzzles}
    for item in sets:
        profiles.add(tuple(by_id[pid]["difficulty"] for pid in item["puzzleIds"]))
    assert profiles == {(1, 2, 2), (1, 2, 3)}, "оба допустимых профиля представлены"


def test_i4_a2_minimal_valid_pack():
    """`I4-A2`: минимальная фикстура — один набор, три головоломки, exit 0."""
    run = run_cli(fixture("valid-minimal"))

    assert run.code == 0
    assert run.codes == []

    manifest = _manifest("valid-minimal")
    assert manifest["setCount"] == 1
    assert manifest["puzzleCount"] == 3


def test_valid_fixtures_are_normalised():
    """Все файлы фикстур — UTF-8 без BOM, отступ 2 пробела, ровно один `\\n` в конце.

    Проверка стоит здесь, а не в редакционном чек-листе: нормализация — предпосылка
    воспроизводимого `sha256`, а не вопрос вкуса (ITERATION_4_DESIGN.md §4.4).
    """
    for name in ("valid", "valid-minimal"):
        for path in sorted((FIXTURES_DIR / name).iterdir()):
            raw = path.read_bytes()
            assert not raw.startswith(b"\xef\xbb\xbf"), f"{path}: BOM"
            text = raw.decode("utf-8")
            assert text.endswith("\n") and not text.endswith("\n\n"), f"{path}: перевод строки"
            assert "\r" not in text, f"{path}: CR"
            assert text == json.dumps(
                json.loads(text), ensure_ascii=False, indent=2
            ) + "\n", f"{path}: форматирование не совпадает с нормализованным"
