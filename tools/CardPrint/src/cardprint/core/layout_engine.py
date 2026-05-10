from __future__ import annotations

import unicodedata
from dataclasses import asdict, dataclass
from typing import Any

from .errors import CardPrintError
from .models import Deadzone, FieldDefinition, Paper, Preset
from .transform import transform_logical_to_physical


@dataclass
class PrintableBounds:
    left: float
    top: float
    right: float
    bottom: float

    def contains(self, x_mm: float, y_mm: float) -> bool:
        return self.left <= x_mm <= self.right and self.top <= y_mm <= self.bottom


@dataclass
class LayoutItem:
    key: str
    label_zh: str
    text: str
    logical_x_mm: float
    logical_y_mm: float
    physical_x_mm: float
    physical_y_mm: float
    font_family: str
    font_size_pt: int
    bold: bool
    italic: bool
    text_align: str
    distribute_align: bool
    line_index: int = 0
    line_count: int = 1
    anchor_x_mm: float = 0.0
    anchor_y_mm: float = 0.0
    print_width_mm: float = 0.0
    print_height_mm: float = 0.0
    line_height_mm: float = 0.0
    render_type: str = "text"
    qr_payload: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def printable_bounds(paper: Paper, deadzone: Deadzone) -> PrintableBounds:
    right = paper.width_mm - deadzone.right
    bottom = paper.height_mm - deadzone.bottom
    return PrintableBounds(
        left=deadzone.left,
        top=deadzone.top,
        right=right,
        bottom=bottom,
    )


def validate_deadzone(paper: Paper, deadzone: Deadzone) -> list[str]:
    errors: list[str] = []
    if deadzone.left + deadzone.right >= paper.width_mm:
        errors.append("左右死区之和不能大于等于纸张宽度。")
    if deadzone.top + deadzone.bottom >= paper.height_mm:
        errors.append("上下死区之和不能大于等于纸张高度。")
    return errors


def _format_field_text(field: FieldDefinition, row: dict[str, Any]) -> str:
    value = row.get(field.key, "")
    text = "" if value is None else str(value)
    if field.max_len > 0:
        text = text[: field.max_len]
    return text


def _pt_to_mm(pt: float) -> float:
    return float(pt) * 25.4 / 72.0


def _line_height_mm(field: FieldDefinition) -> float:
    return max(0.1, _pt_to_mm(float(field.font_size_pt)) * 1.2)


def _glyph_height_mm(field: FieldDefinition) -> float:
    return max(0.1, _pt_to_mm(float(field.font_size_pt)))


def _char_display_units(ch: str) -> int:
    # 全角/宽字符按 2 单位，半角按 1 单位。
    return 2 if unicodedata.east_asian_width(ch) in {"W", "F"} else 1


def _max_units_per_line(field: FieldDefinition) -> int | None:
    width_mm = float(field.print_width_mm)
    if width_mm <= 0:
        return None
    # 1 个“半角单位”约等于字号物理宽度的一半；中文全角字符占 2 单位。
    unit_width_mm = max(0.1, _pt_to_mm(float(field.font_size_pt)) * 0.5)
    return max(1, int(width_mm // unit_width_mm))


def _split_text_by_units(text: str, max_units: int) -> list[str]:
    if max_units <= 0:
        return [text]
    lines: list[str] = []
    for part in (text.splitlines() or [""]):
        if part == "":
            lines.append("")
            continue
        current_chars: list[str] = []
        current_units = 0
        for ch in part:
            ch_units = _char_display_units(ch)
            if current_chars and (current_units + ch_units) > max_units:
                lines.append("".join(current_chars))
                current_chars = [ch]
                current_units = ch_units
            else:
                current_chars.append(ch)
                current_units += ch_units
        if current_chars:
            lines.append("".join(current_chars))
    return lines or [""]


def _wrap_field_text(field: FieldDefinition, text: str) -> tuple[list[str], float]:
    line_height_mm = _line_height_mm(field)
    max_units = _max_units_per_line(field)
    if max_units is None:
        return [text], line_height_mm

    lines = _split_text_by_units(text, max_units=max_units)

    if field.print_height_mm > 0:
        available_for_next_line = max(0.0, float(field.print_height_mm) - _glyph_height_mm(field))
        max_lines = max(1, int(available_for_next_line // line_height_mm) + 1)
        lines = lines[:max_lines]
    return lines, line_height_mm


def validate_fields_in_printable_area(preset: Preset) -> list[dict[str, Any]]:
    bounds = printable_bounds(preset.paper, preset.calibration.deadzone_mm)
    issues: list[dict[str, Any]] = []
    for field in preset.fields:
        if not bounds.contains(float(field.x_mm), float(field.y_mm)):
            issues.append(
                {
                    "key": field.key,
                    "message": "字段锚点落入死区或越界。",
                    "field_point_mm": {"x": field.x_mm, "y": field.y_mm},
                    "printable_bounds_mm": asdict(bounds),
                }
            )
            continue

        if field.print_width_mm > 0:
            right = float(field.x_mm) + float(field.print_width_mm)
            if right > bounds.right:
                issues.append(
                    {
                        "key": field.key,
                        "message": "字段可打印宽度超出可打印范围。",
                        "field_area_mm": {
                            "x": field.x_mm,
                            "y": field.y_mm,
                            "width": field.print_width_mm,
                            "height": field.print_height_mm,
                        },
                        "printable_bounds_mm": asdict(bounds),
                    }
                )

        if field.print_height_mm > 0:
            bottom = float(field.y_mm) + float(field.print_height_mm)
            if bottom > bounds.bottom:
                issues.append(
                    {
                        "key": field.key,
                        "message": "字段可打印高度超出可打印范围。",
                        "field_area_mm": {
                            "x": field.x_mm,
                            "y": field.y_mm,
                            "width": field.print_width_mm,
                            "height": field.print_height_mm,
                        },
                        "printable_bounds_mm": asdict(bounds),
                    }
                )
    return issues


def validate_preset_layout(preset: Preset) -> None:
    deadzone_errors = validate_deadzone(preset.paper, preset.calibration.deadzone_mm)
    if deadzone_errors:
        raise CardPrintError(
            code="INVALID_DEADZONE_RANGE",
            message="死区范围无效。",
            details={"errors": deadzone_errors},
        )
    field_issues = validate_fields_in_printable_area(preset)
    if field_issues:
        raise CardPrintError(
            code="FIELD_IN_DEADZONE",
            message="存在字段落入死区或越界。",
            details={"issues": field_issues},
        )


def build_layout_items(preset: Preset, row: dict[str, Any]) -> list[LayoutItem]:
    validate_preset_layout(preset)
    items: list[LayoutItem] = []
    for field in preset.fields:
        text = _format_field_text(field, row)
        if field.key.strip().upper() == "QRCODE":
            point = transform_logical_to_physical(
                x_mm=float(field.x_mm),
                y_mm=float(field.y_mm),
                paper_width_mm=preset.paper.width_mm,
                paper_height_mm=preset.paper.height_mm,
                calibration=preset.calibration,
            )
            items.append(
                LayoutItem(
                    key=field.key,
                    label_zh=field.label_zh,
                    text="",
                    logical_x_mm=float(field.x_mm),
                    logical_y_mm=float(field.y_mm),
                    physical_x_mm=point.x,
                    physical_y_mm=point.y,
                    font_family=field.font_family,
                    font_size_pt=field.font_size_pt,
                    bold=field.bold,
                    italic=field.italic,
                    text_align=field.text_align,
                    distribute_align=field.distribute_align,
                    line_index=0,
                    line_count=1,
                    anchor_x_mm=float(field.x_mm),
                    anchor_y_mm=float(field.y_mm),
                    print_width_mm=float(field.print_width_mm),
                    print_height_mm=float(field.print_height_mm),
                    line_height_mm=_line_height_mm(field),
                    render_type="qrcode",
                    qr_payload=text.strip(),
                )
            )
            continue
        lines, line_height_mm = _wrap_field_text(field, text)
        total_lines = max(1, len(lines))
        for idx, line_text in enumerate(lines):
            logical_y = float(field.y_mm) + (idx * line_height_mm)
            point = transform_logical_to_physical(
                x_mm=float(field.x_mm),
                y_mm=logical_y,
                paper_width_mm=preset.paper.width_mm,
                paper_height_mm=preset.paper.height_mm,
                calibration=preset.calibration,
            )
            items.append(
                LayoutItem(
                    key=field.key,
                    label_zh=field.label_zh,
                    text=line_text,
                    logical_x_mm=float(field.x_mm),
                    logical_y_mm=logical_y,
                    physical_x_mm=point.x,
                    physical_y_mm=point.y,
                    font_family=field.font_family,
                    font_size_pt=field.font_size_pt,
                    bold=field.bold,
                    italic=field.italic,
                    text_align=field.text_align,
                    distribute_align=field.distribute_align,
                    line_index=idx,
                    line_count=total_lines,
                    anchor_x_mm=float(field.x_mm),
                    anchor_y_mm=float(field.y_mm),
                    print_width_mm=float(field.print_width_mm),
                    print_height_mm=float(field.print_height_mm),
                    line_height_mm=line_height_mm,
                    render_type="text",
                    qr_payload="",
                )
            )
    return items
