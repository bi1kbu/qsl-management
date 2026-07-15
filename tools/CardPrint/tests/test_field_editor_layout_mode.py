from __future__ import annotations

import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

from cardprint.ui.widgets.field_editor import FieldEditor


def _app() -> QApplication:
    return QApplication.instance() or QApplication([])


def test_field_editor_defaults_to_horizontal_and_round_trips_mixed_vertical() -> None:
    app = _app()
    editor = FieldEditor()
    editor.add_row()

    assert editor.get_fields()[0]["layout_mode"] == "horizontal"

    field = editor.get_fields()[0]
    field.update(
        {
            "print_width_mm": 30.0,
            "print_height_mm": 20.0,
            "layout_mode": "mixed_vertical",
        }
    )
    editor.set_fields([field])

    assert editor.get_fields()[0]["layout_mode"] == "mixed_vertical"
    assert app is not None
