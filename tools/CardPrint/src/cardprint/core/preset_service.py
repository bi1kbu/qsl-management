from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .errors import CardPrintError
from .layout_engine import validate_preset_layout
from .models import Preset


def load_json(path: str | Path) -> dict[str, Any]:
    try:
        with Path(path).open("r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError as exc:
        raise CardPrintError(
            code="FILE_NOT_FOUND",
            message="文件不存在。",
            details={"path": str(path)},
        ) from exc
    except json.JSONDecodeError as exc:
        raise CardPrintError(
            code="INVALID_JSON",
            message="JSON 格式错误。",
            details={"path": str(path), "line": exc.lineno, "column": exc.colno},
        ) from exc


def dump_json(path: str | Path, payload: dict[str, Any]) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with Path(path).open("w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def load_preset(path: str | Path) -> Preset:
    data = load_json(path)
    try:
        preset = Preset.from_dict(data)
    except CardPrintError:
        raise
    except Exception as exc:
        raise CardPrintError(
            code="INVALID_PRESET_SCHEMA",
            message="预设结构不合法。",
            details={"path": str(path), "error": str(exc)},
        ) from exc
    validate_preset_layout(preset)
    return preset


def save_preset(path: str | Path, preset: Preset) -> None:
    validate_preset_layout(preset)
    dump_json(path, preset.to_dict())


def validate_preset_file(path: str | Path) -> dict[str, Any]:
    preset = load_preset(path)
    return {
        "name": preset.name,
        "version": preset.version,
        "field_count": len(preset.fields),
        "paper": preset.paper.name,
    }
