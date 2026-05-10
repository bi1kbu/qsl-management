from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Literal

from .errors import CardPrintError

RotationDirection = Literal["left", "right"]
UiFieldType = Literal["text", "select", "checkbox"]

DEFAULT_HEADER_ALIASES: dict[str, str] = {
    "name": "name",
    "full_name": "name",
    "gender": "gender",
    "sex": "gender",
    "id": "id_no",
    "id_card": "id_no",
    "id_no": "id_no",
    "birthday": "birthday",
    "birth_date": "birthday",
    "phone": "phone",
    "mobile": "phone",
}


@dataclass
class PointMM:
    x: float
    y: float


@dataclass
class Paper:
    name: str
    width_mm: float
    height_mm: float

    def __post_init__(self) -> None:
        if self.width_mm <= 0 or self.height_mm <= 0:
            raise CardPrintError(
                code="INVALID_PAPER_SIZE",
                message="纸张宽高必须大于 0。",
                details={"width_mm": self.width_mm, "height_mm": self.height_mm},
            )


@dataclass
class Deadzone:
    top: float = 0.0
    right: float = 0.0
    bottom: float = 0.0
    left: float = 0.0

    def __post_init__(self) -> None:
        for key, value in asdict(self).items():
            if value < 0:
                raise CardPrintError(
                    code="INVALID_DEADZONE",
                    message="死区必须为非负值。",
                    details={"field": key, "value": value},
                )


@dataclass
class OriginOffset:
    x: float = 0.0
    y: float = 0.0


@dataclass
class Calibration:
    rotation_direction: RotationDirection = "right"
    rotation_degree: int = 90
    layout_rotation_enabled: bool = False
    origin_offset_mm: OriginOffset = field(default_factory=OriginOffset)
    deadzone_mm: Deadzone = field(default_factory=Deadzone)

    def __post_init__(self) -> None:
        if self.rotation_direction not in {"left", "right"}:
            raise CardPrintError(
                code="INVALID_ROTATION_DIRECTION",
                message="旋转方向只支持 left/right。",
                details={"rotation_direction": self.rotation_direction},
            )
        if self.rotation_degree not in {0, 90, 180, 270}:
            raise CardPrintError(
                code="INVALID_ROTATION_DEGREE",
                message="旋转角度只支持 0/90/180/270。",
                details={"rotation_degree": self.rotation_degree},
            )


@dataclass
class FieldDefinition:
    key: str
    label_zh: str
    x_mm: float
    y_mm: float
    print_width_mm: float = 0.0
    print_height_mm: float = 0.0
    font_family: str = "SimSun"
    font_size_pt: int = 11
    bold: bool = False
    italic: bool = False
    text_align: str = "left"
    distribute_align: bool = False
    max_len: int = 0

    def __post_init__(self) -> None:
        if not self.key:
            raise CardPrintError(
                code="INVALID_FIELD_KEY",
                message="字段 key 不能为空。",
                details={},
            )
        if self.font_size_pt <= 0:
            raise CardPrintError(
                code="INVALID_FONT_SIZE",
                message="字号必须大于 0。",
                details={"key": self.key, "font_size_pt": self.font_size_pt},
            )
        if self.max_len < 0:
            raise CardPrintError(
                code="INVALID_MAX_LEN",
                message="max_len 不能为负数。",
                details={"key": self.key, "max_len": self.max_len},
            )
        if self.print_width_mm < 0:
            raise CardPrintError(
                code="INVALID_PRINT_WIDTH",
                message="print_width_mm 不能为负数。",
                details={"key": self.key, "print_width_mm": self.print_width_mm},
            )
        if self.print_height_mm < 0:
            raise CardPrintError(
                code="INVALID_PRINT_HEIGHT",
                message="print_height_mm 不能为负数。",
                details={"key": self.key, "print_height_mm": self.print_height_mm},
            )
        if self.text_align not in {"left", "right"}:
            raise CardPrintError(
                code="INVALID_TEXT_ALIGN",
                message="text_align 仅支持 left/right。",
                details={"key": self.key, "text_align": self.text_align},
            )


@dataclass
class SelectOption:
    label: str
    value: str


@dataclass
class UiSchemaItem:
    key: str
    label_zh: str
    type: UiFieldType = "text"
    options: list[SelectOption] = field(default_factory=list)

    def __post_init__(self) -> None:
        if self.type not in {"text", "select", "checkbox"}:
            raise CardPrintError(
                code="INVALID_UI_FIELD_TYPE",
                message="UI 字段类型只支持 text/select/checkbox。",
                details={"key": self.key, "type": self.type},
            )
        if self.type == "select" and not self.options:
            raise CardPrintError(
                code="EMPTY_SELECT_OPTIONS",
                message="下拉项必须提供 options。",
                details={"key": self.key},
            )


@dataclass
class Preset:
    version: str
    name: str
    paper: Paper
    calibration: Calibration
    fields: list[FieldDefinition]
    preferred_printer: str = ""
    ui_schema: list[UiSchemaItem] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not self.version:
            raise CardPrintError(
                code="INVALID_PRESET_VERSION",
                message="预设 version 不能为空。",
                details={},
            )
        if not self.name:
            raise CardPrintError(
                code="INVALID_PRESET_NAME",
                message="预设名称不能为空。",
                details={},
            )
        if not self.fields:
            raise CardPrintError(
                code="EMPTY_PRESET_FIELDS",
                message="预设至少包含一个字段。",
                details={},
            )

        keys = [item.key for item in self.fields]
        duplicates = [key for key in keys if keys.count(key) > 1]
        if duplicates:
            raise CardPrintError(
                code="DUPLICATE_FIELD_KEYS",
                message="字段 key 不允许重复。",
                details={"duplicates": sorted(set(duplicates))},
            )

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "name": self.name,
            "preferred_printer": self.preferred_printer,
            "paper": asdict(self.paper),
            "calibration": {
                "rotation_direction": self.calibration.rotation_direction,
                "rotation_degree": self.calibration.rotation_degree,
                "layout_rotation_enabled": self.calibration.layout_rotation_enabled,
                "origin_offset_mm": asdict(self.calibration.origin_offset_mm),
                "deadzone_mm": asdict(self.calibration.deadzone_mm),
            },
            "fields": [asdict(item) for item in self.fields],
            "ui_schema": [
                {
                    "key": item.key,
                    "label_zh": item.label_zh,
                    "type": item.type,
                    "options": [asdict(opt) for opt in item.options],
                }
                for item in self.ui_schema
            ],
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Preset":
        def _to_float(raw: Any, *, field_name: str, key: str, default: float = 0.0) -> float:
            if raw is None or raw == "":
                return float(default)
            try:
                return float(raw)
            except (TypeError, ValueError) as exc:
                raise CardPrintError(
                    code="INVALID_FIELD_VALUE",
                    message="字段数值格式无效。",
                    details={"key": key, "field": field_name, "value": raw},
                ) from exc

        def _to_int(raw: Any, *, field_name: str, key: str, default: int = 0) -> int:
            if raw is None or raw == "":
                return int(default)
            try:
                return int(raw)
            except (TypeError, ValueError) as exc:
                raise CardPrintError(
                    code="INVALID_FIELD_VALUE",
                    message="字段数值格式无效。",
                    details={"key": key, "field": field_name, "value": raw},
                ) from exc

        def _to_bool(raw: Any, *, default: bool = False) -> bool:
            if isinstance(raw, bool):
                return raw
            if raw is None:
                return default
            text = str(raw).strip().lower()
            if text in {"1", "true", "yes", "y", "on"}:
                return True
            if text in {"0", "false", "no", "n", "off", ""}:
                return False
            return default

        def _to_text_align(raw: Any) -> str:
            text = str(raw or "").strip().lower()
            if text in {"right", "右", "右对齐"}:
                return "right"
            return "left"

        paper_data = data.get("paper", {})
        calibration_data = data.get("calibration", {})
        origin_data = calibration_data.get("origin_offset_mm", {})
        deadzone_data = calibration_data.get("deadzone_mm", {})

        fields: list[FieldDefinition] = []
        for item in data.get("fields", []):
            if not isinstance(item, dict):
                raise CardPrintError(
                    code="INVALID_FIELD_VALUE",
                    message="字段定义格式无效。",
                    details={"value": item},
                )
            key_name = str(item.get("key", "")).strip()
            fields.append(
                FieldDefinition(
                    key=key_name,
                    label_zh=str(item.get("label_zh", "")).strip(),
                    x_mm=_to_float(item.get("x_mm", 0), field_name="x_mm", key=key_name),
                    y_mm=_to_float(item.get("y_mm", 0), field_name="y_mm", key=key_name),
                    print_width_mm=_to_float(
                        item.get("print_width_mm", 0),
                        field_name="print_width_mm",
                        key=key_name,
                    ),
                    print_height_mm=_to_float(
                        item.get("print_height_mm", 0),
                        field_name="print_height_mm",
                        key=key_name,
                    ),
                    font_family=str(item.get("font_family", "SimSun") or "SimSun"),
                    font_size_pt=_to_int(
                        item.get("font_size_pt", 11),
                        field_name="font_size_pt",
                        key=key_name,
                        default=11,
                    ),
                    bold=_to_bool(item.get("bold", False)),
                    italic=_to_bool(item.get("italic", False)),
                    text_align=_to_text_align(item.get("text_align", "left")),
                    distribute_align=_to_bool(item.get("distribute_align", False)),
                    max_len=_to_int(item.get("max_len", 0), field_name="max_len", key=key_name, default=0),
                )
            )

        ui_items: list[UiSchemaItem] = []
        for raw_item in data.get("ui_schema", []):
            options = [
                SelectOption(**option) for option in raw_item.get("options", [])
            ]
            ui_items.append(
                UiSchemaItem(
                    key=raw_item.get("key", ""),
                    label_zh=raw_item.get("label_zh", ""),
                    type=raw_item.get("type", "text"),
                    options=options,
                )
            )

        preset = cls(
            version=data.get("version", ""),
            name=data.get("name", ""),
            paper=Paper(
                name=paper_data.get("name", ""),
                width_mm=float(paper_data.get("width_mm", 0)),
                height_mm=float(paper_data.get("height_mm", 0)),
            ),
            calibration=Calibration(
                rotation_direction=calibration_data.get("rotation_direction", "right"),
                rotation_degree=int(calibration_data.get("rotation_degree", 90)),
                layout_rotation_enabled=False,
                origin_offset_mm=OriginOffset(
                    x=float(origin_data.get("x", 0)),
                    y=float(origin_data.get("y", 0)),
                ),
                deadzone_mm=Deadzone(
                    top=float(deadzone_data.get("top", 0)),
                    right=float(deadzone_data.get("right", 0)),
                    bottom=float(deadzone_data.get("bottom", 0)),
                    left=float(deadzone_data.get("left", 0)),
                ),
            ),
            fields=fields,
            preferred_printer=str(data.get("preferred_printer", "")),
            ui_schema=ui_items,
        )
        return preset


@dataclass
class PrintJob:
    preset_path: str
    rows: list[dict[str, Any]]
    printer_name: str
    paper_name: str = ""

    def __post_init__(self) -> None:
        if not self.preset_path:
            raise CardPrintError(
                code="INVALID_JOB_PRESET",
                message="任务 preset_path 不能为空。",
                details={},
            )
        if not self.rows:
            raise CardPrintError(
                code="EMPTY_JOB_ROWS",
                message="打印任务至少包含一行数据。",
                details={},
            )
        if not self.printer_name:
            raise CardPrintError(
                code="INVALID_JOB_PRINTER",
                message="打印机名称不能为空。",
                details={},
            )

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "PrintJob":
        return cls(
            preset_path=str(data.get("preset_path", "")),
            rows=list(data.get("rows", [])),
            printer_name=str(data.get("printer_name", "")),
            paper_name=str(data.get("paper_name", "")),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "preset_path": self.preset_path,
            "rows": self.rows,
            "printer_name": self.printer_name,
            "paper_name": self.paper_name,
        }

    def preset_resolved_path(self, base_dir: str | Path | None = None) -> Path:
        path = Path(self.preset_path)
        if path.is_absolute() or base_dir is None:
            return path
        return Path(base_dir).joinpath(path).resolve()
