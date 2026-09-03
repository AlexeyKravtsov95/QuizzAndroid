"""Автономный валидатор контентного пакета «По порядку!» (ITERATION_4_DESIGN.md, PR 4A).

Пакет намеренно не зависит ни от чего, кроме стандартной библиотеки и `jsonschema`:
это авторский шлюз, который должен запускаться из любого каталога и не требовать ни
Gradle, ни Android SDK, ни сети (I4-D8).

Модули разведены по уровням правил (ITERATION_4_DESIGN.md §6.1):

* ``loader``          — чтение байтов пакета, кодировка, разбор JSON;
* ``diagnostics``     — находка, коды, детерминированная сортировка и вывод;
* ``units``           — таблица «sortKey → единица → правило разрыва» (I4-D24);
* ``shuffle``         — порт ``DeterministicShuffler`` (ADR-010) для правила 10;
* ``rules_schema``    — JSON Schema Draft 2020-12 и отображение её ошибок в коды;
* ``rules_manifest``  — свойства пакета и байтов (M01–M10, R21);
* ``rules_semantic``  — правила одной головоломки (R04A–R10, R12–R16, R18C);
* ``rules_crossfile`` — ссылки и компоновка наборов (R18–R20D);
* ``cli``             — разбор аргументов, exit-коды, ``--sync-manifest``.
"""

__all__ = [
    "cli",
    "diagnostics",
    "loader",
    "rules_crossfile",
    "rules_manifest",
    "rules_schema",
    "rules_semantic",
    "shuffle",
    "units",
]
