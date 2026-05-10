from __future__ import annotations

import json
import sys
import tempfile
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
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from cardprint.core.datasource_service import load_rows
from cardprint.core.errors import CardPrintError
from cardprint.ui.cli_bridge import run_cli_json
from cardprint.ui.widgets.preview_canvas import PreviewCanvas


class PrinterWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CardPrint 打印工具")
        self.resize(1280, 760)

        self.preset_path: str = ""
        self.preset_data: dict[str, Any] = {}
        self.row_queue: list[dict[str, Any]] = []
        self.input_widgets: dict[str, QWidget] = {}

        splitter = QSplitter(Qt.Horizontal, self)
        splitter.addWidget(self._build_left_panel())
        self.preview = PreviewCanvas(self)
        self.preview.set_editable(False)
        splitter.addWidget(self.preview)
        splitter.setSizes([560, 720])
        self.setCentralWidget(splitter)

        self.refresh_printers()
        self.refresh_papers()

    def _build_left_panel(self) -> QWidget:
        left = QWidget(self)
        outer = QVBoxLayout(left)

        top_row = QHBoxLayout()
        self.btn_load_preset = QPushButton("加载预设")
        self.lbl_preset = QLabel("未加载")
        top_row.addWidget(self.btn_load_preset)
        top_row.addWidget(self.lbl_preset, 1)
        outer.addLayout(top_row)

        self.lbl_calibration = QLabel("标定参数：-", left)
        self.lbl_calibration.setWordWrap(True)
        outer.addWidget(self.lbl_calibration)

        printer_box = QGroupBox("打印设备", left)
        printer_form = QFormLayout(printer_box)
        self.printer_combo = QComboBox(printer_box)
        self.paper_combo = QComboBox(printer_box)
        self.btn_refresh_printer = QPushButton("刷新打印机", printer_box)
        self.btn_refresh_paper = QPushButton("刷新纸张", printer_box)
        printer_form.addRow("打印机", self.printer_combo)
        printer_form.addRow("纸张", self.paper_combo)
        printer_form.addRow(self.btn_refresh_printer, self.btn_refresh_paper)
        outer.addWidget(printer_box)

        self.input_group = QGroupBox("当前记录输入", left)
        self.input_form = QFormLayout(self.input_group)
        self.input_placeholder = QLabel("请先加载预设。", self.input_group)
        self.input_form.addRow(self.input_placeholder)
        outer.addWidget(self.input_group)

        input_btn_row = QHBoxLayout()
        self.btn_add_row = QPushButton("加入队列")
        self.btn_preview_current = QPushButton("预览当前")
        input_btn_row.addWidget(self.btn_add_row)
        input_btn_row.addWidget(self.btn_preview_current)
        outer.addLayout(input_btn_row)

        import_row = QHBoxLayout()
        self.btn_import_file = QPushButton("导入 CSV/XLSX")
        self.btn_clear_queue = QPushButton("清空队列")
        import_row.addWidget(self.btn_import_file)
        import_row.addWidget(self.btn_clear_queue)
        outer.addLayout(import_row)

        self.queue_table = QTableWidget(0, 2, left)
        self.queue_table.setHorizontalHeaderLabels(["序号", "数据摘要"])
        outer.addWidget(self.queue_table, 1)

        print_row = QHBoxLayout()
        self.btn_print_selected = QPushButton("打印选中")
        self.btn_print_all = QPushButton("打印全部")
        print_row.addWidget(self.btn_print_selected)
        print_row.addWidget(self.btn_print_all)
        outer.addLayout(print_row)

        self.btn_load_preset.clicked.connect(self.load_preset)
        self.btn_refresh_printer.clicked.connect(self.refresh_printers)
        self.btn_refresh_paper.clicked.connect(self.refresh_papers)
        self.printer_combo.currentTextChanged.connect(lambda _: self.refresh_papers())
        self.btn_preview_current.clicked.connect(self.preview_current_input)
        self.btn_add_row.clicked.connect(self.add_current_row)
        self.btn_import_file.clicked.connect(self.import_file)
        self.btn_clear_queue.clicked.connect(self.clear_queue)
        self.queue_table.itemSelectionChanged.connect(self.preview_selected_row)
        self.btn_print_selected.clicked.connect(self.print_selected_row)
        self.btn_print_all.clicked.connect(self.print_all_rows)

        return left

    def show_error(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else json.dumps(details, ensure_ascii=False, indent=2)
        QMessageBox.critical(self, title, f"{message}\n{detail_text}")

    def show_info(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else json.dumps(details, ensure_ascii=False, indent=2)
        QMessageBox.information(self, title, f"{message}\n{detail_text}")

    def refresh_printers(self) -> None:
        try:
            payload = run_cli_json(["printer", "list"])
            items = payload["data"].get("items", [])
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
            items = payload["data"].get("items", [])
            self.paper_combo.clear()
            self.paper_combo.addItems(items)
        except Exception as exc:
            self.show_error("刷新纸张失败", str(exc))

    def load_preset(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "选择预设文件", str(Path.cwd()), "JSON Files (*.json)")
        if not path:
            return
        try:
            run_cli_json(["preset", "validate", "--preset", path])
            self.preset_data = json.loads(Path(path).read_text(encoding="utf-8"))
            self.preset_path = path
            self.lbl_preset.setText(path)

            preferred_printer = str(self.preset_data.get("preferred_printer", "")).strip()
            if preferred_printer:
                matched = self._select_printer_by_name(preferred_printer)
                if not matched:
                    self.show_info("提示", f"预设打印机未找到：{preferred_printer}，已保留当前打印机。")

            cal = self.preset_data.get("calibration", {})
            deadzone = cal.get("deadzone_mm", {})
            summary = (
                f"方向:{cal.get('rotation_direction')} 角度:{cal.get('rotation_degree')} "
                f"原点偏移:({cal.get('origin_offset_mm', {}).get('x', 0)}, "
                f"{cal.get('origin_offset_mm', {}).get('y', 0)}) "
                f"死区:{deadzone}"
            )
            self.lbl_calibration.setText(f"标定参数（只读）：{summary}")

            preset_paper_name = str(self.preset_data.get("paper", {}).get("name", "")).strip().casefold()
            if preset_paper_name:
                for idx in range(self.paper_combo.count()):
                    current_name = self.paper_combo.itemText(idx).strip().casefold()
                    if current_name == preset_paper_name:
                        self.paper_combo.setCurrentIndex(idx)
                        break

            self._build_inputs_from_preset()
            self.clear_queue()
            self.preview_current_input()
        except Exception as exc:
            self.show_error("加载预设失败", str(exc))

    def _clear_input_form(self) -> None:
        while self.input_form.rowCount() > 0:
            self.input_form.removeRow(0)
        self.input_widgets.clear()

    def _build_inputs_from_preset(self) -> None:
        self._clear_input_form()
        fields = self.preset_data.get("fields", [])
        for field in fields:
            key = field.get("key", "")
            label = field.get("label_zh", key)
            widget = QLineEdit(self.input_group)
            self.input_form.addRow(label, widget)
            self.input_widgets[key] = widget

        for item in self.preset_data.get("ui_schema", []):
            key = item.get("key", "")
            if not key or key in self.input_widgets:
                continue
            label = item.get("label_zh", key)
            ui_type = item.get("type", "text")
            if ui_type == "select":
                widget = QComboBox(self.input_group)
                for option in item.get("options", []):
                    widget.addItem(str(option.get("label", option.get("value", ""))), str(option.get("value", "")))
            elif ui_type == "checkbox":
                widget = QCheckBox(self.input_group)
            else:
                widget = QLineEdit(self.input_group)
            self.input_form.addRow(label, widget)
            self.input_widgets[key] = widget

    def _collect_current_row(self) -> dict[str, Any]:
        row: dict[str, Any] = {}
        for key, widget in self.input_widgets.items():
            if isinstance(widget, QLineEdit):
                row[key] = widget.text()
            elif isinstance(widget, QComboBox):
                row[key] = widget.currentData()
            elif isinstance(widget, QCheckBox):
                row[key] = widget.isChecked()
            else:
                row[key] = ""
        return row

    def _set_row_to_inputs(self, row: dict[str, Any]) -> None:
        for key, widget in self.input_widgets.items():
            value = row.get(key, "")
            if isinstance(widget, QLineEdit):
                widget.setText("" if value is None else str(value))
            elif isinstance(widget, QComboBox):
                idx = widget.findData(value)
                if idx >= 0:
                    widget.setCurrentIndex(idx)
            elif isinstance(widget, QCheckBox):
                widget.setChecked(bool(value))

    def preview_current_input(self) -> None:
        if not self.preset_path:
            return
        row = self._collect_current_row()
        self._preview_row(row)

    def _preview_row(self, row: dict[str, Any]) -> None:
        if not self.preset_path:
            return
        try:
            payload = run_cli_json(
                [
                    "render",
                    "preview",
                    "--preset",
                    self.preset_path,
                    "--row",
                    json.dumps(row, ensure_ascii=False),
                ]
            )
            scene = payload.get("scene", {})
            self.preview.set_scene(scene)
            self.statusBar().showMessage("预览已更新。", 2000)
        except CardPrintError as exc:
            self.show_error("预览失败", str(exc), exc.details)
        except Exception as exc:
            self.show_error("预览失败", str(exc))

    def add_current_row(self) -> None:
        if not self.preset_path:
            self.show_error("未加载预设", "请先加载预设后再加入队列。")
            return
        row = self._collect_current_row()
        self.row_queue.append(row)
        self._refresh_queue_table()

    def _refresh_queue_table(self) -> None:
        self.queue_table.setRowCount(0)
        for idx, row in enumerate(self.row_queue):
            table_row = self.queue_table.rowCount()
            self.queue_table.insertRow(table_row)
            self.queue_table.setItem(table_row, 0, QTableWidgetItem(str(idx + 1)))
            summary = " | ".join(f"{k}={row.get(k, '')}" for k in list(row.keys())[:3])
            self.queue_table.setItem(table_row, 1, QTableWidgetItem(summary))

    def preview_selected_row(self) -> None:
        selected = self.queue_table.currentRow()
        if selected < 0 or selected >= len(self.row_queue):
            return
        row = self.row_queue[selected]
        self._set_row_to_inputs(row)
        self._preview_row(row)

    def import_file(self) -> None:
        if not self.preset_path:
            self.show_error("未加载预设", "请先加载预设后再导入数据。")
            return
        path, _ = QFileDialog.getOpenFileName(
            self,
            "导入 CSV/XLSX",
            str(Path.cwd()),
            "Data Files (*.csv *.xlsx *.xlsm)",
        )
        if not path:
            return
        try:
            rows = load_rows(path)
            self.row_queue.extend(rows)
            self._refresh_queue_table()
            self.show_info("导入完成", f"已导入 {len(rows)} 行数据。")
        except Exception as exc:
            self.show_error("导入失败", str(exc))

    def clear_queue(self) -> None:
        self.row_queue = []
        self.queue_table.setRowCount(0)

    def _print_rows(self, rows: list[dict[str, Any]]) -> None:
        if not self.preset_path:
            self.show_error("未加载预设", "请先加载预设。")
            return
        if not rows:
            self.show_error("无打印数据", "请先准备至少一条记录。")
            return
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            self.show_error("未选择打印机", "请先选择打印机。")
            return
        paper_name = self.paper_combo.currentText().strip() or self.preset_data.get("paper", {}).get("name", "")

        job = {
            "preset_path": self.preset_path,
            "rows": rows,
            "printer_name": printer_name,
            "paper_name": paper_name,
        }
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
            json.dump(job, f, ensure_ascii=False, indent=2)
            temp_job = f.name
        try:
            payload = run_cli_json(["print", "run", "--job", temp_job], timeout_s=120.0)
            result = payload["data"]["adapter"]
            self.show_info("打印任务完成", "请查看逐行结果。", result)
        except Exception as exc:
            self.show_error("打印失败", str(exc))
        finally:
            try:
                Path(temp_job).unlink(missing_ok=True)
            except Exception:
                pass

    def print_selected_row(self) -> None:
        row_index = self.queue_table.currentRow()
        if row_index < 0 or row_index >= len(self.row_queue):
            self.show_error("未选中记录", "请先在队列中选择一条记录。")
            return
        self._print_rows([self.row_queue[row_index]])

    def print_all_rows(self) -> None:
        self._print_rows(self.row_queue)


def main() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    w = PrinterWindow()
    w.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
