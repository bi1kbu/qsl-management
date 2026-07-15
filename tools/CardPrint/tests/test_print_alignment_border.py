from __future__ import annotations

from cardprint.core.layout_engine import LayoutItem
from cardprint.core.models import Preset
from cardprint.core.units import mm_to_dots
from cardprint.preview.preview_scene import build_preview_scene
from cardprint.printer.win32_adapter import Win32PrinterAdapter


class _FakeTextDc:
    def __init__(self) -> None:
        self.calls: list[tuple[int, int, str]] = []

    def GetTextExtent(self, text: str) -> tuple[int, int]:  # noqa: N802
        return len(text) * 10, 20

    def SetBkMode(self, _: object) -> None:  # noqa: N802
        return None

    def TextOut(self, x: int, y: int, text: str) -> None:  # noqa: N802
        self.calls.append((x, y, text))


class _FakeBorderDc:
    def __init__(self) -> None:
        self.points: list[tuple[str, tuple[int, int]]] = []

    def MoveTo(self, point: tuple[int, int]) -> None:  # noqa: N802
        self.points.append(("move", point))

    def LineTo(self, point: tuple[int, int]) -> None:  # noqa: N802
        self.points.append(("line", point))


def _item(*, text_align: str = "left", distribute_align: bool = False) -> LayoutItem:
    return LayoutItem(
        key="name",
        label_zh="姓名",
        text="AB",
        logical_x_mm=0.0,
        logical_y_mm=0.0,
        physical_x_mm=10.0,
        physical_y_mm=5.0,
        font_family="SimSun",
        font_size_pt=11,
        bold=False,
        italic=False,
        text_align=text_align,
        distribute_align=distribute_align,
        print_width_mm=20.0,
        print_height_mm=8.0,
        print_border=True,
    )


def test_center_alignment_uses_configured_print_width() -> None:
    dc = _FakeTextDc()
    item = _item(text_align="center")
    adapter = Win32PrinterAdapter(force_dry_run=True)

    adapter._draw_text(dc, item, 72, 72, 0, 0)

    base_x = mm_to_dots(item.physical_x_mm, 72)
    width_px = mm_to_dots(item.print_width_mm, 72)
    assert dc.calls == [(base_x + round((width_px - 20) / 2), mm_to_dots(5.0, 72), "AB")]


def test_distribute_alignment_starts_from_original_x() -> None:
    dc = _FakeTextDc()
    item = _item(text_align="center", distribute_align=True)
    adapter = Win32PrinterAdapter(force_dry_run=True)

    adapter._draw_text(dc, item, 72, 72, 0, 0)

    assert dc.calls[0][0] == mm_to_dots(item.physical_x_mm, 72)


def test_print_border_uses_field_position_and_size() -> None:
    dc = _FakeBorderDc()
    item = _item()
    adapter = Win32PrinterAdapter(force_dry_run=True)

    adapter._draw_border(dc, item, 72, 72, 0, 0)

    left = mm_to_dots(item.physical_x_mm, 72)
    top = mm_to_dots(item.physical_y_mm, 72)
    right = left + mm_to_dots(item.print_width_mm, 72)
    bottom = top + mm_to_dots(item.print_height_mm, 72)
    assert dc.points == [
        ("move", (left, top)),
        ("line", (right, top)),
        ("line", (right, bottom)),
        ("line", (left, bottom)),
        ("line", (left, top)),
    ]


def test_preview_scene_carries_center_alignment_and_border() -> None:
    preset = Preset.from_dict(
        {
            "version": "1.0",
            "name": "preview-test",
            "paper": {"name": "Card", "width_mm": 100, "height_mm": 60},
            "calibration": {
                "rotation_direction": "right",
                "rotation_degree": 90,
                "origin_offset_mm": {"x": 0, "y": 0},
                "deadzone_mm": {"top": 0, "right": 0, "bottom": 0, "left": 0},
            },
            "fields": [
                {
                    "key": "name",
                    "label_zh": "姓名",
                    "x_mm": 10,
                    "y_mm": 10,
                    "print_width_mm": 30,
                    "print_height_mm": 10,
                    "print_border": True,
                    "text_align": "center",
                }
            ],
            "ui_schema": [],
        }
    )

    scene = build_preview_scene(preset, {"name": "测试"})

    assert scene["fields"][0]["text_align"] == "center"
    assert scene["fields"][0]["print_border"] is True
    assert scene["items"][0]["text_align"] == "center"
    assert scene["items"][0]["print_border"] is True


def test_gdi_font_spec_rotates_vertical_glyph_clockwise() -> None:
    adapter = Win32PrinterAdapter(force_dry_run=True)
    vertical_item = _item()
    vertical_item.layout_mode = "vertical"
    vertical_item.glyph_rotation_degree = 90

    vertical_spec = adapter._build_font_spec(vertical_item, dpi_y=300)
    horizontal_spec = adapter._build_font_spec(_item(), dpi_y=300)

    assert vertical_spec["escapement"] == 2700
    assert vertical_spec["orientation"] == 2700
    assert horizontal_spec["escapement"] == 0
    assert horizontal_spec["orientation"] == 0


def test_vertical_text_is_drawn_as_single_glyph_without_digit_raise() -> None:
    dc = _FakeTextDc()
    item = _item()
    item.text = "1"
    item.layout_mode = "mixed_vertical"
    item.glyph_rotation_degree = 90
    item.cell_width_mm = 5.0
    item.cell_height_mm = 5.0
    item.digit_raise_ratio = 0.8
    adapter = Win32PrinterAdapter(force_dry_run=True)

    adapter._draw_text(dc, item, 72, 72, 0, 0)

    assert len(dc.calls) == 1
    assert dc.calls[0][2] == "1"
    base_x = mm_to_dots(item.physical_x_mm, 72)
    cell_width_px = mm_to_dots(item.cell_width_mm, 72)
    rotated_width_px = 11
    assert dc.calls[0][0] == base_x + ((cell_width_px + rotated_width_px) // 2)
