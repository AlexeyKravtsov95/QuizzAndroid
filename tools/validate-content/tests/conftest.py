"""Общие помощники тестов валидатора (ITERATION_4_DESIGN.md §17, группа `I4-A`).

Два правила, которым подчинены все тесты этого каталога:

1. **Сравниваются коды, файлы, указатели и порядок — никогда текст сообщения.**
   `message` пишется для человека и обязан меняться без правки тестов (§7.6).
2. **Ни один тест не обращается к системному календарю.** Дата проверки берётся из
   ``expectations.json`` и передаётся ключом ``--validation-date`` (§5.2, I4-A8).
"""

from __future__ import annotations

import io
import json
import shutil
import sys
from pathlib import Path

import pytest

TOOL_DIR = Path(__file__).resolve().parent.parent
FIXTURES_DIR = TOOL_DIR / "fixtures"
sys.path.insert(0, str(TOOL_DIR))

from contentval import cli  # noqa: E402
from contentval import diagnostics as diag  # noqa: E402

MANIFEST = "manifest.json"
PUZZLES = "puzzles-001.json"
SETS = "daily-sets-001.json"

CARD_IDS = ["c1", "c2", "c3", "c4"]

EXPECTATIONS = json.loads((FIXTURES_DIR / "expectations.json").read_text(encoding="utf-8"))

#: Фиксированная дата проверки всех фикстур. Живёт в файле, а не в календаре машины.
VALIDATION_DATE = EXPECTATIONS["validationDate"]


class Run:
    """Результат запуска CLI: код выхода и оба потока."""

    def __init__(self, code: int, stdout: str, stderr: str) -> None:
        self.code = code
        self.stdout = stdout
        self.stderr = stderr

    @property
    def codes(self) -> list[str]:
        """Коды находок в порядке вывода — это и есть контракт."""
        return [finding["code"] for finding in json.loads(self.stdout)["findings"]]

    @property
    def findings(self) -> list[dict]:
        return json.loads(self.stdout)["findings"]


def run_cli(*args: str, validation_date: str | None = VALIDATION_DATE, json_format: bool = True) -> Run:
    """Запустить валидатор в этом же процессе и вернуть код выхода и вывод."""
    argv = list(args)
    if validation_date is not None:
        argv += ["--validation-date", validation_date]
    if json_format:
        argv += ["--format", "json"]
    out, err = io.StringIO(), io.StringIO()
    code = cli.main(argv, out=out, err=err)
    return Run(code, out.getvalue(), err.getvalue())


def fixture(name: str) -> str:
    return str(FIXTURES_DIR / name)


def dump(document: object) -> bytes:
    """Та же нормализация, что у `rebuild.py`: 2 пробела, кириллица как есть, один `\\n`."""
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


_PACK_COUNTER = {"n": 0}


def build_pack(tmp_path: Path, base: str = "valid-minimal", mutate=None, sync: bool = True) -> Path:
    """Скопировать фикстуру во временный каталог и применить точечную правку.

    Помощник существует ради **граничных** проверок (ровно 2 года, ровно 3,00 %,
    все пять отложенных ``sortKey``): держать отдельный каталог фикстур на каждое
    такое значение значило бы утопить полсотни осмысленных негативов в шуме.

    :param mutate: функция ``dict[str, dict] -> None`` над разобранными документами
        пакета (ключи — имена файлов);
    :param sync: пересчитать ли производные значения манифеста. ``False`` нужен
        тестам, которые как раз проверяют их расхождение.
    """
    _PACK_COUNTER["n"] += 1
    destination = tmp_path / f"pack-{_PACK_COUNTER['n']}"
    shutil.copytree(FIXTURES_DIR / base, destination)
    if mutate is not None:
        documents = {
            path.name: json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(destination.iterdir())
            if path.suffix == ".json"
        }
        mutate(documents)
        for name, document in documents.items():
            (destination / name).write_bytes(dump(document))
        if sync:
            cli.sync_manifest(destination, out=io.StringIO())
    return destination


def set_values(
    documents: dict, index: int, sort_key: str, values: list[float], direction: str = "ascending"
) -> None:
    """Заменить значения головоломки и пересчитать её ``correctOrder``.

    Пересчёт обязателен: иначе граничный тест правила 8 падал бы заодно по правилу 6
    и перестал бы проверять именно разрыв.
    """
    puzzle = documents[PUZZLES]["puzzles"][index]
    puzzle["sortKey"] = sort_key
    puzzle["sortDirection"] = direction
    for card, value in zip(puzzle["cards"], values):
        card["sortValue"] = value
        card["displayValue"] = f"{value:g}"
    order = sorted(range(4), key=lambda i: values[i], reverse=direction == "descending")
    puzzle["correctOrder"] = [CARD_IDS[i] for i in order]


@pytest.fixture
def pack(tmp_path):
    """Фабрика временных пакетов: ``pack(mutate, base=..., sync=...)``."""

    def factory(mutate=None, base: str = "valid-minimal", sync: bool = True) -> str:
        return str(build_pack(tmp_path, base=base, mutate=mutate, sync=sync))

    return factory


__all__ = [
    "CARD_IDS",
    "EXPECTATIONS",
    "FIXTURES_DIR",
    "MANIFEST",
    "PUZZLES",
    "SETS",
    "TOOL_DIR",
    "VALIDATION_DATE",
    "build_pack",
    "cli",
    "diag",
    "dump",
    "fixture",
    "run_cli",
    "set_values",
]
