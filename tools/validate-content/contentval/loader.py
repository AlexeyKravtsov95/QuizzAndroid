"""Чтение пакета с диска: байты, кодировка, разбор JSON.

Загрузчик отвечает за «что лежит в каталоге», а не за «правильно ли это по существу».
Всё, что он умеет объявить сам, — это M05 (JSON не разбирается) и M09 (кодировка);
остальные коды принадлежат модулям правил.

Сеть не используется нигде и никогда: инструмент читает только переданный локальный
каталог (ITERATION_4_DESIGN.md §7.1).
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import diagnostics as diag

#: Имя манифеста — константа кода, а не значение из данных (I4-D6).
MANIFEST_NAME = "manifest.json"

#: Закрытый шаблон имени контентного файла. `manifest.json` ему НЕ соответствует,
#: и это намеренно: манифест не объявляет сам себя.
CONTENT_FILE_NAME = re.compile(r"^(puzzles|daily-sets)-[0-9]{3}\.json$")

#: Префиксы двух обязательных типов файлов, в порядке отображения диагностик.
FILE_PREFIXES = ("puzzles-", "daily-sets-")

_UTF8_BOM = b"\xef\xbb\xbf"


class ToolError(Exception):
    """Инструмент не смог выполнить проверку → exit 2.

    Это не «контент плохой», а «валидатор сломан или его неправильно позвали»:
    каталога нет, нет прав, повреждена схема. Разделение обязательно, иначе в CI
    сломанный инструмент выглядит как испорченный контент (ITERATION_4_DESIGN.md §7.6).
    """


@dataclass
class LoadedFile:
    """Один физический файл пакета."""

    name: str
    raw: bytes
    #: Разобранный JSON или ``None``, если разбор не удался (тогда есть находка M05/M09).
    data: object | None = None
    #: ``True``, если файл прочитан и разобран — правила могут на него опираться.
    parsed: bool = False

    @property
    def sha256(self) -> str:
        """SHA-256 от **точных байтов** файла, 64 строчных hex-символа."""
        return hashlib.sha256(self.raw).hexdigest()


@dataclass
class PackDirectory:
    """Содержимое каталога пакета до применения правил."""

    root: Path
    #: Имена всех физических записей каталога (файлы и подкаталоги), отсортированы.
    entries: list[str]
    files: dict[str, LoadedFile] = field(default_factory=dict)
    findings: list[diag.Finding] = field(default_factory=list)

    def get(self, name: str) -> LoadedFile | None:
        return self.files.get(name)


def _check_encoding(name: str, raw: bytes) -> tuple[list[diag.Finding], str | None]:
    """Проверить UTF-8 без BOM и ровно один завершающий перевод строки (M09).

    Возвращает находки и декодированный текст (``None``, если байты не UTF-8 —
    тогда разбирать нечего). BOM, если он есть, отрезается после объявления находки:
    иначе одна ошибка кодировки дала бы ещё и каскадный M05.
    """
    findings: list[diag.Finding] = []
    body = raw

    if body.startswith(_UTF8_BOM):
        findings.append(
            diag.Finding(
                diag.M09_ENCODING,
                name,
                "",
                "файл начинается с BOM: он запрещён — ломает разбор и меняет sha256",
            )
        )
        body = body[len(_UTF8_BOM) :]

    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as error:
        findings.append(
            diag.Finding(
                diag.M09_ENCODING,
                name,
                "",
                f"байты не являются корректным UTF-8: {error}",
            )
        )
        return findings, None

    if not raw:
        findings.append(diag.Finding(diag.M09_ENCODING, name, "", "файл пуст"))
    elif not text.endswith("\n"):
        findings.append(
            diag.Finding(
                diag.M09_ENCODING,
                name,
                "",
                "файл не заканчивается переводом строки: без фиксированной нормализации "
                "sha256 прыгает от смены редактора",
            )
        )
    elif text.endswith("\n\n"):
        findings.append(
            diag.Finding(
                diag.M09_ENCODING,
                name,
                "",
                "файл заканчивается более чем одним переводом строки: требуется ровно один",
            )
        )

    return findings, text


def load_pack(root: Path) -> PackDirectory:
    """Прочитать каталог пакета целиком.

    :raises ToolError: каталога нет, это не каталог или он не читается — exit 2.
    """
    try:
        if not root.exists():
            raise ToolError(f"каталог пакета не найден: {root}")
        if not root.is_dir():
            raise ToolError(f"путь пакета не является каталогом: {root}")
        entries = sorted(entry.name for entry in root.iterdir())
    except ToolError:
        raise
    except OSError as error:
        raise ToolError(f"каталог пакета не читается: {root}: {error}") from error

    pack = PackDirectory(root=root, entries=entries)

    # Читаются только *.json верхнего уровня: подкаталоги и посторонние файлы попадут
    # в M08 по списку `entries`, но открывать их незачем.
    for name in entries:
        path = root / name
        if not path.is_file() or not name.endswith(".json"):
            continue
        try:
            raw = path.read_bytes()
        except OSError as error:
            raise ToolError(f"файл пакета не читается: {path}: {error}") from error

        loaded = LoadedFile(name=name, raw=raw)
        encoding_findings, text = _check_encoding(name, raw)
        pack.findings.extend(encoding_findings)

        if text is not None:
            try:
                loaded.data = json.loads(text)
                loaded.parsed = True
            except json.JSONDecodeError as error:
                pack.findings.append(
                    diag.Finding(
                        diag.M05_MALFORMED_JSON,
                        name,
                        "",
                        f"файл не разбирается как JSON: строка {error.lineno}, "
                        f"столбец {error.colno}: {error.msg}",
                    )
                )
        pack.files[name] = loaded

    return pack
