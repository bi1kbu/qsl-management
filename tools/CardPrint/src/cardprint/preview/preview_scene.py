from __future__ import annotations

from typing import Any

from cardprint.core.layout_engine import build_layout_items, printable_bounds
from cardprint.core.models import Preset


def build_preview_scene(preset: Preset, row: dict[str, Any]) -> dict[str, Any]:
    items = [item.to_dict() for item in build_layout_items(preset, row)]
    bounds = printable_bounds(preset.paper, preset.calibration.deadzone_mm)
    fields = [
        {
            "key": field.key,
            "label_zh": field.label_zh,
            "x_mm": float(field.x_mm),
            "y_mm": float(field.y_mm),
            "print_width_mm": float(field.print_width_mm),
            "print_height_mm": float(field.print_height_mm),
            "font_size_pt": int(field.font_size_pt),
            "distribute_align": bool(field.distribute_align),
            "sample_text": str(row.get(field.key, "")),
        }
        for field in preset.fields
    ]
    return {
        "paper_mm": {
            "width": preset.paper.width_mm,
            "height": preset.paper.height_mm,
            "name": preset.paper.name,
        },
        "deadzone_mm": {
            "top": preset.calibration.deadzone_mm.top,
            "right": preset.calibration.deadzone_mm.right,
            "bottom": preset.calibration.deadzone_mm.bottom,
            "left": preset.calibration.deadzone_mm.left,
        },
        "printable_bounds_mm": {
            "left": bounds.left,
            "top": bounds.top,
            "right": bounds.right,
            "bottom": bounds.bottom,
        },
        "fields": fields,
        "items": items,
    }
