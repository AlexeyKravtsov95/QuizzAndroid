"""JSON Schema Draft 2020-12 и отображение её ошибок в стабильные коды.

Схема — контракт для автора: ``additionalProperties: false`` везде, явный ``type``,
границы у каждой строки, числа и массива. Опечатка вроде ``sourceId`` вместо
``sourceIds`` не имеет других способов быть пойманной (I4-D9).

**Почему у части ошибок схемы собственные коды.** `CONTENT_MODEL.md` §8 называет
отдельными правилами «нет ``correctOrder``» (2), «текст вне лимитов» (3), «нет
``verifiedAt``/``verifiedBy``» (4), «пустой ``sourceIds``» (11) и «у источника нет
локатора» (17). Все пять выражаются схемой полностью, поэтому реализованы схемой —
но обязаны называться своими кодами, а не общим ``R01``. Отображение выполняется
здесь, по ключевому слову и указателю нарушения (ITERATION_4_DESIGN.md §6.2, §7.3).

``FormatChecker`` включается **явно**: в Draft 2020-12 ``format`` по умолчанию —
аннотация, и без этого ``"format": "date"`` не проверялся бы вовсе (§5.2, I4-A33).
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Iterable

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError, ValidationError

from . import diagnostics as diag
from .loader import ToolError

SCHEMA_DIR = Path(__file__).resolve().parent.parent / "schema"

#: Имя файла пакета, к которому применяется подавление находок схемы над `files`.
MANIFEST_FILE_NAME = "manifest.json"

MANIFEST_SCHEMA = "manifest.schema.json"
PUZZLES_SCHEMA = "puzzles.schema.json"
DAILY_SETS_SCHEMA = "daily-sets.schema.json"

_VALIDATOR_CACHE: dict[str, Draft202012Validator] = {}


def validator_for(schema_file: str) -> Draft202012Validator:
    """Собрать (и закешировать) валидатор одной схемы.

    :raises ToolError: схема отсутствует, не разбирается или недопустима как схема —
        это поломка инструмента, а не контента, поэтому exit 2.
    """
    cached = _VALIDATOR_CACHE.get(schema_file)
    if cached is not None:
        return cached

    path = SCHEMA_DIR / schema_file
    try:
        schema = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        raise ToolError(f"схема не читается: {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise ToolError(f"схема не разбирается как JSON: {path}: {error}") from error

    try:
        Draft202012Validator.check_schema(schema)
    except SchemaError as error:
        raise ToolError(f"схема недопустима: {path}: {error.message}") from error

    # format_checker включён явно — иначе "format": "date" остался бы аннотацией.
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    _VALIDATOR_CACHE[schema_file] = validator
    return validator


def _pointer(error: ValidationError) -> str:
    """JSON pointer места нарушения по абсолютному пути ошибки."""
    parts = []
    for part in error.absolute_path:
        text = str(part)
        parts.append(text.replace("~", "~0").replace("/", "~1"))
    return "/" + "/".join(parts) if parts else ""


def _missing_property(error: ValidationError) -> str | None:
    """Имя обязательного поля, если ошибка — нарушение ``required``."""
    if error.validator != "required":
        return None
    message = error.message
    if message.startswith("'") and "' is a required property" in message:
        return message.split("'", 2)[1]
    return None


def _code_for(error: ValidationError) -> str:
    """Отобразить одну ошибку схемы в стабильный код.

    Порядок разбора — от специализированного к общему; всё, что не опознано,
    остаётся ``R01_SCHEMA``.
    """
    path = list(error.absolute_path)
    missing = _missing_property(error)

    # Правило 2: отсутствует correctOrder.
    if missing == "correctOrder":
        return diag.R02_CORRECT_ORDER_MISSING

    # Правило 4: нет verifiedAt или verifiedBy.
    if missing in ("verifiedAt", "verifiedBy"):
        return diag.R04_VERIFICATION_MISSING

    # Правило 11: у карточки нет sourceIds или он пуст.
    if missing == "sourceIds":
        return diag.R11_SOURCE_IDS_EMPTY
    if error.validator == "minItems" and path and path[-1] == "sourceIds":
        return diag.R11_SOURCE_IDS_EMPTY

    # Правило 17: у источника нет ни url, ни reference, либо нет accessedAt.
    in_sources = "sources" in [str(part) for part in path]
    if missing == "accessedAt":
        return diag.R17_SOURCE_LOCATOR_MISSING
    if error.validator == "anyOf" and in_sources:
        return diag.R17_SOURCE_LOCATOR_MISSING

    # Правило 4A: `format: date` включён явно, и его отказ — это ровно «такой даты
    # не существует». Называть это общим R01 значило бы прятать календарную ошибку
    # за формальным кодом; семантический слой (§5.2) объявит тот же код на том же
    # указателе, и дубль схлопнется дедупликацией.
    if error.validator == "format" and error.validator_value == "date":
        return diag.R04A_DATE_NOT_CALENDAR

    # Правило 3: текстовое поле вне лимитов длины.
    if error.validator in ("minLength", "maxLength"):
        return diag.R03_TEXT_LENGTH

    return diag.R01_SCHEMA


_FILES_ELEMENT_POINTER = re.compile(r"^/files/\d+$")
_FILES_PATH_POINTER = re.compile(r"^/files/\d+/path$")
_FILES_SHA_POINTER = re.compile(r"^/files/\d+/sha256$")


def _suppressed(file_name: str, pointer: str, error: ValidationError) -> bool:
    """Находки схемы, владельцем которых является специализированное правило.

    Подавление **точечное**, а не по всему поддереву ``/files``: правила ``M03`` и
    ``M06`` владеют конкретными вопросами, а не массивом целиком. ``M03`` отвечает за
    состав списка (сколько элементов, уникальны ли) и за имя файла; ``M06`` — за
    **значение** хеша, включая регистр. Всё остальное внутри ``/files`` — форма
    документа, и её владелец — схема:

    * элемент без обязательного ``sha256`` — нарушение ``required``;
    * ``sha256`` не строкой — нарушение ``type``;
    * лишнее поле в элементе — нарушение ``additionalProperties: false``.

    Ни один из этих трёх случаев ``M03`` и ``M06`` не ловят: ``M03`` смотрит только
    на ``path``, а ``M06`` сравнивает хеш лишь тогда, когда тот уже является строкой.
    Подавление по всему поддереву пропускало бы их с кодом выхода 0 — то есть строгая
    схема манифеста не действовала бы вовсе.

    Обратная ошибка так же реальна: там, где ``M03`` **отвечает** сам — «files не
    массив», «элемент не объект», «у элемента нет ``path``», — находка схемы обязана
    молчать, иначе одна причина выдаёт два кода.
    """
    if file_name != MANIFEST_FILE_NAME:
        return False

    validator = str(error.validator)

    if pointer == "/files":
        # Сам массив: «это вообще массив», сколько в нём элементов и уникальны ли
        # они — вопросы M03, и он отвечает на них своими сообщениями.
        return validator in ("type", "minItems", "maxItems", "uniqueItems")

    if _FILES_ELEMENT_POINTER.match(pointer):
        # «Элемент обязан быть объектом» и «у элемента обязан быть path» — тоже M03:
        # без пути объявление файла бессмысленно, и именно это M03 и сообщает.
        if validator == "type":
            return True
        return _missing_property(error) == "path"

    if _FILES_PATH_POINTER.match(pointer):
        # Имя файла целиком — M03 (закрытый шаблон, I4-D6).
        return True

    if _FILES_SHA_POINTER.match(pointer):
        # Значение и регистр хеша — M06; тип и наличие поля остаются за схемой.
        return validator in ("pattern", "minLength", "maxLength")

    return False



def validate_document(file_name: str, schema_file: str, data: object) -> list[diag.Finding]:
    """Проверить один разобранный документ по его схеме.

    Ошибки сортируются самим ``jsonschema`` по пути; окончательный порядок находок
    всё равно задаёт :func:`diagnostics.sort_findings`, поэтому здесь важна только
    полнота, а не последовательность.
    """
    validator = validator_for(schema_file)
    findings: list[diag.Finding] = []
    for error in validator.iter_errors(data):
        pointer = _pointer(error)
        if _suppressed(file_name, pointer, error):
            continue
        findings.append(
            diag.Finding(
                _code_for(error),
                file_name,
                pointer,
                f"{error.message} (ограничение схемы: {error.validator})",
            )
        )
    return findings


def has_schema_error(findings: Iterable[diag.Finding], file_name: str) -> bool:
    """Есть ли у файла хоть одна находка уровня схемы.

    Семантические правила пропускают такой файл целиком: считать разрывы и порядок
    у документа, который не собрался по форме, — значит выдавать каскад мусора
    вместо одной точной находки.
    """
    schema_codes = {
        diag.R01_SCHEMA,
        diag.R02_CORRECT_ORDER_MISSING,
        diag.R03_TEXT_LENGTH,
        diag.R04_VERIFICATION_MISSING,
        diag.R11_SOURCE_IDS_EMPTY,
        diag.R17_SOURCE_LOCATOR_MISSING,
    }
    return any(f.file == file_name and f.code in schema_codes for f in findings)
