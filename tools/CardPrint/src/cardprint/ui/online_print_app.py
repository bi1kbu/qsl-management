from __future__ import annotations

import copy
import json
import re
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote, urlencode, urljoin

from PySide6.QtCore import QDate, QObject, Qt, QThread, QTime, QTimer, Signal
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QCheckBox,
    QComboBox,
    QDateEdit,
    QDoubleSpinBox,
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
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QTimeEdit,
    QVBoxLayout,
    QWidget,
)

from cardprint.core.errors import CardPrintError
from cardprint.online.bridge_service import (
    ADDRESS_ENVELOPE_DATASET,
    BridgeService,
    PUBLIC_EYEBALL_ENDPOINT,
    PUBLIC_ONLINE_EYEBALL_ENDPOINT,
    PUBLIC_RECEIPT_ENDPOINT,
    default_bridge_config,
    load_bridge_config,
    map_export_row,
    normalize_bridge_config,
    save_bridge_config,
)
from cardprint.ui.cli_bridge import run_cli_json
from cardprint.ui.widgets.preview_canvas import PreviewCanvas

CARD_VERSION_FILTER_PENDING = "__PENDING__"
ENVELOPE_DESTINATION_ALL = ""
ENVELOPE_DESTINATION_DOMESTIC = "domestic"
ENVELOPE_DESTINATION_INTERNATIONAL = "international"
ACTIVITY_FILTER_ALL = ""
QRCODE_REMARK_DEFAULT = "请扫二维码并确认签收"
CARD_BUSINESS_QSO = "qso"
CARD_BUSINESS_ONLINE = "online"
CARD_BUSINESS_OFFLINE = "offline"
CARD_BUSINESS_OPTIONS: list[tuple[str, str]] = [
    (CARD_BUSINESS_QSO, "通联业务"),
    (CARD_BUSINESS_ONLINE, "线上换卡业务"),
    (CARD_BUSINESS_OFFLINE, "线下换卡业务"),
]


def _json_text(payload: Any) -> str:
    return json.dumps(payload, ensure_ascii=False, indent=2)


def _load_preset_meta(path: str) -> dict[str, Any]:
    if not path:
        return {"field_keys": [], "paper_name": "", "preferred_printer": ""}
    preset_path = Path(path)
    if not preset_path.exists():
        return {"field_keys": [], "paper_name": "", "preferred_printer": ""}
    try:
        raw = json.loads(preset_path.read_text(encoding="utf-8"))
    except Exception:
        return {"field_keys": [], "paper_name": "", "preferred_printer": ""}

    field_keys: list[str] = []
    for item in raw.get("fields", []):
        if not isinstance(item, dict):
            continue
        if str(item.get("fixed_text", "")).strip():
            continue
        key = str(item.get("key", "")).strip()
        if key:
            field_keys.append(key)

    return {
        "field_keys": field_keys,
        "paper_name": str(raw.get("paper", {}).get("name", "")).strip(),
        "preferred_printer": str(raw.get("preferred_printer", "")).strip(),
    }


def _apply_outbound_return_card_defaults(mapped_row: dict[str, Any]) -> None:
    mapped_row["returnCardStatus"] = ""
    mapped_row["回复卡片"] = "⬛"
    mapped_row["欢迎回卡"] = "⬛"
    mapped_row["请回卡片"] = "⬛"
    mapped_row["感谢来卡"] = ""
    mapped_row["感谢您的卡片"] = ""
    mapped_row["感谢您的来卡"] = ""


def _lookup_path_value(row: dict[str, Any], path: str) -> Any:
    cursor: Any = row
    for segment in path.split("."):
        if isinstance(cursor, dict) and segment in cursor:
            cursor = cursor.get(segment)
        else:
            return ""
    return cursor


def _normalize_qrcode_path(value: Any) -> str:
    path = str(value or "").strip()
    if not path:
        return ""
    if not path.startswith("/"):
        path = "/" + path
    if len(path) > 1:
        path = path.rstrip("/")
    return path


def _resolve_qrcode_endpoint(endpoint: str, cfg: dict[str, Any]) -> str:
    normalized_endpoint = _normalize_qrcode_path(endpoint)
    mappings = cfg.get("qrcode", {}).get("path_mappings", {})
    if isinstance(mappings, dict):
        mapped = _normalize_qrcode_path(mappings.get(normalized_endpoint))
        if mapped:
            return mapped
    return normalized_endpoint


def _to_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    text = str(value or "").strip().lower()
    return text in {"1", "true", "yes", "y", "on", "是", "已制卡", "已打包", "√", "⬛"}


def _split_card_versions(value: Any) -> list[str]:
    if isinstance(value, list):
        chunks = [str(item or "").strip().upper() for item in value]
    else:
        text = str(value or "").strip().upper()
        if not text:
            return []
        normalized = (
            text.replace("，", ",")
            .replace("、", ",")
            .replace("；", ",")
            .replace(";", ",")
            .replace("|", ",")
            .replace("/", ",")
        )
        chunks = [item.strip() for item in re.split(r"[,\s]+", normalized)]

    versions: list[str] = []
    seen: set[str] = set()
    for chunk in chunks:
        if not chunk or chunk in seen:
            continue
        seen.add(chunk)
        versions.append(chunk)
    return versions


def _resolve_offline_activity_name(source_row: dict[str, Any]) -> str:
    spec_raw = source_row.get("spec")
    spec = spec_raw if isinstance(spec_raw, dict) else {}
    candidates = [
        spec.get("offlineActivityName"),
        spec.get("activityName"),
        spec.get("activityId"),
        source_row.get("offlineActivityName"),
        source_row.get("activityName"),
        source_row.get("activityId"),
    ]
    for value in candidates:
        text = str(value or "").strip()
        if text:
            return text
    return ""


def _resolve_envelope_destination_country(source_row: dict[str, Any]) -> str:
    for path in (
        "addressInfo.spec.destinationCountry",
        "bureauInfo.spec.destinationCountry",
        "spec.destinationCountry",
        "destinationCountry",
        "destination_country",
        "去向国",
    ):
        text = str(_lookup_path_value(source_row, path) or "").strip()
        if text:
            return text
    return ""


def _build_envelope_recipient_name(source_row: dict[str, Any], mapped_row: dict[str, Any]) -> str:
    spec_raw = source_row.get("spec")
    spec = spec_raw if isinstance(spec_raw, dict) else {}
    address_info = source_row.get("addressInfo")
    address_spec = address_info.get("spec") if isinstance(address_info, dict) else {}
    bureau_info = source_row.get("bureauInfo")
    bureau_spec = bureau_info.get("spec") if isinstance(bureau_info, dict) else {}

    call_sign = (
        str(spec.get("callSign", "")).strip()
        or str(source_row.get("callSign", "")).strip()
        or str(source_row.get("呼号", "")).strip()
    )
    name = ""
    normalized_call_sign = call_sign.casefold()
    for candidate in (
        mapped_row.get("name", ""),
        address_spec.get("name", ""),
        bureau_spec.get("bureauName", ""),
    ):
        candidate_text = str(candidate).strip()
        if not candidate_text:
            continue
        if normalized_call_sign and candidate_text.casefold() == normalized_call_sign:
            continue
        name = candidate_text
        break
    if name:
        if call_sign:
            return f"{name}({call_sign}) （收）"
        return f"{name}（收）"
    if call_sign:
        return f"{call_sign}（收）"
    return str(mapped_row.get("name", "")).strip()


def _build_envelope_recipient_name_by_call_signs(call_signs: list[str]) -> str:
    cleaned: list[str] = []
    seen: set[str] = set()
    for item in call_signs:
        call_sign = str(item or "").strip().upper()
        if not call_sign or call_sign in seen:
            continue
        seen.add(call_sign)
        cleaned.append(call_sign)
    if not cleaned:
        return ""
    return f"{'、'.join(cleaned)}（收）"


class FetchCardVersionsWorker(QObject):
    finished = Signal(dict)
    failed = Signal(str, object)

    def __init__(self, fetcher: Callable[[dict[str, Any]], dict[str, Any]], config: dict[str, Any]) -> None:
        super().__init__()
        self._fetcher = fetcher
        self._config = copy.deepcopy(config)

    def run(self) -> None:
        try:
            self.finished.emit(self._fetcher(copy.deepcopy(self._config)))
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self.failed.emit(str(exc), details)


class BackgroundTaskWorker(QObject):
    finished = Signal(object)
    failed = Signal(str, object)

    def __init__(self, task: Callable[[], Any]) -> None:
        super().__init__()
        self._task = task

    def run(self) -> None:
        try:
            self.finished.emit(self._task())
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self.failed.emit(str(exc), details)


def _fetch_remote_sources_payload(config: dict[str, Any]) -> dict[str, Any]:
    cfg = normalize_bridge_config(config)
    bridge = BridgeService()
    cards_result = bridge.fetch_dataset(cfg, "cards")
    envelopes_result = bridge.fetch_dataset(cfg, "envelopes")
    return {
        "cards": [dict(item.get("source_row", {})) for item in cards_result.get("records", [])],
        "envelopes": [dict(item.get("source_row", {})) for item in envelopes_result.get("records", [])],
        "pulled_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }


class OnlineConfigPage(QWidget):
    def __init__(
        self,
        on_apply: Callable[[str, dict[str, Any]], None],
        on_fetch_card_versions: Callable[[dict[str, Any]], dict[str, Any]],
        parent: QWidget | None = None,
    ) -> None:
        super().__init__(parent)
        self._on_apply = on_apply
        self._on_fetch_card_versions = on_fetch_card_versions
        self._base_config = normalize_bridge_config(default_bridge_config())
        self._card_versions: list[str] = []
        self._card_version_preset_map_by_business: dict[str, dict[str, str]] = {
            CARD_BUSINESS_QSO: {},
            CARD_BUSINESS_ONLINE: {},
            CARD_BUSINESS_OFFLINE: {},
        }
        self._fetch_thread: QThread | None = None
        self._fetch_worker: FetchCardVersionsWorker | None = None

        self.config_path_edit = QLineEdit(self)
        self.btn_new_default = QPushButton("新建默认", self)
        self.btn_load_config = QPushButton("加载配置", self)
        self.btn_save_config = QPushButton("保存配置", self)
        self.btn_apply_config = QPushButton("应用配置", self)

        self.envelope_preset_edit = QLineEdit(self)
        self.btn_envelope_preset = QPushButton("选择...", self)
        self.btn_fetch_versions = QPushButton("登录并拉取卡片版本", self)
        self.btn_clear_version_map = QPushButton("清空版本映射", self)
        self.version_mapping_table = QTableWidget(0, 4, self)
        self.version_mapping_table.setHorizontalHeaderLabels(["业务", "卡片版本", "打印预设", "操作"])
        self.version_mapping_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.version_mapping_table.setSelectionMode(QAbstractItemView.NoSelection)

        self.timeout_spin = QDoubleSpinBox(self)
        self.timeout_spin.setDecimals(1)
        self.timeout_spin.setRange(1.0, 600.0)
        self.timeout_spin.setSingleStep(1.0)
        self.base_url_edit = QLineEdit(self)
        self.qrcode_offline_eyeball_path_edit = QLineEdit(self)
        self.qrcode_online_eyeball_path_edit = QLineEdit(self)
        self.qrcode_receipt_path_edit = QLineEdit(self)
        self.qrcode_offline_eyeball_path_edit.setPlaceholderText("/EYEBALL")
        self.qrcode_online_eyeball_path_edit.setPlaceholderText("/ONLINE_EYEBALL")
        self.qrcode_receipt_path_edit.setPlaceholderText("/rp")
        self.remote_hint = QLabel(
            "远程接口路径与筛选规则已内置固定（卡片接口、封面接口、过滤器）；站点地址、二维码短路径与超时可配置。",
            self,
        )
        self.remote_hint.setWordWrap(True)

        self.auth_type_combo = QComboBox(self)
        self.auth_type_combo.addItem("Basic", "basic")
        self.auth_type_combo.addItem("Bearer", "bearer")
        self.auth_username_edit = QLineEdit(self)
        self.auth_password_edit = QLineEdit(self)
        self.auth_password_edit.setEchoMode(QLineEdit.Password)
        self.auth_token_edit = QLineEdit(self)
        self.auth_operator_edit = QLineEdit(self)
        self.sender_name_edit = QLineEdit(self)
        self.sender_phone_edit = QLineEdit(self)
        self.sender_post_code_edit = QLineEdit(self)
        self.sender_address_edit = QLineEdit(self)

        self._build_ui()
        self._bind_events()

    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)

        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        root.addWidget(scroll, 1)

        body = QWidget(scroll)
        scroll.setWidget(body)
        body_layout = QVBoxLayout(body)

        file_group = QGroupBox("配置文件", body)
        file_layout = QFormLayout(file_group)
        file_row = QWidget(file_group)
        file_row_layout = QHBoxLayout(file_row)
        file_row_layout.setContentsMargins(0, 0, 0, 0)
        file_row_layout.addWidget(self.config_path_edit, 1)
        file_row_layout.addWidget(self.btn_new_default)
        file_row_layout.addWidget(self.btn_load_config)
        file_row_layout.addWidget(self.btn_save_config)
        file_layout.addRow("配置路径", file_row)
        body_layout.addWidget(file_group)

        remote_group = QGroupBox("远程接口与公共设置", body)
        remote_layout = QFormLayout(remote_group)
        remote_layout.addRow("固定说明", self.remote_hint)
        remote_layout.addRow("站点地址", self.base_url_edit)
        remote_layout.addRow("线下换卡二维码路径", self.qrcode_offline_eyeball_path_edit)
        remote_layout.addRow("线上换卡二维码路径", self.qrcode_online_eyeball_path_edit)
        remote_layout.addRow("签收确认二维码路径", self.qrcode_receipt_path_edit)
        remote_layout.addRow("请求超时(秒)", self.timeout_spin)
        body_layout.addWidget(remote_group)

        auth_group = QGroupBox("鉴权信息", body)
        auth_layout = QFormLayout(auth_group)
        auth_layout.addRow("鉴权类型", self.auth_type_combo)
        auth_layout.addRow("用户名", self.auth_username_edit)
        auth_layout.addRow("密码", self.auth_password_edit)
        auth_layout.addRow("Bearer Token", self.auth_token_edit)
        auth_layout.addRow("X-Operator", self.auth_operator_edit)
        body_layout.addWidget(auth_group)

        preset_group = QGroupBox("预设选择", body)
        preset_layout = QFormLayout(preset_group)
        envelope_row = QWidget(preset_group)
        envelope_row_layout = QHBoxLayout(envelope_row)
        envelope_row_layout.setContentsMargins(0, 0, 0, 0)
        envelope_row_layout.addWidget(self.envelope_preset_edit, 1)
        envelope_row_layout.addWidget(self.btn_envelope_preset)
        preset_layout.addRow("信封预设", envelope_row)

        version_action_row = QHBoxLayout()
        version_action_row.setContentsMargins(0, 0, 0, 0)
        version_action_row.addWidget(self.btn_clear_version_map)
        version_action_row.addStretch(1)
        version_container = QWidget(preset_group)
        version_container_layout = QVBoxLayout(version_container)
        version_container_layout.setContentsMargins(0, 0, 0, 0)
        version_container_layout.addLayout(version_action_row)
        version_container_layout.addWidget(self.version_mapping_table)
        preset_layout.addRow("卡片版本映射（按业务独立）", version_container)
        preset_layout.addRow("本台姓名", self.sender_name_edit)
        preset_layout.addRow("本台电话", self.sender_phone_edit)
        preset_layout.addRow("本台邮编", self.sender_post_code_edit)
        preset_layout.addRow("本台地址", self.sender_address_edit)
        body_layout.addWidget(preset_group)

        action_row = QHBoxLayout()
        action_row.addStretch(1)
        action_row.addWidget(self.btn_apply_config)
        action_row.addWidget(self.btn_fetch_versions)
        body_layout.addLayout(action_row)
        body_layout.addStretch(1)

    def _bind_events(self) -> None:
        self.btn_envelope_preset.clicked.connect(lambda: self._choose_preset(self.envelope_preset_edit))
        self.btn_new_default.clicked.connect(self._new_default)
        self.btn_load_config.clicked.connect(self._load_config)
        self.btn_save_config.clicked.connect(self._save_config)
        self.btn_apply_config.clicked.connect(self._apply_config)
        self.btn_fetch_versions.clicked.connect(self._fetch_card_versions)
        self.btn_clear_version_map.clicked.connect(self._clear_card_version_mapping)
        self.auth_type_combo.currentIndexChanged.connect(self._refresh_auth_fields)

    def _show_error(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else _json_text(details)
        QMessageBox.critical(self, title, f"{message}\n{detail_text}")

    def _show_info(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else _json_text(details)
        QMessageBox.information(self, title, f"{message}\n{detail_text}")

    def _choose_preset(self, target_edit: QLineEdit) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "选择预设文件", str(Path.cwd()), "JSON Files (*.json)")
        if not path:
            return
        target_edit.setText(path)

    def _choose_preset_for_version(self, business: str, version: str) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            f"为{dict(CARD_BUSINESS_OPTIONS).get(business, business)}卡片版本 {version} 选择预设",
            str(Path.cwd()),
            "JSON Files (*.json)",
        )
        if not path:
            return
        if business not in self._card_version_preset_map_by_business:
            self._card_version_preset_map_by_business[business] = {}
        self._card_version_preset_map_by_business[business][version] = path
        self._refresh_version_mapping_table()
        self._auto_save_runtime_config()

    def _refresh_version_mapping_table(self) -> None:
        if self._card_versions:
            version_list = list(dict.fromkeys(self._card_versions))
        else:
            all_versions: set[str] = set()
            for mapping in self._card_version_preset_map_by_business.values():
                all_versions.update(mapping.keys())
            version_list = sorted(all_versions)
        self.version_mapping_table.setRowCount(0)
        row_idx = 0
        for business_key, business_label in CARD_BUSINESS_OPTIONS:
            mapping = self._card_version_preset_map_by_business.get(business_key, {})
            for version in version_list:
                self.version_mapping_table.insertRow(row_idx)
                self.version_mapping_table.setItem(row_idx, 0, QTableWidgetItem(business_label))
                self.version_mapping_table.setItem(row_idx, 1, QTableWidgetItem(version))
                self.version_mapping_table.setItem(
                    row_idx,
                    2,
                    QTableWidgetItem(mapping.get(version, "")),
                )
                select_button = QPushButton("选择预设", self.version_mapping_table)
                select_button.clicked.connect(
                    lambda _=False, b=business_key, v=version: self._choose_preset_for_version(b, v)
                )
                self.version_mapping_table.setCellWidget(row_idx, 3, select_button)
                row_idx += 1
        self.version_mapping_table.resizeColumnsToContents()

    def _refresh_auth_fields(self) -> None:
        auth_type = str(self.auth_type_combo.currentData() or "basic")
        is_basic = auth_type == "basic"
        self.auth_username_edit.setEnabled(is_basic)
        self.auth_password_edit.setEnabled(is_basic)
        self.auth_token_edit.setEnabled(not is_basic)

    def _fetch_card_versions(self) -> None:
        if self._fetch_thread is not None and self._fetch_thread.isRunning():
            return
        try:
            runtime_cfg = self._build_config_from_form()
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("拉取版本失败", str(exc), details)
            return

        self.btn_fetch_versions.setEnabled(False)
        self.btn_fetch_versions.setText("正在拉取...")
        self._fetch_thread = QThread(self)
        self._fetch_worker = FetchCardVersionsWorker(self._on_fetch_card_versions, runtime_cfg)
        self._fetch_worker.moveToThread(self._fetch_thread)
        self._fetch_thread.started.connect(self._fetch_worker.run)
        self._fetch_worker.finished.connect(self._handle_fetch_versions_success)
        self._fetch_worker.failed.connect(self._handle_fetch_versions_failure)
        self._fetch_worker.finished.connect(self._fetch_thread.quit)
        self._fetch_worker.failed.connect(self._fetch_thread.quit)
        self._fetch_thread.finished.connect(self._finish_fetch_versions_thread)
        self._fetch_thread.start()

    def _handle_fetch_versions_success(self, fetch_result: dict[str, Any]) -> None:
        versions = fetch_result.get("versions", [])
        sender = fetch_result.get("sender", {})
        sender_error = fetch_result.get("sender_error")
        self._card_versions = [str(item).strip().upper() for item in versions if str(item).strip()]
        allowed_versions = set(self._card_versions)
        trimmed_maps: dict[str, dict[str, str]] = {}
        for business_key, _ in CARD_BUSINESS_OPTIONS:
            source_mapping = self._card_version_preset_map_by_business.get(business_key, {})
            trimmed_maps[business_key] = {
                version: preset for version, preset in source_mapping.items() if version in allowed_versions
            }
        self._card_version_preset_map_by_business = trimmed_maps
        if isinstance(sender, dict):
            self._fill_sender_if_blank(self.sender_name_edit, sender.get("my_name", ""))
            self._fill_sender_if_blank(self.sender_phone_edit, sender.get("my_phone", ""))
            self._fill_sender_if_blank(self.sender_post_code_edit, sender.get("my_postCode", ""))
            self._fill_sender_if_blank(self.sender_address_edit, sender.get("my_address", ""))
        self._refresh_version_mapping_table()
        config_path = self._auto_save_runtime_config()
        details: dict[str, Any] = {
            "版本数量": len(self._card_versions),
            "本台姓名": self.sender_name_edit.text().strip(),
            "路径": config_path,
        }
        message = "已拉取卡片版本，映射配置已自动保存（密码不落盘）。"
        if sender_error:
            details["本台通信地址"] = sender_error
            message += " 本台通信地址拉取失败，已保留当前本地填写内容。"
        else:
            message += " 空白本台地址字段已按远程资料补齐。"
        self._show_info("拉取成功", message, details)

    def _handle_fetch_versions_failure(self, message: str, details: Any) -> None:
        self._show_error("拉取版本失败", message, details)

    def _finish_fetch_versions_thread(self) -> None:
        if self._fetch_worker is not None:
            self._fetch_worker.deleteLater()
        if self._fetch_thread is not None:
            self._fetch_thread.deleteLater()
        self._fetch_worker = None
        self._fetch_thread = None
        self.btn_fetch_versions.setEnabled(True)
        self.btn_fetch_versions.setText("登录并拉取卡片版本")

    def current_card_versions(self) -> list[str]:
        return list(self._card_versions)

    def _clear_card_version_mapping(self) -> None:
        self._card_version_preset_map_by_business = {
            CARD_BUSINESS_QSO: {},
            CARD_BUSINESS_ONLINE: {},
            CARD_BUSINESS_OFFLINE: {},
        }
        self._refresh_version_mapping_table()
        self._auto_save_runtime_config()

    def _auto_save_runtime_config(self) -> str:
        runtime_cfg = self._build_config_from_form()
        config_path = self.config_path_edit.text().strip() or str((Path.cwd() / "bridge_config.json").resolve())
        persist_cfg = self._build_persist_config(runtime_cfg)
        save_bridge_config(config_path, persist_cfg)
        self._on_apply(config_path, runtime_cfg)
        self.config_path_edit.setText(config_path)
        return config_path

    def set_config(self, config_path: str, config: dict[str, Any]) -> None:
        cfg = normalize_bridge_config(config)
        self._base_config = copy.deepcopy(cfg)

        self.config_path_edit.setText(config_path)
        self.envelope_preset_edit.setText(str(cfg.get("presets", {}).get("envelopes", "")))
        map_by_business = cfg.get("presets", {}).get("card_version_map_by_business", {})
        self._card_version_preset_map_by_business = {
            CARD_BUSINESS_QSO: {},
            CARD_BUSINESS_ONLINE: {},
            CARD_BUSINESS_OFFLINE: {},
        }
        if isinstance(map_by_business, dict):
            for business_key, _ in CARD_BUSINESS_OPTIONS:
                mapping = map_by_business.get(business_key, {})
                if not isinstance(mapping, dict):
                    continue
                normalized: dict[str, str] = {}
                for key, value in mapping.items():
                    version = str(key or "").strip().upper()
                    preset_path = str(value or "").strip()
                    if version and preset_path:
                        normalized[version] = preset_path
                self._card_version_preset_map_by_business[business_key] = normalized
        self._refresh_version_mapping_table()

        self.base_url_edit.setText(str(cfg.get("base_url", "")))
        self._set_qrcode_path_fields(cfg)
        self.timeout_spin.setValue(float(cfg.get("common", {}).get("timeout_s", 30.0)))

        auth_type = str(cfg.get("auth", {}).get("type", "basic")).strip().lower()
        idx = self.auth_type_combo.findData(auth_type)
        self.auth_type_combo.setCurrentIndex(idx if idx >= 0 else 0)
        self.auth_username_edit.setText(str(cfg.get("auth", {}).get("username", "")))
        self.auth_password_edit.setText(str(cfg.get("auth", {}).get("password", "")))
        self.auth_token_edit.setText(str(cfg.get("auth", {}).get("token", "")))
        self.auth_operator_edit.setText(str(cfg.get("auth", {}).get("operator", "")))
        sender = cfg.get("sender", {}) if isinstance(cfg.get("sender", {}), dict) else {}
        self.sender_name_edit.setText(str(sender.get("my_name", "")))
        self.sender_phone_edit.setText(str(sender.get("my_phone", "")))
        self.sender_post_code_edit.setText(str(sender.get("my_postCode", "")))
        self.sender_address_edit.setText(str(sender.get("my_address", "")))
        self._refresh_auth_fields()

    def set_base_config(self, config: dict[str, Any]) -> None:
        self._base_config = normalize_bridge_config(config)

    def _build_config_from_form(self) -> dict[str, Any]:
        cfg = copy.deepcopy(self._base_config)
        cfg["presets"]["cards"] = ""
        cfg["presets"]["envelopes"] = self.envelope_preset_edit.text().strip()
        cfg["presets"]["card_version_map_by_business"] = copy.deepcopy(self._card_version_preset_map_by_business)
        cfg["base_url"] = self.base_url_edit.text().strip()
        cfg.setdefault("qrcode", {})
        cfg["qrcode"]["path_mappings"] = self._qrcode_path_mappings_from_form()
        cfg["common"]["timeout_s"] = float(self.timeout_spin.value())

        cfg["auth"]["type"] = str(self.auth_type_combo.currentData() or "basic")
        cfg["auth"]["username"] = self.auth_username_edit.text().strip()
        cfg["auth"]["password"] = self.auth_password_edit.text()
        cfg["auth"]["token"] = self.auth_token_edit.text().strip()
        cfg["auth"]["operator"] = self.auth_operator_edit.text().strip()
        cfg["sender"]["my_name"] = self.sender_name_edit.text().strip()
        cfg["sender"]["my_phone"] = self.sender_phone_edit.text().strip()
        cfg["sender"]["my_postCode"] = self.sender_post_code_edit.text().strip()
        cfg["sender"]["my_address"] = self.sender_address_edit.text().strip()

        cfg["writeback"]["enabled"] = True
        return normalize_bridge_config(cfg)

    def _set_qrcode_path_fields(self, cfg: dict[str, Any]) -> None:
        mappings = cfg.get("qrcode", {}).get("path_mappings", {})
        if not isinstance(mappings, dict):
            mappings = {}
        self.qrcode_offline_eyeball_path_edit.setText(str(mappings.get(PUBLIC_EYEBALL_ENDPOINT, "/EYEBALL")))
        self.qrcode_online_eyeball_path_edit.setText(
            str(mappings.get(PUBLIC_ONLINE_EYEBALL_ENDPOINT, "/ONLINE_EYEBALL"))
        )
        self.qrcode_receipt_path_edit.setText(str(mappings.get(PUBLIC_RECEIPT_ENDPOINT, "/rp")))

    def _qrcode_path_mappings_from_form(self) -> dict[str, str]:
        return {
            PUBLIC_EYEBALL_ENDPOINT: self.qrcode_offline_eyeball_path_edit.text().strip(),
            PUBLIC_ONLINE_EYEBALL_ENDPOINT: self.qrcode_online_eyeball_path_edit.text().strip(),
            PUBLIC_RECEIPT_ENDPOINT: self.qrcode_receipt_path_edit.text().strip(),
        }

    def _fill_sender_if_blank(self, target_edit: QLineEdit, value: Any) -> None:
        if target_edit.text().strip():
            return
        text = str(value or "").strip()
        if text:
            target_edit.setText(text)

    def _build_persist_config(self, runtime_config: dict[str, Any]) -> dict[str, Any]:
        cfg = copy.deepcopy(runtime_config)
        cfg.setdefault("auth", {})
        cfg["auth"]["password"] = ""
        return normalize_bridge_config(cfg)

    def _new_default(self) -> None:
        default_path = self.config_path_edit.text().strip() or str((Path.cwd() / "bridge_config.json").resolve())
        cfg = default_bridge_config()
        self.set_config(default_path, cfg)
        self._on_apply(default_path, cfg)
        self._show_info("已重置", "已加载默认在线打印配置。")

    def _load_config(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "加载在线配置", str(Path.cwd()), "JSON Files (*.json)")
        if not path:
            return
        try:
            cfg = load_bridge_config(path)
            self.set_config(path, cfg)
            self._on_apply(path, cfg)
            self._show_info("加载成功", "在线配置已加载。", {"path": path})
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("加载失败", str(exc), details)

    def _save_config(self) -> None:
        target = self.config_path_edit.text().strip()
        if not target:
            target, _ = QFileDialog.getSaveFileName(
                self,
                "保存在线配置",
                str((Path.cwd() / "bridge_config.json").resolve()),
                "JSON Files (*.json)",
            )
            if not target:
                return
            self.config_path_edit.setText(target)

        try:
            runtime_cfg = self._build_config_from_form()
            persist_cfg = self._build_persist_config(runtime_cfg)
            save_bridge_config(target, persist_cfg)
            self._on_apply(target, runtime_cfg)
            self._show_info("保存成功", "在线配置已保存。", {"path": target})
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("保存失败", str(exc), details)

    def _apply_config(self) -> None:
        try:
            runtime_cfg = self._build_config_from_form()
            config_path = self.config_path_edit.text().strip() or str((Path.cwd() / "bridge_config.json").resolve())
            persist_cfg = self._build_persist_config(runtime_cfg)
            save_bridge_config(config_path, persist_cfg)
            self._on_apply(config_path, runtime_cfg)
            self.config_path_edit.setText(config_path)
            self._show_info("应用成功", "在线配置已应用并保存（密码不落盘）。", {"path": config_path})
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("应用失败", str(exc), details)

class OnlineDatasetPage(QWidget):
    def __init__(
        self,
        *,
        dataset: str,
        title: str,
        card_business: str = "",
        get_config: Callable[[], dict[str, Any]],
        set_config: Callable[[dict[str, Any]], None],
        parent: QWidget | None = None,
    ) -> None:
        super().__init__(parent)
        self.dataset = dataset
        self.title = title
        self.card_business = card_business.strip().lower()
        self._get_config = get_config
        self._set_config = set_config
        self._bridge = BridgeService()
        self._preset_dataset = "envelopes" if dataset == ADDRESS_ENVELOPE_DATASET else dataset
        self._enable_card_version_filter = dataset == "cards"
        self._enable_activity_filter = dataset == "cards" and self.card_business == CARD_BUSINESS_OFFLINE
        self._enable_batch_check = dataset == ADDRESS_ENVELOPE_DATASET
        self._enable_address_envelope_filter = dataset == ADDRESS_ENVELOPE_DATASET
        self._enable_envelope_destination_filter = dataset == "envelopes"

        self._preset_path = ""
        self._preset_meta: dict[str, Any] = {"field_keys": [], "paper_name": "", "preferred_printer": ""}
        self._card_version_preset_map: dict[str, str] = {}
        self._configured_card_versions: list[str] = []
        self._source_rows: list[dict[str, Any]] = []
        self._source_timestamp: str = ""
        self.records: list[dict[str, Any]] = []
        self._table_schema: list[tuple[str, list[str]]] = []
        self._qrcode_remark_text: str = QRCODE_REMARK_DEFAULT
        self._queue_thread: QThread | None = None
        self._queue_worker: BackgroundTaskWorker | None = None

        self.lbl_preset = QLabel("未配置", self)
        self.lbl_preset.setWordWrap(True)
        self.lbl_preset.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.lbl_source = QLabel("队列：未生成", self)
        self.lbl_source.setWordWrap(True)
        self.lbl_mapping = QLabel("字段映射已内置固定，不支持在此页面修改。", self)
        self.lbl_mapping.setWordWrap(True)

        self.printer_combo = QComboBox(self)
        self.paper_combo = QComboBox(self)
        self.btn_refresh_printer = QPushButton("刷新打印机", self)
        self.btn_refresh_paper = QPushButton("刷新纸张", self)

        self.btn_build_queue = QPushButton("重新拉取并生成队列", self)
        self.btn_remove_selected = QPushButton("移除选中条目", self)
        self.btn_clear = QPushButton("清空队列", self)
        self.card_version_filter_combo = QComboBox(self)
        self.card_version_filter_combo.addItem("请选择卡片版本", CARD_VERSION_FILTER_PENDING)
        self.card_version_filter_combo.addItem("全部版本", "")
        self.card_version_filter_combo.setEnabled(self._enable_card_version_filter)
        self.card_version_filter_combo.setVisible(self._enable_card_version_filter)
        self.activity_filter_combo = QComboBox(self)
        self.activity_filter_combo.addItem("全部活动", ACTIVITY_FILTER_ALL)
        self.activity_filter_combo.setEnabled(self._enable_activity_filter)
        self.activity_filter_combo.setVisible(self._enable_activity_filter)
        self.envelope_destination_filter_combo = QComboBox(self)
        self.envelope_destination_filter_combo.addItem("全部卡片", ENVELOPE_DESTINATION_ALL)
        self.envelope_destination_filter_combo.addItem("国内卡片", ENVELOPE_DESTINATION_DOMESTIC)
        self.envelope_destination_filter_combo.addItem("国际卡片", ENVELOPE_DESTINATION_INTERNATIONAL)
        self.envelope_destination_filter_combo.setEnabled(self._enable_envelope_destination_filter)
        self.envelope_destination_filter_combo.setVisible(self._enable_envelope_destination_filter)
        self.address_envelope_filter_edit = QLineEdit(self)
        self.address_envelope_filter_edit.setPlaceholderText("输入呼号、地址编号或卡片局编号")
        self.address_envelope_filter_edit.setEnabled(self._enable_address_envelope_filter)
        self.address_envelope_filter_edit.setVisible(self._enable_address_envelope_filter)
        self.qrcode_remark_container = QWidget(self)
        self.qrcode_remark_label = QLabel("二维码提示文字", self.qrcode_remark_container)
        self.qrcode_remark_edit = QLineEdit(self.qrcode_remark_container)
        self.qrcode_remark_edit.setText(self._qrcode_remark_text)
        self.qrcode_remark_edit.setPlaceholderText(QRCODE_REMARK_DEFAULT)
        self.qrcode_remark_container.setVisible(False)

        self.record_table = QTableWidget(0, 1, self)
        self.record_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.record_table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.record_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self._setup_record_table_schema()

        self.btn_print_selected = QPushButton("打印选中", self)
        self.btn_print_checked = QPushButton("打印勾选", self)
        self.btn_print_checked.setVisible(self._enable_batch_check)
        self.btn_print_all = QPushButton("打印全部", self)
        self.lbl_result = QLabel("未执行打印。", self)
        self.lbl_result.setWordWrap(True)

        self.preview = PreviewCanvas(self)
        self.preview.set_editable(False)

        self._build_ui()
        self._bind_events()
        self.refresh_printers()
        self.refresh_papers()

    def _build_ui(self) -> None:
        splitter = QSplitter(Qt.Horizontal, self)

        left = QWidget(splitter)
        left_layout = QVBoxLayout(left)

        preset_group = QGroupBox("当前使用预设", left)
        preset_layout = QVBoxLayout(preset_group)
        preset_layout.addWidget(self.lbl_preset)
        preset_layout.addWidget(self.lbl_source)
        left_layout.addWidget(preset_group)

        mapping_group = QGroupBox("字段映射", left)
        mapping_layout = QVBoxLayout(mapping_group)
        mapping_layout.addWidget(self.lbl_mapping)
        left_layout.addWidget(mapping_group)

        printer_group = QGroupBox("打印设备", left)
        printer_layout = QFormLayout(printer_group)
        printer_layout.addRow("打印机", self.printer_combo)
        printer_layout.addRow("纸张", self.paper_combo)
        printer_layout.addRow(self.btn_refresh_printer, self.btn_refresh_paper)
        left_layout.addWidget(printer_group)

        fetch_row = QHBoxLayout()
        if self._enable_card_version_filter:
            fetch_row.addWidget(QLabel("卡片版本", left))
            fetch_row.addWidget(self.card_version_filter_combo)
        if self._enable_activity_filter:
            fetch_row.addWidget(QLabel("关联活动", left))
            fetch_row.addWidget(self.activity_filter_combo)
        if self._enable_envelope_destination_filter:
            fetch_row.addWidget(QLabel("去向类型", left))
            fetch_row.addWidget(self.envelope_destination_filter_combo)
        if self._enable_address_envelope_filter:
            fetch_row.addWidget(QLabel("筛选", left))
            fetch_row.addWidget(self.address_envelope_filter_edit, 1)
        fetch_row.addWidget(self.btn_build_queue)
        fetch_row.addWidget(self.btn_remove_selected)
        fetch_row.addWidget(self.btn_clear)
        left_layout.addLayout(fetch_row)
        if self._enable_card_version_filter:
            qrcode_row = QHBoxLayout()
            qrcode_row.setContentsMargins(0, 0, 0, 0)
            qrcode_row.addWidget(self.qrcode_remark_label)
            qrcode_row.addWidget(self.qrcode_remark_edit, 1)
            self.qrcode_remark_container.setLayout(qrcode_row)
            left_layout.addWidget(self.qrcode_remark_container)

        left_layout.addWidget(self.record_table, 2)

        print_row = QHBoxLayout()
        print_row.addWidget(self.btn_print_selected)
        print_row.addWidget(self.btn_print_checked)
        print_row.addWidget(self.btn_print_all)
        left_layout.addLayout(print_row)
        left_layout.addWidget(self.lbl_result)

        splitter.addWidget(left)
        splitter.addWidget(self.preview)
        splitter.setSizes([620, 760])

        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        root.addWidget(splitter, 1)

    def _bind_events(self) -> None:
        self.btn_refresh_printer.clicked.connect(self.refresh_printers)
        self.btn_refresh_paper.clicked.connect(self.refresh_papers)
        self.printer_combo.currentTextChanged.connect(lambda _: self.refresh_papers())
        self.btn_build_queue.clicked.connect(self.refresh_queue_from_remote)
        self.btn_remove_selected.clicked.connect(self.remove_selected_record)
        self.btn_clear.clicked.connect(self.clear_rows)
        if self._enable_card_version_filter:
            self.card_version_filter_combo.currentIndexChanged.connect(self._on_card_version_filter_changed)
            self.qrcode_remark_edit.textChanged.connect(self._on_qrcode_remark_text_changed)
        if self._enable_activity_filter:
            self.activity_filter_combo.currentIndexChanged.connect(self._on_activity_filter_changed)
        if self._enable_envelope_destination_filter:
            self.envelope_destination_filter_combo.currentIndexChanged.connect(self._on_envelope_destination_filter_changed)
        if self._enable_address_envelope_filter:
            self.address_envelope_filter_edit.textChanged.connect(self._on_address_envelope_filter_changed)
        self.record_table.itemSelectionChanged.connect(self.preview_selected_record)
        self.btn_print_selected.clicked.connect(self.print_selected_record)
        self.btn_print_checked.clicked.connect(self.print_checked_records)
        self.btn_print_all.clicked.connect(self.print_all_records)

    def _show_error(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else _json_text(details)
        QMessageBox.critical(self, title, f"{message}\n{detail_text}")

    def _show_info(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else _json_text(details)
        QMessageBox.information(self, title, f"{message}\n{detail_text}")

    def _status(self, text: str) -> None:
        window = self.window()
        if isinstance(window, QMainWindow):
            window.statusBar().showMessage(text, 3000)

    def _apply_printer_and_paper_from_preset(self, preset_path: str) -> None:
        path = str(preset_path or "").strip()
        if not path:
            return
        meta = _load_preset_meta(path)
        preferred_printer = str(meta.get("preferred_printer", "")).strip()
        if preferred_printer:
            self._select_printer_by_name(preferred_printer, refresh_if_unchanged=True)
        preferred_paper = str(meta.get("paper_name", "")).strip().casefold()
        if preferred_paper:
            for idx in range(self.paper_combo.count()):
                if self.paper_combo.itemText(idx).strip().casefold() == preferred_paper:
                    self.paper_combo.setCurrentIndex(idx)
                    break

    def _select_printer_by_name(self, printer_name: str, refresh_if_unchanged: bool = False) -> bool:
        target = printer_name.strip().casefold()
        if not target:
            return False
        current = self.printer_combo.currentIndex()
        for idx in range(self.printer_combo.count()):
            if self.printer_combo.itemText(idx).strip().casefold() != target:
                continue
            self.printer_combo.setCurrentIndex(idx)
            if idx == current and refresh_if_unchanged:
                self.refresh_papers()
            return True
        return False

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
            self._show_error("刷新打印机失败", str(exc))

    def refresh_papers(self) -> None:
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            return
        try:
            payload = run_cli_json(["printer", "papers", "--printer", printer_name])
            items = payload["data"].get("items", [])
            previous = self.paper_combo.currentText().strip().casefold()
            self.paper_combo.clear()
            self.paper_combo.addItems(items)
            if previous:
                for idx, name in enumerate(items):
                    if str(name).strip().casefold() == previous:
                        self.paper_combo.setCurrentIndex(idx)
                        break
        except Exception as exc:
            self._show_error("刷新纸张失败", str(exc))

    def set_config(self, config: dict[str, Any]) -> None:
        cfg = normalize_bridge_config(config)
        self._preset_path = str(cfg.get("presets", {}).get(self._preset_dataset, "")).strip()
        self._card_version_preset_map = {}
        if self.dataset == "cards":
            map_by_business = cfg.get("presets", {}).get("card_version_map_by_business", {})
            mapping = map_by_business.get(self.card_business, {}) if isinstance(map_by_business, dict) else {}
            if isinstance(mapping, dict):
                for key, value in mapping.items():
                    version = str(key or "").strip().upper()
                    path = str(value or "").strip()
                    if version and path:
                        self._card_version_preset_map[version] = path
        self._preset_meta = _load_preset_meta(self._preset_path)
        if self.dataset == "cards":
            business_label = dict(CARD_BUSINESS_OPTIONS).get(self.card_business, self.card_business or "-")
            self.lbl_preset.setText(
                f"业务：{business_label}\n默认：{self._preset_path or '未配置'}\n版本映射：{len(self._card_version_preset_map)} 项"
            )
        else:
            self.lbl_preset.setText(self._preset_path or "未配置")
        self._refresh_qrcode_remark_visibility()

        preferred_printer = str(self._preset_meta.get("preferred_printer", "")).strip()
        if preferred_printer:
            self._select_printer_by_name(preferred_printer, refresh_if_unchanged=True)
        preset_paper = str(self._preset_meta.get("paper_name", "")).strip().casefold()
        if preset_paper:
            for idx in range(self.paper_combo.count()):
                if self.paper_combo.itemText(idx).strip().casefold() == preset_paper:
                    self.paper_combo.setCurrentIndex(idx)
                    break

        self._update_source_label()

    def set_source_rows(self, rows: list[dict[str, Any]], timestamp_text: str) -> None:
        self._source_rows = list(rows)
        self._source_timestamp = timestamp_text
        self._update_source_label()

    def set_card_versions(self, versions: list[str]) -> None:
        normalized: list[str] = []
        seen: set[str] = set()
        for item in versions:
            version = str(item or "").strip().upper()
            if not version or version in seen:
                continue
            seen.add(version)
            normalized.append(version)
        self._configured_card_versions = normalized
        self._refresh_card_version_filter_options()

    def _update_source_label(self) -> None:
        if self._enable_card_version_filter:
            self._refresh_card_version_filter_options()
        if self._enable_activity_filter:
            self._refresh_activity_filter_options()
        if not self._source_rows:
            self.lbl_source.setText("队列：未生成")
            return
        ts = self._source_timestamp or "-"
        self.lbl_source.setText(f"队列来源：{len(self._source_rows)} 条（更新时间 {ts}）")

    def _setup_record_table_schema(self) -> None:
        if self.dataset == "cards":
            self._table_schema = [
                ("记录ID", ["record_id"]),
                ("呼号", ["mapped_row.peerCallsign", "source_row.spec.callSign", "source_row.callSign"]),
                ("卡片类型", ["mapped_row.card_tpye", "source_row.spec.cardType", "source_row.cardType"]),
                ("卡片版本", ["source_row.spec.cardVersion", "source_row.cardVersion"]),
                ("日期", ["mapped_row.Date", "source_row.spec.cardDate", "source_row.cardDate"]),
                ("时间", ["mapped_row.Time", "source_row.spec.cardTime", "source_row.cardTime"]),
                ("频率", ["mapped_row.frequency", "source_row.qsoInfo.spec.freq", "source_row.spec.freq"]),
                ("模式", ["mapped_row.mode", "source_row.qsoInfo.spec.myRigMode", "source_row.spec.myRigMode"]),
                ("信号报告", ["mapped_row.rstSent", "source_row.qsoInfo.spec.rstSent", "source_row.spec.rstSent"]),
                (
                    "QTH",
                    [
                        "mapped_row.my_qth",
                        "mapped_row.qth",
                        "source_row.qsoInfo.spec.myQth",
                        "source_row.spec.myQth",
                        "source_row.qsoInfo.spec.qth",
                        "source_row.spec.qth",
                    ],
                ),
            ]
            if self.card_business == CARD_BUSINESS_OFFLINE:
                self._table_schema.insert(4, ("关联活动", ["source_row.spec.offlineActivityName", "source_row.offlineActivityName"]))
        elif self.dataset == ADDRESS_ENVELOPE_DATASET:
            self._table_schema = [
                ("来源", ["source_row.sourceLabel"]),
                ("编号", ["source_row.metadata.name"]),
                ("呼号/卡片局", ["source_row.spec.callSign", "source_row.spec.bureauName", "source_row.bureauName"]),
                ("收件姓名", ["mapped_row.name", "source_row.addressInfo.spec.name", "source_row.bureauInfo.spec.bureauName"]),
                ("收件地址", ["mapped_row.address", "source_row.addressInfo.spec.address", "source_row.bureauInfo.spec.address"]),
                ("收件邮编", ["mapped_row.postCode", "source_row.addressInfo.spec.postalCode", "source_row.bureauInfo.spec.postalCode"]),
                ("去向国", ["mapped_row.destinationCountry", "source_row.addressInfo.spec.destinationCountry", "source_row.bureauInfo.spec.destinationCountry"]),
                ("收件电话", ["mapped_row.phone", "source_row.addressInfo.spec.telephone", "source_row.bureauInfo.spec.telephone"]),
                ("邮箱", ["source_row.addressInfo.spec.email", "source_row.spec.email"]),
            ]
        else:
            self._table_schema = [
                ("记录ID", ["record_id"]),
                ("呼号", ["source_row.spec.callSign", "source_row.callSign"]),
                ("收件姓名", ["mapped_row.name", "source_row.addressInfo.spec.name", "source_row.bureauInfo.spec.bureauName"]),
                ("收件地址", ["mapped_row.address", "source_row.addressInfo.spec.address", "source_row.bureauInfo.spec.address"]),
                ("收件邮编", ["mapped_row.postCode", "source_row.addressInfo.spec.postalCode", "source_row.bureauInfo.spec.postalCode"]),
                ("去向国", ["mapped_row.destinationCountry", "source_row.addressInfo.spec.destinationCountry", "source_row.bureauInfo.spec.destinationCountry"]),
                ("收件电话", ["mapped_row.phone", "source_row.addressInfo.spec.telephone", "source_row.bureauInfo.spec.telephone"]),
            ]
        check_columns = 1 if self._enable_batch_check else 0
        self.record_table.setColumnCount(len(self._table_schema) + check_columns)
        headers = ["勾选"] if self._enable_batch_check else []
        headers.extend([title for title, _ in self._table_schema])
        self.record_table.setHorizontalHeaderLabels(headers)

    def _pick_display_value(self, record: dict[str, Any], candidates: list[str]) -> str:
        for path in candidates:
            value = _lookup_path_value(record, path)
            if value is None:
                continue
            text = str(value).strip()
            if text:
                return text
        return ""

    def _matches_card_business(self, source_row: dict[str, Any]) -> bool:
        if self.dataset != "cards":
            return True
        spec_raw = source_row.get("spec")
        spec = spec_raw if isinstance(spec_raw, dict) else {}
        scene_type = str(spec.get("sceneType", "")).strip().upper()
        card_type = str(spec.get("cardType", "")).strip().upper()
        if self.card_business == CARD_BUSINESS_QSO:
            return scene_type in {"QSO", "SWL"} or card_type in {"QSO", "SWL"}
        if self.card_business == CARD_BUSINESS_ONLINE:
            return scene_type == "ONLINE_EYEBALL"
        if self.card_business == CARD_BUSINESS_OFFLINE:
            return scene_type == "EYEBALL"
        return True

    def _matches_address_envelope_filter(self, source_row: dict[str, Any]) -> bool:
        if not self._enable_address_envelope_filter:
            return True
        keyword = self.address_envelope_filter_edit.text().strip().upper()
        if not keyword:
            return True
        spec_raw = source_row.get("spec")
        spec = spec_raw if isinstance(spec_raw, dict) else {}
        metadata_raw = source_row.get("metadata")
        metadata = metadata_raw if isinstance(metadata_raw, dict) else {}
        candidates = [
            metadata.get("name"),
            spec.get("callSign"),
            source_row.get("callSign"),
            spec.get("bureauName"),
            source_row.get("bureauName"),
        ]
        return any(keyword in str(item or "").strip().upper() for item in candidates)

    def _matches_envelope_destination_filter(self, source_row: dict[str, Any]) -> bool:
        if not getattr(self, "_enable_envelope_destination_filter", False):
            return True
        selected = str(self.envelope_destination_filter_combo.currentData() or "").strip()
        if not selected:
            return True
        has_destination_country = bool(_resolve_envelope_destination_country(source_row))
        if selected == ENVELOPE_DESTINATION_DOMESTIC:
            return not has_destination_country
        if selected == ENVELOPE_DESTINATION_INTERNATIONAL:
            return has_destination_country
        return True

    def _matches_queue_rule(self, source_row: dict[str, Any]) -> bool:
        if not self._matches_card_business(source_row):
            return False
        if self.dataset == "envelopes" and not self._matches_envelope_destination_filter(source_row):
            return False
        if self.dataset == ADDRESS_ENVELOPE_DATASET and not self._matches_address_envelope_filter(source_row):
            return False
        spec_raw = source_row.get("spec")
        spec = spec_raw if isinstance(spec_raw, dict) else {}
        if self._enable_activity_filter:
            selected_activity = str(self.activity_filter_combo.currentData() or "").strip()
            if selected_activity and _resolve_offline_activity_name(source_row) != selected_activity:
                return False
        card_issued = _to_bool(spec.get("cardIssued", source_row.get("cardIssued")))
        envelope_printed = _to_bool(spec.get("envelopePrinted", source_row.get("envelopePrinted")))
        selected_version = (
            str(self.card_version_filter_combo.currentData() or "").strip().upper()
            if self._enable_card_version_filter
            else ""
        )
        if self.dataset == "cards":
            # 卡片打印队列：仅未制卡。
            if card_issued:
                return False
            if selected_version == CARD_VERSION_FILTER_PENDING:
                return False
            if not selected_version:
                return True
            row_versions = _split_card_versions(self._resolve_source_card_version(source_row))
            if not row_versions:
                return False
            return selected_version in row_versions
        if self.dataset == "envelopes":
            # 封面打印队列：所有未打包卡片。
            if envelope_printed:
                return False
            return True
        return True

    def _resolve_source_card_version(self, source_row: dict[str, Any]) -> str:
        spec_raw = source_row.get("spec")
        spec = spec_raw if isinstance(spec_raw, dict) else {}
        return str(spec.get("cardVersion", "")).strip().upper()

    def _refresh_card_version_filter_options(self) -> None:
        if not self._enable_card_version_filter:
            return
        selected = str(self.card_version_filter_combo.currentData() or "").strip().upper()
        versions_set: set[str] = set()
        for version in self._configured_card_versions:
            versions_set.add(version)
        for row in self._source_rows:
            spec_raw = row.get("spec")
            spec = spec_raw if isinstance(spec_raw, dict) else {}
            if self.dataset == "cards" and _to_bool(spec.get("cardIssued", row.get("cardIssued"))):
                continue
            version_text = self._resolve_source_card_version(row)
            for version in _split_card_versions(version_text):
                versions_set.add(version)
        versions = sorted(versions_set)
        self.card_version_filter_combo.blockSignals(True)
        self.card_version_filter_combo.clear()
        self.card_version_filter_combo.addItem("请选择卡片版本", CARD_VERSION_FILTER_PENDING)
        self.card_version_filter_combo.addItem("全部版本", "")
        for version in versions:
            self.card_version_filter_combo.addItem(version, version)
        if selected and selected != CARD_VERSION_FILTER_PENDING:
            idx = self.card_version_filter_combo.findData(selected)
            if idx >= 0:
                self.card_version_filter_combo.setCurrentIndex(idx)
            else:
                self.card_version_filter_combo.setCurrentIndex(0 if self.dataset == "cards" else 1)
        else:
            self.card_version_filter_combo.setCurrentIndex(0 if self.dataset == "cards" else 1)
        self.card_version_filter_combo.blockSignals(False)

    def _refresh_activity_filter_options(self) -> None:
        if not self._enable_activity_filter:
            return
        selected = str(self.activity_filter_combo.currentData() or "").strip()
        activity_set: set[str] = set()
        for row in self._source_rows:
            if not self._matches_card_business(row):
                continue
            spec_raw = row.get("spec")
            spec = spec_raw if isinstance(spec_raw, dict) else {}
            if _to_bool(spec.get("cardIssued", row.get("cardIssued"))):
                continue
            activity_name = _resolve_offline_activity_name(row)
            if activity_name:
                activity_set.add(activity_name)
        activities = sorted(activity_set, key=lambda item: item.casefold())
        self.activity_filter_combo.blockSignals(True)
        self.activity_filter_combo.clear()
        self.activity_filter_combo.addItem("全部活动", ACTIVITY_FILTER_ALL)
        for activity_name in activities:
            self.activity_filter_combo.addItem(activity_name, activity_name)
        if selected:
            idx = self.activity_filter_combo.findData(selected)
            self.activity_filter_combo.setCurrentIndex(idx if idx >= 0 else 0)
        else:
            self.activity_filter_combo.setCurrentIndex(0)
        self.activity_filter_combo.blockSignals(False)

    def _build_envelope_group_key(self, mapped_row: dict[str, Any]) -> tuple[str, str, str]:
        post_code = str(mapped_row.get("postCode", "") or mapped_row.get("postalCode", "")).strip().upper()
        address = str(mapped_row.get("address", "")).strip().upper()
        phone = str(mapped_row.get("phone", "")).strip().upper()
        return (post_code, address, phone)

    def _rebuild_rows_from_source(self) -> None:
        if not self._source_rows:
            self.records = []
            self._refresh_record_table()
            self.preview.set_scene({})
            result_text = f"{self.title}: 已完成拉取，远程 0 条，生成队列 0 条。"
            self.lbl_result.setText(result_text)
            self._status(result_text)
            return
        if self.dataset == "cards":
            selected_version = str(self.card_version_filter_combo.currentData() or "").strip().upper()
            if selected_version == CARD_VERSION_FILTER_PENDING:
                self.records = []
                self._refresh_record_table()
                self.preview.set_scene({})
                result_text = f"{self.title}: 已完成拉取，远程 {len(self._source_rows)} 条；请选择卡片版本后生成队列。"
                self.lbl_result.setText(result_text)
                self._status(result_text)
                return
        try:
            cfg = normalize_bridge_config(self._get_config())
            self._set_config(cfg)
            dataset_cfg = cfg.get("writeback", {}).get("datasets", {}).get(self.dataset, {}) or {}
            id_field = str(dataset_cfg.get("id_field", "")).strip()
            mapping = cfg.get("mappings", {}).get(self.dataset, {}) or {}
            self.records = []
            skipped_by_rule = 0
            selected_version = (
                str(self.card_version_filter_combo.currentData() or "").strip().upper()
                if self._enable_card_version_filter
                else ""
            )
            envelope_groups: dict[tuple[str, str, str], dict[str, Any]] = {}
            for index, source_row in enumerate(self._source_rows):
                if not self._matches_queue_rule(source_row):
                    skipped_by_rule += 1
                    continue
                source_version_text = self._resolve_source_card_version(source_row) if self.dataset == "cards" else ""
                source_versions = _split_card_versions(source_version_text) if self.dataset == "cards" else []
                if self.dataset == "cards" and source_versions:
                    versions_to_emit = source_versions
                    if selected_version and selected_version not in {"", CARD_VERSION_FILTER_PENDING}:
                        versions_to_emit = [version for version in source_versions if version == selected_version]
                    for version in versions_to_emit:
                        derived_row = copy.deepcopy(source_row)
                        spec_raw = derived_row.get("spec")
                        spec = spec_raw if isinstance(spec_raw, dict) else {}
                        spec["cardVersion"] = version
                        derived_row["spec"] = spec

                        mapped_row = map_export_row(derived_row, mapping)
                        self._apply_business_defaults_to_mapped_row(mapped_row)
                        self._apply_qrcode_fields_to_mapped_row(mapped_row, derived_row, cfg)
                        record_id = "" if not id_field else str(_lookup_path_value(derived_row, id_field)).strip()
                        self.records.append(
                            {
                                "index": index,
                                "source_row": derived_row,
                                "mapped_row": mapped_row,
                                "record_id": record_id,
                            }
                        )
                    continue

                mapped_row = map_export_row(source_row, mapping)
                self._apply_business_defaults_to_mapped_row(mapped_row)
                self._apply_qrcode_fields_to_mapped_row(mapped_row, source_row, cfg)
                if self.dataset == ADDRESS_ENVELOPE_DATASET:
                    recipient = _build_envelope_recipient_name(source_row, mapped_row)
                    if recipient:
                        mapped_row["name"] = recipient
                if self.dataset == "envelopes":
                    group_key = self._build_envelope_group_key(mapped_row)
                    call_sign = str(_lookup_path_value(source_row, "spec.callSign") or _lookup_path_value(source_row, "callSign") or "").strip().upper()
                    record_id = "" if not id_field else str(_lookup_path_value(source_row, id_field)).strip()
                    if group_key not in envelope_groups:
                        envelope_groups[group_key] = {
                            "index": index,
                            "source_rows": [source_row],
                            "mapped_row": mapped_row,
                            "call_signs": [call_sign] if call_sign else [],
                            "record_ids": [record_id] if record_id else [],
                        }
                    else:
                        group = envelope_groups[group_key]
                        group["source_rows"].append(source_row)
                        if call_sign:
                            group["call_signs"].append(call_sign)
                        if record_id:
                            group["record_ids"].append(record_id)
                    continue

                record_id = "" if not id_field else str(_lookup_path_value(source_row, id_field)).strip()
                self.records.append(
                    {
                        "index": index,
                        "source_row": source_row,
                        "mapped_row": mapped_row,
                        "record_id": record_id,
                    }
                )

            if self.dataset == "envelopes":
                for group in envelope_groups.values():
                    call_signs: list[str] = list(group.get("call_signs", []))
                    mapped_row = dict(group.get("mapped_row", {}))
                    source_rows = list(group.get("source_rows", []))
                    if len(source_rows) > 1:
                        recipient = _build_envelope_recipient_name_by_call_signs(call_signs)
                    else:
                        single_source = source_rows[0] if source_rows else {}
                        recipient = _build_envelope_recipient_name(single_source, mapped_row)
                    if recipient:
                        mapped_row["name"] = recipient
                    merged_source_row = source_rows[0] if source_rows else {}
                    record_ids = [str(item).strip() for item in group.get("record_ids", []) if str(item).strip()]
                    record_id_text = "、".join(dict.fromkeys(record_ids))
                    self.records.append(
                        {
                            "index": int(group.get("index", 0)),
                            "source_row": merged_source_row,
                            "source_rows": source_rows,
                            "mapped_row": mapped_row,
                            "record_id": record_id_text,
                        }
                    )
            self._refresh_record_table()
            if self.record_table.rowCount() > 0:
                self.record_table.selectRow(0)
            result_text = (
                f"{self.title}: 已完成拉取，远程 {len(self._source_rows)} 条，"
                f"生成队列 {len(self.records)} 条，过滤跳过 {skipped_by_rule} 条。"
            )
            self.lbl_result.setText(result_text)
            self._status(result_text)
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self.lbl_result.setText(f"{self.title}: 生成队列失败：{exc}")
            self._show_error("生成队列失败", str(exc), details)

    def refresh_queue_from_remote(self) -> None:
        if self._queue_thread is not None and self._queue_thread.isRunning():
            return
        try:
            cfg = normalize_bridge_config(self._get_config())
            self._set_config(cfg)
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("拉取失败", str(exc), details)
            return

        self.btn_build_queue.setEnabled(False)
        self.btn_build_queue.setText("正在拉取...")
        self.lbl_source.setText("队列：正在拉取远程数据...")
        result_text = f"{self.title}: 正在拉取远程数据，请稍候..."
        self.lbl_result.setText(result_text)
        self._status(result_text)
        self._queue_thread = QThread(self)
        self._queue_worker = BackgroundTaskWorker(
            lambda cfg=copy.deepcopy(cfg), dataset=self.dataset: BridgeService().fetch_dataset(cfg, dataset)
        )
        self._queue_worker.moveToThread(self._queue_thread)
        self._queue_thread.started.connect(self._queue_worker.run)
        self._queue_worker.finished.connect(self._handle_queue_fetch_success)
        self._queue_worker.failed.connect(self._handle_queue_fetch_failure)
        self._queue_worker.finished.connect(self._queue_thread.quit)
        self._queue_worker.failed.connect(self._queue_thread.quit)
        self._queue_thread.finished.connect(self._finish_queue_fetch_thread)
        self._queue_thread.start()

    def _handle_queue_fetch_success(self, result: Any) -> None:
        payload = result if isinstance(result, dict) else {}
        self._source_rows = [dict(item.get("source_row", {})) for item in payload.get("records", [])]
        self._source_timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        self._update_source_label()
        self._rebuild_rows_from_source()

    def _handle_queue_fetch_failure(self, message: str, details: Any) -> None:
        self.lbl_result.setText(f"{self.title}: 拉取失败：{message}")
        self._show_error("拉取失败", message, details)

    def _finish_queue_fetch_thread(self) -> None:
        if self._queue_worker is not None:
            self._queue_worker.deleteLater()
        if self._queue_thread is not None:
            self._queue_thread.deleteLater()
        self._queue_worker = None
        self._queue_thread = None
        self.btn_build_queue.setEnabled(True)
        self.btn_build_queue.setText("重新拉取并生成队列")

    def _on_card_version_filter_changed(self) -> None:
        if not self._enable_card_version_filter:
            return
        selected_version = str(self.card_version_filter_combo.currentData() or "").strip().upper()
        target_preset = ""
        if selected_version and selected_version not in {"", CARD_VERSION_FILTER_PENDING}:
            target_preset = str(self._card_version_preset_map.get(selected_version, "")).strip()
        if not target_preset:
            target_preset = self._preset_path
        self._apply_printer_and_paper_from_preset(target_preset)
        self._refresh_qrcode_remark_visibility()

        self._rebuild_rows_from_source()

    def _on_activity_filter_changed(self) -> None:
        if not self._enable_activity_filter:
            return
        self._rebuild_rows_from_source()

    def _on_envelope_destination_filter_changed(self) -> None:
        if not self._enable_envelope_destination_filter:
            return
        self._rebuild_rows_from_source()

    def _on_address_envelope_filter_changed(self) -> None:
        if not self._enable_address_envelope_filter:
            return
        self._rebuild_rows_from_source()

    def _current_qrcode_remark_text(self) -> str:
        text = self.qrcode_remark_edit.text().strip()
        return text or QRCODE_REMARK_DEFAULT

    def _has_field_in_preset(self, preset_path: str, field_key: str) -> bool:
        if not preset_path:
            return False
        meta = _load_preset_meta(preset_path)
        keys = [str(item).strip().upper() for item in meta.get("field_keys", [])]
        return field_key.strip().upper() in keys

    def _resolve_active_card_preset_path(self) -> str:
        if not self._enable_card_version_filter:
            return self._preset_path
        selected_version = str(self.card_version_filter_combo.currentData() or "").strip().upper()
        if selected_version and selected_version not in {"", CARD_VERSION_FILTER_PENDING}:
            mapped = str(self._card_version_preset_map.get(selected_version, "")).strip()
            if mapped:
                return mapped
        return self._preset_path

    def _refresh_qrcode_remark_visibility(self) -> None:
        if not self._enable_card_version_filter:
            self.qrcode_remark_container.setVisible(False)
            return
        preset_path = self._resolve_active_card_preset_path()
        visible = self._has_field_in_preset(preset_path, "QRCODE_remark")
        self.qrcode_remark_container.setVisible(visible)

    def _build_qrcode_url(self, source_row: dict[str, Any], cfg: dict[str, Any]) -> str:
        spec_raw = source_row.get("spec")
        spec = spec_raw if isinstance(spec_raw, dict) else {}
        call_sign = (
            str(spec.get("callSign", "")).strip().upper()
            or str(source_row.get("callSign", "")).strip().upper()
        )
        card_id = str(_lookup_path_value(source_row, "metadata.name") or source_row.get("cardId") or "").strip()
        scene_type = str(spec.get("sceneType", "")).strip().upper()
        if scene_type == "EYEBALL":
            endpoint = PUBLIC_EYEBALL_ENDPOINT
        else:
            endpoint = PUBLIC_RECEIPT_ENDPOINT
        endpoint = _resolve_qrcode_endpoint(endpoint, cfg)
        query: dict[str, str] = {}
        if scene_type != "EYEBALL" and call_sign:
            query["cs"] = call_sign

        base_url = str(cfg.get("base_url", "")).strip().rstrip("/")
        if not base_url:
            return ""
        if card_id:
            encoded_card_id = quote(card_id, safe="")
            url = urljoin(f"{base_url}/", f"{endpoint.lstrip('/')}/{encoded_card_id}")
        else:
            url = urljoin(f"{base_url}/", endpoint.lstrip("/"))
        if not query:
            return url
        return f"{url}?{urlencode(query)}"

    def _apply_qrcode_fields_to_mapped_row(
        self,
        mapped_row: dict[str, Any],
        source_row: dict[str, Any],
        cfg: dict[str, Any],
    ) -> None:
        if self.dataset != "cards":
            return
        mapped_row["QRCODE_remark"] = self._current_qrcode_remark_text()
        mapped_row["QRCODE"] = self._build_qrcode_url(source_row, cfg)

    def _apply_business_defaults_to_mapped_row(self, mapped_row: dict[str, Any]) -> None:
        if self.dataset == "cards" and self.card_business == CARD_BUSINESS_OFFLINE:
            mapped_row["postCardStatus"] = "⬛"
            mapped_row["UTC"] = ""
            mapped_row["UTC+8"] = "⬛"

    def _on_qrcode_remark_text_changed(self) -> None:
        self._qrcode_remark_text = self._current_qrcode_remark_text()
        if self.dataset != "cards":
            return
        try:
            cfg = normalize_bridge_config(self._get_config())
        except Exception:
            return
        for record in self.records:
            mapped_row = record.get("mapped_row")
            source_row = record.get("source_row")
            if not isinstance(mapped_row, dict) or not isinstance(source_row, dict):
                continue
            self._apply_qrcode_fields_to_mapped_row(mapped_row, source_row, cfg)
        self.preview_selected_record()

    def _refresh_record_table(self) -> None:
        self.record_table.setRowCount(0)
        for record in self.records:
            table_row = self.record_table.rowCount()
            self.record_table.insertRow(table_row)
            col_offset = 0
            if self._enable_batch_check:
                check_item = QTableWidgetItem("")
                check_item.setFlags((check_item.flags() | Qt.ItemIsUserCheckable) & ~Qt.ItemIsEditable)
                check_item.setCheckState(Qt.Unchecked)
                self.record_table.setItem(table_row, 0, check_item)
                col_offset = 1
            for col_idx, (_, candidate_paths) in enumerate(self._table_schema):
                self.record_table.setItem(
                    table_row,
                    col_idx + col_offset,
                    QTableWidgetItem(self._pick_display_value(record, candidate_paths)),
                )

    def clear_rows(self) -> None:
        self.records = []
        self.record_table.setRowCount(0)
        self.preview.set_scene({})
        self.lbl_result.setText("未执行打印。")

    def remove_selected_record(self) -> None:
        idx = self.record_table.currentRow()
        if idx < 0 or idx >= len(self.records):
            self._show_error("未选择记录", "请先选择要移除的队列条目。")
            return
        self.records.pop(idx)
        self._refresh_record_table()
        if self.records:
            self.record_table.selectRow(min(idx, len(self.records) - 1))
        else:
            self.preview.set_scene({})
        self._status(f"{self.title}: 已移除 1 条队列记录，剩余 {len(self.records)} 条。")

    def _preview_row(self, row: dict[str, Any]) -> None:
        if not self._preset_path:
            return
        try:
            payload = run_cli_json(
                [
                    "render",
                    "preview",
                    "--preset",
                    self._preset_path,
                    "--row",
                    json.dumps(row, ensure_ascii=False),
                ]
            )
            self.preview.set_scene(payload.get("scene", {}))
            self._status(f"{self.title}: 预览已更新。")
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("预览失败", str(exc), details)

    def _resolve_card_version(self, record: dict[str, Any]) -> str:
        source_row = record.get("source_row", {})
        spec = source_row.get("spec") if isinstance(source_row, dict) else {}
        if isinstance(spec, dict):
            version = str(spec.get("cardVersion", "")).strip().upper()
            if version:
                return version
        return ""

    def _resolve_preset_path_for_record(self, record: dict[str, Any], default_preset_path: str) -> str:
        if self.dataset != "cards":
            return default_preset_path
        version = self._resolve_card_version(record)
        if version:
            mapped = str(self._card_version_preset_map.get(version, "")).strip()
            if mapped:
                return mapped
        return default_preset_path

    def preview_selected_record(self) -> None:
        idx = self.record_table.currentRow()
        if idx < 0 or idx >= len(self.records):
            return
        record = self.records[idx]
        mapped_row = dict(record.get("mapped_row", {}) or {})
        preview_preset_path = self._resolve_preset_path_for_record(record, self._preset_path)
        if not preview_preset_path:
            if self.dataset == ADDRESS_ENVELOPE_DATASET:
                self._show_error("未配置预设", "请先在配置页配置信封预设。")
                return
            self._show_error("未配置预设", "当前条目未匹配到可用预设，请先在配置页配置卡片版本映射或默认预设。")
            return
        original_preset_path = self._preset_path
        self._preset_path = preview_preset_path
        try:
            cfg = normalize_bridge_config(self._get_config())
            source_row = record.get("source_row", {})
            if isinstance(source_row, dict):
                self._apply_qrcode_fields_to_mapped_row(mapped_row, source_row, cfg)
            self._preview_row(mapped_row)
        finally:
            self._preset_path = original_preset_path

    def _build_paper_name(self) -> str:
        selected = self.paper_combo.currentText().strip()
        if selected:
            return selected
        return str(self._preset_meta.get("paper_name", "")).strip()

    def _print_records(self, records: list[dict[str, Any]]) -> None:
        if self._enable_card_version_filter:
            selected_version = str(self.card_version_filter_combo.currentData() or "").strip().upper()
            if selected_version in {CARD_VERSION_FILTER_PENDING, ""}:
                self._show_error(
                    "未选择卡片版本",
                    "卡片打印前必须先选择一个具体卡片版本，不能使用“请选择卡片版本”或“全部版本”。",
                )
                return
        if not records:
            self._show_error("无打印数据", "请先重新拉取并生成队列。")
            return
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            self._show_error("未选择打印机", "请先选择打印机。")
            return
        try:
            cfg = normalize_bridge_config(self._get_config())
            self._set_config(cfg)
            default_preset_path = str(cfg.get("presets", {}).get(self._preset_dataset, "")).strip()
            grouped_records: dict[str, list[dict[str, Any]]] = {}
            for record in records:
                preset_path = self._resolve_preset_path_for_record(record, default_preset_path)
                if not preset_path:
                    if self.dataset == ADDRESS_ENVELOPE_DATASET:
                        self._show_error("未配置预设", "请先在配置页配置信封预设。")
                        return
                    version_text = self._resolve_card_version(record) or "未设置版本"
                    self._show_error(
                        "未配置预设",
                        f"存在未匹配预设的卡片版本：{version_text}。请先在配置页配置映射或默认预设。",
                    )
                    return
                grouped_records.setdefault(preset_path, []).append(record)

            total_count = 0
            success_count = 0
            failed_count = 0
            failed_details: list[dict[str, Any]] = []
            for preset_path, grouped in grouped_records.items():
                run_cli_json(["preset", "validate", "--preset", preset_path])
                rows: list[dict[str, Any]] = []
                for record in grouped:
                    mapped_row = dict(record.get("mapped_row", {}))
                    source_row = record.get("source_row", {})
                    if isinstance(source_row, dict):
                        self._apply_qrcode_fields_to_mapped_row(mapped_row, source_row, cfg)
                    rows.append(mapped_row)
                job = {
                    "preset_path": preset_path,
                    "rows": rows,
                    "printer_name": printer_name,
                    "paper_name": self._build_paper_name(),
                }
                with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
                    json.dump(job, f, ensure_ascii=False, indent=2)
                    job_path = f.name
                try:
                    payload = run_cli_json(["print", "run", "--job", job_path], timeout_s=180.0)
                finally:
                    try:
                        Path(job_path).unlink(missing_ok=True)
                    except Exception:
                        pass

                adapter = payload["data"]["adapter"]
                print_rows = list(adapter.get("rows", []))
                total_count += len(print_rows)
                success_count += sum(1 for item in print_rows if str(item.get("status", "")).lower() == "success")
                failed_rows = [item for item in print_rows if str(item.get("status", "")).lower() != "success"]
                failed_count += len(failed_rows)
                if failed_rows:
                    failed_details.append(
                        {
                            "预设": preset_path,
                            "失败条目": [
                                {
                                    "index": item.get("index"),
                                    "message": str(item.get("message", "")).strip(),
                                }
                                for item in failed_rows
                            ],
                        }
                    )

            if self.dataset == ADDRESS_ENVELOPE_DATASET:
                result_text = f"打印完成：成功 {success_count} / 总计 {total_count}。补打信封不回写业务状态。"
            else:
                result_text = (
                    f"打印完成：成功 {success_count} / 总计 {total_count}。"
                    "打印结果不会自动回写，请到“制卡确认/打包确认”页签手动确认。"
                )
            self.lbl_result.setText(result_text)
            self._status(result_text)
            if failed_count > 0:
                self._show_error(
                    "部分条目打印失败",
                    "存在打印失败条目，可能出现“队列一闪而过但未出纸”。请按详情排查预设、纸张和驱动。",
                    {
                        "成功": success_count,
                        "失败": failed_count,
                        "总计": total_count,
                        "详情": failed_details,
                    },
                )
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("打印失败", str(exc), details)

    def print_selected_record(self) -> None:
        idx = self.record_table.currentRow()
        if idx < 0 or idx >= len(self.records):
            self._show_error("未选择记录", "请先选择一条记录。")
            return
        self._print_records([self.records[idx]])

    def print_checked_records(self) -> None:
        if not self._enable_batch_check:
            return
        selected: list[dict[str, Any]] = []
        for row in range(self.record_table.rowCount()):
            item = self.record_table.item(row, 0)
            if item is None or item.checkState() != Qt.Checked:
                continue
            if 0 <= row < len(self.records):
                selected.append(self.records[row])
        if not selected:
            self._show_error("未勾选记录", "请先勾选至少一条信封记录。")
            return
        self._print_records(selected)

    def print_all_records(self) -> None:
        self._print_records(self.records)


class EyeballReprintPage(QWidget):
    def __init__(
        self,
        *,
        get_config: Callable[[], dict[str, Any]],
        set_config: Callable[[dict[str, Any]], None],
        get_config_path: Callable[[], str],
        parent: QWidget | None = None,
    ) -> None:
        super().__init__(parent)
        self._get_config = get_config
        self._set_config = set_config
        self._get_config_path = get_config_path
        self._preset_path = ""
        self._preset_meta: dict[str, Any] = {"field_keys": [], "paper_name": "", "preferred_printer": ""}

        self.preset_edit = QLineEdit(self)
        self.btn_choose_preset = QPushButton("选择并保存...", self)
        self.call_sign_edit = QLineEdit(self)
        self.call_sign_edit.setPlaceholderText("例如：BI1KBU")
        self.date_edit = QDateEdit(self)
        self.date_edit.setCalendarPopup(True)
        self.date_edit.setDisplayFormat("yyyy-MM-dd")
        self.time_edit = QTimeEdit(self)
        self.time_edit.setDisplayFormat("HH:mm:ss")
        self.realtime_check = QCheckBox("实时", self)
        self.realtime_check.setChecked(True)
        self.btn_build_preview = QPushButton("生成预览", self)

        self.printer_combo = QComboBox(self)
        self.paper_combo = QComboBox(self)
        self.btn_refresh_printer = QPushButton("刷新打印机", self)
        self.btn_refresh_paper = QPushButton("刷新纸张", self)
        self.btn_print = QPushButton("打印当前补卡", self)
        self.lbl_result = QLabel("未执行打印。", self)
        self.lbl_result.setWordWrap(True)
        self.preview = PreviewCanvas(self)
        self.preview.set_editable(False)

        self._timer = QTimer(self)
        self._timer.setInterval(1000)
        self._timer.timeout.connect(self._sync_now_if_realtime)

        self._build_ui()
        self._bind_events()
        self.refresh_printers()
        self.refresh_papers()
        self._sync_now_if_realtime()
        self._timer.start()

    def _build_ui(self) -> None:
        splitter = QSplitter(Qt.Horizontal, self)
        left = QWidget(splitter)
        left_layout = QVBoxLayout(left)

        preset_group = QGroupBox("补卡预设", left)
        preset_layout = QFormLayout(preset_group)
        preset_row = QWidget(preset_group)
        preset_row_layout = QHBoxLayout(preset_row)
        preset_row_layout.setContentsMargins(0, 0, 0, 0)
        preset_row_layout.addWidget(self.preset_edit, 1)
        preset_row_layout.addWidget(self.btn_choose_preset)
        preset_layout.addRow("补打眼球卡片预设（专用预设）", preset_row)
        left_layout.addWidget(preset_group)

        input_group = QGroupBox("补打内容", left)
        input_layout = QFormLayout(input_group)
        input_layout.addRow("呼号（对方呼号）", self.call_sign_edit)
        input_layout.addRow("日期（卡片日期）", self.date_edit)
        time_row = QWidget(input_group)
        time_row_layout = QHBoxLayout(time_row)
        time_row_layout.setContentsMargins(0, 0, 0, 0)
        time_row_layout.addWidget(self.time_edit)
        time_row_layout.addWidget(self.realtime_check)
        input_layout.addRow("时间（卡片时间）", time_row)
        left_layout.addWidget(input_group)

        printer_group = QGroupBox("打印设备", left)
        printer_layout = QFormLayout(printer_group)
        printer_layout.addRow("打印机（输出设备）", self.printer_combo)
        printer_layout.addRow("纸张（打印纸张）", self.paper_combo)
        printer_layout.addRow(self.btn_refresh_printer, self.btn_refresh_paper)
        left_layout.addWidget(printer_group)

        action_row = QHBoxLayout()
        action_row.addWidget(self.btn_build_preview)
        action_row.addWidget(self.btn_print)
        left_layout.addLayout(action_row)
        left_layout.addWidget(self.lbl_result)
        left_layout.addStretch(1)

        splitter.addWidget(left)
        splitter.addWidget(self.preview)
        splitter.setSizes([520, 860])

        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        root.addWidget(splitter, 1)

    def _bind_events(self) -> None:
        self.btn_choose_preset.clicked.connect(self.choose_preset)
        self.btn_refresh_printer.clicked.connect(self.refresh_printers)
        self.btn_refresh_paper.clicked.connect(self.refresh_papers)
        self.printer_combo.currentTextChanged.connect(lambda _: self.refresh_papers())
        self.btn_build_preview.clicked.connect(self.preview_current)
        self.btn_print.clicked.connect(self.print_current)
        self.realtime_check.stateChanged.connect(lambda _: self._sync_now_if_realtime())

    def _show_error(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else _json_text(details)
        QMessageBox.critical(self, title, f"{message}\n{detail_text}")

    def _status(self, text: str) -> None:
        window = self.window()
        if isinstance(window, QMainWindow):
            window.statusBar().showMessage(text, 3000)

    def set_config(self, config: dict[str, Any]) -> None:
        cfg = normalize_bridge_config(config)
        self._preset_path = str(cfg.get("presets", {}).get("eyeball_reprint_card", "")).strip()
        self.preset_edit.setText(self._preset_path)
        self._preset_meta = _load_preset_meta(self._preset_path)
        self._apply_printer_and_paper_from_preset(self._preset_path)

    def choose_preset(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "选择补打眼球卡片预设", str(Path.cwd()), "JSON Files (*.json)")
        if not path:
            return
        self.preset_edit.setText(path)
        self._preset_path = path
        self._preset_meta = _load_preset_meta(path)
        self._apply_printer_and_paper_from_preset(path)
        self._persist_preset_path(path)
        self.preview_current()

    def _persist_preset_path(self, path: str) -> None:
        cfg = normalize_bridge_config(self._get_config())
        cfg.setdefault("presets", {})
        cfg["presets"]["eyeball_reprint_card"] = path.strip()
        persist_cfg = copy.deepcopy(cfg)
        persist_cfg.setdefault("auth", {})
        persist_cfg["auth"]["password"] = ""
        config_path = self._get_config_path().strip() or str((Path.cwd() / "bridge_config.json").resolve())
        save_bridge_config(config_path, persist_cfg)
        self._set_config(cfg)
        self._status(f"补打眼球卡片预设已保存：{config_path}")

    def _apply_printer_and_paper_from_preset(self, preset_path: str) -> None:
        meta = _load_preset_meta(preset_path)
        preferred_printer = str(meta.get("preferred_printer", "")).strip()
        if preferred_printer:
            self._select_printer_by_name(preferred_printer, refresh_if_unchanged=True)
        preferred_paper = str(meta.get("paper_name", "")).strip().casefold()
        if preferred_paper:
            for idx in range(self.paper_combo.count()):
                if self.paper_combo.itemText(idx).strip().casefold() == preferred_paper:
                    self.paper_combo.setCurrentIndex(idx)
                    break

    def _select_printer_by_name(self, printer_name: str, refresh_if_unchanged: bool = False) -> bool:
        target = printer_name.strip().casefold()
        if not target:
            return False
        current = self.printer_combo.currentIndex()
        for idx in range(self.printer_combo.count()):
            if self.printer_combo.itemText(idx).strip().casefold() != target:
                continue
            self.printer_combo.setCurrentIndex(idx)
            if idx == current and refresh_if_unchanged:
                self.refresh_papers()
            return True
        return False

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
            self._show_error("刷新打印机失败", str(exc))

    def refresh_papers(self) -> None:
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            return
        try:
            payload = run_cli_json(["printer", "papers", "--printer", printer_name])
            items = payload["data"].get("items", [])
            previous = self.paper_combo.currentText().strip().casefold()
            self.paper_combo.clear()
            self.paper_combo.addItems(items)
            if previous:
                for idx, name in enumerate(items):
                    if str(name).strip().casefold() == previous:
                        self.paper_combo.setCurrentIndex(idx)
                        break
        except Exception as exc:
            self._show_error("刷新纸张失败", str(exc))

    def _sync_now_if_realtime(self) -> None:
        if not self.realtime_check.isChecked():
            self.date_edit.setEnabled(True)
            self.time_edit.setEnabled(True)
            return
        now = datetime.now()
        self.date_edit.setDate(QDate(now.year, now.month, now.day))
        self.time_edit.setTime(QTime(now.hour, now.minute, now.second))
        self.date_edit.setEnabled(False)
        self.time_edit.setEnabled(False)

    def _build_source_row(self) -> dict[str, Any]:
        call_sign = self.call_sign_edit.text().strip().upper()
        if not call_sign:
            raise CardPrintError(code="INVALID_REPRINT_INPUT", message="呼号不能为空。")
        date_text = self.date_edit.date().toString("yyyy-MM-dd")
        time_text = self.time_edit.time().toString("HHmm")
        return {
            "metadata": {"name": ""},
            "spec": {
                "callSign": call_sign,
                "cardType": "EYEBALL",
                "sceneType": "EYEBALL",
                "cardDate": date_text,
                "cardTime": time_text,
                "cardSent": True,
                "cardReceived": False,
                "receiptConfirmed": False,
            },
            "cardType": "EYEBALL",
            "cardDate": date_text,
            "cardTime": time_text,
            "cardSent": True,
            "cardReceived": False,
        }

    def _build_mapped_row(self) -> dict[str, Any]:
        cfg = normalize_bridge_config(self._get_config())
        mapping = cfg.get("mappings", {}).get("cards", {}) or {}
        source_row = self._build_source_row()
        mapped_row = map_export_row(source_row, mapping)
        mapped_row["peerCallsign"] = source_row["spec"]["callSign"]
        mapped_row["Date"] = source_row["spec"]["cardDate"]
        mapped_row["Time"] = source_row["spec"]["cardTime"]
        mapped_row["card_tpye"] = "EYEBALL"
        mapped_row["cardType"] = "EYEBALL"
        mapped_row["EYEBALL"] = "⬛"
        mapped_row["QSO"] = ""
        mapped_row["SWL"] = ""
        mapped_row["postCardStatus"] = "⬛"
        mapped_row["发出卡片"] = "⬛"
        _apply_outbound_return_card_defaults(mapped_row)
        return mapped_row

    def _resolve_preset_path(self) -> str:
        path = self.preset_edit.text().strip() or self._preset_path
        if not path:
            raise CardPrintError(code="MISSING_PRESET", message="请先选择补打眼球卡片预设。")
        if not Path(path).exists():
            raise CardPrintError(
                code="MISSING_PRESET",
                message="补打眼球卡片预设文件不存在。",
                details={"path": path},
            )
        if path != self._preset_path:
            self._persist_preset_path(path)
        return path

    def _build_paper_name(self) -> str:
        selected = self.paper_combo.currentText().strip()
        if selected:
            return selected
        return str(self._preset_meta.get("paper_name", "")).strip()

    def preview_current(self) -> None:
        try:
            preset_path = self._resolve_preset_path()
            mapped_row = self._build_mapped_row()
            payload = run_cli_json(
                [
                    "render",
                    "preview",
                    "--preset",
                    preset_path,
                    "--row",
                    json.dumps(mapped_row, ensure_ascii=False),
                ]
            )
            self.preview.set_scene(payload.get("scene", {}))
            self.lbl_result.setText("预览已生成。")
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("预览失败", str(exc), details)

    def print_current(self) -> None:
        printer_name = self.printer_combo.currentText().strip()
        if not printer_name:
            self._show_error("未选择打印机", "请先选择打印机。")
            return
        try:
            preset_path = self._resolve_preset_path()
            mapped_row = self._build_mapped_row()
            run_cli_json(["preset", "validate", "--preset", preset_path])
            job = {
                "preset_path": preset_path,
                "rows": [mapped_row],
                "printer_name": printer_name,
                "paper_name": self._build_paper_name(),
            }
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
                json.dump(job, f, ensure_ascii=False, indent=2)
                job_path = f.name
            try:
                payload = run_cli_json(["print", "run", "--job", job_path], timeout_s=180.0)
            finally:
                try:
                    Path(job_path).unlink(missing_ok=True)
                except Exception:
                    pass
            adapter = payload["data"]["adapter"]
            rows = list(adapter.get("rows", []))
            success_count = sum(1 for item in rows if str(item.get("status", "")).lower() == "success")
            result_text = f"补打眼球卡片完成：成功 {success_count} / 总计 {len(rows)}。不回写业务状态。"
            self.lbl_result.setText(result_text)
            self._status(result_text)
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("打印失败", str(exc), details)


class OnlinePrintWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CardPrint 在线打印")
        self.resize(1420, 860)

        self.config_path = str((Path.cwd() / "bridge_config.json").resolve())
        self.config = normalize_bridge_config(default_bridge_config())
        self._bridge = BridgeService()
        self._source_rows: dict[str, list[dict[str, Any]]] = {"cards": [], "envelopes": []}
        self._source_timestamp: str = ""
        self._available_card_versions: list[str] = []

        self.tabs = QTabWidget(self)
        self.config_page = OnlineConfigPage(self._on_config_applied, self._fetch_card_versions_for_config, self)
        self.qso_cards_page = OnlineDatasetPage(
            dataset="cards",
            title="通联业务制卡",
            card_business=CARD_BUSINESS_QSO,
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            parent=self,
        )
        self.online_cards_page = OnlineDatasetPage(
            dataset="cards",
            title="线上换卡业务制卡",
            card_business=CARD_BUSINESS_ONLINE,
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            parent=self,
        )
        self.offline_cards_page = OnlineDatasetPage(
            dataset="cards",
            title="线下换卡业务制卡",
            card_business=CARD_BUSINESS_OFFLINE,
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            parent=self,
        )
        self.eyeball_reprint_page = EyeballReprintPage(
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            get_config_path=self._get_config_path,
            parent=self,
        )
        self.envelopes_page = OnlineDatasetPage(
            dataset="envelopes",
            title="封面打印",
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            parent=self,
        )
        self.card_confirm_page = OnlineManualConfirmPage(
            dataset="cards",
            title="确认制卡",
            confirm_button_label="确认制卡回写",
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            parent=self,
        )
        self.envelope_confirm_page = OnlineManualConfirmPage(
            dataset="envelopes",
            title="打包确认",
            confirm_button_label="确认打包回写",
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            parent=self,
        )
        self.address_envelopes_page = OnlineDatasetPage(
            dataset=ADDRESS_ENVELOPE_DATASET,
            title="补打信封",
            get_config=self._get_config_copy,
            set_config=self._set_config_from_dataset,
            parent=self,
        )
        self.tabs.addTab(self.config_page, "配置页")
        self.tabs.addTab(self.qso_cards_page, "通联业务制卡")
        self.tabs.addTab(self.online_cards_page, "线上换卡业务制卡")
        self.tabs.addTab(self.offline_cards_page, "线下换卡业务制卡")
        self.tabs.addTab(self.eyeball_reprint_page, "补打眼球卡片")
        self.tabs.addTab(self.card_confirm_page, "确认制卡")
        self.tabs.addTab(self.envelopes_page, "封面打印")
        self.tabs.addTab(self.envelope_confirm_page, "打包确认")
        self.tabs.addTab(self.address_envelopes_page, "补打信封")
        self.setCentralWidget(self.tabs)

        self._load_initial_config()

    def _load_initial_config(self) -> None:
        config_file = Path(self.config_path)
        if not config_file.exists():
            self._apply_config(self.config_path, self.config, refresh_config_page=True)
            self.statusBar().showMessage(f"未找到配置文件，已加载默认配置：{self.config_path}", 5000)
            return
        try:
            loaded = load_bridge_config(config_file)
        except Exception as exc:
            self._apply_config(self.config_path, self.config, refresh_config_page=True)
            details = exc.details if isinstance(exc, CardPrintError) else {"error": str(exc)}
            QMessageBox.warning(
                self,
                "自动回填失败",
                f"配置文件读取失败，已回退默认配置。\n{_json_text({'path': self.config_path, 'details': details})}",
            )
            return

        self._apply_config(self.config_path, loaded, refresh_config_page=True)
        self.statusBar().showMessage(f"已自动回填配置：{self.config_path}", 5000)

    def _get_config_copy(self) -> dict[str, Any]:
        return copy.deepcopy(self.config)

    def _get_config_path(self) -> str:
        return self.config_path

    def _set_config_from_dataset(self, config: dict[str, Any]) -> None:
        self.config = normalize_bridge_config(config)
        self.config_page.set_base_config(self.config)
        self.config_page.set_config(self.config_path, self.config)
        self.qso_cards_page.set_config(self.config)
        self.online_cards_page.set_config(self.config)
        self.offline_cards_page.set_config(self.config)
        self.eyeball_reprint_page.set_config(self.config)
        self.envelopes_page.set_config(self.config)
        self.address_envelopes_page.set_config(self.config)
        self.card_confirm_page.set_config(self.config)
        self.envelope_confirm_page.set_config(self.config)

    def _on_config_applied(self, config_path: str, config: dict[str, Any]) -> None:
        current_versions = self.config_page.current_card_versions()
        if current_versions:
            self._available_card_versions = current_versions
        self._apply_config(config_path, config, refresh_config_page=False)

    def _fetch_card_versions_for_config(self, config: dict[str, Any]) -> dict[str, Any]:
        cfg = normalize_bridge_config(config)
        versions = self._bridge.fetch_card_versions(cfg)
        sender: dict[str, str] = {}
        sender_error: dict[str, Any] | None = None
        try:
            sender = self._bridge.fetch_station_sender(cfg)
        except Exception as exc:
            sender_error = {
                "message": str(exc),
                "details": exc.details if isinstance(exc, CardPrintError) else None,
            }
        return {
            "versions": versions,
            "sender": sender,
            "sender_error": sender_error,
        }

    def _apply_config(self, config_path: str, config: dict[str, Any], *, refresh_config_page: bool) -> None:
        self.config_path = config_path
        self.config = normalize_bridge_config(config)
        self.config_page.set_base_config(self.config)
        if refresh_config_page:
            self.config_page.set_config(self.config_path, self.config)
        self.qso_cards_page.set_config(self.config)
        self.online_cards_page.set_config(self.config)
        self.offline_cards_page.set_config(self.config)
        self.eyeball_reprint_page.set_config(self.config)
        self.envelopes_page.set_config(self.config)
        self.address_envelopes_page.set_config(self.config)
        map_by_business = self.config.get("presets", {}).get("card_version_map_by_business", {}) or {}
        version_key_set: set[str] = set(self._available_card_versions)
        if isinstance(map_by_business, dict):
            for mapping in map_by_business.values():
                if isinstance(mapping, dict):
                    version_key_set.update(str(key).strip().upper() for key in mapping.keys() if str(key).strip())
        version_keys = sorted(version_key_set)
        self.qso_cards_page.set_card_versions(version_keys)
        self.online_cards_page.set_card_versions(version_keys)
        self.offline_cards_page.set_card_versions(version_keys)
        self.envelopes_page.set_card_versions(version_keys)
        self.address_envelopes_page.set_card_versions(version_keys)
        self.card_confirm_page.set_config(self.config)
        self.envelope_confirm_page.set_config(self.config)
        self.card_confirm_page.set_source_rows(self._source_rows.get("cards", []), self._source_timestamp)
        self.envelope_confirm_page.set_source_rows(self._source_rows.get("envelopes", []), self._source_timestamp)
        self.statusBar().showMessage(f"配置已应用：{self.config_path}", 3000)

    def _pull_remote_sources(self, config_path: str, config: dict[str, Any]) -> dict[str, Any]:
        cfg = normalize_bridge_config(config)
        self._apply_config(config_path, cfg, refresh_config_page=False)

        payload = _fetch_remote_sources_payload(cfg)
        self._apply_remote_sources_payload(payload)
        self.statusBar().showMessage(
            f"远程源拉取完成：cards={len(self._source_rows['cards'])}, envelopes={len(self._source_rows['envelopes'])}",
            5000,
        )
        return {
            "cards": len(self._source_rows["cards"]),
            "envelopes": len(self._source_rows["envelopes"]),
            "pulled_at": self._source_timestamp,
        }

    def _apply_remote_sources_payload(self, payload: dict[str, Any]) -> None:
        self._source_rows["cards"] = list(payload.get("cards", []))
        self._source_rows["envelopes"] = list(payload.get("envelopes", []))
        self._source_timestamp = str(payload.get("pulled_at", "")).strip() or datetime.now().strftime(
            "%Y-%m-%d %H:%M:%S"
        )
        self.card_confirm_page.set_source_rows(self._source_rows["cards"], self._source_timestamp)
        self.envelope_confirm_page.set_source_rows(self._source_rows["envelopes"], self._source_timestamp)

    def refresh_sources_for_manual_confirm(self) -> None:
        self._pull_remote_sources(self.config_path, self.config)


class OnlineManualConfirmPage(QWidget):
    def __init__(
        self,
        *,
        dataset: str,
        title: str,
        confirm_button_label: str,
        get_config: Callable[[], dict[str, Any]],
        set_config: Callable[[dict[str, Any]], None],
        parent: QWidget | None = None,
    ) -> None:
        super().__init__(parent)
        self.dataset = dataset
        self.title = title
        self.confirm_button_label = confirm_button_label
        self._get_config = get_config
        self._set_config = set_config
        self._bridge = BridgeService()
        self._source_rows: list[dict[str, Any]] = []
        self._source_timestamp: str = ""
        self.records: list[dict[str, Any]] = []
        self._table_schema: list[tuple[str, list[str]]] = []
        self._refresh_thread: QThread | None = None
        self._refresh_worker: BackgroundTaskWorker | None = None

        self.lbl_source = QLabel("远程源：未拉取", self)
        self.lbl_source.setWordWrap(True)
        self.lbl_source.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.lbl_version_summary = QLabel("", self)
        self.lbl_version_summary.setWordWrap(True)
        self.lbl_version_summary.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.lbl_tip = QLabel("仅回写勾选条目，不会执行打印。", self)
        self.lbl_tip.setWordWrap(True)

        self.btn_refresh_and_build = QPushButton("一键拉取并生成待确认清单", self)
        self.btn_select_all = QPushButton("全选", self)
        self.btn_unselect_all = QPushButton("取消全选", self)
        self.btn_confirm = QPushButton(self.confirm_button_label, self)

        self.record_table = QTableWidget(0, 1, self)
        self.record_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.record_table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.record_table.setEditTriggers(QAbstractItemView.NoEditTriggers)

        self.lbl_result = QLabel("未执行确认。", self)
        self.lbl_result.setWordWrap(True)

        self._setup_record_table_schema()
        self._build_ui()
        self._bind_events()

    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)

        info_group = QGroupBox("数据来源", self)
        info_layout = QVBoxLayout(info_group)
        info_layout.addWidget(self.lbl_source)
        if self.dataset == "cards":
            info_layout.addWidget(self.lbl_version_summary)
        info_layout.addWidget(self.lbl_tip)
        root.addWidget(info_group)

        action_row = QHBoxLayout()
        action_row.addWidget(self.btn_refresh_and_build)
        action_row.addWidget(self.btn_select_all)
        action_row.addWidget(self.btn_unselect_all)
        action_row.addStretch(1)
        action_row.addWidget(self.btn_confirm)
        root.addLayout(action_row)

        root.addWidget(self.record_table, 1)
        root.addWidget(self.lbl_result)

    def _bind_events(self) -> None:
        self.btn_refresh_and_build.clicked.connect(self._refresh_remote_and_build)
        self.btn_select_all.clicked.connect(lambda: self._set_all_checked(True))
        self.btn_unselect_all.clicked.connect(lambda: self._set_all_checked(False))
        self.btn_confirm.clicked.connect(self.confirm_selected)

    def _show_error(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else _json_text(details)
        QMessageBox.critical(self, title, f"{message}\n{detail_text}")

    def _show_info(self, title: str, message: str, details: Any | None = None) -> None:
        detail_text = "" if details is None else _json_text(details)
        QMessageBox.information(self, title, f"{message}\n{detail_text}")

    def _status(self, text: str) -> None:
        window = self.window()
        if isinstance(window, QMainWindow):
            window.statusBar().showMessage(text, 3000)

    def set_config(self, config: dict[str, Any]) -> None:
        _ = normalize_bridge_config(config)

    def set_source_rows(self, rows: list[dict[str, Any]], timestamp_text: str) -> None:
        self._source_rows = list(rows)
        self._source_timestamp = timestamp_text
        self._update_source_label()

    def _update_source_label(self) -> None:
        if not self._source_rows:
            self.lbl_source.setText("远程源：未拉取")
            if self.dataset == "cards":
                self.lbl_version_summary.setText("版本汇总：未拉取")
            return
        ts = self._source_timestamp or "-"
        self.lbl_source.setText(f"远程源：{len(self._source_rows)} 条（更新时间 {ts}）")
        if self.dataset == "cards":
            self._update_version_summary_from_rows(self._source_rows)

    def _update_version_summary_from_rows(self, rows: list[dict[str, Any]]) -> None:
        if self.dataset != "cards":
            return
        version_counter: dict[str, int] = {}
        for source_row in rows:
            if not self._matches_queue_rule(source_row):
                continue
            for version in _split_card_versions(_lookup_path_value(source_row, "spec.cardVersion")):
                version_counter[version] = version_counter.get(version, 0) + 1
        if not version_counter:
            self.lbl_version_summary.setText("版本汇总：无待制卡版本")
            return
        sorted_versions = sorted(version_counter.keys())
        details = "、".join([f"{item}({version_counter[item]})" for item in sorted_versions])
        self.lbl_version_summary.setText(f"版本汇总：共 {len(sorted_versions)} 个版本；{details}")

    def _setup_record_table_schema(self) -> None:
        if self.dataset == "cards":
            self._table_schema = [
                ("记录ID", ["record_id"]),
                ("呼号", ["source_row.spec.callSign", "source_row.callSign"]),
                ("卡片类型", ["source_row.spec.cardType", "source_row.cardType"]),
                ("日期", ["source_row.spec.cardDate", "source_row.cardDate"]),
                ("时间", ["source_row.spec.cardTime", "source_row.cardTime"]),
                ("制卡状态", ["source_row.spec.cardIssued", "source_row.cardIssued"]),
            ]
        else:
            self._table_schema = [
                ("记录ID", ["record_id"]),
                ("呼号", ["source_row.spec.callSign", "source_row.callSign"]),
                ("收件姓名", ["source_row.addressInfo.spec.name", "source_row.bureauInfo.spec.bureauName"]),
                ("收件地址", ["source_row.addressInfo.spec.address", "source_row.bureauInfo.spec.address"]),
                ("收件邮编", ["source_row.addressInfo.spec.postalCode", "source_row.bureauInfo.spec.postalCode"]),
                ("去向国", ["source_row.addressInfo.spec.destinationCountry", "source_row.bureauInfo.spec.destinationCountry"]),
                ("打包状态", ["source_row.spec.envelopePrinted", "source_row.envelopePrinted"]),
            ]
        self.record_table.setColumnCount(len(self._table_schema) + 1)
        headers = ["选择"] + [title for title, _ in self._table_schema]
        self.record_table.setHorizontalHeaderLabels(headers)

    def _pick_display_value(self, record: dict[str, Any], candidates: list[str]) -> str:
        for path in candidates:
            value = _lookup_path_value(record, path)
            if value is None:
                continue
            text = str(value).strip()
            if text:
                return text
        return ""

    def _matches_queue_rule(self, source_row: dict[str, Any]) -> bool:
        spec_raw = source_row.get("spec")
        spec = spec_raw if isinstance(spec_raw, dict) else {}
        if self.dataset == "cards":
            card_issued = _to_bool(spec.get("cardIssued", source_row.get("cardIssued")))
            return not card_issued
        envelope_printed = _to_bool(spec.get("envelopePrinted", source_row.get("envelopePrinted")))
        return not envelope_printed

    def build_rows_from_source(self) -> None:
        if not self._source_rows:
            self.records = []
            self._refresh_record_table()
            result_text = f"{self.title}: 已完成拉取，远程 0 条，生成待确认 0 条。"
            self.lbl_result.setText(result_text)
            self._status(result_text)
            return
        try:
            cfg = normalize_bridge_config(self._get_config())
            self._set_config(cfg)
            dataset_cfg = cfg.get("writeback", {}).get("datasets", {}).get(self.dataset, {}) or {}
            id_field = str(dataset_cfg.get("id_field", "")).strip()
            self.records = []
            skipped_by_rule = 0
            for index, source_row in enumerate(self._source_rows):
                if not self._matches_queue_rule(source_row):
                    skipped_by_rule += 1
                    continue
                record_id = "" if not id_field else str(_lookup_path_value(source_row, id_field)).strip()
                self.records.append(
                    {
                        "index": index,
                        "source_row": source_row,
                        "record_id": record_id,
                    }
                )
            self._refresh_record_table()
            if self.dataset == "cards":
                self._update_version_summary_from_rows(self._source_rows)
            result_text = (
                f"{self.title}: 已完成拉取，远程 {len(self._source_rows)} 条，"
                f"生成待确认 {len(self.records)} 条，过滤跳过 {skipped_by_rule} 条。"
            )
            self.lbl_result.setText(result_text)
            self._status(result_text)
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self.lbl_result.setText(f"{self.title}: 生成失败：{exc}")
            self._show_error("生成失败", str(exc), details)

    def _refresh_record_table(self) -> None:
        self.record_table.setRowCount(0)
        for record in self.records:
            table_row = self.record_table.rowCount()
            self.record_table.insertRow(table_row)
            check_item = QTableWidgetItem("")
            check_item.setFlags((check_item.flags() | Qt.ItemIsUserCheckable) & ~Qt.ItemIsEditable)
            check_item.setCheckState(Qt.Unchecked)
            self.record_table.setItem(table_row, 0, check_item)
            for col_idx, (_, candidate_paths) in enumerate(self._table_schema, start=1):
                self.record_table.setItem(
                    table_row,
                    col_idx,
                    QTableWidgetItem(self._pick_display_value(record, candidate_paths)),
                )

    def _set_all_checked(self, checked: bool) -> None:
        target_state = Qt.Checked if checked else Qt.Unchecked
        for row in range(self.record_table.rowCount()):
            item = self.record_table.item(row, 0)
            if item is not None:
                item.setCheckState(target_state)

    def _selected_records(self) -> list[dict[str, Any]]:
        selected: list[dict[str, Any]] = []
        for row in range(self.record_table.rowCount()):
            item = self.record_table.item(row, 0)
            if item is None or item.checkState() != Qt.Checked:
                continue
            if 0 <= row < len(self.records):
                selected.append(self.records[row])
        return selected

    def _refresh_remote_source(self) -> bool:
        window = self.window()
        if isinstance(window, OnlinePrintWindow):
            try:
                window.refresh_sources_for_manual_confirm()
                return True
            except Exception as exc:
                details = exc.details if isinstance(exc, CardPrintError) else None
                self._show_error("刷新失败", str(exc), details)
                return False
        return False

    def _refresh_remote_and_build(self) -> None:
        if self._refresh_thread is not None and self._refresh_thread.isRunning():
            return
        window = self.window()
        if not isinstance(window, OnlinePrintWindow):
            return
        try:
            cfg = normalize_bridge_config(self._get_config())
            self._set_config(cfg)
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("刷新失败", str(exc), details)
            return

        self.btn_refresh_and_build.setEnabled(False)
        self.btn_refresh_and_build.setText("正在拉取...")
        self.lbl_source.setText("远程源：正在拉取...")
        result_text = f"{self.title}: 正在拉取远程源，请稍候..."
        self.lbl_result.setText(result_text)
        self._status(result_text)
        self._refresh_thread = QThread(self)
        self._refresh_worker = BackgroundTaskWorker(lambda cfg=copy.deepcopy(cfg): _fetch_remote_sources_payload(cfg))
        self._refresh_worker.moveToThread(self._refresh_thread)
        self._refresh_thread.started.connect(self._refresh_worker.run)
        self._refresh_worker.finished.connect(self._handle_refresh_success)
        self._refresh_worker.failed.connect(self._handle_refresh_failure)
        self._refresh_worker.finished.connect(self._refresh_thread.quit)
        self._refresh_worker.failed.connect(self._refresh_thread.quit)
        self._refresh_thread.finished.connect(self._finish_refresh_thread)
        self._refresh_thread.start()

    def _handle_refresh_success(self, result: Any) -> None:
        payload = result if isinstance(result, dict) else {}
        window = self.window()
        if isinstance(window, OnlinePrintWindow):
            window._apply_remote_sources_payload(payload)
        self.build_rows_from_source()

    def _handle_refresh_failure(self, message: str, details: Any) -> None:
        self._update_source_label()
        self.lbl_result.setText(f"{self.title}: 拉取失败：{message}")
        self._show_error("刷新失败", message, details)

    def _finish_refresh_thread(self) -> None:
        if self._refresh_worker is not None:
            self._refresh_worker.deleteLater()
        if self._refresh_thread is not None:
            self._refresh_thread.deleteLater()
        self._refresh_worker = None
        self._refresh_thread = None
        self.btn_refresh_and_build.setEnabled(True)
        self.btn_refresh_and_build.setText("一键拉取并生成待确认清单")

    def confirm_selected(self) -> None:
        selected_records = self._selected_records()
        if not selected_records:
            self._show_error("未选择记录", "请先勾选至少一条记录。")
            return
        try:
            cfg = normalize_bridge_config(self._get_config())
            self._set_config(cfg)
            print_rows = [{"index": index, "status": "success"} for index in range(len(selected_records))]
            writeback = self._bridge.writeback_success(cfg, self.dataset, selected_records, print_rows)
            result_text = (
                f"{self.title}完成：回写 success={writeback.get('success', 0)} "
                f"failed={writeback.get('failed', 0)} skipped={writeback.get('skipped', 0)}"
            )
            self.lbl_result.setText(result_text)
            self._status(result_text)
            if self._refresh_remote_source():
                self.build_rows_from_source()
        except Exception as exc:
            details = exc.details if isinstance(exc, CardPrintError) else None
            self._show_error("确认回写失败", str(exc), details)


def main() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    window = OnlinePrintWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
