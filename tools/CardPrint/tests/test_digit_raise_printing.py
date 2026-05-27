from __future__ import annotations

from typing import Any

from cardprint.core.layout_engine import LayoutItem
from cardprint.printer.win32_adapter import Win32PrinterAdapter


class _FakeDc:
    def __init__(self) -> None:
        self.calls: list[tuple[int, int, str]] = []

    def GetTextExtent(self, text: str) -> tuple[int, int]:  # noqa: N802
        return len(text) * 10, 20

    def SetBkMode(self, _: Any) -> None:  # noqa: N802
        return None

    def TextOut(self, x: int, y: int, text: str) -> None:  # noqa: N802
        self.calls.append((x, y, text))


def _text_item(text: str, digit_raise_ratio: float) -> LayoutItem:
    return LayoutItem(
        key="peerCallsign",
        label_zh="呼号",
        text=text,
        logical_x_mm=0.0,
        logical_y_mm=0.0,
        physical_x_mm=0.0,
        physical_y_mm=10.0,
        font_family="SimSun",
        font_size_pt=36,
        bold=False,
        italic=False,
        text_align="left",
        distribute_align=False,
        digit_raise_ratio=digit_raise_ratio,
    )


def test_digits_are_drawn_higher_when_digit_raise_enabled() -> None:
    dc = _FakeDc()
    adapter = Win32PrinterAdapter(force_dry_run=True)

    adapter._draw_text(
        dc=dc,
        item=_text_item("BI1KBU", 0.5),
        dpi_x=72,
        dpi_y=72,
        physical_offset_x_px=0,
        physical_offset_y_px=0,
    )

    digit_call = next(call for call in dc.calls if call[2] == "1")
    letter_call = next(call for call in dc.calls if call[2] == "B")
    assert digit_call[1] < letter_call[1]
