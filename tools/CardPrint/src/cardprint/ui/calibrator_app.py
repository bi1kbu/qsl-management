from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSplitter,
    QVBoxLayout,
    QWidget,
    QDoubleSpinBox,
)

from cardprint.core.models import Preset
from cardprint.core.preset_service import save_preset
from cardprint.preview.preview_scene import build_preview_scene
from cardprint.ui.cli_bridge import run_cli_json
from cardprint.ui.widgets.deadzone_editor import DeadzoneEditor
from cardprint.ui.widgets.field_editor import FieldEditor
from cardprint.ui.widgets.preview_canvas import PreviewCanvas


class CalibratorWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.paper_options: list[dict[str, Any]] = []
        self.setWindowTitle("CardPrint 标定工具")
        self.resize(1280, 760)

        splitter = QSplitter(Qt.Horizontal, self)
        splitter.addWidget(self._build_left_panel())
        self.preview = PreviewCanvas(self)
        self.preview.set_editable(True)
        splitter.addWidget(self.preview)
        splitter.setSizes([560, 720])
        self.setCentralWidget(splitter)

        self._bind_events()
        self.refresh_printers()
        self.refresh_papers()
        self.update_preview()

    def _build_left_panel(self) -> QWidget:
        container = QWidget(self)
        wrapper = QVBoxLayout(container)

        scroll = QScrollArea(container)
        scroll.setWidgetResizable(True)
        body = QWidget()
        scroll.setWidget(body)
        wrapper.addWidget(scroll)

        layout = QVBoxLayout(body)
        layout.setSpacing(10)

        self.preset_name = QLineEdit("模板A", body)
        self.preset_version = QLineEdit("1.0", body)
        self.paper_name = QLineEdit("CustomCard", body)
        self.paper_width = self._spin(85.6)
        self.paper_height = self._spin(54.0)
        self.paper_size_label = QLabel("未选择纸张", body)
        self.use_custom_paper = QCheckBox("自定义纸张参数", body)
        self.center_only_mode = QCheckBox("锁定死区", body)
        self.center_only_mode.setChecked(True)

        self.printer_combo = QComboBox(body)
        self.paper_combo = QComboBox(body)
        self.rotation_dir = QComboBox(body)
        self.rotation_dir.addItem("右旋", "right")
        self.rotation_dir.addItem("左旋", "left")
        self.rotation_degree = QComboBox(body)
        for degree in [90, 180, 270, 0]:
            self.rotation_degree.addItem(str(degree), degree)
        self.origin_center_x = self._spin(42.8)
        self.origin_center_y = self._spin(27.0)
        self.origin_offset_label = QLabel("换算偏移: X 0.00 mm, Y 0.00 mm", body)

        form = QFormLayout()
        form.addRow("预设名称", self.preset_name)
        form.addRow("预设版本", self.preset_version)
        form.addRow("打印机", self.printer_combo)
        form.addRow("纸张选项", self.paper_combo)
        form.addRow("纸张参数", self.paper_size_label)
        form.addRow("", self.use_custom_paper)
        form.addRow("旋转方向", self.rotation_dir)
        form.addRow("旋转角度", self.rotation_degree)
        form.addRow("原点中心 X(mm, 距左)", self.origin_center_x)
        form.addRow("原点中心 Y(mm, 距上)", self.origin_center_y)
        form.addRow("内部偏移", self.origin_offset_label)
        layout.addLayout(form)

        self.paper_custom_group = QGroupBox("自定义纸张参数", body)
        paper_custom_form = QFormLayout(self.paper_custom_group)
        paper_custom_form.addRow("纸张名称", self.paper_name)
        paper_custom_form.addRow("纸宽(mm)", self.paper_width)
        paper_custom_form.addRow("纸高(mm)", self.paper_height)
        self.paper_custom_group.setVisible(False)
        layout.addWidget(self.paper_custom_group)

        btn_row = QHBoxLayout()
        self.btn_refresh_printer = QPushButton("刷新打印机")
        self.btn_refresh_paper = QPushButton("刷新纸张")
        self.btn_print_cross = QPushButton("打印标定页")
        self.btn_print_effect = QPushButton("打印标定效果")
        btn_row.addWidget(self.btn_refresh_printer)
        btn_row.addWidget(self.btn_refresh_paper)
        btn_row.addWidget(self.btn_print_cross)
        btn_row.addWidget(self.btn_print_effect)
        layout.addLayout(btn_row)

        layout.addWidget(QLabel("死区设置", body))
        layout.addWidget(self.center_only_mode)
        self.deadzone_mode_label = QLabel("当前已锁定死区：死区参数不会被修改。", body)
        layout.addWidget(self.deadzone_mode_label)
        self.deadzone_editor = DeadzoneEditor(body)
        self.deadzone_editor.setEnabled(False)
        layout.addWidget(self.deadzone_editor)

        layout.addWidget(QLabel("字段模板（支持坐标/字体/样式；右侧预览可拖拽位置与文本框）", body))
        self.field_editor = FieldEditor(body)
        self.field_editor.setMinimumHeight(260)
        self.field_editor.add_row()
        layout.addWidget(self.field_editor)

        layout.addWidget(QLabel("打印程序 UI 额外输入方案（JSON）", body))
        self.ui_schema_edit = QLineEdit("[]", body)
        layout.addWidget(self.ui_schema_edit)

        bottom_row = QHBoxLayout()
        self.btn_preview = QPushButton("刷新预览")
        self.btn_save = QPushButton("保存预设")
        self.btn_load = QPushButton("加载预设")
        bottom_row.addWidget(self.btn_preview)
        bottom_row.addWidget(self.btn_save)
        bottom_row.addWidget(self.btn_load)
        layout.addLayout(bottom_row)
        layout.addStretch(1)

        return container

    def _spin(self, value: float) -> QDoubleSpinBox:
        spin = QDoubleSpinBox(self)
        spin.setRange(-9999, 9999)
        spin.setDecimals(2)
        spin.setSingleStep(0.1)
        spin.setValue(value)
        return spin

    def _bind_events(self) -> None:
        self.btn_refresh_printer.clicked.connect(self.refresh_printers)
        self.btn_refresh_paper.clicked.connect(self.refresh_papers)
        self.btn_print_cross.clicked.connect(self.print_cross)
        self.btn_print_effect.clicked.connect(self.print_calibrated_effect)
        self.btn_preview.clicked.connect(lambda: self.update_preview(show_error_dialog=True))
        self.btn_save.clicked.connect(self.save_preset_file)
        self.btn_load.clicked.connect(self.load_preset_file)
        self.printer_combo.currentTextChanged.connect(lambda _: self.refresh_papers())
        self.paper_combo.currentIndexChanged.connect(self._on_paper_option_changed)
        self.use_custom_paper.toggled.connect(self._on_custom_paper_toggled)
        self.center_only_mode.toggled.connect(self._on_center_only_mode_toggled)
        self.paper_name.textChanged.connect(self._on_paper_config_changed)
        self.paper_width.valueChanged.connect(self._on_paper_config_changed)
        self.paper_height.valueChanged.connect(self._on_paper_config_changed)
        self.deadzone_editor.valueChanged.connect(self._on_deadzone_changed)
        self.origin_center_x.valueChanged.connect(lambda _: self.update_preview())
        self.origin_center_y.valueChanged.connect(lambda _: self.update_preview())
        self.rotation_dir.currentIndexChanged.connect(lambda _: self.update_preview())
        self.rotation_degree.currentIndexChanged.connect(lambda _: self.update_preview())
        self.field_editor.fieldsChanged.connect(lambda: self.update_preview())
        self.field_editor.positionAdjusted.connect(lambda msg: self._show_soft_message(msg, 6000))
        self.preview.fieldMoved.connect(self._on_preview_field_moved)
        self.preview.fieldAreaChanged.connect(self._on_preview_field_area_changed)

    def show_error(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else json.dumps(details, ensure_ascii=False, indent=2)
        QMessageBox.critical(self, title, f"{message}\n{detail_text}")

    def show_info(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else json.dumps(details, ensure_ascii=False, indent=2)
        QMessageBox.information(self, title, f"{message}\n{detail_text}")

    def _show_soft_message(self, message: str, timeout_ms: int = 5000) -> None:
        self.statusBar().showMessage(message, timeout_ms)

    def refresh_printers(self) -> None:
        try:
            payload = run_cli_json(["printer", "list"])
            data = payload["data"]
            items = data.get("items", [])
            previous = self.printer_combo.currentText().strip().casefold()
            self.printer_combo.clear()
            self.printer_combo.addItems(items)
            if previous:
                for idx, name in enumerate(items):
                    if str(name).strip().casefold() == previous:
                        self.printer_combo.setCurrentIndex(idx)
                        break
        except Exception as exc:
            self.show_error("刷新打印机失败", str(exc))

    def _select_printer_by_name(self, printer_name: str, refresh_if_unchanged: bool = False) -> bool:
        target = printer_name.strip().casefold()
        if not target:
            return False
        current_index = self.printer_combo.currentIndex()
        for idx in range(self.printer_combo.count()):
            name = self.printer_combo.itemText(idx).strip().casefold()
            if name == target:
                self.printer_combo.setCurrentIndex(idx)
                # 当索引未变化时，Qt 不会发出 currentTextChanged；此处手动刷新一次纸张。
                if idx == current_index and refresh_if_unchanged:
                    self.refresh_papers()
                return True
        return False

    def refresh_papers(self) -> None:
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            return
        try:
            payload = run_cli_json(["printer", "papers", "--printer", printer_name])
            data = payload["data"]
            current = self._selected_paper_option()
            current_name = str(current.get("name", "")).strip().casefold() if current else ""

            options = data.get("options", [])
            if not isinstance(options, list) or not options:
                items = data.get("items", [])
                options = [
                    {"name": str(name).strip(), "width_mm": None, "height_mm": None, "source": "legacy"}
                    for name in items
                ]
            self.paper_options = [opt for opt in options if str(opt.get("name", "")).strip()]
            self.paper_combo.clear()
            for opt in self.paper_options:
                self.paper_combo.addItem(self._paper_option_label(opt), opt)

            if current_name:
                for idx, opt in enumerate(self.paper_options):
                    if str(opt.get("name", "")).strip().casefold() == current_name:
                        self.paper_combo.setCurrentIndex(idx)
                        break
            if self.paper_combo.count() > 0 and self.paper_combo.currentIndex() < 0:
                self.paper_combo.setCurrentIndex(0)
            self._apply_selected_paper_to_fields()
            self._update_field_position_limits()
            self.update_preview()
        except Exception as exc:
            self.show_error("刷新纸张失败", str(exc))

    def _paper_option_label(self, option: dict[str, Any]) -> str:
        name = str(option.get("name", "")).strip()
        width = option.get("width_mm")
        height = option.get("height_mm")
        if isinstance(width, (int, float)) and isinstance(height, (int, float)) and width > 0 and height > 0:
            return f"{name} ({width:g} x {height:g} mm)"
        return name

    def _selected_paper_option(self) -> dict[str, Any] | None:
        data = self.paper_combo.currentData()
        if isinstance(data, dict):
            return data
        idx = self.paper_combo.currentIndex()
        if 0 <= idx < len(self.paper_options):
            return self.paper_options[idx]
        return None

    def _apply_selected_paper_to_fields(self, reset_center: bool = True) -> None:
        option = self._selected_paper_option()
        if not option:
            self.paper_size_label.setText("未选择纸张")
            return
        name = str(option.get("name", "")).strip()
        width = option.get("width_mm")
        height = option.get("height_mm")
        self.paper_name.setText(name)
        if isinstance(width, (int, float)) and isinstance(height, (int, float)) and width > 0 and height > 0:
            self.paper_width.setValue(float(width))
            self.paper_height.setValue(float(height))
            self.paper_size_label.setText(f"{name} / {width:g} x {height:g} mm")
            if reset_center:
                self._reset_origin_center_to_theoretical(float(width), float(height))
        else:
            self.paper_size_label.setText(f"{name} / 未提供尺寸（可勾选自定义）")

    def _on_paper_option_changed(self) -> None:
        if not self.use_custom_paper.isChecked():
            self._apply_selected_paper_to_fields(reset_center=True)
        self._update_field_position_limits()
        self.update_preview()

    def _on_custom_paper_toggled(self, checked: bool) -> None:
        self.paper_custom_group.setVisible(checked)
        if not checked:
            self._apply_selected_paper_to_fields(reset_center=True)
        self._update_field_position_limits()
        self.update_preview()

    def _on_deadzone_changed(self) -> None:
        self._update_field_position_limits()
        self.update_preview()

    def _on_center_only_mode_toggled(self, checked: bool) -> None:
        self.deadzone_editor.setEnabled(not checked)
        if checked:
            self.deadzone_mode_label.setText("当前已锁定死区：死区参数不会被修改。")
        else:
            self.deadzone_mode_label.setText("已解锁死区：红色区域会同步更新并限制字段位置。")
        self._update_field_position_limits()
        self.update_preview()

    def _on_paper_config_changed(self, *_args: Any) -> None:
        self._update_field_position_limits()
        self.update_preview()

    def _on_preview_field_moved(self, key: str, x_mm: float, y_mm: float) -> None:
        self.field_editor.set_field_position(key=key, x_mm=x_mm, y_mm=y_mm)

    def _on_preview_field_area_changed(self, key: str, width_mm: float, height_mm: float) -> None:
        self.field_editor.set_field_print_area(
            key=key,
            width_mm=width_mm,
            height_mm=height_mm,
        )

    def _update_field_position_limits(self) -> None:
        try:
            _, width_mm, height_mm = self._resolve_effective_paper()
        except Exception:
            self.field_editor.clear_position_limits()
            return

        deadzone = self.deadzone_editor.value()
        x_min = float(deadzone.get("left", 0.0))
        x_max = float(width_mm - deadzone.get("right", 0.0))
        y_min = float(deadzone.get("top", 0.0))
        y_max = float(height_mm - deadzone.get("bottom", 0.0))
        if x_max < x_min or y_max < y_min:
            self.field_editor.clear_position_limits()
            return
        self.field_editor.set_position_limits(x_min=x_min, x_max=x_max, y_min=y_min, y_max=y_max)

    def _resolve_effective_paper(self) -> tuple[str, float, float]:
        if self.use_custom_paper.isChecked():
            name = self.paper_name.text().strip()
            width_mm = float(self.paper_width.value())
            height_mm = float(self.paper_height.value())
            if not name:
                raise ValueError("自定义纸张名称不能为空。")
            if width_mm <= 0 or height_mm <= 0:
                raise ValueError("自定义纸张宽高必须大于 0。")
            return name, width_mm, height_mm

        option = self._selected_paper_option()
        if not option:
            raise ValueError("请先选择打印机纸张。")
        name = str(option.get("name", "")).strip()
        width = option.get("width_mm")
        height = option.get("height_mm")
        if not name:
            raise ValueError("当前纸张名称无效。")
        if not isinstance(width, (int, float)) or not isinstance(height, (int, float)) or width <= 0 or height <= 0:
            raise ValueError("当前纸张未返回尺寸，请勾选“自定义纸张参数”后手动填写。")
        return name, float(width), float(height)

    def _reset_origin_center_to_theoretical(self, width_mm: float, height_mm: float) -> None:
        if width_mm <= 0 or height_mm <= 0:
            return
        self.origin_center_x.setValue(round(width_mm / 2.0, 2))
        self.origin_center_y.setValue(round(height_mm / 2.0, 2))

    def _set_origin_center_from_offset(
        self,
        paper_width_mm: float,
        paper_height_mm: float,
        offset_x_mm: float,
        offset_y_mm: float,
    ) -> None:
        if paper_width_mm <= 0 or paper_height_mm <= 0:
            return
        center_x = (paper_width_mm / 2.0) - offset_x_mm
        center_y = (paper_height_mm / 2.0) - offset_y_mm
        self.origin_center_x.setValue(round(center_x, 2))
        self.origin_center_y.setValue(round(center_y, 2))

    def _update_origin_offset_label(
        self,
        paper_width_mm: float,
        paper_height_mm: float,
        center_x_mm: float,
        center_y_mm: float,
    ) -> tuple[float, float]:
        offset_x = (paper_width_mm / 2.0) - center_x_mm
        offset_y = (paper_height_mm / 2.0) - center_y_mm
        self.origin_offset_label.setText(f"换算偏移: X {offset_x:.2f} mm, Y {offset_y:.2f} mm")
        return offset_x, offset_y

    def collect_preset(self) -> Preset:
        try:
            ui_schema = json.loads(self.ui_schema_edit.text().strip() or "[]")
        except json.JSONDecodeError as exc:
            raise ValueError(f"UI Schema JSON 无效: {exc}") from exc
        paper_name, paper_width_mm, paper_height_mm = self._resolve_effective_paper()
        center_x_mm = float(self.origin_center_x.value())
        center_y_mm = float(self.origin_center_y.value())
        origin_offset_x, origin_offset_y = self._update_origin_offset_label(
            paper_width_mm=paper_width_mm,
            paper_height_mm=paper_height_mm,
            center_x_mm=center_x_mm,
            center_y_mm=center_y_mm,
        )

        data = {
            "version": self.preset_version.text().strip(),
            "name": self.preset_name.text().strip(),
            "preferred_printer": self.printer_combo.currentText().strip(),
            "paper": {
                "name": paper_name,
                "width_mm": paper_width_mm,
                "height_mm": paper_height_mm,
            },
            "calibration": {
                "rotation_direction": self.rotation_dir.currentData(),
                "rotation_degree": int(self.rotation_degree.currentData()),
                "origin_offset_mm": {
                    "x": origin_offset_x,
                    "y": origin_offset_y,
                },
                "deadzone_mm": self.deadzone_editor.value(),
            },
            "fields": self.field_editor.get_fields(),
            "ui_schema": ui_schema,
        }
        return Preset.from_dict(data)

    def update_preview(self, show_error_dialog: bool = False) -> None:
        try:
            preset = self.collect_preset()
            # 标定预览仅展示字段锚点与名称，不再用“名称=值”造成重复显示。
            sample_row = {field.key: "" for field in preset.fields}
            scene = build_preview_scene(preset, sample_row)
            self.preview.set_scene(scene)
            self._show_soft_message("预览已更新。", 2000)
        except ValueError as exc:
            # 启动阶段或切换打印机阶段，允许暂时未选择纸张，不弹窗打断操作。
            self.preview.set_scene({})
            self.origin_offset_label.setText("换算偏移: X -, Y -")
            self._show_soft_message(str(exc))
            if show_error_dialog:
                self.show_error("预览更新失败", str(exc))
        except Exception as exc:
            self.preview.set_scene({})
            self.origin_offset_label.setText("换算偏移: X -, Y -")
            self._show_soft_message(f"预览更新失败：{exc}")
            if show_error_dialog:
                self.show_error("预览更新失败", str(exc))

    def print_cross(self) -> None:
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            self.show_error("参数缺失", "请先选择打印机。")
            return
        try:
            paper_name, paper_width_mm, paper_height_mm = self._resolve_effective_paper()
            payload = run_cli_json(
                [
                    "calibrate",
                    "print-cross",
                    "--printer",
                    printer_name,
                    "--paper",
                    paper_name,
                    "--width-mm",
                    str(paper_width_mm),
                    "--height-mm",
                    str(paper_height_mm),
                ]
            )
            self.show_info("标定页已发送", "请按打印结果进行测量。", payload.get("data"))
        except Exception as exc:
            self.show_error("打印标定页失败", str(exc))

    def print_calibrated_effect(self) -> None:
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            self.show_error("参数缺失", "请先选择打印机。")
            return
        try:
            preset = self.collect_preset()
            paper_name, paper_width_mm, paper_height_mm = self._resolve_effective_paper()
            payload = run_cli_json(
                [
                    "calibrate",
                    "print-cross",
                    "--printer",
                    printer_name,
                    "--paper",
                    paper_name,
                    "--width-mm",
                    str(paper_width_mm),
                    "--height-mm",
                    str(paper_height_mm),
                    "--cross-offset-x-mm",
                    str(preset.calibration.origin_offset_mm.x),
                    "--cross-offset-y-mm",
                    str(preset.calibration.origin_offset_mm.y),
                ],
                timeout_s=120.0,
            )
            self.show_info(
                "打印标定效果完成",
                "已按当前标定参数打印校正后十字，请重新测量中心位置。",
                payload.get("data"),
            )
        except Exception as exc:
            self.show_error("打印标定效果失败", str(exc))

    def save_preset_file(self) -> None:
        try:
            preset = self.collect_preset()
            path, _ = QFileDialog.getSaveFileName(
                self,
                "保存预设",
                str(Path.cwd() / "presets" / "preset.json"),
                "JSON Files (*.json)",
            )
            if not path:
                return
            save_preset(path, preset)
            self.show_info("保存成功", f"已保存到：{path}")
        except Exception as exc:
            self.show_error("保存失败", str(exc))

    def load_preset_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "加载预设", str(Path.cwd()), "JSON Files (*.json)")
        if not path:
            return
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
            preset = Preset.from_dict(data)
            self.preset_name.setText(preset.name)
            self.preset_version.setText(preset.version)
            if preset.preferred_printer:
                matched_printer = self._select_printer_by_name(preset.preferred_printer)
                if not matched_printer:
                    self._show_soft_message(f"预设打印机未找到：{preset.preferred_printer}")
            self.paper_name.setText(preset.paper.name)
            self.paper_width.setValue(preset.paper.width_mm)
            self.paper_height.setValue(preset.paper.height_mm)

            matched_idx = -1
            for idx, option in enumerate(self.paper_options):
                if str(option.get("name", "")).strip().casefold() == preset.paper.name.strip().casefold():
                    matched_idx = idx
                    break
            if matched_idx >= 0:
                self.paper_combo.setCurrentIndex(matched_idx)
                option = self.paper_options[matched_idx]
                opt_w = option.get("width_mm")
                opt_h = option.get("height_mm")
                if isinstance(opt_w, (int, float)) and isinstance(opt_h, (int, float)):
                    same_size = abs(float(opt_w) - preset.paper.width_mm) < 0.2 and abs(float(opt_h) - preset.paper.height_mm) < 0.2
                    self.use_custom_paper.setChecked(not same_size)
                else:
                    self.use_custom_paper.setChecked(True)
            else:
                self.use_custom_paper.setChecked(True)

            self.rotation_dir.setCurrentIndex(0 if preset.calibration.rotation_direction == "right" else 1)
            idx = self.rotation_degree.findData(preset.calibration.rotation_degree)
            if idx >= 0:
                self.rotation_degree.setCurrentIndex(idx)
            self._set_origin_center_from_offset(
                paper_width_mm=preset.paper.width_mm,
                paper_height_mm=preset.paper.height_mm,
                offset_x_mm=preset.calibration.origin_offset_mm.x,
                offset_y_mm=preset.calibration.origin_offset_mm.y,
            )
            self.deadzone_editor.set_value(
                {
                    "top": preset.calibration.deadzone_mm.top,
                    "right": preset.calibration.deadzone_mm.right,
                    "bottom": preset.calibration.deadzone_mm.bottom,
                    "left": preset.calibration.deadzone_mm.left,
                }
            )
            self._update_field_position_limits()
            self.field_editor.set_fields([field.__dict__ for field in preset.fields])
            self.ui_schema_edit.setText(json.dumps(preset.to_dict().get("ui_schema", []), ensure_ascii=False))
            self.update_preview()
            self.show_info("加载成功", f"已加载：{path}")
        except Exception as exc:
            self.show_error("加载失败", str(exc))


def main() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    w = CalibratorWindow()
    w.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
