from __future__ import annotations

from typing import Any, Protocol


class JsonDataSourcePlugin(Protocol):
    def name(self) -> str:
        ...

    def fetch(self, query: dict[str, Any]) -> list[dict[str, Any]]:
        ...
