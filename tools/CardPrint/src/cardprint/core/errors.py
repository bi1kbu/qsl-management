from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class CardPrintError(Exception):
    code: str
    message: str
    details: Any = field(default_factory=dict)
    exit_code: int = 1

    def to_payload(self) -> dict[str, Any]:
        return {
            "ok": False,
            "error": {
                "code": self.code,
                "message": self.message,
                "details": self.details,
            },
        }

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"
