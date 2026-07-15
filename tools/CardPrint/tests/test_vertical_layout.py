from __future__ import annotations

from cardprint.core.layout_engine import build_layout_items
from cardprint.core.models import Preset


def _preset(
    *,
    layout_mode: str,
    width_mm: float = 30.0,
    height_mm: float = 30.0,
    text_align: str = "left",
    distribute_align: bool = False,
    print_border: bool = False,
) -> Preset:
    return Preset.from_dict(
        {
            "version": "1.0",
            "name": "vertical-layout-test",
            "paper": {"name": "Card", "width_mm": 100, "height_mm": 60},
            "calibration": {
                "rotation_direction": "right",
                "rotation_degree": 90,
                "origin_offset_mm": {"x": 0, "y": 0},
                "deadzone_mm": {"top": 0, "right": 0, "bottom": 0, "left": 0},
            },
            "fields": [
                {
                    "key": "text",
                    "label_zh": "文字",
                    "x_mm": 10,
                    "y_mm": 10,
                    "print_width_mm": width_mm,
                    "print_height_mm": height_mm,
                    "print_border": print_border,
                    "font_family": "SimSun",
                    "font_size_pt": 11,
                    "text_align": text_align,
                    "layout_mode": layout_mode,
                    "distribute_align": distribute_align,
                }
            ],
            "ui_schema": [],
        }
    )


def test_vertical_layout_rotates_every_glyph_and_forced_break_moves_left() -> None:
    preset = _preset(layout_mode="vertical")
    items = build_layout_items(preset, {"text": "甲乙/n丙"})

    assert [item.text for item in items] == ["甲", "乙", "丙"]
    assert [item.glyph_rotation_degree for item in items] == [90, 90, 90]
    assert items[1].logical_y_mm > items[0].logical_y_mm
    assert items[2].column_index == 1
    assert items[2].logical_x_mm < items[0].logical_x_mm
    assert items[2].logical_y_mm == items[0].logical_y_mm


def test_mixed_vertical_only_rotates_ascii_letters_and_digits() -> None:
    preset = _preset(layout_mode="mixed_vertical")
    items = build_layout_items(preset, {"text": "中A1。b"})

    assert [item.text for item in items] == ["中", "A", "1", "。", "b"]
    assert [item.glyph_rotation_degree for item in items] == [0, 90, 90, 0, 90]


def test_rotated_ascii_glyphs_use_compact_width_based_spacing() -> None:
    preset = _preset(layout_mode="mixed_vertical")
    items = build_layout_items(preset, {"text": "AB"})

    vertical_gap = items[1].logical_y_mm - items[0].logical_y_mm
    assert vertical_gap < items[0].line_height_mm
    assert items[0].cell_height_mm < items[0].line_height_mm


def test_vertical_height_overflow_automatically_moves_left() -> None:
    preset = _preset(layout_mode="vertical", height_mm=4.0)
    items = build_layout_items(preset, {"text": "甲乙丙"})

    assert [item.column_index for item in items] == [0, 1, 2]
    assert items[1].logical_x_mm < items[0].logical_x_mm
    assert items[2].logical_x_mm < items[1].logical_x_mm
    assert all(item.logical_y_mm == 10.0 for item in items)


def test_consecutive_forced_breaks_preserve_empty_column() -> None:
    preset = _preset(layout_mode="mixed_vertical")
    items = build_layout_items(preset, {"text": "甲/n/n乙"})

    assert [item.text for item in items] == ["甲", "", "乙"]
    assert [item.column_index for item in items] == [0, 1, 2]


def test_vertical_width_limits_column_count() -> None:
    preset = _preset(layout_mode="vertical", width_mm=4.0, height_mm=4.0)
    items = build_layout_items(preset, {"text": "甲乙丙"})

    assert [item.text for item in items] == ["甲"]


def test_vertical_center_alignment_centers_column_along_height() -> None:
    preset = _preset(layout_mode="vertical", height_mm=30.0, text_align="center")
    items = build_layout_items(preset, {"text": "甲"})

    assert len(items) == 1
    assert items[0].logical_y_mm > 10.0


def test_vertical_distribute_alignment_uses_full_height() -> None:
    preset = _preset(layout_mode="mixed_vertical", height_mm=30.0, distribute_align=True)
    items = build_layout_items(preset, {"text": "甲乙丙"})

    first_gap = items[1].logical_y_mm - items[0].logical_y_mm
    second_gap = items[2].logical_y_mm - items[1].logical_y_mm
    assert round(first_gap, 6) == round(second_gap, 6)
    assert first_gap > items[0].line_height_mm


def test_vertical_border_is_carried_once_and_uses_field_anchor() -> None:
    preset = _preset(layout_mode="vertical", print_border=True)
    items = build_layout_items(preset, {"text": "甲乙"})

    assert items[0].print_border is True
    assert items[1].print_border is False
    assert items[0].anchor_x_mm == 10.0
    assert items[0].logical_x_mm > items[0].anchor_x_mm
    assert items[0].physical_anchor_x_mm == 10.0


def test_qrcode_ignores_vertical_layout_mode() -> None:
    preset = _preset(layout_mode="vertical")
    preset.fields[0].key = "QRCODE"
    payload = "https://example.test/rp/1"
    items = build_layout_items(preset, {"QRCODE": payload})

    assert len(items) == 1
    assert items[0].layout_mode == "horizontal"
    assert items[0].glyph_rotation_degree == 0
    assert items[0].qr_payload == payload
