from __future__ import annotations

import base64
import copy
import csv
import io
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

from cardprint.core.errors import CardPrintError

ADDRESS_ENVELOPE_DATASET = "address_envelopes"
DATASETS = ("cards", "envelopes", ADDRESS_ENVELOPE_DATASET)
LEGACY_CARD_EXPORT_ENDPOINT = "/apis/qsl.admin/v1/exports/cards"
LEGACY_ENVELOPE_EXPORT_ENDPOINT = "/apis/qsl.admin/v1/exports/envelopes"
LEGACY_WRITEBACK_URL = "/apis/qsl.admin/v1/qsl-card-records/{id}"
HALO_CARD_RECORDS_ENDPOINT = "/apis/qsl-management.bi1kbu.com/v1alpha1/card-records"
HALO_WRITEBACK_URL = "/apis/qsl-management.bi1kbu.com/v1alpha1/card-records/{id}"
PUBLIC_EYEBALL_ENDPOINT = "/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL"
PUBLIC_ONLINE_EYEBALL_ENDPOINT = "/apis/api.qsl-management.bi1kbu.com/v1alpha1/ONLINE_EYEBALL"
PUBLIC_RECEIPT_ENDPOINT = "/apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public"
PUBLIC_STATION_CARDS_ENDPOINT = "/apis/api.qsl-management.bi1kbu.com/v1alpha1/exchange-online/-/station-cards"
FIXED_BASE_URL = "http://localhost:8090"
EXTENSION_PAGE_SIZE = 1000
FIXED_DATASET_ENDPOINTS: dict[str, str] = {
    "cards": HALO_CARD_RECORDS_ENDPOINT,
    "envelopes": HALO_CARD_RECORDS_ENDPOINT,
    ADDRESS_ENVELOPE_DATASET: "",
}
FIXED_QRCODE_PATH_MAPPINGS: dict[str, str] = {
    PUBLIC_EYEBALL_ENDPOINT: "/eyeball",
    PUBLIC_ONLINE_EYEBALL_ENDPOINT: "/online_eyeball",
    PUBLIC_RECEIPT_ENDPOINT: "/rp",
}
FIXED_DATASET_FILTERS: dict[str, dict[str, Any]] = {
    "cards": {},
    "envelopes": {},
    ADDRESS_ENVELOPE_DATASET: {},
}

FIXED_DATASET_MAPPINGS: dict[str, dict[str, Any]] = {
    "cards": {
        "peerCallsign": {"type": "coalesce", "sources": ["spec.callSign", "callSign", "呼号", "对方呼号"]},
        "Date": {"type": "coalesce", "sources": ["spec.cardDate", "cardDate", "Date", "日期"]},
        "Time": {"type": "coalesce", "sources": ["spec.cardTime", "cardTime", "Time", "时间"]},
        "UTC": {
            "type": "checkbox",
            "sources": ["qsoInfo.spec.timezone", "spec.timezone", "timezone", "QSO时区"],
            "true_values": ["", "UTC", "UTC+0", "UTC+00", "GMT", "Z"],
            "true_output": "⬛",
            "false_output": "",
        },
        "UTC+8": {
            "type": "checkbox",
            "sources": ["qsoInfo.spec.timezone", "spec.timezone", "timezone", "QSO时区"],
            "true_values": ["UTC+8", "UTC+08", "GMT+8", "GMT+08"],
            "true_output": "⬛",
            "false_output": "",
        },
        "UTF": {
            "type": "checkbox",
            "sources": ["qsoInfo.spec.timezone", "spec.timezone", "timezone", "QSO时区"],
            "true_values": ["", "UTC", "UTC+0", "UTC+00", "GMT", "Z"],
            "true_output": "⬛",
            "false_output": "",
        },
        "UTF-8": {
            "type": "checkbox",
            "sources": ["qsoInfo.spec.timezone", "spec.timezone", "timezone", "QSO时区"],
            "true_values": ["UTC+8", "UTC+08", "GMT+8", "GMT+08"],
            "true_output": "⬛",
            "false_output": "",
        },
        "QSO": {
            "type": "checkbox",
            "sources": ["spec.cardType", "cardType", "卡片类型"],
            "true_values": ["QSO"],
            "true_output": "⬛",
            "false_output": "",
        },
        "SWL": {
            "type": "checkbox",
            "sources": ["spec.cardType", "cardType", "卡片类型"],
            "true_values": ["SWL"],
            "true_output": "⬛",
            "false_output": "",
        },
        "EYEBALL": {
            "type": "checkbox",
            "sources": ["spec.cardType", "cardType", "卡片类型"],
            "true_values": ["EYEBALL"],
            "true_output": "⬛",
            "false_output": "",
        },
        "frequency": {"type": "coalesce", "sources": ["qsoInfo.spec.freq", "spec.freq", "freq", "频率"]},
        "equipmentId": {"type": "coalesce", "sources": ["qsoInfo.spec.myRig", "spec.myRig", "myRig", "设备"]},
        "mode": {"type": "coalesce", "sources": ["qsoInfo.spec.myRigMode", "spec.myRigMode", "mode"]},
        "mode_type": {"type": "coalesce", "sources": ["qsoInfo.spec.myRigMode", "spec.myRigMode", "mode"]},
        "mode_FM": {
            "type": "checkbox",
            "sources": ["qsoInfo.spec.myRigMode", "spec.myRigMode", "mode"],
            "true_values": ["FM"],
            "true_output": "⬛",
            "false_output": "",
        },
        "mode_CW": {
            "type": "checkbox",
            "sources": ["qsoInfo.spec.myRigMode", "spec.myRigMode", "mode"],
            "true_values": ["CW"],
            "true_output": "⬛",
            "false_output": "",
        },
        "mode_SSB": {
            "type": "checkbox",
            "sources": ["qsoInfo.spec.myRigMode", "spec.myRigMode", "mode"],
            "true_values": ["SSB"],
            "true_output": "⬛",
            "false_output": "",
        },
        "powerPresetId": {"type": "coalesce", "sources": ["qsoInfo.spec.myRigPwr", "spec.myRigPwr", "myRigPwr", "功率"]},
        "antennaId": {"type": "coalesce", "sources": ["qsoInfo.spec.myRigAnt", "spec.myRigAnt", "myRigAnt", "天线"]},
        "rstSent": {"type": "coalesce", "sources": ["qsoInfo.spec.rstSent", "spec.rstSent", "rstSent"]},
        "qth": {
            "type": "coalesce",
            "sources": [
                "offlineActivityInfo.spec.activityLocation",
                "qsoInfo.spec.myQth",
                "spec.myQth",
                "qsoInfo.spec.qth",
                "spec.qth",
                "qth",
            ],
        },
        "my_qth": {
            "type": "coalesce",
            "sources": [
                "offlineActivityInfo.spec.activityLocation",
                "qsoInfo.spec.myQth",
                "spec.myQth",
                "qsoInfo.spec.qth",
                "spec.qth",
                "qth",
            ],
        },
        "postCardStatus": {
            "type": "checkbox",
            "sources": ["spec.cardSent", "cardSent", "发卡状态", "已发"],
            "true_values": ["1", "true", "yes", "y", "on", "是", "√", "⬛"],
            "true_output": "⬛",
            "false_output": "",
        },
        "returnCardStatus": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["1", "true", "yes", "y", "on", "是", "√", "⬛"],
            "true_output": "⬛",
            "false_output": "",
        },
        "欢迎回卡": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["", "0", "false", "no", "n", "off", "否"],
            "true_output": "⬛",
            "false_output": "",
        },
        "回复卡片": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["", "0", "false", "no", "n", "off", "否"],
            "true_output": "⬛",
            "false_output": "",
        },
        "请回卡片": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["", "0", "false", "no", "n", "off", "否"],
            "true_output": "⬛",
            "false_output": "",
        },
        "感谢来卡": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["1", "true", "yes", "y", "on", "是", "√", "⬛"],
            "true_output": "⬛",
            "false_output": "",
        },
        "发出卡片": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["1", "true", "yes", "y", "on", "是", "√", "⬛"],
            "true_output": "⬛",
            "false_output": "",
        },
        "感谢您的来卡": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["1", "true", "yes", "y", "on", "是", "√", "⬛"],
            "true_output": "⬛",
            "false_output": "",
        },
        "感谢您的卡片": {
            "type": "checkbox",
            "sources": ["spec.cardReceived", "cardReceived", "收卡状态", "已收"],
            "true_values": ["1", "true", "yes", "y", "on", "是", "√", "⬛"],
            "true_output": "⬛",
            "false_output": "",
        },
        "remark": {"type": "coalesce", "sources": ["spec.cardRemarks", "cardRemarks", "remark", "备注"]},
        "card_tpye": {"type": "coalesce", "sources": ["spec.cardType", "cardType", "卡片类型"]},
        "cadr_id": {"type": "coalesce", "sources": ["metadata.name", "cardId", "卡片ID"]},
        "card_id": {"type": "coalesce", "sources": ["metadata.name", "cardId", "卡片ID"]},
    },
    "envelopes": {
        "postCode": {
            "type": "coalesce",
            "sources": [
                "addressInfo.spec.postalCode",
                "bureauInfo.spec.postalCode",
                "spec.postalCode",
                "postalCode",
                "收件邮编",
            ],
        },
        "address": {
            "type": "coalesce",
            "sources": [
                "addressInfo.spec.address",
                "bureauInfo.spec.address",
                "spec.address",
                "address",
                "收件地址",
            ],
        },
        "destinationCountry": {
            "type": "coalesce",
            "sources": [
                "addressInfo.spec.destinationCountry",
                "bureauInfo.spec.destinationCountry",
                "spec.destinationCountry",
                "destinationCountry",
                "destination_country",
                "去向国",
            ],
        },
        "name": {
            "type": "coalesce",
            "sources": [
                "addressInfo.spec.name",
                "bureauInfo.spec.bureauName",
                "spec.callSign",
                "callSign",
                "呼号",
            ],
        },
        "phone": {
            "type": "coalesce",
            "sources": [
                "addressInfo.spec.telephone",
                "bureauInfo.spec.telephone",
                "spec.telephone",
                "telephone",
                "收件电话",
            ],
        },
        "my_address": {"type": "coalesce", "sources": ["my_address", "myAddress", "本台地址"]},
        "my_name": {"type": "coalesce", "sources": ["my_name", "myName", "本台姓名"]},
        "my_phone": {"type": "coalesce", "sources": ["my_phone", "myPhone", "本台电话"]},
        "my_postCode": {"type": "coalesce", "sources": ["my_postCode", "myPostCode", "本台邮编"]},
    },
}
FIXED_DATASET_MAPPINGS[ADDRESS_ENVELOPE_DATASET] = copy.deepcopy(FIXED_DATASET_MAPPINGS["envelopes"])

CARD_BUSINESS_KEYS = ("qso", "online", "offline")

FIXED_WRITEBACK_DATASETS: dict[str, dict[str, Any]] = {
    "cards": {
        "id_field": "metadata.name",
        "url": HALO_WRITEBACK_URL,
        "method": "PUT",
        "body": {
            "cardIssued": True,
            "cardIssuedAt": "${now}",
        },
    },
    "envelopes": {
        "id_field": "metadata.name",
        "url": HALO_WRITEBACK_URL,
        "method": "PUT",
        "body": {
            "envelopePrinted": True,
        },
    },
    ADDRESS_ENVELOPE_DATASET: {
        "id_field": "",
        "url": "",
        "method": "PUT",
        "body": {},
    },
}


def default_bridge_config() -> dict[str, Any]:
    return {
        "base_url": FIXED_BASE_URL,
        "sender": {
            "my_name": "",
            "my_phone": "",
            "my_postCode": "",
            "my_address": "",
        },
        "auth": {
            "type": "basic",
            "username": "",
            "password": "",
            "token": "",
            "operator": "",
        },
        "endpoints": copy.deepcopy(FIXED_DATASET_ENDPOINTS),
        "writeback": {
            "enabled": True,
            "datasets": copy.deepcopy(FIXED_WRITEBACK_DATASETS),
        },
        "filters": copy.deepcopy(FIXED_DATASET_FILTERS),
        "mappings": copy.deepcopy(FIXED_DATASET_MAPPINGS),
        "presets": {
            "cards": "",
            "envelopes": "",
            ADDRESS_ENVELOPE_DATASET: "",
            "eyeball_reprint_card": "",
            "custom_single_print": "",
            "card_version_map_by_business": {key: {} for key in CARD_BUSINESS_KEYS},
        },
        "qrcode": {
            "path_mappings": copy.deepcopy(FIXED_QRCODE_PATH_MAPPINGS),
        },
        "common": {"timeout_s": 30.0},
    }


def _deep_merge(dst: dict[str, Any], src: dict[str, Any]) -> dict[str, Any]:
    out = copy.deepcopy(dst)
    for key, value in (src or {}).items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = value
    return out


def _migrate_legacy_routes(cfg: dict[str, Any]) -> dict[str, Any]:
    endpoints = cfg.setdefault("endpoints", {})
    if str(endpoints.get("cards", "")).strip() == LEGACY_CARD_EXPORT_ENDPOINT:
        endpoints["cards"] = HALO_CARD_RECORDS_ENDPOINT
    if str(endpoints.get("envelopes", "")).strip() == LEGACY_ENVELOPE_EXPORT_ENDPOINT:
        endpoints["envelopes"] = HALO_CARD_RECORDS_ENDPOINT

    writeback_cfg = cfg.setdefault("writeback", {}).setdefault("datasets", {})
    for dataset in DATASETS:
        dataset_cfg = writeback_cfg.setdefault(dataset, {})
        url = str(dataset_cfg.get("url", "")).strip()
        if url == LEGACY_WRITEBACK_URL:
            dataset_cfg["url"] = HALO_WRITEBACK_URL
        id_field = str(dataset_cfg.get("id_field", "")).strip()
        if id_field == "卡片ID":
            dataset_cfg["id_field"] = "metadata.name"
    return cfg


def _normalize_url_path(value: Any) -> str:
    path = str(value or "").strip()
    if not path:
        return ""
    if not path.startswith("/"):
        path = "/" + path
    if len(path) > 1:
        path = path.rstrip("/")
    return path


def normalize_bridge_config(raw: dict[str, Any]) -> dict[str, Any]:
    cfg = _migrate_legacy_routes(_deep_merge(default_bridge_config(), raw or {}))
    base_url = str(cfg.get("base_url", "")).strip()
    if not base_url:
        base_url = FIXED_BASE_URL
    if not re.match(r"^https?://", base_url, flags=re.IGNORECASE):
        raise CardPrintError(
            code="INVALID_BRIDGE_CONFIG",
            message="base_url 必须以 http:// 或 https:// 开头。",
            details={"value": base_url},
        )
    cfg["base_url"] = base_url.rstrip("/")

    timeout = cfg.get("common", {}).get("timeout_s", 30.0)
    try:
        cfg["common"]["timeout_s"] = max(1.0, float(timeout))
    except (TypeError, ValueError) as exc:
        raise CardPrintError(
            code="INVALID_BRIDGE_CONFIG",
            message="common.timeout_s 必须是数字。",
            details={"value": timeout},
        ) from exc

    auth_type = str(cfg.get("auth", {}).get("type", "basic")).strip().lower()
    if auth_type not in {"basic", "bearer"}:
        raise CardPrintError(
            code="INVALID_BRIDGE_CONFIG",
            message="auth.type 仅支持 basic/bearer。",
            details={"type": auth_type},
        )
    cfg["auth"]["type"] = auth_type
    sender_cfg = cfg.setdefault("sender", {})
    cfg["sender"] = {
        "my_name": str(sender_cfg.get("my_name", "")).strip(),
        "my_phone": str(sender_cfg.get("my_phone", "")).strip(),
        "my_postCode": str(sender_cfg.get("my_postCode", "")).strip(),
        "my_address": str(sender_cfg.get("my_address", "")).strip(),
    }
    cfg.setdefault("writeback", {})
    cfg["writeback"]["enabled"] = bool(cfg["writeback"].get("enabled", True))
    cfg["writeback"]["datasets"] = {}
    cfg["mappings"] = {}
    cfg["endpoints"] = {}
    cfg["filters"] = {}

    raw_qrcode_mappings = cfg.get("qrcode", {}).get("path_mappings", {})
    normalized_qrcode_mappings = copy.deepcopy(FIXED_QRCODE_PATH_MAPPINGS)
    if isinstance(raw_qrcode_mappings, dict):
        for raw_source, raw_target in raw_qrcode_mappings.items():
            source = _normalize_url_path(raw_source)
            target = _normalize_url_path(raw_target)
            if source and target:
                normalized_qrcode_mappings[source] = target
    cfg["qrcode"] = {"path_mappings": normalized_qrcode_mappings}

    raw_map_by_business = cfg.get("presets", {}).get("card_version_map_by_business", {})
    normalized_map_by_business: dict[str, dict[str, str]] = {key: {} for key in CARD_BUSINESS_KEYS}
    if isinstance(raw_map_by_business, dict):
        for business_key in CARD_BUSINESS_KEYS:
            raw_map = raw_map_by_business.get(business_key, {})
            if not isinstance(raw_map, dict):
                continue
            normalized_map: dict[str, str] = {}
            for raw_version, raw_path in raw_map.items():
                version = str(raw_version or "").strip().upper()
                path = str(raw_path or "").strip()
                if not version or not path:
                    continue
                normalized_map[version] = path
            normalized_map_by_business[business_key] = normalized_map

    for dataset in DATASETS:
        cfg["endpoints"][dataset] = str(FIXED_DATASET_ENDPOINTS[dataset])
        cfg["filters"][dataset] = copy.deepcopy(FIXED_DATASET_FILTERS[dataset])

        cfg["mappings"][dataset] = copy.deepcopy(FIXED_DATASET_MAPPINGS[dataset])
        cfg["writeback"]["datasets"][dataset] = copy.deepcopy(FIXED_WRITEBACK_DATASETS[dataset])

        preset_path = str(
            cfg.get("presets", {}).get(dataset, "")
            or (cfg.get("presets", {}).get("envelopes", "") if dataset == ADDRESS_ENVELOPE_DATASET else "")
        ).strip()
        cfg["presets"][dataset] = preset_path
    cfg["presets"]["eyeball_reprint_card"] = str(
        cfg.get("presets", {}).get("eyeball_reprint_card", "")
    ).strip()
    cfg["presets"]["custom_single_print"] = str(
        cfg.get("presets", {}).get("custom_single_print", "")
    ).strip()
    cfg["presets"]["card_version_map_by_business"] = normalized_map_by_business

    return cfg


def load_bridge_config(path: str | Path) -> dict[str, Any]:
    config_path = Path(path)
    if not config_path.exists():
        raise CardPrintError(
            code="BRIDGE_CONFIG_NOT_FOUND",
            message="桥接配置文件不存在。",
            details={"path": str(config_path)},
        )
    try:
        raw = json.loads(config_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise CardPrintError(
            code="INVALID_BRIDGE_CONFIG",
            message="桥接配置 JSON 无效。",
            details={"path": str(config_path), "line": exc.lineno, "column": exc.colno},
        ) from exc
    if not isinstance(raw, dict):
        raise CardPrintError(
            code="INVALID_BRIDGE_CONFIG",
            message="桥接配置必须为 JSON 对象。",
            details={"path": str(config_path)},
        )
    return normalize_bridge_config(raw)


def save_bridge_config(path: str | Path, config: dict[str, Any]) -> None:
    cfg = normalize_bridge_config(config)
    out_path = Path(path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_csv_text(content: str) -> list[dict[str, Any]]:
    stream = io.StringIO(content)
    reader = csv.DictReader(stream)
    rows: list[dict[str, Any]] = []
    for row in reader:
        normalized_row = {str(k).strip(): ("" if v is None else v) for k, v in row.items() if k is not None}
        rows.append(normalized_row)
    return rows


def _decode_bytes(raw: bytes) -> str:
    for enc in ("utf-8-sig", "utf-8", "gb18030", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def _join_url(base_url: str, endpoint: str) -> str:
    return urllib.parse.urljoin(base_url.rstrip("/") + "/", endpoint.lstrip("/"))


def _build_auth_headers(auth_config: dict[str, Any]) -> dict[str, str]:
    auth_type = str(auth_config.get("type", "basic")).strip().lower()
    headers: dict[str, str] = {}
    if auth_type == "basic":
        username = str(auth_config.get("username", ""))
        password = str(auth_config.get("password", ""))
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {token}"
    elif auth_type == "bearer":
        token = str(auth_config.get("token", "")).strip()
        if token:
            headers["Authorization"] = f"Bearer {token}"
    operator = str(auth_config.get("operator", "")).strip()
    if operator:
        headers["X-Operator"] = operator
    return headers


def _http_request(
    *,
    base_url: str,
    endpoint: str,
    method: str,
    auth_config: dict[str, Any],
    timeout_s: float,
    payload: Any | None = None,
) -> tuple[str, str]:
    url = _join_url(base_url, endpoint)
    headers = _build_auth_headers(auth_config)
    data_bytes: bytes | None = None
    if payload is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
        data_bytes = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url=url,
        data=data_bytes,
        method=method.upper(),
        headers=headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as resp:
            content_type = str(resp.headers.get("Content-Type", ""))
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        raw = exc.read() if hasattr(exc, "read") else b""
        raise CardPrintError(
            code="REMOTE_HTTP_ERROR",
            message="远程接口返回错误。",
            details={
                "status": getattr(exc, "code", None),
                "url": url,
                "method": method.upper(),
                "body": _decode_bytes(raw),
            },
        ) from exc
    except urllib.error.URLError as exc:
        raise CardPrintError(
            code="REMOTE_CONNECT_ERROR",
            message="无法连接远程接口。",
            details={"url": url, "method": method.upper(), "error": str(exc)},
        ) from exc
    return content_type, _decode_bytes(raw)


def _rows_from_export_response(content_type: str, text: str) -> list[dict[str, Any]]:
    lowered_type = content_type.lower()
    if "html" in lowered_type or _looks_like_html(text):
        raise CardPrintError(
            code="REMOTE_AUTH_REQUIRED",
            message="远程接口返回登录页或 HTML，可能未登录、无权限或接口路径被反向代理改写。",
            details={
                "content_type": content_type,
                "body_preview": _preview_text(text),
            },
        )
    if "json" in lowered_type:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            if isinstance(parsed.get("items"), list):
                return [dict(item) for item in parsed["items"] if isinstance(item, dict)]
            if isinstance(parsed.get("rows"), list):
                return [dict(item) for item in parsed["rows"] if isinstance(item, dict)]
            if isinstance(parsed.get("data"), list):
                return [dict(item) for item in parsed["data"] if isinstance(item, dict)]
            if isinstance(parsed.get("csv"), str):
                return parse_csv_text(parsed["csv"])
            if isinstance(parsed.get("content"), str):
                return parse_csv_text(parsed["content"])
        if isinstance(parsed, list):
            return [dict(item) for item in parsed if isinstance(item, dict)]
        raise CardPrintError(
            code="REMOTE_EXPORT_FORMAT_ERROR",
            message="导出接口返回格式无法识别。",
            details={"content_type": content_type},
        )

    # 默认按 CSV 解析。
    rows = parse_csv_text(text)
    if rows:
        return rows

    # 兜底：如果其实是 JSON 文本，尝试再次识别。
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return rows
    if isinstance(parsed, list):
        return [dict(item) for item in parsed if isinstance(item, dict)]
    if isinstance(parsed, dict) and isinstance(parsed.get("rows"), list):
        return [dict(item) for item in parsed["rows"] if isinstance(item, dict)]
    return rows


def _looks_like_html(text: str) -> bool:
    stripped = str(text or "").lstrip().lower()
    return stripped.startswith("<!doctype html") or stripped.startswith("<html")


def _preview_text(text: str, limit: int = 160) -> str:
    normalized = re.sub(r"\s+", " ", str(text or "")).strip()
    return normalized[:limit]


def _parse_json_object_text(text: str, *, code: str, message: str, details: dict[str, Any]) -> dict[str, Any]:
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        raise CardPrintError(
            code=code,
            message=message,
            details={**details, "line": exc.lineno, "column": exc.colno},
        ) from exc
    if not isinstance(parsed, dict):
        raise CardPrintError(
            code=code,
            message=message,
            details={**details, "type": type(parsed).__name__},
        )
    return parsed


def _parse_extension_list_response(content_type: str, text: str) -> tuple[list[dict[str, Any]], int | None]:
    if "json" not in str(content_type or "").lower():
        return _rows_from_export_response(content_type, text), None
    parsed = _parse_json_object_text(
        text,
        code="REMOTE_EXTENSION_FORMAT_ERROR",
        message="远程扩展列表响应不是有效 JSON 对象。",
        details={"content_type": content_type},
    )
    raw_items = parsed.get("items", [])
    items = [item for item in raw_items if isinstance(item, dict)] if isinstance(raw_items, list) else []
    total_raw = parsed.get("total")
    try:
        total = int(total_raw) if total_raw is not None else None
    except (TypeError, ValueError):
        total = None
    return items, total


def _fetch_extension_items(cfg: dict[str, Any], plural: str) -> list[dict[str, Any]]:
    all_items: list[dict[str, Any]] = []
    page = 1
    while True:
        query = urllib.parse.urlencode(
            {"page": page, "size": EXTENSION_PAGE_SIZE, "sort": "metadata.creationTimestamp,desc"}
        )
        endpoint = f"/apis/qsl-management.bi1kbu.com/v1alpha1/{plural}?{query}"
        content_type, text = _http_request(
            base_url=str(cfg["base_url"]),
            endpoint=endpoint,
            method="GET",
            auth_config=dict(cfg["auth"]),
            timeout_s=float(cfg["common"]["timeout_s"]),
            payload=None,
        )
        page_items, total = _parse_extension_list_response(content_type, text)
        all_items.extend(page_items)
        if total is not None:
            if len(all_items) >= total:
                break
        elif len(page_items) < EXTENSION_PAGE_SIZE:
            break
        if not page_items:
            break
        page += 1
    return all_items


def _fetch_extension_endpoint_items(cfg: dict[str, Any], endpoint: str) -> list[dict[str, Any]]:
    content_type, text = _http_request(
        base_url=cfg["base_url"],
        endpoint=endpoint,
        method="GET",
        auth_config=cfg["auth"],
        timeout_s=float(cfg["common"]["timeout_s"]),
        payload=None,
    )
    return [item for item in _rows_from_export_response(content_type, text) if isinstance(item, dict)]


def _fetch_card_record_items(cfg: dict[str, Any]) -> list[dict[str, Any]]:
    return _fetch_extension_items(cfg, "card-records")


def _fetch_public_station_card_items(cfg: dict[str, Any]) -> list[dict[str, Any]]:
    content_type, text = _http_request(
        base_url=str(cfg["base_url"]),
        endpoint=PUBLIC_STATION_CARDS_ENDPOINT,
        method="GET",
        auth_config={"type": "bearer", "username": "", "password": "", "token": "", "operator": ""},
        timeout_s=float(cfg["common"]["timeout_s"]),
        payload=None,
    )
    return _rows_from_export_response(content_type, text)


def _resolve_sender_fields(cfg: dict[str, Any]) -> dict[str, str]:
    station_spec = _fetch_station_profile(cfg)
    my_name_remote = str(station_spec.get("myName", "")).strip()
    my_phone_remote = str(station_spec.get("myTelephone", "")).strip()
    my_post_code_remote = str(station_spec.get("myPostalCode", "")).strip()
    my_address_remote = str(station_spec.get("myAddress", "")).strip()
    sender_cfg = cfg.get("sender", {}) if isinstance(cfg.get("sender", {}), dict) else {}
    return {
        "my_name": str(sender_cfg.get("my_name", "")).strip() or my_name_remote,
        "myName": str(sender_cfg.get("my_name", "")).strip() or my_name_remote,
        "my_phone": str(sender_cfg.get("my_phone", "")).strip() or my_phone_remote,
        "myPhone": str(sender_cfg.get("my_phone", "")).strip() or my_phone_remote,
        "my_postCode": str(sender_cfg.get("my_postCode", "")).strip() or my_post_code_remote,
        "myPostCode": str(sender_cfg.get("my_postCode", "")).strip() or my_post_code_remote,
        "my_address": str(sender_cfg.get("my_address", "")).strip() or my_address_remote,
        "myAddress": str(sender_cfg.get("my_address", "")).strip() or my_address_remote,
    }


def _station_card_version_rows(items: list[dict[str, Any]]) -> list[tuple[int, str]]:
    version_rows: list[tuple[int, str]] = []
    seen: set[str] = set()
    for item in items:
        spec = item.get("spec") if isinstance(item.get("spec"), dict) else item
        version = str(spec.get("cardVersion", "")).strip().upper()
        if not version or version in seen:
            continue
        seen.add(version)
        sort_raw = spec.get("sortOrder")
        try:
            sort_order = int(sort_raw)
        except (TypeError, ValueError):
            sort_order = 2**31 - 1
        if sort_order <= 0:
            sort_order = 2**31 - 1
        version_rows.append((sort_order, version))
    version_rows.sort(key=lambda item: (item[0], item[1].casefold()))
    return version_rows


def _enrich_rows_for_card_mapping(cfg: dict[str, Any], rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not rows:
        return []
    qso_refs: set[str] = set()
    offline_activity_refs: set[str] = set()
    for row in rows:
        spec = row.get("spec")
        if not isinstance(spec, dict):
            continue
        ref = str(spec.get("qsoRecordName", "")).strip()
        if ref:
            qso_refs.add(ref)
            qso_refs.add(ref.upper())
        if _normalize_scene_type(spec) == "EYEBALL":
            activity_ref = str(spec.get("offlineActivityName", "")).strip()
            if activity_ref:
                offline_activity_refs.add(activity_ref)
                offline_activity_refs.add(activity_ref.upper())
    if not qso_refs and not offline_activity_refs:
        return rows

    qso_index = _build_name_index(_fetch_extension_items(cfg, "qso-records")) if qso_refs else {}
    offline_activity_index = (
        _build_offline_activity_index(_fetch_extension_items(cfg, "offline-activities"))
        if offline_activity_refs
        else {}
    )
    enriched: list[dict[str, Any]] = []
    for row in rows:
        out = dict(row)
        spec = out.get("spec")
        if isinstance(spec, dict):
            ref = str(spec.get("qsoRecordName", "")).strip()
            key = ref if ref in qso_index else ref.upper()
            out["qsoInfo"] = qso_index.get(key)
            activity_ref = str(spec.get("offlineActivityName", "")).strip()
            activity_key = activity_ref if activity_ref in offline_activity_index else activity_ref.upper()
            out["offlineActivityInfo"] = offline_activity_index.get(activity_key)
        enriched.append(out)
    return enriched


def _build_name_index(items: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    index: dict[str, dict[str, Any]] = {}
    for item in items:
        metadata = item.get("metadata")
        if not isinstance(metadata, dict):
            continue
        name = str(metadata.get("name", "")).strip()
        if not name:
            continue
        index[name] = item
        index[name.upper()] = item
    return index


def _build_offline_activity_index(items: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    index = _build_name_index(items)
    for item in items:
        spec = item.get("spec")
        if not isinstance(spec, dict):
            continue
        activity_name = str(spec.get("activityName", "")).strip()
        if activity_name:
            index[activity_name] = item
            index[activity_name.upper()] = item
    return index


def _fetch_station_profile(cfg: dict[str, Any]) -> dict[str, Any]:
    items = _fetch_extension_items(cfg, "station-profiles")
    if not items:
        return {}
    preferred_name = "qsl-station-profile-default"
    for item in items:
        metadata = item.get("metadata")
        if isinstance(metadata, dict) and str(metadata.get("name", "")).strip() == preferred_name:
            spec = item.get("spec")
            return spec if isinstance(spec, dict) else {}
    first_spec = items[0].get("spec")
    return first_spec if isinstance(first_spec, dict) else {}


def _enrich_rows_for_envelope_mapping(cfg: dict[str, Any], rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not rows:
        return []

    sender_fields = _resolve_sender_fields(cfg)

    address_refs: set[str] = set()
    for row in rows:
        spec = row.get("spec")
        if not isinstance(spec, dict):
            continue
        ref = str(spec.get("addressEntryName", "")).strip()
        if ref:
            address_refs.add(ref)
            address_refs.add(ref.upper())

    address_index: dict[str, dict[str, Any]] = {}
    bureau_index: dict[str, dict[str, Any]] = {}
    if address_refs:
        address_items = _fetch_extension_items(cfg, "address-book-entries")
        bureau_items = _fetch_extension_items(cfg, "bureau-entries")
        address_index = _build_name_index(address_items)
        bureau_index = _build_name_index(bureau_items)

    enriched: list[dict[str, Any]] = []
    for row in rows:
        out = dict(row)
        spec = out.get("spec")
        ref = ""
        if isinstance(spec, dict):
            ref = str(spec.get("addressEntryName", "")).strip()
        key = ref if ref in address_index or ref in bureau_index else ref.upper()
        out["addressInfo"] = address_index.get(key)
        out["bureauInfo"] = bureau_index.get(key)
        out.update(sender_fields)
        enriched.append(out)
    return enriched


def _build_address_envelope_items(cfg: dict[str, Any]) -> list[dict[str, Any]]:
    sender_fields = _resolve_sender_fields(cfg)
    rows: list[dict[str, Any]] = []

    for item in _fetch_extension_items(cfg, "address-book-entries"):
        spec = item.get("spec") if isinstance(item.get("spec"), dict) else {}
        out = dict(item)
        out["sourceType"] = "address"
        out["sourceLabel"] = "地址簿"
        out["addressInfo"] = item
        out["bureauInfo"] = None
        out["callSign"] = str(spec.get("callSign", "")).strip()
        out.update(sender_fields)
        rows.append(out)

    for item in _fetch_extension_items(cfg, "bureau-entries"):
        spec = item.get("spec") if isinstance(item.get("spec"), dict) else {}
        out = dict(item)
        out["sourceType"] = "bureau"
        out["sourceLabel"] = "卡片局"
        out["addressInfo"] = None
        out["bureauInfo"] = item
        out["callSign"] = ""
        out["bureauName"] = str(spec.get("bureauName", "")).strip()
        out.update(sender_fields)
        rows.append(out)

    return rows


def _normalize_bool_text(value: Any) -> str:
    return str(value).strip().lower()


def _is_empty_value(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return not value.strip()
    return False


def _extract_source_value(rule: dict[str, Any], row: dict[str, Any]) -> Any:
    sources = rule.get("sources")
    if isinstance(sources, list):
        for source in sources:
            source_key = str(source).strip()
            if not source_key:
                continue
            value = _lookup_source_value(row, source_key)
            if not _is_empty_value(value):
                return value

    source_key = (
        str(rule.get("source", "")).strip()
        or str(rule.get("field", "")).strip()
        or str(rule.get("from", "")).strip()
    )
    if not source_key:
        return ""
    return _lookup_source_value(row, source_key)


def _lookup_source_value(row: dict[str, Any], source_key: str) -> Any:
    direct_value = row.get(source_key, None)
    if source_key in row:
        return direct_value
    nested_value = _lookup_row_path(row, source_key)
    return nested_value


def _apply_mapping_rule(rule: Any, row: dict[str, Any]) -> Any:
    if isinstance(rule, str):
        return _lookup_source_value(row, rule)
    if not isinstance(rule, dict):
        return ""

    source_value = _extract_source_value(rule, row)

    rule_type = str(rule.get("type", "")).strip().lower()
    if rule_type == "coalesce":
        return source_value

    if isinstance(rule.get("map"), dict):
        mapping = {str(k): v for k, v in rule["map"].items()}
        return mapping.get(str(source_value), rule.get("default", ""))

    if rule_type == "checkbox" or "true_values" in rule:
        true_values = rule.get("true_values", ["1", "true", "yes", "y", "on", "是", "√", "⬛"])
        true_set = {_normalize_bool_text(item) for item in true_values}
        true_output = rule.get("true_output", rule.get("true_value", "⬛"))
        false_output = rule.get("false_output", rule.get("false_value", ""))
        return true_output if _normalize_bool_text(source_value) in true_set else false_output

    if "template" in rule:
        template = str(rule.get("template", ""))
        return render_template_data(template, record_id="", row=row)

    return source_value


def map_export_row(source_row: dict[str, Any], mapping: dict[str, Any]) -> dict[str, Any]:
    if not mapping:
        return dict(source_row)
    out: dict[str, Any] = {}
    for target_key, rule in mapping.items():
        out[str(target_key)] = _apply_mapping_rule(rule, source_row)
    return out


ROW_TEMPLATE_PATTERN = re.compile(r"\$\{row\.([a-zA-Z0-9_\-.\u4e00-\u9fff]+)\}")


def _lookup_row_path(row: dict[str, Any], path: str) -> Any:
    cursor: Any = row
    for segment in path.split("."):
        if isinstance(cursor, dict) and segment in cursor:
            cursor = cursor.get(segment)
        else:
            return ""
    return cursor


def render_template_data(
    template: Any,
    *,
    record_id: str,
    row: dict[str, Any],
    now_text: str | None = None,
) -> Any:
    now_value = now_text or datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(template, str):
        text = template.replace("${id}", str(record_id)).replace("{id}", str(record_id)).replace("${now}", now_value)

        def _replace_row(match: re.Match[str]) -> str:
            key_path = match.group(1)
            value = _lookup_row_path(row, key_path)
            return "" if value is None else str(value)

        return ROW_TEMPLATE_PATTERN.sub(_replace_row, text)
    if isinstance(template, list):
        return [render_template_data(item, record_id=record_id, row=row, now_text=now_value) for item in template]
    if isinstance(template, dict):
        return {
            str(key): render_template_data(value, record_id=record_id, row=row, now_text=now_value)
            for key, value in template.items()
        }
    return template


def _flatten_query_params(payload: dict[str, Any]) -> dict[str, str]:
    flat: dict[str, str] = {}
    for key, raw_value in payload.items():
        name = str(key).strip()
        if not name:
            continue
        if raw_value is None:
            continue
        if isinstance(raw_value, bool):
            flat[name] = "true" if raw_value else "false"
            continue
        if isinstance(raw_value, (int, float, str)):
            value = str(raw_value).strip()
            if value:
                flat[name] = value
            continue
        serialized = json.dumps(raw_value, ensure_ascii=False)
        if serialized:
            flat[name] = serialized
    return flat


def _build_extension_put_payload(
    *,
    current_resource: dict[str, Any],
    record_id: str,
    spec_patch: dict[str, Any],
) -> dict[str, Any]:
    api_version = str(current_resource.get("apiVersion", "qsl-management.bi1kbu.com/v1alpha1"))
    kind = str(current_resource.get("kind", "CardRecord"))
    metadata_raw = current_resource.get("metadata")
    metadata = metadata_raw if isinstance(metadata_raw, dict) else {}
    status_raw = current_resource.get("status")
    status = status_raw if isinstance(status_raw, dict) else {}
    spec_raw = current_resource.get("spec")
    spec = dict(spec_raw) if isinstance(spec_raw, dict) else {}
    for key, value in spec_patch.items():
        spec[str(key)] = value
    _apply_card_state_consistency(spec)
    if any(str(key) in {"cardIssued", "envelopePrinted", "cardSent", "cardReceived", "receiptConfirmed"} for key in spec_patch):
        status["flowStatus"] = _resolve_card_flow_status(spec)
    return {
        "apiVersion": api_version,
        "kind": kind,
        "metadata": {
            "name": record_id,
            "version": metadata.get("version"),
        },
        "spec": spec,
        "status": status,
    }


def _truthy(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"true", "yes", "1", "是", "已"}


def _resolve_card_flow_status(spec: dict[str, Any]) -> str:
    if _truthy(spec.get("cardReceived")):
        return "已收卡片"
    if _truthy(spec.get("receiptConfirmed")):
        return "已签收"
    if _truthy(spec.get("cardSent")):
        return "已发信"
    if _truthy(spec.get("envelopePrinted")):
        return "已打包"
    if _truthy(spec.get("cardIssued")):
        return "已制卡"
    return ""


def _normalize_scene_type(spec: dict[str, Any]) -> str:
    scene_type = str(spec.get("sceneType", "") or "").strip().upper()
    if scene_type:
        return scene_type
    card_type = str(spec.get("cardType", "") or "").strip().upper()
    if card_type == "SWL":
        return "SWL"
    if card_type == "EYEBALL":
        return "EYEBALL"
    return "QSO"


def _now_text() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _apply_card_state_consistency(spec: dict[str, Any]) -> None:
    scene_type = _normalize_scene_type(spec)
    if _truthy(spec.get("cardSent")) and scene_type in {"QSO", "SWL", "ONLINE_EYEBALL"}:
        if not _truthy(spec.get("cardIssued")):
            spec["cardIssued"] = True
            spec["cardIssuedAt"] = _now_text()
        elif not str(spec.get("cardIssuedAt", "") or "").strip():
            spec["cardIssuedAt"] = _now_text()
        spec["envelopePrinted"] = True
    if _truthy(spec.get("receiptConfirmed")) and scene_type in {"EYEBALL", "ONLINE_EYEBALL"}:
        if not _truthy(spec.get("cardSent")):
            spec["cardSent"] = True
            spec["sentAt"] = _now_text()
        elif not str(spec.get("sentAt", "") or "").strip():
            spec["sentAt"] = _now_text()
    if _truthy(spec.get("receiptConfirmed")) and scene_type == "ONLINE_EYEBALL":
        if not _truthy(spec.get("cardIssued")):
            spec["cardIssued"] = True
            spec["cardIssuedAt"] = _now_text()
        elif not str(spec.get("cardIssuedAt", "") or "").strip():
            spec["cardIssuedAt"] = _now_text()
        spec["envelopePrinted"] = True
    if not _truthy(spec.get("cardIssued")):
        spec["cardIssuedAt"] = ""
        spec["createdMailStatus"] = ""
        spec["createdMailSentAt"] = ""
        spec["createdMailLastError"] = ""
    if not _truthy(spec.get("envelopePrinted")):
        spec["createdMailStatus"] = ""
        spec["createdMailSentAt"] = ""
        spec["createdMailLastError"] = ""
    if not _truthy(spec.get("cardSent")):
        spec["sentAt"] = ""
        spec["sentMailStatus"] = ""
        spec["sentMailSentAt"] = ""
        spec["sentMailLastError"] = ""
    if not _truthy(spec.get("cardReceived")):
        spec["receivedAt"] = ""
        spec["receivedRecordCodes"] = ""
        spec["receivedMailStatus"] = ""
        spec["receivedMailSentAt"] = ""
        spec["receivedMailLastError"] = ""


class BridgeService:
    def fetch_card_versions(self, config: dict[str, Any]) -> list[str]:
        cfg = normalize_bridge_config(config)

        # 卡片版本使用公开站点卡片接口，不要求后台登录；本台通信地址仍走受保护接口。
        version_rows = _station_card_version_rows(_fetch_public_station_card_items(cfg))
        return [version for _, version in version_rows]

    def fetch_station_sender(self, config: dict[str, Any]) -> dict[str, str]:
        cfg = normalize_bridge_config(config)
        spec = _fetch_station_profile(cfg)
        return {
            "my_name": str(spec.get("myName", "")).strip(),
            "my_phone": str(spec.get("myTelephone", "")).strip(),
            "my_postCode": str(spec.get("myPostalCode", "")).strip(),
            "my_address": str(spec.get("myAddress", "")).strip(),
        }

    def fetch_dataset(self, config: dict[str, Any], dataset: str) -> dict[str, Any]:
        dataset_name = str(dataset).strip().lower()
        if dataset_name not in DATASETS:
            raise CardPrintError(
                code="INVALID_DATASET",
                message="未知的数据集。",
                details={"dataset": dataset},
            )

        cfg = normalize_bridge_config(config)
        endpoint = cfg["endpoints"][dataset_name]
        timeout_s = float(cfg["common"]["timeout_s"])
        filters = cfg["filters"].get(dataset_name, {})
        if dataset_name == ADDRESS_ENVELOPE_DATASET:
            source_rows = _build_address_envelope_items(cfg)
        elif endpoint == HALO_CARD_RECORDS_ENDPOINT and not filters:
            source_rows = _fetch_card_record_items(cfg)
        else:
            method = "POST" if "/exports/" in endpoint else "GET"
            endpoint_with_query = endpoint
            payload = None
            if method == "GET":
                query = urllib.parse.urlencode(_flatten_query_params(filters), doseq=True)
                if query:
                    joiner = "&" if "?" in endpoint else "?"
                    endpoint_with_query = f"{endpoint}{joiner}{query}"
            else:
                payload = filters
            if method == "GET":
                source_rows = _fetch_extension_endpoint_items(cfg, endpoint_with_query)
            else:
                content_type, text = _http_request(
                    base_url=cfg["base_url"],
                    endpoint=endpoint_with_query,
                    method=method,
                    auth_config=cfg["auth"],
                    timeout_s=timeout_s,
                    payload=payload,
                )
                source_rows = _rows_from_export_response(content_type, text)
        if dataset_name == "cards":
            source_rows = _enrich_rows_for_card_mapping(cfg, source_rows)
        elif dataset_name == "envelopes":
            source_rows = _enrich_rows_for_envelope_mapping(cfg, source_rows)
        mapping = cfg["mappings"].get(dataset_name, {})
        id_field = str(
            cfg.get("writeback", {}).get("datasets", {}).get(dataset_name, {}).get("id_field", "")
        ).strip()

        records: list[dict[str, Any]] = []
        for index, source_row in enumerate(source_rows):
            mapped_row = map_export_row(source_row, mapping)
            record_id = "" if not id_field else str(_lookup_source_value(source_row, id_field)).strip()
            records.append(
                {
                    "index": index,
                    "source_row": source_row,
                    "mapped_row": mapped_row,
                    "record_id": record_id,
                }
            )

        return {
            "dataset": dataset_name,
            "count": len(records),
            "records": records,
            "id_field": id_field,
        }

    def writeback_success(
        self,
        config: dict[str, Any],
        dataset: str,
        records: list[dict[str, Any]],
        print_rows: list[dict[str, Any]],
    ) -> dict[str, Any]:
        dataset_name = str(dataset).strip().lower()
        if dataset_name not in DATASETS:
            raise CardPrintError(
                code="INVALID_DATASET",
                message="未知的数据集。",
                details={"dataset": dataset},
            )

        cfg = normalize_bridge_config(config)
        writeback_cfg = cfg.get("writeback", {})
        if dataset_name == ADDRESS_ENVELOPE_DATASET:
            return {
                "enabled": False,
                "dataset": dataset_name,
                "success": 0,
                "failed": 0,
                "skipped": len(print_rows),
                "errors": [],
            }
        if not bool(writeback_cfg.get("enabled", False)):
            return {
                "enabled": False,
                "dataset": dataset_name,
                "success": 0,
                "failed": 0,
                "skipped": len(print_rows),
                "errors": [],
            }

        dataset_cfg = writeback_cfg.get("datasets", {}).get(dataset_name, {}) or {}
        id_field = str(dataset_cfg.get("id_field", "")).strip()
        url_template = str(dataset_cfg.get("url", "")).strip()
        method = str(dataset_cfg.get("method", "PUT")).strip().upper() or "PUT"
        body_template = dataset_cfg.get("body", {})
        timeout_s = float(cfg["common"]["timeout_s"])

        success_count = 0
        failed_count = 0
        skipped_count = 0
        errors: list[dict[str, Any]] = []

        for row_result in print_rows:
            status = str(row_result.get("status", "")).strip().lower()
            index_raw = row_result.get("index", -1)
            try:
                index = int(index_raw)
            except (TypeError, ValueError):
                index = -1

            if status != "success":
                skipped_count += 1
                continue
            if index < 0 or index >= len(records):
                skipped_count += 1
                errors.append(
                    {
                        "index": index_raw,
                        "reason": "打印结果索引无效，无法定位原始记录。",
                    }
                )
                continue

            record = records[index]
            source_row = dict(record.get("source_row", {}))
            record_id = str(record.get("record_id", "")).strip()
            if not record_id and id_field:
                record_id = str(_lookup_source_value(source_row, id_field)).strip()

            if not record_id:
                skipped_count += 1
                errors.append(
                    {
                        "index": index,
                        "reason": "缺少记录 ID，已跳过回写。",
                        "id_field": id_field,
                    }
                )
                continue

            endpoint = render_template_data(url_template, record_id=record_id, row=source_row)
            body = render_template_data(body_template, record_id=record_id, row=source_row)
            try:
                if dataset_name in {"cards", "envelopes"} and isinstance(body, dict):
                    # Halo 扩展资源更新要求基于当前对象进行完整 PUT，这里先拉取再合并 spec。
                    content_type, current_text = _http_request(
                        base_url=cfg["base_url"],
                        endpoint=str(endpoint),
                        method="GET",
                        auth_config=cfg["auth"],
                        timeout_s=timeout_s,
                        payload=None,
                    )
                    current_resource = _parse_json_object_text(
                        current_text,
                        code="REMOTE_WRITEBACK_FORMAT_ERROR",
                        message="回写前读取记录失败，响应不是对象。",
                        details={"endpoint": str(endpoint), "content_type": content_type},
                    )
                    put_payload = _build_extension_put_payload(
                        current_resource=current_resource,
                        record_id=record_id,
                        spec_patch=body,
                    )
                    _http_request(
                        base_url=cfg["base_url"],
                        endpoint=str(endpoint),
                        method="PUT",
                        auth_config=cfg["auth"],
                        timeout_s=timeout_s,
                        payload=put_payload,
                    )
                else:
                    _http_request(
                        base_url=cfg["base_url"],
                        endpoint=str(endpoint),
                        method=method,
                        auth_config=cfg["auth"],
                        timeout_s=timeout_s,
                        payload=body,
                    )
                success_count += 1
            except CardPrintError as exc:
                failed_count += 1
                errors.append(
                    {
                        "index": index,
                        "record_id": record_id,
                        "code": exc.code,
                        "message": exc.message,
                        "details": exc.details,
                    }
                )

        return {
            "enabled": True,
            "dataset": dataset_name,
            "success": success_count,
            "failed": failed_count,
            "skipped": skipped_count,
            "errors": errors,
        }
