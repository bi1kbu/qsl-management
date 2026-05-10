from __future__ import annotations

from typing import Any

from cardprint.core.models import Preset
from cardprint.preview.preview_scene import build_preview_scene


def render_preview_payload(preset: Preset, row: dict[str, Any]) -> dict[str, Any]:
    scene = build_preview_scene(preset, row)
    return {
        "ok": True,
        "scene": scene,
    }
