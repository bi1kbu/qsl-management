from __future__ import annotations

import os
import unicodedata
from dataclasses import asdict
from typing import Any

from cardprint.core.errors import CardPrintError
from cardprint.core.layout_engine import LayoutItem
from cardprint.core.units import mm_to_dots, pt_to_pixels
try:
    import qrcode
except ImportError:  # pragma: no cover - depends on host
    qrcode = None

try:
    import win32con  # type: ignore
    import win32gui  # type: ignore
    import win32print  # type: ignore
    import win32ui  # type: ignore
except ImportError:  # pragma: no cover - depends on host
    win32con = None
    win32gui = None
    win32print = None
    win32ui = None


class Win32PrinterAdapter:
    QRCODE_MIN_MODULE_DOTS = 4
    QRCODE_MAX_MODULE_DOTS = 8
    DEFAULT_PAPER_OPTIONS: list[dict[str, Any]] = [
        {"name": "CustomCard", "width_mm": 85.6, "height_mm": 54.0, "source": "default"},
        {"name": "A4", "width_mm": 210.0, "height_mm": 297.0, "source": "default"},
        {"name": "Letter", "width_mm": 215.9, "height_mm": 279.4, "source": "default"},
    ]

    def __init__(self, force_dry_run: bool = False) -> None:
        self._native_enabled = (
            not force_dry_run
            and os.name == "nt"
            and win32con is not None
            and win32gui is not None
            and win32print is not None
            and win32ui is not None
        )

    @property
    def is_dry_run(self) -> bool:
        return not self._native_enabled

    @staticmethod
    def _is_mock_printer(printer_name: str) -> bool:
        return printer_name.strip().lower().startswith("mock")

    @staticmethod
    def _dedupe_names(names: list[str]) -> list[str]:
        seen: set[str] = set()
        out: list[str] = []
        for raw in names:
            name = str(raw).strip()
            if not name:
                continue
            key = name.casefold()
            if key in seen:
                continue
            seen.add(key)
            out.append(name)
        return out

    @staticmethod
    def _to_optional_mm(value: Any, scale: float) -> float | None:
        try:
            val = float(value)
        except (TypeError, ValueError):
            return None
        if val <= 0:
            return None
        return round(val / scale, 2)

    @staticmethod
    def _parse_device_cap_paper_sizes(size_data: Any) -> list[tuple[float | None, float | None]]:
        if not isinstance(size_data, (list, tuple)) or not size_data:
            return []

        pairs: list[tuple[Any, Any]] = []
        first = size_data[0]
        if isinstance(first, (list, tuple)) and len(first) >= 2:
            for item in size_data:
                if isinstance(item, (list, tuple)) and len(item) >= 2:
                    pairs.append((item[0], item[1]))
        else:
            flat = list(size_data)
            for idx in range(0, len(flat) - 1, 2):
                pairs.append((flat[idx], flat[idx + 1]))

        out: list[tuple[float | None, float | None]] = []
        for width_raw, height_raw in pairs:
            width_mm = Win32PrinterAdapter._to_optional_mm(width_raw, scale=10.0)
            height_mm = Win32PrinterAdapter._to_optional_mm(height_raw, scale=10.0)
            out.append((width_mm, height_mm))
        return out

    @staticmethod
    def _parse_form_size_mm(size_data: Any) -> tuple[float | None, float | None]:
        width_raw = None
        height_raw = None
        if isinstance(size_data, dict):
            width_raw = size_data.get("cx", size_data.get("width"))
            height_raw = size_data.get("cy", size_data.get("height"))
        elif isinstance(size_data, (list, tuple)) and len(size_data) >= 2:
            width_raw, height_raw = size_data[0], size_data[1]
        if width_raw is None or height_raw is None:
            return None, None
        try:
            width_value = float(width_raw)
            height_value = float(height_raw)
        except (TypeError, ValueError):
            return None, None
        if width_value <= 0 or height_value <= 0:
            return None, None

        max_value = max(width_value, height_value)
        # EnumForms 常见单位是 0.001mm，部分驱动会用 0.1mm。
        if max_value > 10000:
            scale = 1000.0
        elif max_value > 500:
            scale = 10.0
        else:
            scale = 1.0
        return round(width_value / scale, 2), round(height_value / scale, 2)

    @staticmethod
    def _upsert_option(
        options_map: dict[str, dict[str, Any]],
        ordered_keys: list[str],
        name: str,
        width_mm: float | None,
        height_mm: float | None,
        source: str,
    ) -> None:
        clean_name = str(name).strip()
        if not clean_name:
            return
        key = clean_name.casefold()
        existing = options_map.get(key)
        if existing is None:
            options_map[key] = {
                "name": clean_name,
                "width_mm": width_mm,
                "height_mm": height_mm,
                "source": source,
            }
            ordered_keys.append(key)
            return

        existing_width = existing.get("width_mm")
        existing_height = existing.get("height_mm")
        if (existing_width is None or existing_height is None) and width_mm and height_mm:
            existing["width_mm"] = width_mm
            existing["height_mm"] = height_mm
        if source and source not in str(existing.get("source", "")):
            existing["source"] = f"{existing.get('source', '')}|{source}".strip("|")

    @staticmethod
    def _char_display_units(ch: str) -> int:
        return 2 if unicodedata.east_asian_width(ch) in {"W", "F"} else 1

    @staticmethod
    def _contains_cjk_text(text: str) -> bool:
        for ch in text:
            codepoint = ord(ch)
            if (
                0x4E00 <= codepoint <= 0x9FFF
                or 0x3400 <= codepoint <= 0x4DBF
                or 0x3000 <= codepoint <= 0x303F
            ):
                return True
        return False

    @staticmethod
    def _text_width_px(dc: Any, text: str, item: LayoutItem, dpi_x: int) -> int:
        try:
            text_w_px, _ = dc.GetTextExtent(text)
            return int(text_w_px)
        except Exception:
            return int(max(1, len(text)) * pt_to_pixels(item.font_size_pt, dpi_x) * 0.55)

    @staticmethod
    def _text_extent_px(dc: Any, text: str, item: LayoutItem, dpi_x: int, dpi_y: int) -> tuple[int, int]:
        try:
            text_w_px, text_h_px = dc.GetTextExtent(text)
            return int(text_w_px), int(text_h_px)
        except Exception:
            return (
                int(max(1, len(text)) * pt_to_pixels(item.font_size_pt, dpi_x) * 0.55),
                max(1, pt_to_pixels(item.font_size_pt, dpi_y)),
            )

    def _build_font_spec(self, item: LayoutItem, dpi_y: int) -> dict[str, Any]:
        font_height = max(1, pt_to_pixels(item.font_size_pt, dpi_y))
        text_value = str(getattr(item, "text", "") or "")
        default_charset = int(getattr(win32con, "DEFAULT_CHARSET", 1))
        cjk_charset = int(getattr(win32con, "GB2312_CHARSET", 134))
        out_tt_only = int(getattr(win32con, "OUT_TT_ONLY_PRECIS", 7))
        proof_quality = int(getattr(win32con, "PROOF_QUALITY", 2))
        rotation_degree = int(getattr(item, "glyph_rotation_degree", 0) or 0) % 360
        # GDI 使用逆时针角度；2700（十分之一度）对应视觉上的顺时针 90°。
        gdi_angle = ((360 - rotation_degree) % 360) * 10
        return {
            "name": item.font_family,
            "height": -font_height,
            "weight": 700 if item.bold else 400,
            "italic": int(item.italic),
            "charset": cjk_charset if self._contains_cjk_text(text_value) else default_charset,
            "out precision": out_tt_only,
            "quality": proof_quality,
            "escapement": gdi_angle,
            "orientation": gdi_angle,
        }

    def _draw_text_with_digit_raise(
        self,
        dc: Any,
        px: int,
        py: int,
        text: str,
        item: LayoutItem,
        dpi_x: int,
        dpi_y: int,
    ) -> None:
        digit_raise_ratio = float(getattr(item, "digit_raise_ratio", 0.0) or 0.0)
        digit_raise_px = int(round(pt_to_pixels(item.font_size_pt, dpi_y) * digit_raise_ratio))
        if digit_raise_px <= 0 or not any(ch.isdigit() for ch in text):
            dc.TextOut(px, py, text)
            return

        cursor_x = int(px)
        for ch in text:
            dc.TextOut(cursor_x, py - digit_raise_px if ch.isdigit() else py, ch)
            cursor_x += self._text_width_px(dc, ch, item, dpi_x)

    def _draw_text(
        self,
        dc: Any,
        item: LayoutItem,
        dpi_x: int,
        dpi_y: int,
        physical_offset_x_px: int,
        physical_offset_y_px: int,
    ) -> None:
        px = mm_to_dots(item.physical_x_mm, dpi_x) - physical_offset_x_px
        py = mm_to_dots(item.physical_y_mm, dpi_y) - physical_offset_y_px
        text = item.text or ""
        layout_mode = str(getattr(item, "layout_mode", "horizontal") or "horizontal").strip().lower()

        # 纵向布局已由布局引擎拆成单字单元；这里只负责在单元格内定位字形。
        if layout_mode in {"vertical", "mixed_vertical"}:
            try:
                dc.SetBkMode(win32con.TRANSPARENT)
            except Exception:
                pass
            if not text:
                return
            cell_width_px = mm_to_dots(float(getattr(item, "cell_width_mm", 0.0) or 0.0), dpi_x)
            cell_height_px = mm_to_dots(float(getattr(item, "cell_height_mm", 0.0) or 0.0), dpi_y)
            rotation_degree = int(getattr(item, "glyph_rotation_degree", 0) or 0) % 360
            if rotation_degree:
                rotated_width_px = max(1, pt_to_pixels(item.font_size_pt, dpi_y))
                # 顺时针旋转字体从参考点向左展开，因此参考 X 需位于单元格右半侧。
                px += max(0, (cell_width_px + rotated_width_px) // 2)
            else:
                text_w_px, text_h_px = self._text_extent_px(dc, text, item, dpi_x, dpi_y)
                px += max(0, (cell_width_px - text_w_px) // 2)
                py += max(0, (cell_height_px - text_h_px) // 2)
            dc.TextOut(px, py, text)
            return

        align_mode = str(getattr(item, "text_align", "left") or "left").strip().lower()
        if align_mode not in {"left", "center", "right"}:
            align_mode = "left"
        width_px = mm_to_dots(float(item.print_width_mm), dpi_x) if float(item.print_width_mm) > 0 else 0

        if not item.distribute_align and align_mode in {"center", "right"} and width_px > 0 and text:
            text_w_px = self._text_width_px(dc, text, item, dpi_x)
            if align_mode == "center":
                px = max(px, px + int(round((width_px - text_w_px) / 2.0)))
            else:
                px = max(px, px + width_px - int(text_w_px))

        # 文本背景改为透明，避免驱动在 OPAQUE 模式下对字符框做底色填充，
        # 导致与二维码区域叠加时出现“黑块/白块”假象。
        try:
            dc.SetBkMode(win32con.TRANSPARENT)
        except Exception:
            pass

        if (
            not item.distribute_align
            or width_px <= 0
            or len(text) <= 1
        ):
            self._draw_text_with_digit_raise(dc, px, py, text, item, dpi_x, dpi_y)
            return

        units = [self._char_display_units(ch) for ch in text]
        total_units = sum(units)
        if total_units <= 1:
            self._draw_text_with_digit_raise(dc, px, py, text, item, dpi_x, dpi_y)
            return

        digit_raise_ratio = float(getattr(item, "digit_raise_ratio", 0.0) or 0.0)
        digit_raise_px = int(round(pt_to_pixels(item.font_size_pt, dpi_y) * digit_raise_ratio))
        step_px = width_px / float(total_units - 1)
        cursor_units = 0.0
        for index, ch in enumerate(text):
            char_x = int(round(px + cursor_units * step_px))
            dc.TextOut(char_x, py - digit_raise_px if digit_raise_px > 0 and ch.isdigit() else py, ch)
            cursor_units += float(units[index])

    def _draw_border(
        self,
        dc: Any,
        item: LayoutItem,
        dpi_x: int,
        dpi_y: int,
        physical_offset_x_px: int,
        physical_offset_y_px: int,
    ) -> None:
        if not bool(getattr(item, "print_border", False)):
            return
        width_px = mm_to_dots(float(item.print_width_mm), dpi_x)
        height_px = mm_to_dots(float(item.print_height_mm), dpi_y)
        if width_px <= 0 or height_px <= 0:
            return
        anchor_x_mm = getattr(item, "physical_anchor_x_mm", None)
        anchor_y_mm = getattr(item, "physical_anchor_y_mm", None)
        if anchor_x_mm is None:
            anchor_x_mm = item.physical_x_mm
        if anchor_y_mm is None:
            anchor_y_mm = item.physical_y_mm
        left = mm_to_dots(float(anchor_x_mm), dpi_x) - physical_offset_x_px
        top = mm_to_dots(float(anchor_y_mm), dpi_y) - physical_offset_y_px
        right = left + width_px
        bottom = top + height_px
        dc.MoveTo((left, top))
        dc.LineTo((right, top))
        dc.LineTo((right, bottom))
        dc.LineTo((left, bottom))
        dc.LineTo((left, top))

    def _draw_qrcode(
        self,
        dc: Any,
        item: LayoutItem,
        dpi_x: int,
        dpi_y: int,
        physical_offset_x_px: int,
        physical_offset_y_px: int,
    ) -> None:
        payload = str(getattr(item, "qr_payload", "") or "").strip()
        if not payload:
            return
        layout = self._build_qrcode_layout(item, dpi_x, dpi_y)
        if layout["matrix_size"] <= 0:
            return

        matrix = layout["matrix"]
        module_px = layout["module_px"]
        qr_size_px = layout["qr_size_px"]
        width_px = layout["width_px"]
        height_px = layout["height_px"]

        px = mm_to_dots(item.physical_x_mm, dpi_x) - physical_offset_x_px
        py = mm_to_dots(item.physical_y_mm, dpi_y) - physical_offset_y_px
        offset_x = max(0, (width_px - qr_size_px) // 2)
        offset_y = max(0, (height_px - qr_size_px) // 2)
        start_x = px + offset_x
        start_y = py + offset_y

        # 先清空二维码区域背景，避免旧驱动缓存导致残影或整块黑底。
        dc.FillSolidRect((px, py, px + width_px, py + height_px), 0xFFFFFF)

        for row_index, row in enumerate(matrix):
            y = start_y + row_index * module_px
            for col_index, cell in enumerate(row):
                if not cell:
                    continue
                x = start_x + col_index * module_px
                # PyCDC.FillSolidRect 在 pywin32 中按 (left, top, right, bottom) 解释元组。
                dc.FillSolidRect((x, y, x + module_px, y + module_px), 0x000000)

    def _build_qrcode_layout(self, item: LayoutItem, dpi_x: int, dpi_y: int) -> dict[str, Any]:
        payload = str(getattr(item, "qr_payload", "") or "").strip()
        if not payload:
            return {
                "matrix": [],
                "matrix_size": 0,
                "module_px": self.QRCODE_MIN_MODULE_DOTS,
                "qr_size_px": 0,
                "width_px": 0,
                "height_px": 0,
            }
        if qrcode is None:
            raise CardPrintError(
                code="QRCODE_DEPENDENCY_MISSING",
                message="未安装二维码依赖，请先安装 qrcode。",
                details={},
            )

        width_px = mm_to_dots(float(item.print_width_mm), dpi_x)
        height_px = mm_to_dots(float(item.print_height_mm), dpi_y)
        if width_px <= 0 or height_px <= 0:
            raise CardPrintError(
                code="QRCODE_AREA_INVALID",
                message="QRCODE 区域宽高必须大于 0。",
                details={
                    "key": item.key,
                    "print_width_mm": item.print_width_mm,
                    "print_height_mm": item.print_height_mm,
                },
            )

        available_px = min(width_px, height_px)
        candidates: list[dict[str, Any]] = []
        failed_candidates: list[dict[str, Any]] = []
        for correction_priority, (correction_label, correction_level) in enumerate((
            ("L", qrcode.constants.ERROR_CORRECT_L),
            ("M", qrcode.constants.ERROR_CORRECT_M),
        )):
            qr_obj = qrcode.QRCode(
                version=None,
                error_correction=correction_level,
                box_size=1,
                border=1,
            )
            qr_obj.add_data(payload)
            qr_obj.make(fit=True)
            matrix = qr_obj.get_matrix()
            matrix_size = len(matrix)
            for module_px in range(self.QRCODE_MAX_MODULE_DOTS, self.QRCODE_MIN_MODULE_DOTS - 1, -1):
                qr_size_px = module_px * matrix_size
                candidate = {
                    "matrix": matrix,
                    "matrix_size": matrix_size,
                    "module_px": module_px,
                    "qr_size_px": qr_size_px,
                    "width_px": width_px,
                    "height_px": height_px,
                    "error_correction": correction_label,
                    "correction_priority": correction_priority,
                }
                if qr_size_px <= available_px:
                    candidates.append(candidate)
                else:
                    failed_candidates.append(candidate)
        if not candidates:
            failed = min(failed_candidates, key=lambda item: item["qr_size_px"])
            raise CardPrintError(
                code="QRCODE_URL_TOO_LONG",
                message="二维码尺寸超过打印区域，请缩短URL长度。",
                details={
                    "key": item.key,
                    "matrix_size": failed["matrix_size"],
                    "module_dots": failed["module_px"],
                    "required_dots": failed["qr_size_px"],
                    "available_dots": available_px,
                    "payload_length": len(payload),
                    "error_correction": failed["error_correction"],
                },
            )
        return max(candidates, key=lambda item: (item["module_px"], item["correction_priority"]))

    def _preflight_qrcode_items(self, rows: list[list[LayoutItem]], dpi_x: int, dpi_y: int) -> None:
        for items in rows:
            for item in items:
                if str(getattr(item, "render_type", "text")).strip().lower() == "qrcode":
                    self._build_qrcode_layout(item, dpi_x, dpi_y)

    def list_printers(self) -> list[str]:
        if not self._native_enabled:
            return ["MockPrinter"]
        try:
            flags = win32print.PRINTER_ENUM_LOCAL | win32print.PRINTER_ENUM_CONNECTIONS
            raw_items = win32print.EnumPrinters(flags)
            names = sorted({item[2] for item in raw_items if len(item) >= 3})
            return names or ["MockPrinter"]
        except Exception as exc:  # pragma: no cover - depends on host
            raise CardPrintError(
                code="PRINTER_ENUM_FAILED",
                message="无法枚举打印机。",
                details={"error": str(exc)},
            ) from exc

    def _device_capabilities(self, printer_name: str, port_name: str | None, capability: int) -> Any:
        try:
            return win32print.DeviceCapabilities(printer_name, port_name, capability)
        except Exception:
            if port_name is not None:
                try:
                    return win32print.DeviceCapabilities(printer_name, None, capability)
                except Exception:
                    return None
            return None

    def _find_paper_id(self, printer_name: str, port_name: str | None, paper_name: str) -> int | None:
        cap_names = self._device_capabilities(printer_name, port_name, win32con.DC_PAPERNAMES)
        cap_ids = self._device_capabilities(printer_name, port_name, win32con.DC_PAPERS)
        if not isinstance(cap_names, (list, tuple)) or not isinstance(cap_ids, (list, tuple)):
            return None
        target = paper_name.strip().casefold()
        if not target:
            return None
        for idx, raw_name in enumerate(cap_names):
            name = str(raw_name).strip()
            if name.casefold() != target:
                continue
            if idx >= len(cap_ids):
                continue
            try:
                return int(cap_ids[idx])
            except (TypeError, ValueError):
                return None
        return None

    def _apply_paper_to_dc(self, dc: Any, printer_name: str, paper_name: str) -> dict[str, Any]:
        result: dict[str, Any] = {
            "requested_paper": paper_name,
            "applied": False,
            "method": "",
            "paper_id": None,
        }
        if not paper_name.strip():
            return result

        hprinter = None
        try:
            hprinter = win32print.OpenPrinter(printer_name)
            info = win32print.GetPrinter(hprinter, 2)
            port_name = info.get("pPortName")
            devmode = info.get("pDevMode")
            if devmode is None:
                result["error"] = "pDevMode 不可用"
                return result

            paper_id = self._find_paper_id(printer_name, port_name, paper_name)
            if paper_id is not None:
                devmode.PaperSize = int(paper_id)
                devmode.Fields |= win32con.DM_PAPERSIZE
                result["paper_id"] = int(paper_id)
                result["method"] = "paper_size"

            try:
                devmode.FormName = paper_name
                devmode.Fields |= win32con.DM_FORMNAME
                if not result["method"]:
                    result["method"] = "form_name"
            except Exception:
                pass

            win32gui.ResetDC(dc.GetHandleOutput(), devmode)
            result["applied"] = True
            return result
        except Exception as exc:
            result["error"] = str(exc)
            return result
        finally:
            if hprinter is not None:
                try:
                    win32print.ClosePrinter(hprinter)
                except Exception:
                    pass

    def list_papers(self, printer_name: str) -> list[str]:
        options = self.list_paper_options(printer_name)
        return [str(item.get("name", "")).strip() for item in options if str(item.get("name", "")).strip()]

    def list_paper_options(self, printer_name: str) -> list[dict[str, Any]]:
        if not self._native_enabled or self._is_mock_printer(printer_name):
            return [dict(item) for item in self.DEFAULT_PAPER_OPTIONS]

        options_map: dict[str, dict[str, Any]] = {}
        ordered_keys: list[str] = []

        hprinter = None
        port_name: str | None = None
        try:
            hprinter = win32print.OpenPrinter(printer_name)
            info = win32print.GetPrinter(hprinter, 2)
            port_name = info.get("pPortName")

            cap_names = self._device_capabilities(printer_name, port_name, win32con.DC_PAPERNAMES)
            cap_sizes_raw = self._device_capabilities(printer_name, port_name, win32con.DC_PAPERSIZE)
            cap_ids = self._device_capabilities(printer_name, port_name, win32con.DC_PAPERS)
            cap_sizes = self._parse_device_cap_paper_sizes(cap_sizes_raw)
            if isinstance(cap_names, (list, tuple)):
                for idx, raw_name in enumerate(cap_names):
                    name = str(raw_name).strip()
                    width_mm = None
                    height_mm = None
                    if idx < len(cap_sizes):
                        width_mm, height_mm = cap_sizes[idx]
                    self._upsert_option(
                        options_map,
                        ordered_keys,
                        name=name,
                        width_mm=width_mm,
                        height_mm=height_mm,
                        source="device_capabilities",
                    )
                    if isinstance(cap_ids, (list, tuple)) and idx < len(cap_ids):
                        key = name.casefold()
                        if key in options_map:
                            try:
                                options_map[key]["paper_id"] = int(cap_ids[idx])
                            except (TypeError, ValueError):
                                pass

            forms = win32print.EnumForms(hprinter)
            for form in forms or []:
                if isinstance(form, dict):
                    name = str(form.get("Name", "")).strip()
                    width_mm, height_mm = self._parse_form_size_mm(form.get("Size"))
                    self._upsert_option(
                        options_map,
                        ordered_keys,
                        name=name,
                        width_mm=width_mm,
                        height_mm=height_mm,
                        source="enum_forms",
                    )
                elif isinstance(form, (tuple, list)) and form:
                    name = str(form[0]).strip()
                    width_mm = None
                    height_mm = None
                    if len(form) > 1:
                        width_mm, height_mm = self._parse_form_size_mm(form[1])
                    self._upsert_option(
                        options_map,
                        ordered_keys,
                        name=name,
                        width_mm=width_mm,
                        height_mm=height_mm,
                        source="enum_forms",
                    )
        except Exception:
            pass
        finally:
            if hprinter is not None:
                try:
                    win32print.ClosePrinter(hprinter)
                except Exception:
                    pass

        if not ordered_keys:
            return [dict(item) for item in self.DEFAULT_PAPER_OPTIONS]
        return [options_map[key] for key in ordered_keys]

    def print_calibration_cross(
        self,
        printer_name: str,
        paper_name: str,
        width_mm: float,
        height_mm: float,
        cross_offset_x_mm: float = 0.0,
        cross_offset_y_mm: float = 0.0,
        title: str = "CardPrint Calibration",
    ) -> dict[str, Any]:
        if not self._native_enabled or self._is_mock_printer(printer_name):
            return {
                "mode": "dry_run",
                "printer_name": printer_name,
                "paper_name": paper_name,
                "paper_mm": {"width": width_mm, "height": height_mm},
                "cross_offset_mm": {"x": cross_offset_x_mm, "y": cross_offset_y_mm},
                "cross_center_mm": {
                    "x": round((width_mm / 2.0) + cross_offset_x_mm, 2),
                    "y": round((height_mm / 2.0) + cross_offset_y_mm, 2),
                },
                "title": title,
            }

        dc = None
        try:  # pragma: no cover - depends on host
            dc = win32ui.CreateDC()
            dc.CreatePrinterDC(printer_name)
            paper_apply = self._apply_paper_to_dc(dc, printer_name, paper_name)
            dpi_x = dc.GetDeviceCaps(win32con.LOGPIXELSX)
            dpi_y = dc.GetDeviceCaps(win32con.LOGPIXELSY)
            page_w_px = mm_to_dots(width_mm, dpi_x)
            page_h_px = mm_to_dots(height_mm, dpi_y)
            physical_w_px = dc.GetDeviceCaps(win32con.PHYSICALWIDTH)
            physical_h_px = dc.GetDeviceCaps(win32con.PHYSICALHEIGHT)
            printable_w_px = dc.GetDeviceCaps(win32con.HORZRES)
            printable_h_px = dc.GetDeviceCaps(win32con.VERTRES)
            physical_offset_x_px = dc.GetDeviceCaps(win32con.PHYSICALOFFSETX)
            physical_offset_y_px = dc.GetDeviceCaps(win32con.PHYSICALOFFSETY)
            is_effect_check = abs(cross_offset_x_mm) > 1e-6 or abs(cross_offset_y_mm) > 1e-6
            requested_exceeds_physical = (
                page_w_px > physical_w_px or page_h_px > physical_h_px
            )

            dc.StartDoc(title)
            dc.StartPage()

            margin_px = mm_to_dots(2, dpi_x)
            left, top = margin_px, margin_px
            draw_right = min(page_w_px, printable_w_px) - margin_px
            draw_bottom = min(page_h_px, printable_h_px) - margin_px
            if draw_right <= left:
                draw_right = left + 1
            if draw_bottom <= top:
                draw_bottom = top + 1

            # 初次标定才画外框；效果复测页避免外框干扰。
            if not is_effect_check:
                dc.MoveTo((left, top))
                dc.LineTo((draw_right, top))
                dc.LineTo((draw_right, draw_bottom))
                dc.LineTo((left, draw_bottom))
                dc.LineTo((left, top))

            # 将“纸张物理坐标”映射到 DC 可打印坐标：减去硬件不可打印边距。
            ref_cx = (page_w_px // 2) - physical_offset_x_px
            ref_cy = (page_h_px // 2) - physical_offset_y_px
            cx = ref_cx + mm_to_dots(cross_offset_x_mm, dpi_x)
            cy = ref_cy + mm_to_dots(cross_offset_y_mm, dpi_y)
            cx = max(left, min(cx, draw_right))
            cy = max(top, min(cy, draw_bottom))

            if is_effect_check:
                # 短线十字作为理论中心参考（未应用偏移）。
                ref_half_x = mm_to_dots(4.0, dpi_x)
                ref_half_y = mm_to_dots(4.0, dpi_y)
                ref_cx = max(left, min(ref_cx, draw_right))
                ref_cy = max(top, min(ref_cy, draw_bottom))
                dc.MoveTo((max(left, ref_cx - ref_half_x), ref_cy))
                dc.LineTo((min(draw_right, ref_cx + ref_half_x), ref_cy))
                dc.MoveTo((ref_cx, max(top, ref_cy - ref_half_y)))
                dc.LineTo((ref_cx, min(draw_bottom, ref_cy + ref_half_y)))

                ref_label = "理论中心参考"
                try:
                    ref_w, ref_h = dc.GetTextExtent(ref_label)
                except Exception:
                    ref_w = mm_to_dots(16, dpi_x)
                    ref_h = mm_to_dots(4, dpi_y)
                ref_tx = max(left, min(ref_cx + mm_to_dots(2.0, dpi_x), draw_right - ref_w))
                ref_ty = max(top, min(ref_cy - mm_to_dots(2.5, dpi_y) - ref_h, draw_bottom - ref_h))
                dc.TextOut(ref_tx, ref_ty, ref_label)

            dc.MoveTo((cx, top))
            dc.LineTo((cx, draw_bottom))
            dc.MoveTo((left, cy))
            dc.LineTo((draw_right, cy))
            if is_effect_check:
                tip_text = (
                    f"标定效果复测：当前偏移 X={cross_offset_x_mm:.2f}mm, "
                    f"Y={cross_offset_y_mm:.2f}mm；若仍有偏差请继续微调。"
                )
            else:
                tip_text = "初次标定：测量十字中心到上边/左边的距离，并回填到标定参数。"
            if requested_exceeds_physical:
                tip_text += " 注意：当前纸张尺寸疑似未生效（请求尺寸超过物理纸张尺寸）。"
            try:
                text_w_px, text_h_px = dc.GetTextExtent(tip_text)
            except Exception:
                # 回退估算，避免因驱动不支持文字测量导致失败。
                text_w_px = int(len(tip_text) * mm_to_dots(1.7, dpi_x))
                text_h_px = mm_to_dots(4.0, dpi_y)

            text_x = (page_w_px - text_w_px) // 2
            text_x = max(left, min(text_x, max(left, draw_right - text_w_px)))
            text_y = cy - mm_to_dots(2.0, dpi_y) - text_h_px
            text_y = max(top, text_y)
            dc.TextOut(text_x, text_y, tip_text)

            dc.EndPage()
            dc.EndDoc()
            dc.DeleteDC()
            return {
                "mode": "native",
                "printer_name": printer_name,
                "paper_name": paper_name,
                "paper_mm": {"width": width_mm, "height": height_mm},
                "cross_offset_mm": {"x": cross_offset_x_mm, "y": cross_offset_y_mm},
                "cross_center_mm": {
                    "x": round((width_mm / 2.0) + cross_offset_x_mm, 2),
                    "y": round((height_mm / 2.0) + cross_offset_y_mm, 2),
                },
                "paper_apply": paper_apply,
                "physical_area_px": {"width": physical_w_px, "height": physical_h_px},
                "printable_area_px": {"width": printable_w_px, "height": printable_h_px},
                "physical_offset_px": {"x": physical_offset_x_px, "y": physical_offset_y_px},
                "requested_area_px": {"width": page_w_px, "height": page_h_px},
                "requested_exceeds_physical": requested_exceeds_physical,
                "title": title,
            }
        except Exception as exc:  # pragma: no cover - depends on host
            raise CardPrintError(
                code="PRINT_CALIBRATION_FAILED",
                message="打印标定页失败。",
                details={"printer_name": printer_name, "paper_name": paper_name, "error": str(exc)},
            ) from exc

    def print_layout_rows(
        self,
        printer_name: str,
        paper_name: str,
        rows: list[list[LayoutItem]],
        job_name: str = "CardPrint Job",
    ) -> dict[str, Any]:
        if not self._native_enabled or self._is_mock_printer(printer_name):
            return {
                "mode": "dry_run",
                "printer_name": printer_name,
                "paper_name": paper_name,
                "rows": [
                    {
                        "index": index,
                        "status": "success",
                        "message": "dry-run 模式，未发送到实际打印机。",
                        "item_count": len(items),
                    }
                    for index, items in enumerate(rows)
                ],
            }

        try:  # pragma: no cover - depends on host
            dc = win32ui.CreateDC()
            dc.CreatePrinterDC(printer_name)
            paper_apply = self._apply_paper_to_dc(dc, printer_name, paper_name)
            dpi_x = dc.GetDeviceCaps(win32con.LOGPIXELSX)
            dpi_y = dc.GetDeviceCaps(win32con.LOGPIXELSY)
            physical_offset_x_px = dc.GetDeviceCaps(win32con.PHYSICALOFFSETX)
            physical_offset_y_px = dc.GetDeviceCaps(win32con.PHYSICALOFFSETY)
            self._preflight_qrcode_items(rows, dpi_x, dpi_y)

            result_rows: list[dict[str, Any]] = []
            dc.StartDoc(job_name)
            for index, items in enumerate(rows):
                try:
                    dc.StartPage()
                    for item in items:
                        if str(getattr(item, "render_type", "text")).strip().lower() == "qrcode":
                            self._draw_qrcode(
                                dc=dc,
                                item=item,
                                dpi_x=dpi_x,
                                dpi_y=dpi_y,
                                physical_offset_x_px=physical_offset_x_px,
                                physical_offset_y_px=physical_offset_y_px,
                            )
                            self._draw_border(
                                dc=dc,
                                item=item,
                                dpi_x=dpi_x,
                                dpi_y=dpi_y,
                                physical_offset_x_px=physical_offset_x_px,
                                physical_offset_y_px=physical_offset_y_px,
                            )
                        else:
                            font_spec = self._build_font_spec(item, dpi_y)
                            font = win32ui.CreateFont(font_spec)
                            dc.SelectObject(font)
                            self._draw_text(
                                dc=dc,
                                item=item,
                                dpi_x=dpi_x,
                                dpi_y=dpi_y,
                                physical_offset_x_px=physical_offset_x_px,
                                physical_offset_y_px=physical_offset_y_px,
                            )
                            self._draw_border(
                                dc=dc,
                                item=item,
                                dpi_x=dpi_x,
                                dpi_y=dpi_y,
                                physical_offset_x_px=physical_offset_x_px,
                                physical_offset_y_px=physical_offset_y_px,
                            )
                    dc.EndPage()
                    result_rows.append(
                        {
                            "index": index,
                            "status": "success",
                            "message": "",
                            "item_count": len(items),
                        }
                    )
                except Exception as row_exc:
                    try:
                        dc.EndPage()
                    except Exception:
                        pass
                    result_rows.append(
                        {
                            "index": index,
                            "status": "failed",
                            "message": str(row_exc),
                            "item_count": len(items),
                            "items": [asdict(item) for item in items],
                        }
                    )
            dc.EndDoc()
            dc.DeleteDC()
            return {
                "mode": "native",
                "printer_name": printer_name,
                "paper_name": paper_name,
                "paper_apply": paper_apply,
                "physical_offset_px": {"x": physical_offset_x_px, "y": physical_offset_y_px},
                "rows": result_rows,
            }
        except CardPrintError:
            if dc is not None:
                try:
                    dc.DeleteDC()
                except Exception:
                    pass
            raise
        except Exception as exc:  # pragma: no cover - depends on host
            raise CardPrintError(
                code="PRINT_JOB_FAILED",
                message="发送打印任务失败。",
                details={"printer_name": printer_name, "paper_name": paper_name, "error": str(exc)},
            ) from exc
