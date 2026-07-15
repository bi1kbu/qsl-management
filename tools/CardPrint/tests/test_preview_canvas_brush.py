from __future__ import annotations

import os
from typing import Any

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication

from cardprint.core.models import Preset
from cardprint.preview.preview_scene import build_preview_scene
from cardprint.ui.widgets.preview_canvas import PreviewCanvas


class _FontMetrics:
    @staticmethod
    def horizontalAdvance(text: str) -> int:  # noqa: N802
        return len(text) * 10


class _Font:
    def setFamily(self, _: str) -> None:  # noqa: N802
        return None

    def setPointSizeF(self, _: float) -> None:  # noqa: N802
        return None

    def setBold(self, _: bool) -> None:  # noqa: N802
        return None

    def setItalic(self, _: bool) -> None:  # noqa: N802
        return None


class _RecordingPainter:
    def __init__(self) -> None:
        self._brush: Any = None
        self._states: list[Any] = []
        self.rect_brushes: list[Any] = []

    def save(self) -> None:
        self._states.append(self._brush)

    def restore(self) -> None:
        self._brush = self._states.pop()

    def setPen(self, _: Any) -> None:  # noqa: N802
        return None

    def setBrush(self, brush: Any) -> None:  # noqa: N802
        self._brush = brush

    def font(self) -> _Font:
        return _Font()

    def setFont(self, _: _Font) -> None:  # noqa: N802
        return None

    def drawRect(self, _: Any) -> None:  # noqa: N802
        self.rect_brushes.append(self._brush)

    def drawEllipse(self, *_: Any) -> None:  # noqa: N802
        return None

    def drawText(self, *_: Any) -> None:  # noqa: N802
        return None

    def fillRect(self, *_: Any) -> None:  # noqa: N802
        return None

    def fontMetrics(self) -> _FontMetrics:  # noqa: N802
        return _FontMetrics()


def _app() -> QApplication:
    return QApplication.instance() or QApplication([])


def test_print_border_and_field_boxes_are_never_filled() -> None:
    app = _app()
    canvas = PreviewCanvas()
    canvas._paper_w_mm = 100.0
    canvas._paper_h_mm = 60.0
    canvas._page_left = 0.0
    canvas._page_top = 0.0
    canvas._scale = 1.0
    canvas.set_scene(
        {
            "items": [
                {
                    "key": "name",
                    "label_zh": "姓名",
                    "text": "测试",
                    "logical_x_mm": 10,
                    "logical_y_mm": 10,
                    "print_width_mm": 30,
                    "print_height_mm": 10,
                    "print_border": True,
                    "text_align": "left",
                }
            ],
            "fields": [
                {
                    "key": "name",
                    "x_mm": 10,
                    "y_mm": 10,
                    "print_width_mm": 30,
                    "print_height_mm": 10,
                    "font_size_pt": 11,
                    "sample_text": "测试",
                },
                {
                    "key": "callsign",
                    "x_mm": 50,
                    "y_mm": 10,
                    "print_width_mm": 20,
                    "print_height_mm": 10,
                    "font_size_pt": 11,
                    "sample_text": "BI1KBU",
                },
            ],
        }
    )
    painter = _RecordingPainter()

    canvas._draw_items(painter, 0.0, 0.0, 1.0)
    canvas._draw_field_boxes(painter, 0.0, 0.0, 1.0)

    assert painter.rect_brushes
    assert all(brush == Qt.NoBrush for brush in painter.rect_brushes)
    assert app is not None


def test_non_editable_preview_does_not_draw_calibration_field_boxes() -> None:
    app = _app()
    canvas = PreviewCanvas()
    canvas.resize(600, 400)
    canvas.set_editable(False)
    canvas.set_scene(
        {
            "paper_mm": {"width": 100, "height": 60},
            "deadzone_mm": {"top": 0, "right": 0, "bottom": 0, "left": 0},
            "printable_bounds_mm": {"left": 0, "top": 0, "right": 100, "bottom": 60},
            "items": [
                {
                    "key": "name",
                    "label_zh": "姓名",
                    "text": "测试",
                    "logical_x_mm": 10,
                    "logical_y_mm": 10,
                    "print_width_mm": 30,
                    "print_height_mm": 10,
                    "print_border": True,
                    "text_align": "left",
                }
            ],
            "fields": [
                {
                    "key": "name",
                    "x_mm": 10,
                    "y_mm": 10,
                    "print_width_mm": 30,
                    "print_height_mm": 10,
                    "font_size_pt": 11,
                    "sample_text": "测试",
                }
            ],
        }
    )

    canvas.show()
    app.processEvents()
    image = canvas.grab()

    assert not image.isNull()
    assert canvas._field_geometries == []


def test_editable_preview_keeps_calibration_field_boxes() -> None:
    app = _app()
    canvas = PreviewCanvas()
    canvas.resize(600, 400)
    canvas.set_editable(True)
    canvas.set_scene(
        {
            "paper_mm": {"width": 100, "height": 60},
            "deadzone_mm": {"top": 0, "right": 0, "bottom": 0, "left": 0},
            "printable_bounds_mm": {"left": 0, "top": 0, "right": 100, "bottom": 60},
            "items": [],
            "fields": [
                {
                    "key": "name",
                    "x_mm": 10,
                    "y_mm": 10,
                    "print_width_mm": 30,
                    "print_height_mm": 10,
                    "font_size_pt": 11,
                    "sample_text": "测试",
                }
            ],
        }
    )

    canvas.show()
    app.processEvents()
    image = canvas.grab()

    assert not image.isNull()
    assert len(canvas._field_geometries) == 1


def test_mixed_vertical_preview_renders_without_calibration_overlay() -> None:
    app = _app()
    preset = Preset.from_dict(
        {
            "version": "1.0",
            "name": "mixed-vertical-preview",
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
                    "print_width_mm": 30,
                    "print_height_mm": 30,
                    "print_border": True,
                    "layout_mode": "mixed_vertical",
                }
            ],
            "ui_schema": [],
        }
    )
    scene = build_preview_scene(preset, {"text": "中文AB12/n背面"})
    canvas = PreviewCanvas()
    canvas.resize(600, 400)
    canvas.set_editable(False)
    canvas.set_scene(scene)

    canvas.show()
    app.processEvents()
    image = canvas.grab()

    assert not image.isNull()
    assert canvas._field_geometries == []
    assert {item["glyph_rotation_degree"] for item in scene["items"]} == {0, 90}
