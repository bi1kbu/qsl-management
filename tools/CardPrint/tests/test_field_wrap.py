from __future__ import annotations

from cardprint.core.layout_engine import build_layout_items
from cardprint.core.models import Preset


def _build_preset(print_width_mm: float, print_height_mm: float = 0.0) -> Preset:
    return Preset.from_dict(
        {
            "version": "1.0",
            "name": "wrap-test",
            "paper": {"name": "Card", "width_mm": 100, "height_mm": 60},
            "calibration": {
                "rotation_direction": "right",
                "rotation_degree": 90,
                "origin_offset_mm": {"x": 0, "y": 0},
                "deadzone_mm": {"top": 1, "right": 1, "bottom": 1, "left": 1},
            },
            "fields": [
                {
                    "key": "name",
                    "label_zh": "姓名",
                    "x_mm": 10,
                    "y_mm": 10,
                    "print_width_mm": print_width_mm,
                    "print_height_mm": print_height_mm,
                    "font_family": "SimSun",
                    "font_size_pt": 11,
                    "bold": False,
                    "italic": False,
                    "max_len": 0,
                }
            ],
            "ui_schema": [],
        }
    )


def test_wrap_generates_multi_line_items() -> None:
    preset = _build_preset(print_width_mm=12.0)
    items = build_layout_items(preset, {"name": "ABCDEFGHIJKL1234567890"})
    assert len(items) >= 2
    assert items[0].line_index == 0
    assert items[1].line_index == 1
    assert items[1].logical_y_mm > items[0].logical_y_mm


def test_no_wrap_width_keeps_single_line() -> None:
    preset = _build_preset(print_width_mm=0.0)
    items = build_layout_items(preset, {"name": "ABCDEFGHIJKL1234567890"})
    assert len(items) == 1


def test_literal_slash_n_forces_line_break_without_print_width() -> None:
    preset = _build_preset(print_width_mm=0.0)
    items = build_layout_items(preset, {"name": "第一行/n/n第三行"})

    assert [item.text for item in items] == ["第一行", "", "第三行"]
    assert [item.line_index for item in items] == [0, 1, 2]


def test_real_line_break_and_auto_wrap_can_be_combined() -> None:
    preset = _build_preset(print_width_mm=12.0)
    items = build_layout_items(preset, {"name": "ABCD\nEFGHIJKL"})

    assert items[0].text == "ABCD"
    assert "".join(item.text for item in items[1:]) == "EFGHIJKL"


def test_uppercase_slash_n_is_not_a_line_break() -> None:
    preset = _build_preset(print_width_mm=0.0)
    items = build_layout_items(preset, {"name": "第一行/N第二行"})

    assert [item.text for item in items] == ["第一行/N第二行"]


def test_qrcode_payload_does_not_interpret_slash_n_as_line_break() -> None:
    preset = _build_preset(print_width_mm=0.0)
    preset.fields[0].key = "QRCODE"
    payload = "https://example.test/n/value"
    items = build_layout_items(preset, {"QRCODE": payload})

    assert len(items) == 1
    assert items[0].qr_payload == payload


def test_max_len_ignores_forced_line_break_marker() -> None:
    preset = _build_preset(print_width_mm=0.0)
    preset.fields[0].max_len = 3
    items = build_layout_items(preset, {"name": "AB/nCD"})

    assert [item.text for item in items] == ["AB", "C"]


def test_max_len_does_not_leave_trailing_empty_line() -> None:
    preset = _build_preset(print_width_mm=0.0)
    preset.fields[0].max_len = 2
    items = build_layout_items(preset, {"name": "AB/nCD"})

    assert [item.text for item in items] == ["AB"]


def test_height_limit_applies_to_forced_lines_without_print_width() -> None:
    preset = _build_preset(print_width_mm=0.0, print_height_mm=4.0)
    items = build_layout_items(preset, {"name": "第一行/n第二行/n第三行"})

    assert [item.text for item in items] == ["第一行"]


def test_fixed_text_overrides_row_value() -> None:
    preset = _build_preset(print_width_mm=0.0)
    preset.fields[0].fixed_text = "固定辅助说明"
    items = build_layout_items(preset, {"name": "行数据"})
    assert len(items) == 1
    assert items[0].text == "固定辅助说明"


def test_digit_raise_ratio_is_carried_to_layout_item() -> None:
    preset = _build_preset(print_width_mm=0.0)
    preset.fields[0].digit_raise_ratio = 0.5
    items = build_layout_items(preset, {"name": "BI1KBU"})
    assert len(items) == 1
    assert items[0].digit_raise_ratio == 0.5


def test_print_border_is_only_carried_by_first_text_line() -> None:
    preset = _build_preset(print_width_mm=20.0, print_height_mm=20.0)
    preset.fields[0].print_border = True
    items = build_layout_items(preset, {"name": "第一行/n第二行"})

    assert len(items) == 2
    assert items[0].print_border is True
    assert items[1].print_border is False


def test_cjk_wrap_uses_fullwidth_estimation() -> None:
    preset = _build_preset(print_width_mm=89.0, print_height_mm=12.6)
    text = "测试内容" * 20
    items = build_layout_items(preset, {"name": text})
    assert len(items) == 2
    # 90mm / 11pt 约在 23~24 个中文字符换行，确保不会整段挤在第一行。
    assert len(items[0].text) <= 25


def test_height_limit_counts_last_glyph_height_not_next_line_gap() -> None:
    preset = Preset.from_dict(
        {
            "version": "1.0",
            "name": "envelope-wrap-test",
            "paper": {"name": "Envelope", "width_mm": 176, "height_mm": 125},
            "calibration": {
                "rotation_direction": "right",
                "rotation_degree": 90,
                "layout_rotation_enabled": False,
                "origin_offset_mm": {"x": 0, "y": 0},
                "deadzone_mm": {"top": 0, "right": 0, "bottom": 0, "left": 0},
            },
            "fields": [
                {
                    "key": "address",
                    "label_zh": "收件人地址",
                    "x_mm": 30,
                    "y_mm": 30,
                    "print_width_mm": 80,
                    "print_height_mm": 20,
                    "font_family": "SimSun",
                    "font_size_pt": 16,
                    "bold": False,
                    "italic": False,
                    "max_len": 0,
                }
            ],
            "ui_schema": [],
        }
    )

    text = "安徽省滁州市琅琊区安徽省滁州市琅琊新区滁阳街道银山路测试园区三号楼"
    items = build_layout_items(preset, {"address": text})

    assert len(items) == 3
