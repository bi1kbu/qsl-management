from __future__ import annotations

from typing import Any

from PySide6.QtCore import Signal
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (
    QComboBox,
    QHBoxLayout,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)


class FieldEditor(QWidget):
    fieldsChanged = Signal()
    positionAdjusted = Signal(str)

    COLUMNS = [
        "key",
        "label_zh",
        "x_mm",
        "y_mm",
        "print_width_mm",
        "print_height_mm",
        "print_border",
        "font_family",
        "font_size_pt",
        "bold",
        "italic",
        "text_align",
        "layout_mode",
        "distribute_align",
        "max_len",
        "digit_raise_ratio",
        "fixed_text",
    ]
    X_COL = COLUMNS.index("x_mm")
    Y_COL = COLUMNS.index("y_mm")
    W_COL = COLUMNS.index("print_width_mm")
    H_COL = COLUMNS.index("print_height_mm")
    BOOL_COLS = {
        COLUMNS.index("bold"),
        COLUMNS.index("italic"),
        COLUMNS.index("print_border"),
        COLUMNS.index("distribute_align"),
    }
    ALIGN_COL = COLUMNS.index("text_align")
    LAYOUT_MODE_COL = COLUMNS.index("layout_mode")
    HEADER_LABELS = [
        "字段Key",
        "字段名称",
        "X坐标(mm)",
        "Y坐标(mm)",
        "打印宽度(mm)",
        "打印高度(mm)",
        "打印边框",
        "字体",
        "字号(pt)",
        "加粗",
        "斜体",
        "文本对齐",
        "排版方式",
        "分散对齐",
        "最大长度",
        "数字上移比例",
        "固定文本",
    ]

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._x_min: float | None = None
        self._x_max: float | None = None
        self._y_min: float | None = None
        self._y_max: float | None = None
        self._updating = False

        self.table = QTableWidget(0, len(self.COLUMNS), self)
        self.table.setHorizontalHeaderLabels(self.HEADER_LABELS)
        self.table.itemChanged.connect(self._on_item_changed)

        add_btn = QPushButton("新增字段")
        remove_btn = QPushButton("删除字段")
        up_btn = QPushButton("上移")
        down_btn = QPushButton("下移")
        add_btn.clicked.connect(self.add_row)
        remove_btn.clicked.connect(self.remove_selected_row)
        up_btn.clicked.connect(self.move_selected_up)
        down_btn.clicked.connect(self.move_selected_down)

        btn_row = QHBoxLayout()
        btn_row.addWidget(add_btn)
        btn_row.addWidget(remove_btn)
        btn_row.addWidget(up_btn)
        btn_row.addWidget(down_btn)
        btn_row.addStretch(1)

        layout = QVBoxLayout(self)
        layout.addLayout(btn_row)
        layout.addWidget(self.table)

    def set_position_limits(self, x_min: float, x_max: float, y_min: float, y_max: float) -> None:
        self._x_min = float(x_min)
        self._x_max = float(x_max)
        self._y_min = float(y_min)
        self._y_max = float(y_max)
        self._clamp_all_rows()

    def clear_position_limits(self) -> None:
        self._x_min = None
        self._x_max = None
        self._y_min = None
        self._y_max = None
        self._apply_coord_cell_color()

    def add_row(self) -> None:
        x_default = self._x_min if self._x_min is not None else 10.0
        y_default = self._y_min if self._y_min is not None else 10.0
        row = self.table.rowCount()
        self._updating = True
        self.table.insertRow(row)
        defaults = [
            "field_key",
            "字段",
            f"{x_default:.2f}",
            f"{y_default:.2f}",
            "0",
            "0",
            "false",
            "SimSun",
            "11",
            "false",
            "false",
            "left",
            "horizontal",
            "false",
            "0",
            "0",
            "",
        ]
        for col, value in enumerate(defaults):
            self._set_cell_text(row, col, value)
        self._updating = False
        self._clamp_row(row, emit_message=False)
        self._apply_coord_cell_color()
        self.fieldsChanged.emit()

    def remove_selected_row(self) -> None:
        row = self.table.currentRow()
        if row >= 0:
            self.table.removeRow(row)
            self.fieldsChanged.emit()

    def _read_row_values(self, row: int) -> list[str]:
        values: list[str] = []
        for col in range(len(self.COLUMNS)):
            values.append(self._get_cell_text(row, col))
        return values

    def _write_row_values(self, row: int, values: list[str]) -> None:
        for col, value in enumerate(values):
            self._set_cell_text(row, col, value)

    def _move_selected_row(self, delta: int) -> None:
        row = self.table.currentRow()
        if row < 0:
            return
        target = row + delta
        if target < 0 or target >= self.table.rowCount():
            return
        current_values = self._read_row_values(row)
        target_values = self._read_row_values(target)
        self._updating = True
        self._write_row_values(row, target_values)
        self._write_row_values(target, current_values)
        self._updating = False
        self.table.setCurrentCell(target, self.table.currentColumn())
        self._apply_coord_cell_color()
        self.fieldsChanged.emit()

    def move_selected_up(self) -> None:
        self._move_selected_row(delta=-1)

    def move_selected_down(self) -> None:
        self._move_selected_row(delta=1)

    def _parse_float(self, text: str, fallback: float = 0.0) -> float:
        try:
            return float(text.strip())
        except Exception:
            return fallback

    def _set_cell_text(self, row: int, col: int, value: str) -> None:
        if col in self.BOOL_COLS:
            combo = self.table.cellWidget(row, col)
            if not isinstance(combo, QComboBox):
                combo = QComboBox(self.table)
                combo.addItem("false")
                combo.addItem("true")
                combo.currentTextChanged.connect(self._on_bool_combo_changed)
                self.table.setCellWidget(row, col, combo)
            normalized = self._normalize_bool_text(value)
            idx = combo.findText(normalized)
            combo.setCurrentIndex(idx if idx >= 0 else 0)
            if self.table.item(row, col) is None:
                self.table.setItem(row, col, QTableWidgetItem(normalized))
            else:
                self.table.item(row, col).setText(normalized)
            return
        if col == self.ALIGN_COL:
            combo = self.table.cellWidget(row, col)
            if not isinstance(combo, QComboBox):
                combo = QComboBox(self.table)
                combo.addItem("left")
                combo.addItem("center")
                combo.addItem("right")
                combo.currentTextChanged.connect(self._on_bool_combo_changed)
                self.table.setCellWidget(row, col, combo)
            normalized = self._normalize_align_text(value)
            idx = combo.findText(normalized)
            combo.setCurrentIndex(idx if idx >= 0 else 0)
            if self.table.item(row, col) is None:
                self.table.setItem(row, col, QTableWidgetItem(normalized))
            else:
                self.table.item(row, col).setText(normalized)
            return
        if col == self.LAYOUT_MODE_COL:
            combo = self.table.cellWidget(row, col)
            if not isinstance(combo, QComboBox):
                combo = QComboBox(self.table)
                combo.addItem("horizontal")
                combo.addItem("vertical")
                combo.addItem("mixed_vertical")
                combo.currentTextChanged.connect(self._on_bool_combo_changed)
                self.table.setCellWidget(row, col, combo)
            normalized = self._normalize_layout_mode_text(value)
            idx = combo.findText(normalized)
            combo.setCurrentIndex(idx if idx >= 0 else 0)
            if self.table.item(row, col) is None:
                self.table.setItem(row, col, QTableWidgetItem(normalized))
            else:
                self.table.item(row, col).setText(normalized)
            return
        item = self.table.item(row, col)
        if item is None:
            item = QTableWidgetItem(value)
            self.table.setItem(row, col, item)
            return
        item.setText(value)

    def _get_cell_text(self, row: int, col: int) -> str:
        if col in self.BOOL_COLS:
            combo = self.table.cellWidget(row, col)
            if isinstance(combo, QComboBox):
                return self._normalize_bool_text(combo.currentText())
        if col == self.ALIGN_COL:
            combo = self.table.cellWidget(row, col)
            if isinstance(combo, QComboBox):
                return self._normalize_align_text(combo.currentText())
        if col == self.LAYOUT_MODE_COL:
            combo = self.table.cellWidget(row, col)
            if isinstance(combo, QComboBox):
                return self._normalize_layout_mode_text(combo.currentText())
        item = self.table.item(row, col)
        return item.text().strip() if item else ""

    def _normalize_bool_text(self, value: Any) -> str:
        return "true" if str(value).strip().lower() == "true" else "false"

    def _normalize_align_text(self, value: Any) -> str:
        text = str(value).strip().lower()
        if text in {"center", "中", "居中", "居中对齐"}:
            return "center"
        if text in {"right", "右", "右对齐"}:
            return "right"
        return "left"

    def _normalize_layout_mode_text(self, value: Any) -> str:
        text = str(value).strip().lower()
        if text in {"vertical", "纵向", "竖向"}:
            return "vertical"
        if text in {"mixed_vertical", "mixed-vertical", "混合纵向", "混合竖向"}:
            return "mixed_vertical"
        return "horizontal"

    def _on_bool_combo_changed(self, _: str) -> None:
        if self._updating:
            return
        self.fieldsChanged.emit()

    def _clamp(self, value: float, minimum: float | None, maximum: float | None) -> float:
        out = value
        if minimum is not None and out < minimum:
            out = minimum
        if maximum is not None and out > maximum:
            out = maximum
        return out

    def _limits_ready(self) -> bool:
        return (
            self._x_min is not None
            and self._x_max is not None
            and self._y_min is not None
            and self._y_max is not None
            and self._x_max >= self._x_min
            and self._y_max >= self._y_min
        )

    def _clamp_row(self, row: int, emit_message: bool = True) -> None:
        x_item = self.table.item(row, self.X_COL)
        y_item = self.table.item(row, self.Y_COL)
        w_item = self.table.item(row, self.W_COL)
        h_item = self.table.item(row, self.H_COL)
        x_val = self._parse_float(x_item.text() if x_item else "0", 0.0)
        y_val = self._parse_float(y_item.text() if y_item else "0", 0.0)
        w_val = self._parse_float(w_item.text() if w_item else "0", 0.0)
        h_val = self._parse_float(h_item.text() if h_item else "0", 0.0)

        if not self._limits_ready():
            clamped_w = max(0.0, w_val)
            clamped_h = max(0.0, h_val)
            changed = abs(clamped_w - w_val) > 1e-9 or abs(clamped_h - h_val) > 1e-9
            if changed:
                self._updating = True
                self._set_cell_text(row, self.W_COL, f"{clamped_w:.2f}")
                self._set_cell_text(row, self.H_COL, f"{clamped_h:.2f}")
                self._updating = False
            return

        clamped_x = self._clamp(x_val, self._x_min, self._x_max)
        clamped_y = self._clamp(y_val, self._y_min, self._y_max)
        max_w = max(0.0, float(self._x_max) - clamped_x)
        max_h = max(0.0, float(self._y_max) - clamped_y)
        clamped_w = self._clamp(max(0.0, w_val), 0.0, max_w)
        clamped_h = self._clamp(max(0.0, h_val), 0.0, max_h)

        changed = (
            abs(clamped_x - x_val) > 1e-9
            or abs(clamped_y - y_val) > 1e-9
            or abs(clamped_w - w_val) > 1e-9
            or abs(clamped_h - h_val) > 1e-9
        )
        if changed:
            self._updating = True
            self._set_cell_text(row, self.X_COL, f"{clamped_x:.2f}")
            self._set_cell_text(row, self.Y_COL, f"{clamped_y:.2f}")
            self._set_cell_text(row, self.W_COL, f"{clamped_w:.2f}")
            self._set_cell_text(row, self.H_COL, f"{clamped_h:.2f}")
            self._updating = False
            if emit_message:
                key_item = self.table.item(row, self.COLUMNS.index("key"))
                key_name = key_item.text().strip() if key_item else f"row_{row+1}"
                self.positionAdjusted.emit(
                    f"字段 {key_name} 位置或可打印区域超出范围，已自动调整。"
                )

    def _clamp_all_rows(self) -> None:
        if self.table.rowCount() <= 0:
            return
        for row in range(self.table.rowCount()):
            self._clamp_row(row, emit_message=False)
        self._apply_coord_cell_color()
        self.fieldsChanged.emit()

    def _apply_coord_cell_color(self) -> None:
        in_range_color = QColor("#F8FAFC")
        limited_color = QColor("#FFF1F2")
        for row in range(self.table.rowCount()):
            for col in (self.X_COL, self.Y_COL):
                item = self.table.item(row, col)
                if item is None:
                    continue
                if self._limits_ready():
                    item.setBackground(limited_color)
                else:
                    item.setBackground(in_range_color)

    def _on_item_changed(self, item: QTableWidgetItem) -> None:
        if self._updating:
            return
        if item.column() in (self.X_COL, self.Y_COL, self.W_COL, self.H_COL):
            self._clamp_row(item.row(), emit_message=True)
            self._apply_coord_cell_color()
        self.fieldsChanged.emit()

    def _find_row_by_key(self, key: str) -> int:
        target = key.strip().casefold()
        if not target:
            return -1
        key_col = self.COLUMNS.index("key")
        for row in range(self.table.rowCount()):
            if self._get_cell_text(row, key_col).casefold() == target:
                return row
        return -1

    def set_field_position(self, key: str, x_mm: float, y_mm: float) -> bool:
        row = self._find_row_by_key(key)
        if row < 0:
            return False
        self._updating = True
        self._set_cell_text(row, self.X_COL, f"{float(x_mm):.2f}")
        self._set_cell_text(row, self.Y_COL, f"{float(y_mm):.2f}")
        self._updating = False
        self._clamp_row(row, emit_message=False)
        self._apply_coord_cell_color()
        self.fieldsChanged.emit()
        return True

    def set_field_print_area(self, key: str, width_mm: float, height_mm: float) -> bool:
        row = self._find_row_by_key(key)
        if row < 0:
            return False
        self._updating = True
        self._set_cell_text(row, self.W_COL, f"{max(0.0, float(width_mm)):.2f}")
        self._set_cell_text(row, self.H_COL, f"{max(0.0, float(height_mm)):.2f}")
        self._updating = False
        self._clamp_row(row, emit_message=False)
        self._apply_coord_cell_color()
        self.fieldsChanged.emit()
        return True

    def get_fields(self) -> list[dict[str, Any]]:
        fields: list[dict[str, Any]] = []
        for row in range(self.table.rowCount()):
            values: dict[str, Any] = {}
            for col, key in enumerate(self.COLUMNS):
                values[key] = self._get_cell_text(row, col)
            fields.append(
                {
                    "key": values["key"],
                    "label_zh": values["label_zh"],
                    "x_mm": float(values["x_mm"] or 0),
                    "y_mm": float(values["y_mm"] or 0),
                    "print_width_mm": float(values["print_width_mm"] or 0),
                    "print_height_mm": float(values["print_height_mm"] or 0),
                    "print_border": str(values["print_border"]).lower() == "true",
                    "font_family": values["font_family"] or "SimSun",
                    "font_size_pt": int(values["font_size_pt"] or 11),
                    "bold": str(values["bold"]).lower() == "true",
                    "italic": str(values["italic"]).lower() == "true",
                    "text_align": self._normalize_align_text(values["text_align"]),
                    "layout_mode": self._normalize_layout_mode_text(values["layout_mode"]),
                    "distribute_align": str(values["distribute_align"]).lower() == "true",
                    "max_len": int(values["max_len"] or 0),
                    "digit_raise_ratio": float(values["digit_raise_ratio"] or 0),
                    "fixed_text": values["fixed_text"],
                }
            )
        return fields

    def set_fields(self, fields: list[dict[str, Any]]) -> None:
        self._updating = True
        self.table.setRowCount(0)
        for field in fields:
            row = self.table.rowCount()
            self.table.insertRow(row)
            for col, key in enumerate(self.COLUMNS):
                value = field.get(key, "")
                self._set_cell_text(row, col, str(value))
        self._updating = False
        self._clamp_all_rows()
