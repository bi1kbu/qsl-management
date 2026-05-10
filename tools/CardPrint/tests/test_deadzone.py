from __future__ import annotations

import pytest

from cardprint.core.errors import CardPrintError
from cardprint.core.layout_engine import validate_preset_layout
from cardprint.core.models import Preset


def build_preset(field_x: float, field_y: float) -> Preset:
    return Preset.from_dict(
        {
            "version": "1.0",
            "name": "test",
            "paper": {"name": "Card", "width_mm": 100, "height_mm": 50},
            "calibration": {
                "rotation_direction": "right",
                "rotation_degree": 90,
                "origin_offset_mm": {"x": 0, "y": 0},
                "deadzone_mm": {"top": 3, "right": 4, "bottom": 5, "left": 6},
            },
            "fields": [
                {
                    "key": "name",
                    "label_zh": "姓名",
                    "x_mm": field_x,
                    "y_mm": field_y,
                    "font_family": "SimSun",
                    "font_size_pt": 11,
                    "bold": False,
                    "italic": False,
                    "max_len": 20,
                }
            ],
            "ui_schema": [],
        }
    )


def test_field_inside_printable_area() -> None:
    preset = build_preset(20, 20)
    validate_preset_layout(preset)


def test_field_in_deadzone_raises() -> None:
    preset = build_preset(2, 2)
    with pytest.raises(CardPrintError) as exc_info:
        validate_preset_layout(preset)
    assert exc_info.value.code == "FIELD_IN_DEADZONE"
