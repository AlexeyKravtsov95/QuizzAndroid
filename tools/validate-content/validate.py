#!/usr/bin/env python3
"""Валидатор контентного пакета «По порядку!» — точка входа.

    python3 tools/validate-content/validate.py <pack-directory>

Коды выхода: 0 — нарушений нет, 1 — пакет прочитан и найдены нарушения, 2 —
инструмент не смог выполнить проверку. Подробности — `README.md` и
`docs/ITERATION_4_DESIGN.md` §7.6.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Скрипт обязан запускаться из любого рабочего каталога: путь к пакету `contentval`
# берётся от расположения файла, а не от cwd и не от PYTHONPATH.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from contentval.cli import main  # noqa: E402  — только после правки sys.path

if __name__ == "__main__":
    raise SystemExit(main())
