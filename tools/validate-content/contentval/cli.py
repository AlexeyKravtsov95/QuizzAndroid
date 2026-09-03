"""Точка входа валидатора: аргументы, конвейер правил, коды выхода, ``--sync-manifest``.

**Единственное место во всём инструменте, где появляется системная дата** — значение
по умолчанию для ``--validation-date``. Дальше дата передаётся правилам аргументом:
бизнес-правило, читающее календарь машины, невоспроизводимо, а тест на нём — таймер
(ITERATION_4_DESIGN.md §5.2).

**Коды выхода** (§7.6):

* ``0`` — нарушений нет;
* ``1`` — пакет прочитан, найдены нарушения;
* ``2`` — инструмент не смог выполнить проверку: каталога нет, нет прав, повреждена
  схема, внутренняя ошибка. Без этого разделения «контент плохой» и «валидатор
  сломан» выглядят в CI одинаково, и второе чинят как первое.

Ожидаемая пользовательская ошибка печатается одной строкой и **никогда** не печатает
traceback: traceback в CI читается как поломка инструмента.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path
from typing import Sequence

from . import diagnostics as diag
from . import rules_crossfile, rules_manifest, rules_schema, rules_semantic
from .loader import CONTENT_FILE_NAME, MANIFEST_NAME, PackDirectory, ToolError, load_pack

EXIT_OK = 0
EXIT_VIOLATIONS = 1
EXIT_TOOL_ERROR = 2


def _validation_date(value: str) -> date:
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            f"--validation-date ожидает дату вида YYYY-MM-DD, получено {value!r}"
        ) from error
    if parsed.isoformat() != value:
        raise argparse.ArgumentTypeError(
            f"--validation-date ожидает дату вида YYYY-MM-DD, получено {value!r}"
        )
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="validate.py",
        description=(
            "Валидатор контентного пакета «По порядку!»: JSON Schema Draft 2020-12, "
            "21 правило CONTENT_MODEL.md §8 и правила уровня пакета M01–M10. "
            "Сети не использует, читает только переданный каталог."
        ),
    )
    parser.add_argument("pack", help="каталог пакета (manifest.json + два контентных файла)")
    parser.add_argument(
        "--validation-date",
        type=_validation_date,
        default=None,
        metavar="YYYY-MM-DD",
        help=(
            "дата, относительно которой ISO-даты контента считаются будущими (R04B). "
            "По умолчанию — сегодняшняя; тесты всегда передают фиксированное значение"
        ),
    )
    parser.add_argument(
        "--format",
        choices=("human", "json"),
        default="human",
        help="формат вывода находок; порядок находок в обоих одинаков",
    )
    parser.add_argument(
        "--sync-manifest",
        action="store_true",
        help=(
            "пересчитать sha256, setCount и puzzleCount в манифесте и записать их. "
            "Ничего другого не меняет и контентные файлы не трогает"
        ),
    )
    parser.add_argument(
        "--expect-sets",
        type=int,
        default=None,
        metavar="N",
        help="критерий объёма: ожидаемое число наборов (M10)",
    )
    parser.add_argument(
        "--expect-puzzles",
        type=int,
        default=None,
        metavar="N",
        help="критерий объёма: ожидаемое число головоломок (M10)",
    )
    return parser


def validate_pack(
    root: Path,
    validation_date: date,
    expect_sets: int | None = None,
    expect_puzzles: int | None = None,
) -> list[diag.Finding]:
    """Прогнать все правила по каталогу пакета и вернуть отсортированные находки.

    :raises ToolError: инструмент не смог выполнить проверку (exit 2).
    """
    pack = load_pack(root)
    findings: list[diag.Finding] = list(pack.findings)

    manifest_findings, facts, manifest_usable = rules_manifest.check_manifest(pack)
    findings.extend(manifest_findings)

    if not manifest_usable:
        return diag.sort_findings(findings)

    findings.extend(rules_manifest.check_envelopes(pack, facts))

    puzzles_document = _document(pack, facts.puzzles_file)
    sets_document = _document(pack, facts.daily_sets_file)

    # --- Схема контентных файлов --------------------------------------------
    if puzzles_document is not None:
        findings.extend(
            rules_schema.validate_document(
                facts.puzzles_file, rules_schema.PUZZLES_SCHEMA, puzzles_document
            )
        )
    if sets_document is not None:
        findings.extend(
            rules_schema.validate_document(
                facts.daily_sets_file, rules_schema.DAILY_SETS_SCHEMA, sets_document
            )
        )

    puzzles_ok = puzzles_document is not None and not rules_schema.has_schema_error(
        findings, facts.puzzles_file
    )
    sets_ok = sets_document is not None and not rules_schema.has_schema_error(
        findings, facts.daily_sets_file
    )

    # --- Семантика одной головоломки ----------------------------------------
    if puzzles_ok:
        findings.extend(
            rules_semantic.check_puzzle_file(
                facts.puzzles_file, puzzles_document, facts.content_version, validation_date
            )
        )

    # --- Ссылки и компоновка наборов ----------------------------------------
    if puzzles_ok and sets_ok:
        puzzles_by_id = rules_semantic.index_by_id(rules_semantic.collect_puzzles(puzzles_document))
        findings.extend(
            rules_crossfile.check_sets(
                facts.daily_sets_file, sets_document, puzzles_by_id, facts.puzzles_file
            )
        )

    # --- Счётчики манифеста и критерий объёма --------------------------------
    actual_puzzles = _count(puzzles_document, "puzzles")
    actual_sets = _count(sets_document, "sets")
    if puzzles_document is not None and sets_document is not None:
        findings.extend(rules_manifest.check_counts(facts, actual_puzzles, actual_sets))
        findings.extend(
            rules_manifest.check_expected_volume(
                actual_sets, actual_puzzles, expect_sets, expect_puzzles
            )
        )

    return diag.sort_findings(findings)


def _document(pack: PackDirectory, name: str | None) -> dict | None:
    if name is None:
        return None
    loaded = pack.get(name)
    if loaded is None or not loaded.parsed or not isinstance(loaded.data, dict):
        return None
    return loaded.data


def _count(document: dict | None, field: str) -> int:
    if document is None:
        return 0
    value = document.get(field)
    return len(value) if isinstance(value, list) else 0


# --- Режим --sync-manifest --------------------------------------------------


def _readable(root: Path, name: object, out) -> Path | None:
    """Путь к контентному файлу пакета — или ``None``, если имя недопустимо.

    **Тот же закрытый allow-list, что у правила M03, и до любого обращения к диску.**
    Режим синхронизации получает имена из данных, а не из кода, поэтому обязан
    проверять их сам: обычная валидация объявит `M03` только после того, как файл
    уже прочитан, а «прочитали, но потом сообщили» защитой не является. Без этой
    проверки `../secret.json` или абсолютный путь в `files[].path` заставляли бы
    инструмент прочитать файл за пределами каталога пакета и записать его sha256
    в манифест (I4-D6).
    """
    if not isinstance(name, str) or not CONTENT_FILE_NAME.match(name):
        print(
            f"пропущено: недопустимое имя файла {name!r} — синхронизация не обращается "
            "к диску по именам вне закрытого шаблона; нарушение объявит M03",
            file=out,
        )
        return None
    return root / name


def _replace_value(text: str, field: str, old: object, new: object) -> tuple[str, bool]:
    """Заменить ровно одно значение, сохранив форматирование манифеста.

    Пробелы вокруг двоеточия в шаблоне не фиксируются: минифицированный
    ``"sha256":"…"`` — такой же валидный JSON, как ``"sha256": "…"``, и подстановка
    по точной строке молча не срабатывала бы, а вызывающий всё равно отчитался бы
    об изменении. Возвращаемый признак говорит, произошла ли замена **на самом деле**.
    """
    quoted = '"' if isinstance(old, str) else ""
    pattern = re.compile(
        rf'("{re.escape(field)}"\s*:\s*){quoted}{re.escape(str(old))}{quoted}(?=\s*[,}}\n])'
    )
    replacement = rf"\g<1>{quoted}{new}{quoted}"
    updated, count = pattern.subn(replacement, text, count=1)
    return updated, count == 1


def sync_manifest(root: Path, out=sys.stdout) -> int:
    """Пересчитать производные значения манифеста и записать их.

    Меняются **только** ``files[].sha256``, ``setCount`` и ``puzzleCount``.
    ``contentVersion``, ``schemaVersion``, ``packId``, имена файлов и содержимое
    контентных файлов не трогаются никогда: производные данные, поддерживаемые
    руками через пять батчей, — гарантированный источник шума, а всё остальное —
    осознанные решения автора (ITERATION_4_DESIGN.md §4.7).

    Правка выполняется **текстовой заменой** конкретных значений, а не
    пересериализацией документа: так форматирование манифеста, порядок ключей и
    комментарии автора остаются ровно такими, какими он их написал, и diff в PR
    показывает только то, что действительно изменилось.

    :returns: число изменённых значений.
    :raises ToolError: манифест отсутствует, не разбирается или не той формы.
    """
    manifest_path = root / MANIFEST_NAME
    if not manifest_path.is_file():
        raise ToolError(f"нечего синхронизировать: нет {manifest_path}")

    try:
        text = manifest_path.read_text(encoding="utf-8")
    except OSError as error:
        raise ToolError(f"манифест не читается: {manifest_path}: {error}") from error
    try:
        manifest = json.loads(text)
    except json.JSONDecodeError as error:
        raise ToolError(
            f"манифест не разбирается как JSON, синхронизация невозможна: {error}"
        ) from error
    if not isinstance(manifest, dict) or not isinstance(manifest.get("files"), list):
        raise ToolError("манифест не той формы: ожидается объект с массивом files")

    changes = 0

    for entry in manifest["files"]:
        if not isinstance(entry, dict):
            continue
        name = entry.get("path")
        recorded = entry.get("sha256")
        target = _readable(root, name, out)
        if target is None:
            continue
        if not isinstance(recorded, str):
            print(
                f"пропущено: у {name} нет строкового sha256, пересчитывать нечего "
                "(нарушение объявит схема манифеста)",
                file=out,
            )
            continue
        if not target.is_file():
            print(f"пропущено: файла {name} нет в каталоге, sha256 не пересчитан", file=out)
            continue

        actual = hashlib.sha256(target.read_bytes()).hexdigest()
        if actual != recorded:
            text, replaced = _replace_value(text, "sha256", recorded, actual)
            if replaced:
                print(f"sha256 {name}: {recorded} → {actual}", file=out)
                changes += 1
            else:
                print(
                    f"не удалось обновить sha256 {name}: значение {recorded!r} не найдено "
                    "в тексте манифеста — синхронизация ничего не изменила",
                    file=out,
                )

    counts = {
        "puzzleCount": _count_in_file(root, manifest, "puzzles-", "puzzles"),
        "setCount": _count_in_file(root, manifest, "daily-sets-", "sets"),
    }
    for field, actual in counts.items():
        if actual is None:
            continue
        recorded = manifest.get(field)
        if isinstance(recorded, int) and not isinstance(recorded, bool) and recorded != actual:
            text, replaced = _replace_value(text, field, recorded, actual)
            if replaced:
                print(f"{field}: {recorded} → {actual}", file=out)
                changes += 1
            else:
                print(
                    f"не удалось обновить {field}: значение {recorded} не найдено в тексте "
                    "манифеста — синхронизация ничего не изменила",
                    file=out,
                )

    if changes:
        manifest_path.write_text(text, encoding="utf-8")
    else:
        print("манифест уже синхронизирован: изменять нечего", file=out)
    return changes


def _count_in_file(root: Path, manifest: dict, prefix: str, field: str) -> int | None:
    """Фактическое число элементов в контентном файле пакета.

    Имя файла проходит тот же allow-list, что и в :func:`_readable`: это второй
    проход синхронизации, и он обращается к диску по данным из манифеста ровно так же.
    """
    for entry in manifest.get("files", []):
        if not isinstance(entry, dict):
            continue
        name = entry.get("path")
        if not isinstance(name, str) or not name.startswith(prefix):
            continue
        if not CONTENT_FILE_NAME.match(name):
            return None
        target = root / name
        if not target.is_file():
            return None
        try:
            document = json.loads(target.read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError):
            return None
        value = document.get(field) if isinstance(document, dict) else None
        return len(value) if isinstance(value, list) else None
    return None


def main(argv: Sequence[str] | None = None, out=None, err=None) -> int:
    """Разобрать аргументы, выполнить проверку, вернуть код выхода."""
    out = out if out is not None else sys.stdout
    err = err if err is not None else sys.stderr

    parser = build_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)

    # Единственное обращение к системному календарю во всём инструменте.
    validation_date = args.validation_date or date.today()
    root = Path(args.pack)

    try:
        if args.sync_manifest:
            # При `--format json` отчёт синхронизации уходит в stderr: stdout обязан
            # оставаться разбираемым целиком, иначе машиночитаемый вывод перестаёт
            # быть машиночитаемым ровно тогда, когда его смешали с человеческим.
            sync_manifest(root, out=err if args.format == "json" else out)
        findings = validate_pack(
            root,
            validation_date=validation_date,
            expect_sets=args.expect_sets,
            expect_puzzles=args.expect_puzzles,
        )
    except ToolError as error:
        # Ожидаемая пользовательская ошибка: одна строка, без traceback.
        print(f"validate.py: {error}", file=err)
        return EXIT_TOOL_ERROR
    except Exception as error:  # noqa: BLE001 — граница процесса: exit 2, а не traceback
        print(f"validate.py: внутренняя ошибка инструмента: {error!r}", file=err)
        return EXIT_TOOL_ERROR

    if args.format == "json":
        print(diag.format_json(findings), file=out)
    elif findings:
        print(diag.format_human(findings), file=out)

    if not findings:
        if args.format == "human":
            print(f"нарушений нет: {root}", file=out)
        return EXIT_OK
    return EXIT_VIOLATIONS
