from __future__ import annotations

import types

from cardprint.printer.win32_adapter import Win32PrinterAdapter
import cardprint.printer.win32_adapter as adapter_module


class _FakeWin32Print:
    def __init__(self) -> None:
        self.closed = False

    def DeviceCapabilities(self, printer_name, port_name, capability):  # noqa: N802
        if capability == 16:  # DC_PAPERNAMES
            return ["A4", "Letter"]
        if capability == 3:  # DC_PAPERSIZE (0.1mm units)
            return [(2100, 2970), (2159, 2794)]
        if capability == 2:  # DC_PAPERS
            return [9, 1]
        return []

    def OpenPrinter(self, printer_name):  # noqa: N802
        return "printer-handle"

    def GetPrinter(self, handle, level):  # noqa: N802
        return {"pPortName": "LPT1"}

    def EnumForms(self, handle):  # noqa: N802
        return [
            {"Name": "Card85.6x54"},
            {"Name": "A4"},
        ]

    def ClosePrinter(self, handle):  # noqa: N802
        self.closed = True


def test_list_papers_merge_device_caps_and_forms(monkeypatch) -> None:
    fake_print = _FakeWin32Print()
    fake_con = types.SimpleNamespace(DC_PAPERNAMES=16, DC_PAPERSIZE=3, DC_PAPERS=2)

    monkeypatch.setattr(adapter_module, "win32print", fake_print)
    monkeypatch.setattr(adapter_module, "win32con", fake_con)
    monkeypatch.setattr(adapter_module, "win32ui", object())
    monkeypatch.setattr(adapter_module, "win32gui", object())

    adapter = Win32PrinterAdapter()
    papers = adapter.list_papers("AnyPrinter")

    assert "Card85.6x54" in papers
    assert "A4" in papers
    assert "Letter" in papers
    assert papers.count("A4") == 1
    assert fake_print.closed is True


def test_list_paper_options_include_dimensions(monkeypatch) -> None:
    fake_print = _FakeWin32Print()
    fake_con = types.SimpleNamespace(DC_PAPERNAMES=16, DC_PAPERSIZE=3, DC_PAPERS=2)

    monkeypatch.setattr(adapter_module, "win32print", fake_print)
    monkeypatch.setattr(adapter_module, "win32con", fake_con)
    monkeypatch.setattr(adapter_module, "win32ui", object())
    monkeypatch.setattr(adapter_module, "win32gui", object())

    adapter = Win32PrinterAdapter()
    options = adapter.list_paper_options("AnyPrinter")
    by_name = {opt["name"]: opt for opt in options}

    assert by_name["A4"]["width_mm"] == 210.0
    assert by_name["A4"]["height_mm"] == 297.0
    assert by_name["Card85.6x54"]["width_mm"] is None or by_name["Card85.6x54"]["width_mm"] > 0
