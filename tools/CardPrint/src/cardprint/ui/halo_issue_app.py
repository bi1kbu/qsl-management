from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
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
    QSplitter,
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
    QPlainTextEdit,
)

from cardprint.core.errors import CardPrintError
from cardprint.halo import HaloIssueReadonlyService
from cardprint.ui.cli_bridge import run_cli_json


def _json_text(payload: Any) -> str:
    return json.dumps(payload, ensure_ascii=False, indent=2)


class ConfigTab(QWidget):
    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.base_url_input = QLineEdit(self)
        self.base_url_input.setText("http://127.0.0.1:8090")
        self.username_input = QLineEdit(self)
        self.password_input = QLineEdit(self)
        self.password_input.setEchoMode(QLineEdit.Password)
        self.timeout_input = QLineEdit(self)
        self.timeout_input.setText("20")

        self.card_preset_input = QLineEdit(self)
        self.envelope_preset_input = QLineEdit(self)
        self.card_preset_button = QPushButton("选择卡片预设", self)
        self.envelope_preset_button = QPushButton("选择信封预设", self)
        self.status_label = QLabel("请先配置 Halo 和预设。", self)
        self.status_label.setWordWrap(True)

        self._build_ui()
        self._bind_events()

    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        box = QGroupBox("Halo 与预设配置", self)
        form = QFormLayout(box)
        form.addRow("Halo 地址", self.base_url_input)
        form.addRow("用户名", self.username_input)
        form.addRow("密码", self.password_input)
        form.addRow("超时(秒)", self.timeout_input)

        card_row = QWidget(box)
        card_row_layout = QHBoxLayout(card_row)
        card_row_layout.setContentsMargins(0, 0, 0, 0)
        card_row_layout.addWidget(self.card_preset_input, 1)
        card_row_layout.addWidget(self.card_preset_button)
        form.addRow("卡片打印预设", card_row)

        envelope_row = QWidget(box)
        envelope_row_layout = QHBoxLayout(envelope_row)
        envelope_row_layout.setContentsMargins(0, 0, 0, 0)
        envelope_row_layout.addWidget(self.envelope_preset_input, 1)
        envelope_row_layout.addWidget(self.envelope_preset_button)
        form.addRow("信封打印预设", envelope_row)
        form.addRow("状态", self.status_label)

        layout.addWidget(box)
        layout.addStretch(1)

    def _bind_events(self) -> None:
        self.card_preset_button.clicked.connect(lambda: self._choose_preset(self.card_preset_input, "卡片"))
        self.envelope_preset_button.clicked.connect(
            lambda: self._choose_preset(self.envelope_preset_input, "信封")
        )

    def _choose_preset(self, target_input: QLineEdit, preset_label: str) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            f"选择{preset_label}预设",
            str(Path.cwd()),
            "JSON Files (*.json)",
        )
        if not path:
            return
        try:
            run_cli_json(["preset", "validate", "--preset", path])
            target_input.setText(path)
            self.status_label.setText(f"{preset_label}预设已校验通过。")
        except Exception as exc:
            QMessageBox.critical(self, "预设无效", str(exc))

    def set_status(self, text: str) -> None:
        self.status_label.setText(text)

    def get_runtime_config(self) -> dict[str, Any]:
        base_url = self.base_url_input.text().strip()
        username = self.username_input.text().strip()
        password = self.password_input.text()
        timeout_text = self.timeout_input.text().strip()
        if not base_url:
            raise CardPrintError(code="HALO_BASE_URL_REQUIRED", message="Halo 地址不能为空。", details={})
        if not username:
            raise CardPrintError(code="HALO_USERNAME_REQUIRED", message="用户名不能为空。", details={})
        if not password:
            raise CardPrintError(code="HALO_PASSWORD_REQUIRED", message="密码不能为空。", details={})
        try:
            timeout_s = float(timeout_text)
        except ValueError as exc:
            raise CardPrintError(
                code="HALO_TIMEOUT_INVALID",
                message="超时时间必须是数字。",
                details={"timeout": timeout_text},
            ) from exc
        return {
            "base_url": base_url,
            "username": username,
            "password": password,
            "timeout_s": timeout_s,
            "card_preset_path": self.card_preset_input.text().strip(),
            "envelope_preset_path": self.envelope_preset_input.text().strip(),
        }


class PrintTab(QWidget):
    def __init__(self, *, kind: str, title: str, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.kind = kind
        self.title = title
        self.pending_rows: list[dict[str, Any]] = []
        self.raw_payload: dict[str, Any] = {}
        self.on_fetch: Callable[[PrintTab], None] | None = None
        self.on_print: Callable[[PrintTab, list[dict[str, Any]]], None] | None = None
        self.on_clear: Callable[[PrintTab], None] | None = None
        self.get_preset_path: Callable[[str], str] | None = None

        self.preset_hint_label = QLabel("当前预设：未配置", self)
        self.fetch_button = QPushButton("登录并拉取待制卡数据", self)
        self.clear_button = QPushButton("清空数据", self)
        self.summary_label = QLabel("未拉取数据。", self)
        self.summary_label.setWordWrap(True)

        self.printer_combo = QComboBox(self)
        self.paper_combo = QComboBox(self)
        self.refresh_printer_button = QPushButton("刷新打印机", self)
        self.refresh_paper_button = QPushButton("刷新纸张", self)
        self.print_selected_button = QPushButton("打印选中条目", self)
        self.print_all_button = QPushButton("打印全部条目", self)

        self.table = QTableWidget(0, 10, self)
        self.table.setHorizontalHeaderLabels(
            [
                "卡片ID",
                "呼号",
                "卡片类型",
                "卡片版本",
                "卡片日期",
                "卡片时间",
                "关联QSO",
                "地址编号",
                "地址来源",
                "收件邮箱",
            ]
        )
        self.table.setAlternatingRowColors(True)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)

        self.json_view = QPlainTextEdit(self)
        self.json_view.setReadOnly(True)
        self.json_view.setPlaceholderText("拉取后显示完整 JSON。")

        self._build_ui()
        self._bind_events()
        self.refresh_printers()
        self.refresh_papers()

    def _build_ui(self) -> None:
        root = QVBoxLayout(self)

        action_group = QGroupBox(f"{self.title}操作", self)
        action_form = QFormLayout(action_group)
        action_form.addRow("预设状态", self.preset_hint_label)

        fetch_row = QWidget(action_group)
        fetch_row_layout = QHBoxLayout(fetch_row)
        fetch_row_layout.setContentsMargins(0, 0, 0, 0)
        fetch_row_layout.addWidget(self.fetch_button)
        fetch_row_layout.addWidget(self.clear_button)
        fetch_row_layout.addStretch(1)
        action_form.addRow(fetch_row)
        action_form.addRow("数据摘要", self.summary_label)

        printer_row = QWidget(action_group)
        printer_row_layout = QHBoxLayout(printer_row)
        printer_row_layout.setContentsMargins(0, 0, 0, 0)
        printer_row_layout.addWidget(self.printer_combo, 1)
        printer_row_layout.addWidget(self.refresh_printer_button)
        action_form.addRow("打印机", printer_row)

        paper_row = QWidget(action_group)
        paper_row_layout = QHBoxLayout(paper_row)
        paper_row_layout.setContentsMargins(0, 0, 0, 0)
        paper_row_layout.addWidget(self.paper_combo, 1)
        paper_row_layout.addWidget(self.refresh_paper_button)
        action_form.addRow("纸张", paper_row)

        print_row = QWidget(action_group)
        print_row_layout = QHBoxLayout(print_row)
        print_row_layout.setContentsMargins(0, 0, 0, 0)
        print_row_layout.addWidget(self.print_selected_button)
        print_row_layout.addWidget(self.print_all_button)
        print_row_layout.addStretch(1)
        action_form.addRow(print_row)
        root.addWidget(action_group)

        splitter = QSplitter(Qt.Horizontal, self)
        splitter.addWidget(self.table)
        splitter.addWidget(self.json_view)
        splitter.setSizes([820, 540])
        root.addWidget(splitter, 1)

    def _bind_events(self) -> None:
        self.fetch_button.clicked.connect(self._trigger_fetch)
        self.clear_button.clicked.connect(self._trigger_clear)
        self.refresh_printer_button.clicked.connect(self.refresh_printers)
        self.refresh_paper_button.clicked.connect(self.refresh_papers)
        self.printer_combo.currentTextChanged.connect(lambda _: self.refresh_papers())
        self.print_selected_button.clicked.connect(self._trigger_print_selected)
        self.print_all_button.clicked.connect(self._trigger_print_all)

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

    def refresh_papers(self) -> None:
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            self.paper_combo.clear()
            return
        try:
            payload = run_cli_json(["printer", "papers", "--printer", printer_name])
            items = payload["data"].get("items", [])
            self.paper_combo.clear()
            self.paper_combo.addItems(items)
        except Exception as exc:
            self.show_error("刷新纸张失败", str(exc))

    def show_error(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else "\n" + _json_text(details)
        QMessageBox.critical(self, title, f"{message}{detail_text}")

    def show_info(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else "\n" + _json_text(details)
        QMessageBox.information(self, title, f"{message}{detail_text}")

    def set_dataset(self, rows: list[dict[str, Any]], payload: dict[str, Any], summary: str) -> None:
        self.pending_rows = rows
        self.raw_payload = payload
        self.summary_label.setText(summary)
        self.table.setRowCount(len(rows))
        for row_idx, row in enumerate(rows):
            card_info = row.get("cardInfo") or {}
            card_spec = card_info.get("spec") or {}
            qso_info = row.get("qsoInfo") or {}
            qso_name = ""
            if isinstance(qso_info, dict):
                qso_name = str((qso_info.get("metadata") or {}).get("name", ""))

            address_source = "-"
            address_id = str(card_spec.get("addressEntryName", "") or "-")
            mail_target = str(card_spec.get("mailTargetEmail", "") or "-")
            if row.get("addressInfo"):
                address_source = "通信地址"
                address_spec = (row.get("addressInfo") or {}).get("spec") or {}
                if str(address_spec.get("email", "")).strip():
                    mail_target = str(address_spec.get("email"))
            elif row.get("bureauInfo"):
                address_source = "卡片局"

            values = [
                str(row.get("cardId", "")),
                str(card_spec.get("callSign", "")),
                str(card_spec.get("cardType", "")),
                str(card_spec.get("cardVersion", "")),
                str(card_spec.get("cardDate", "")),
                str(card_spec.get("cardTime", "")),
                qso_name or str(card_spec.get("qsoRecordName", "")),
                address_id,
                address_source,
                mail_target,
            ]
            for col_idx, value in enumerate(values):
                self.table.setItem(row_idx, col_idx, QTableWidgetItem(value))
        self.table.resizeColumnsToContents()
        self.json_view.setPlainText(_json_text(payload))

    def clear_dataset(self) -> None:
        self.pending_rows = []
        self.raw_payload = {}
        self.table.setRowCount(0)
        self.json_view.clear()
        self.summary_label.setText("未拉取数据。")

    def refresh_preset_hint(self) -> None:
        preset_path = ""
        if callable(self.get_preset_path):
            preset_path = str(self.get_preset_path(self.kind) or "").strip()
        self.preset_hint_label.setText(f"当前预设：{preset_path or '未配置'}")

    def _trigger_fetch(self) -> None:
        if callable(self.on_fetch):
            self.on_fetch(self)

    def _trigger_clear(self) -> None:
        if callable(self.on_clear):
            self.on_clear(self)
        else:
            self.clear_dataset()

    def _trigger_print_selected(self) -> None:
        selected_indices = sorted({index.row() for index in self.table.selectionModel().selectedRows()})
        selected_rows = [self.pending_rows[idx] for idx in selected_indices if 0 <= idx < len(self.pending_rows)]
        if not selected_rows:
            self.show_error("未选中记录", "请先在清单中选中一条或多条记录。")
            return
        if callable(self.on_print):
            self.on_print(self, selected_rows)

    def _trigger_print_all(self) -> None:
        if not self.pending_rows:
            self.show_error("无打印数据", "请先登录并拉取待制卡数据。")
            return
        if callable(self.on_print):
            self.on_print(self, self.pending_rows)


class HaloIssueWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CardPrint Halo 制卡签发打印工作台")
        self.resize(1460, 900)

        self.config_tab = ConfigTab(self)
        self.envelope_tab = PrintTab(kind="envelope", title="信封打印", parent=self)
        self.card_tab = PrintTab(kind="card", title="卡片打印", parent=self)

        self.envelope_tab.on_fetch = self.fetch_remote_data
        self.card_tab.on_fetch = self.fetch_remote_data
        self.envelope_tab.on_print = self.print_rows_for_tab
        self.card_tab.on_print = self.print_rows_for_tab
        self.envelope_tab.on_clear = self.clear_data_for_tab
        self.card_tab.on_clear = self.clear_data_for_tab
        self.envelope_tab.get_preset_path = self.get_preset_path
        self.card_tab.get_preset_path = self.get_preset_path

        self._build_ui()
        self._refresh_preset_hints()

        self.config_tab.card_preset_input.textChanged.connect(self._refresh_preset_hints)
        self.config_tab.envelope_preset_input.textChanged.connect(self._refresh_preset_hints)

    def _build_ui(self) -> None:
        tabs = QTabWidget(self)
        tabs.addTab(self.config_tab, "配置")
        tabs.addTab(self.envelope_tab, "信封打印")
        tabs.addTab(self.card_tab, "卡片打印")
        self.setCentralWidget(tabs)

    def _refresh_preset_hints(self) -> None:
        self.envelope_tab.refresh_preset_hint()
        self.card_tab.refresh_preset_hint()

    def get_preset_path(self, kind: str) -> str:
        cfg = self.config_tab.get_runtime_config()
        if kind == "card":
            return str(cfg.get("card_preset_path", ""))
        return str(cfg.get("envelope_preset_path", ""))

    def clear_data_for_tab(self, current_tab: PrintTab) -> None:
        current_tab.clear_dataset()

    def fetch_remote_data(self, current_tab: PrintTab) -> None:
        try:
            cfg = self.config_tab.get_runtime_config()
        except CardPrintError as exc:
            current_tab.show_error("配置错误", exc.message, {"code": exc.code, "details": exc.details})
            return

        current_tab.fetch_button.setEnabled(False)
        current_tab.fetch_button.setText("拉取中...")
        QApplication.processEvents()
        try:
            service = HaloIssueReadonlyService(base_url=cfg["base_url"], timeout_s=cfg["timeout_s"])
            payload = service.fetch_issue_readonly(username=cfg["username"], password=cfg["password"])
            pending_rows = payload.get("pendingIssueRows") or []
            if not isinstance(pending_rows, list):
                pending_rows = []
            rows = [row for row in pending_rows if isinstance(row, dict)]
            summary = payload.get("summary") or {}
            summary_text = (
                "拉取成功："
                f"待制卡 {summary.get('pendingCardCount', 0)} 条，"
                f"QSO {summary.get('qsoCount', 0)} 条，"
                f"地址 {summary.get('addressCount', 0)} 条，"
                f"卡片局 {summary.get('bureauCount', 0)} 条。"
            )
            self.card_tab.set_dataset(rows, payload, summary_text)
            self.envelope_tab.set_dataset(rows, payload, summary_text)
            self.config_tab.set_status("登录成功，数据已同步到“信封打印”和“卡片打印”。")
        except CardPrintError as exc:
            current_tab.show_error("拉取失败", exc.message, {"code": exc.code, "details": exc.details})
        except Exception as exc:  # pragma: no cover
            current_tab.show_error("拉取失败", str(exc))
        finally:
            current_tab.fetch_button.setEnabled(True)
            current_tab.fetch_button.setText("登录并拉取待制卡数据")

    def _to_print_row(self, row: dict[str, Any], tab_kind: str) -> dict[str, Any]:
        card_info = row.get("cardInfo") or {}
        card_spec = card_info.get("spec") or {}
        qso_info = row.get("qsoInfo") or {}
        qso_spec = (qso_info.get("spec") or {}) if isinstance(qso_info, dict) else {}
        qso_id = str((qso_info.get("metadata") or {}).get("name", "")) if isinstance(qso_info, dict) else ""

        address_source = "-"
        address_name = ""
        address_spec: dict[str, Any] = {}
        if row.get("addressInfo"):
            address_source = "通信地址"
            address_spec = (
                ((row.get("addressInfo") or {}).get("spec") or {})
                if isinstance(row.get("addressInfo"), dict)
                else {}
            )
            address_name = str(address_spec.get("name", ""))
        elif row.get("bureauInfo"):
            address_source = "卡片局"
            bureau_spec = (
                ((row.get("bureauInfo") or {}).get("spec") or {})
                if isinstance(row.get("bureauInfo"), dict)
                else {}
            )
            address_spec = bureau_spec
            address_name = str(bureau_spec.get("bureauName", ""))

        card_id = str(row.get("cardId", ""))
        call_sign = str(card_spec.get("callSign", ""))
        card_type = str(card_spec.get("cardType", ""))
        card_version = str(card_spec.get("cardVersion", ""))
        card_date = str(card_spec.get("cardDate", ""))
        card_time = str(card_spec.get("cardTime", ""))
        card_remarks = str(card_spec.get("cardRemarks", ""))
        qso_name = str(card_spec.get("qsoRecordName", ""))
        address_id = str(card_spec.get("addressEntryName", ""))
        email = str(address_spec.get("email", "") or card_spec.get("mailTargetEmail", ""))

        card_type_upper = card_type.upper()
        timezone = str(qso_spec.get("timezone", "") or "UTC")
        frequency = str(qso_spec.get("freq", ""))
        mode = str(qso_spec.get("myRigMode", ""))
        qth = str(qso_spec.get("qth", ""))
        rst_sent = str(qso_spec.get("rstSent", ""))
        equipment_id = str(qso_spec.get("myRig", ""))
        power_preset_id = str(qso_spec.get("myRigPwr", ""))
        antenna_id = str(qso_spec.get("myRigAnt", ""))
        post_card_status = "⬛" if bool(card_spec.get("cardSent")) else ""
        card_received = self._to_bool(card_spec.get("cardReceived"))
        request_return_card = not card_received
        thanks_received_card = card_received
        return_card_status = "⬛" if thanks_received_card else ""
        normalized_timezone = timezone.strip().upper().replace(" ", "")
        utc_box = "⬛" if normalized_timezone in {"", "UTC", "GMT", "Z"} else ""
        utc8_box = "⬛" if normalized_timezone in {"UTC+8", "UTC+08", "GMT+8", "GMT+08"} else ""

        result = {
            "打印场景": "信封" if tab_kind == "envelope" else "卡片",
            "信封打印": "⬛" if tab_kind == "envelope" else "",
            "卡片打印": "⬛" if tab_kind == "card" else "",
            "卡片ID": card_id,
            "呼号": call_sign,
            "对方呼号": call_sign,
            "卡片类型": card_type,
            "卡片版本": card_version,
            "卡片日期": card_date,
            "卡片时间": card_time,
            "卡片备注": card_remarks,
            "关联QSO_ID": qso_name,
            "QSO编号": qso_id,
            "QSO日期": str(qso_spec.get("date", "")),
            "QSO时间": str(qso_spec.get("time", "")),
            "QSO时区": str(qso_spec.get("timezone", "")),
            "QSO频率": str(qso_spec.get("freq", "")),
            "QSO模式": str(qso_spec.get("myRigMode", "")),
            "地址编号": address_id,
            "地址来源": address_source,
            "收件姓名": address_name,
            "收件地址": str(address_spec.get("address", "")),
            "收件邮编": str(address_spec.get("postalCode", "")),
            "去向国": str(address_spec.get("destinationCountry", "")),
            "收件电话": str(address_spec.get("telephone", "")),
            "收件邮箱": email,
            "card_id": card_id,
            "call_sign": call_sign,
            "card_type": card_type,
            "card_version": card_version,
            "card_date": card_date,
            "card_time": card_time,
            "qso_id": qso_id,
            "qso_name": qso_name,
            "address_id": address_id,
            "address_source": address_source,
            "address_name": address_name,
            "address": str(address_spec.get("address", "")),
            "postal_code": str(address_spec.get("postalCode", "")),
            "destinationCountry": str(address_spec.get("destinationCountry", "")),
            "destination_country": str(address_spec.get("destinationCountry", "")),
            "telephone": str(address_spec.get("telephone", "")),
            "email": email,
            "peerCallsign": call_sign,
            "Date": card_date or str(qso_spec.get("date", "")),
            "Time": card_time or str(qso_spec.get("time", "")),
            "UTC": utc_box,
            "UTC+8": utc8_box,
            "UTF": utc_box,
            "UTF-8": utc8_box,
            "QSO": "⬛" if card_type_upper == "QSO" else "",
            "SWL": "⬛" if card_type_upper == "SWL" else "",
            "EYEBALL": "⬛" if card_type_upper == "EYEBALL" else "",
            "frequency": frequency,
            "equipmentId": equipment_id,
            "mode": mode,
            "mode_type": mode,
            "mode_FM": "⬛" if mode.upper() == "FM" else "",
            "mode_CW": "⬛" if mode.upper() == "CW" else "",
            "mode_SSB": "⬛" if mode.upper() == "SSB" else "",
            "powerPresetId": power_preset_id,
            "antennaId": antenna_id,
            "rstSent": rst_sent,
            "qth": qth,
            "postCardStatus": post_card_status,
            "returnCardStatus": return_card_status,
            "欢迎回卡": "⬛" if request_return_card else "",
            "回复卡片": "⬛" if request_return_card else "",
            "请回卡片": "⬛" if request_return_card else "",
            "感谢来卡": return_card_status,
            "感谢您的卡片": "⬛" if thanks_received_card else "",
            "感谢您的来卡": "⬛" if thanks_received_card else "",
            "remark": card_remarks,
            "card_tpye": card_type_upper,
            "cadr_id": card_id,
        }
        for source in (card_spec, qso_spec, address_spec):
            if isinstance(source, dict):
                for key, value in source.items():
                    result[str(key)] = "" if value is None else str(value)
        return result

    @staticmethod
    def _to_bool(value: Any) -> bool:
        if isinstance(value, bool):
            return value
        return str(value or "").strip().lower() in {"1", "true", "yes", "y", "on", "是", "已", "√", "⬛"}

    def _ensure_rows_renderable(self, *, rows: list[dict[str, Any]], preset_path: str, tab_kind: str) -> None:
        for index, row in enumerate(rows):
            preview_payload = run_cli_json(
                [
                    "render",
                    "preview",
                    "--preset",
                    preset_path,
                    "--row",
                    json.dumps(self._to_print_row(row, tab_kind), ensure_ascii=False),
                ]
            )
            scene = preview_payload.get("scene", {})
            items = scene.get("items", []) if isinstance(scene, dict) else []
            if not isinstance(items, list) or not items:
                card_id = str((row.get("cardInfo") or {}).get("metadata", {}).get("name", row.get("cardId", "")))
                raise CardPrintError(
                    code="PRINT_EMPTY_LAYOUT",
                    message="当前条目匹配不到任何可打印字段，请检查预设字段名。",
                    details={"row_index": index, "card_id": card_id, "preset": preset_path},
                )

    def print_rows_for_tab(self, current_tab: PrintTab, rows: list[dict[str, Any]]) -> None:
        try:
            cfg = self.config_tab.get_runtime_config()
        except CardPrintError as exc:
            current_tab.show_error("配置错误", exc.message, {"code": exc.code, "details": exc.details})
            return
        preset_path = cfg["card_preset_path"] if current_tab.kind == "card" else cfg["envelope_preset_path"]
        if not preset_path:
            current_tab.show_error("未配置预设", "请先到“配置”页面设置对应预设。")
            return
        printer_name = current_tab.printer_combo.currentText().strip()
        if not printer_name:
            current_tab.show_error("未选择打印机", "请先选择打印机。")
            return
        paper_name = current_tab.paper_combo.currentText().strip()
        if not paper_name:
            current_tab.show_error("未选择纸张", "请先选择纸张。")
            return

        try:
            run_cli_json(["preset", "validate", "--preset", preset_path])
            self._ensure_rows_renderable(rows=rows, preset_path=preset_path, tab_kind=current_tab.kind)
        except CardPrintError as exc:
            current_tab.show_error("无法打印", exc.message, {"code": exc.code, "details": exc.details})
            return
        except Exception as exc:
            current_tab.show_error("无法打印", str(exc))
            return

        print_rows = [self._to_print_row(item, current_tab.kind) for item in rows]
        job_payload = {
            "preset_path": preset_path,
            "rows": print_rows,
            "printer_name": printer_name,
            "paper_name": paper_name,
        }
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fp:
            json.dump(job_payload, fp, ensure_ascii=False, indent=2)
            temp_job = fp.name
        try:
            result_payload = run_cli_json(["print", "run", "--job", temp_job], timeout_s=180.0)
            adapter_result = result_payload.get("data", {}).get("adapter", {})
            mode = str(adapter_result.get("mode", ""))
            if mode == "dry_run":
                current_tab.show_info("打印结果", "当前是 dry-run 模式，未发送到真实打印机。", adapter_result)
                return
            current_tab.show_info("打印完成", f"已提交 {len(print_rows)} 条打印任务。", adapter_result)
        except Exception as exc:
            current_tab.show_error("打印失败", str(exc))
        finally:
            try:
                Path(temp_job).unlink(missing_ok=True)
            except Exception:
                pass


def main() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    window = HaloIssueWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
