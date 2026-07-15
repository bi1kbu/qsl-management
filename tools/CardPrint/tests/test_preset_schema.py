from __future__ import annotations

import json
from pathlib import Path

import pytest

from cardprint.core.errors import CardPrintError
from cardprint.core.models import Preset
from cardprint.core.preset_service import load_preset, save_preset


def sample_preset_dict() -> dict:
    return {
        "version": "1.0",
        "name": "schema-test",
        "preferred_printer": "OKi 5330SC",
        "paper": {"name": "Card", "width_mm": 85.6, "height_mm": 54.0},
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
                "font_family": "SimSun",
                "font_size_pt": 11,
                "bold": False,
                "italic": False,
                "max_len": 20,
            }
        ],
        "ui_schema": [],
    }


def test_save_and_load_preset(tmp_path: Path) -> None:
    preset = Preset.from_dict(sample_preset_dict())
    file_path = tmp_path / "preset.json"
    save_preset(file_path, preset)

    loaded = load_preset(file_path)
    assert loaded.name == "schema-test"
    assert loaded.paper.width_mm == 85.6
    assert loaded.preferred_printer == "OKi 5330SC"


def test_duplicate_field_key_rejected(tmp_path: Path) -> None:
    data = sample_preset_dict()
    data["fields"].append(data["fields"][0].copy())
    file_path = tmp_path / "preset.json"
    file_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    with pytest.raises(CardPrintError) as exc_info:
        load_preset(file_path)
    assert exc_info.value.code == "DUPLICATE_FIELD_KEYS"


def test_field_value_string_types_are_normalized(tmp_path: Path) -> None:
    data = sample_preset_dict()
    data["fields"][0]["x_mm"] = "10.5"
    data["fields"][0]["y_mm"] = "11"
    data["fields"][0]["font_size_pt"] = "12"
    data["fields"][0]["bold"] = "true"
    data["fields"][0]["italic"] = "false"
    data["fields"][0]["max_len"] = "30"
    file_path = tmp_path / "preset.json"
    file_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    loaded = load_preset(file_path)
    field = loaded.fields[0]
    assert field.x_mm == 10.5
    assert field.y_mm == 11.0
    assert field.font_size_pt == 12
    assert field.bold is True
    assert field.italic is False
    assert field.max_len == 30
    assert field.print_border is False


def test_center_alignment_and_print_border_round_trip(tmp_path: Path) -> None:
    data = sample_preset_dict()
    data["fields"][0].update(
        {
            "print_width_mm": 30,
            "print_height_mm": 12,
            "text_align": "center",
            "print_border": "true",
        }
    )
    file_path = tmp_path / "preset.json"
    file_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    loaded = load_preset(file_path)
    assert loaded.fields[0].text_align == "center"
    assert loaded.fields[0].print_border is True

    save_preset(file_path, loaded)
    saved = json.loads(file_path.read_text(encoding="utf-8"))
    assert saved["fields"][0]["text_align"] == "center"
    assert saved["fields"][0]["print_border"] is True


def test_layout_mode_round_trip_and_chinese_normalization(tmp_path: Path) -> None:
    data = sample_preset_dict()
    data["fields"][0].update(
        {
            "print_width_mm": 30,
            "print_height_mm": 20,
            "layout_mode": "混合纵向",
        }
    )
    file_path = tmp_path / "preset.json"
    file_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    loaded = load_preset(file_path)
    assert loaded.fields[0].layout_mode == "mixed_vertical"

    save_preset(file_path, loaded)
    saved = json.loads(file_path.read_text(encoding="utf-8"))
    assert saved["fields"][0]["layout_mode"] == "mixed_vertical"


def test_vertical_layout_requires_width_and_height() -> None:
    data = sample_preset_dict()
    data["fields"][0]["layout_mode"] = "vertical"

    with pytest.raises(CardPrintError) as exc_info:
        Preset.from_dict(data)
    assert exc_info.value.code == "VERTICAL_LAYOUT_AREA_REQUIRED"


def test_center_alignment_requires_print_width() -> None:
    data = sample_preset_dict()
    data["fields"][0]["text_align"] = "center"

    with pytest.raises(CardPrintError) as exc_info:
        Preset.from_dict(data)
    assert exc_info.value.code == "CENTER_ALIGN_WIDTH_REQUIRED"


def test_print_border_requires_width_and_height() -> None:
    data = sample_preset_dict()
    data["fields"][0]["print_border"] = True

    with pytest.raises(CardPrintError) as exc_info:
        Preset.from_dict(data)
    assert exc_info.value.code == "PRINT_BORDER_AREA_REQUIRED"


def test_fixed_text_field_round_trip(tmp_path: Path) -> None:
    data = sample_preset_dict()
    data["fields"][0]["fixed_text"] = "固定辅助说明"
    file_path = tmp_path / "preset.json"
    file_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    loaded = load_preset(file_path)
    assert loaded.fields[0].fixed_text == "固定辅助说明"

    save_preset(file_path, loaded)
    saved = json.loads(file_path.read_text(encoding="utf-8"))
    assert saved["fields"][0]["fixed_text"] == "固定辅助说明"


def test_digit_raise_ratio_field_round_trip(tmp_path: Path) -> None:
    data = sample_preset_dict()
    data["fields"][0]["digit_raise_ratio"] = "0.33"
    file_path = tmp_path / "preset.json"
    file_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    loaded = load_preset(file_path)
    assert loaded.fields[0].digit_raise_ratio == 0.33

    save_preset(file_path, loaded)
    saved = json.loads(file_path.read_text(encoding="utf-8"))
    assert saved["fields"][0]["digit_raise_ratio"] == 0.33


def test_digit_raise_ratio_range_rejected() -> None:
    data = sample_preset_dict()
    data["fields"][0]["digit_raise_ratio"] = "1.5"

    with pytest.raises(CardPrintError) as exc_info:
        Preset.from_dict(data)
    assert exc_info.value.code == "INVALID_DIGIT_RAISE_RATIO"
