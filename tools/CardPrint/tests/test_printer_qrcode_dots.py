from __future__ import annotations

import pytest

pytest.importorskip("qrcode")

from cardprint.core.errors import CardPrintError
from cardprint.core.layout_engine import LayoutItem
from cardprint.printer.win32_adapter import Win32PrinterAdapter


class FakeDc:
    def __init__(self) -> None:
        self.rects: list[tuple[tuple[int, int, int, int], int]] = []

    def FillSolidRect(self, rect: tuple[int, int, int, int], color: int) -> None:
        self.rects.append((rect, color))


def test_qrcode_print_uses_largest_integer_dots_per_module() -> None:
    adapter = Win32PrinterAdapter(force_dry_run=True)
    dc = FakeDc()
    item = _qrcode_item("https://e.test/rp/C1")

    adapter._draw_qrcode(
        dc=dc,
        item=item,
        dpi_x=300,
        dpi_y=300,
        physical_offset_x_px=0,
        physical_offset_y_px=0,
    )

    black_rects = [rect for rect, color in dc.rects if color == 0x000000]
    assert black_rects
    left, top, right, bottom = black_rects[0]
    assert right - left == 8
    assert bottom - top == 8


def test_qrcode_print_shrinks_to_fit_small_area() -> None:
    adapter = Win32PrinterAdapter(force_dry_run=True)
    dc = FakeDc()
    item = _qrcode_item(
        "https://example.test/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL/C1180",
        size_mm=16.0,
    )

    adapter._draw_qrcode(
        dc=dc,
        item=item,
        dpi_x=270,
        dpi_y=270,
        physical_offset_x_px=0,
        physical_offset_y_px=0,
    )

    black_rects = [rect for rect, color in dc.rects if color == 0x000000]
    assert black_rects
    left, top, right, bottom = black_rects[0]
    assert right - left == 4
    assert bottom - top == 4


def test_qrcode_print_can_lower_error_correction_to_fit() -> None:
    adapter = Win32PrinterAdapter(force_dry_run=True)
    item = _qrcode_item(
        "https://example.test/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL/C1180"
        + ("A" * 17),
        size_mm=16.0,
    )

    layout = adapter._build_qrcode_layout(item, dpi_x=270, dpi_y=270)

    assert layout["module_px"] == 4
    assert layout["error_correction"] == "L"


def test_qrcode_print_rejects_url_when_fixed_modules_exceed_area() -> None:
    adapter = Win32PrinterAdapter(force_dry_run=True)
    dc = FakeDc()
    item = _qrcode_item(
        "https://example.test/apis/api.qsl-management.bi1kbu.com/v1alpha1/"
        "ONLINE_EYEBALL/C1002?cs=BI1KBU&token="
        + ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" * 20)
    )

    with pytest.raises(CardPrintError) as exc_info:
        adapter._draw_qrcode(
            dc=dc,
            item=item,
            dpi_x=300,
            dpi_y=300,
            physical_offset_x_px=0,
            physical_offset_y_px=0,
        )

    assert exc_info.value.code == "QRCODE_URL_TOO_LONG"
    assert "请缩短URL长度" in exc_info.value.message
    assert exc_info.value.details["module_dots"] == 4


def _qrcode_item(payload: str, size_mm: float = 24.0) -> LayoutItem:
    return LayoutItem(
        key="QRCODE",
        label_zh="二维码",
        text=payload,
        logical_x_mm=0.0,
        logical_y_mm=0.0,
        physical_x_mm=0.0,
        physical_y_mm=0.0,
        font_family="SimSun",
        font_size_pt=11,
        bold=False,
        italic=False,
        text_align="left",
        distribute_align=False,
        print_width_mm=size_mm,
        print_height_mm=size_mm,
        render_type="qrcode",
        qr_payload=payload,
    )
