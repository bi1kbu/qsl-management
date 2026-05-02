#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
将 SQLite 地址库转换为 QSL 管理系统可导入的 ZIP。

默认输出三个数据集：
- qso-record.csv（通联记录）
- card-record.csv（卡片记录）
- address-management.csv（地址管理）

导入格式对齐系统当前实现：
- CSV 第一列表头必须是 id#<dataset>
- ZIP 内每个 CSV 文件名包含 dataset 关键词
"""

from __future__ import annotations

import argparse
import csv
import io
import sqlite3
import zipfile
from pathlib import Path
from typing import Any, Iterable

QSO_DATASET = "qso-record"
CARD_DATASET = "card-record"
ADDRESS_DATASET = "address-management"
BUREAU_DATASET = "bureau-management"

QSO_HEADERS = [
    f"id#{QSO_DATASET}",
    "callSign",
    "date",
    "time",
    "timezone",
    "freq",
    "myRig",
    "myRigMode",
    "myRigAnt",
    "myRigPwr",
    "myQth",
    "operator",
    "rig",
    "ant",
    "pwr",
    "qth",
    "rstSent",
    "rstRcvd",
    "remarks",
]

CARD_HEADERS = [
    f"id#{CARD_DATASET}",
    "callSign",
    "cardType",
    "cardVersion",
    "qsoRecordName",
    "cardDate",
    "cardTime",
    "cardRemarks",
    "cardSent",
    "cardIssued",
    "cardReceived",
    "receiptConfirmed",
    "cardIssuedAt",
    "sentAt",
    "receivedAt",
    "createdMailStatus",
    "createdMailSentAt",
    "createdMailLastError",
    "sentMailStatus",
    "sentMailSentAt",
    "sentMailLastError",
    "receivedMailStatus",
    "receivedMailSentAt",
    "receivedMailLastError",
    "mailTargetEmail",
]

ADDRESS_HEADERS = [
    f"id#{ADDRESS_DATASET}",
    "callSign",
    "name",
    "telephone",
    "postalCode",
    "address",
    "email",
    "addressRemarks",
]

BUREAU_HEADERS = [
    f"id#{BUREAU_DATASET}",
    "bureauName",
    "telephone",
    "postalCode",
    "address",
    "addressRemarks",
]


def text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def norm_callsign(value: Any) -> str:
    return text(value).upper()


def to_bool(value: Any) -> bool:
    raw = text(value).lower()
    if raw in {"1", "true", "yes", "y", "是"}:
        return True
    if raw in {"0", "false", "no", "n", "否", ""}:
        return False
    try:
        return int(raw) != 0
    except Exception:
        return False


def bool_to_text(value: bool) -> str:
    return "true" if value else "false"


def to_bool_text_zh(value: Any) -> str:
    return "是" if to_bool(value) else "否"


def first_non_empty(row: sqlite3.Row, keys: Iterable[str]) -> str:
    for key in keys:
        if key in row.keys():
            val = text(row[key])
            if val:
                return val
    return ""


def normalize_hhmm(raw_time: str) -> str:
    value = raw_time.strip()
    if not value:
        return ""

    if ":" in value:
        parts = value.split(":")
        if len(parts) >= 2:
            hh = parts[0].zfill(2)
            mm = parts[1].zfill(2)
            return f"{hh}{mm}"

    digits = "".join(ch for ch in value if ch.isdigit())
    if len(digits) >= 4:
        return digits[:4]
    if len(digits) == 3:
        return f"0{digits}"
    return digits


def infer_card_type(row: sqlite3.Row) -> str:
    if "qso_eyeball" in row.keys() and to_bool(row["qso_eyeball"]):
        return "EYEBALL"
    if "qso_swl" in row.keys() and to_bool(row["qso_swl"]):
        return "SWL"
    return "QSO"


def is_bureau_address(address: str) -> bool:
    normalized = text(address).replace(" ", "")
    return "上海" in normalized and "399-5" in normalized


def build_address_remark(row: sqlite3.Row) -> str:
    parts: list[str] = []

    source = text(row["source"]) if "source" in row.keys() else ""
    if source:
        parts.append(f"来源:{source}")

    qso_rmks = text(row["qso_rmks"]) if "qso_rmks" in row.keys() else ""
    if qso_rmks:
        parts.append("原备注:" + qso_rmks.replace("\n", " / "))

    created_at = text(row["created_at"]) if "created_at" in row.keys() else ""
    if created_at:
        parts.append(f"创建时间:{created_at}")

    return "；".join(parts)


def build_card_remark(row: sqlite3.Row) -> str:
    parts: list[str] = []

    source = text(row["source"]) if "source" in row.keys() else ""
    if source:
        parts.append(f"来源:{source}")

    qso_rmks = text(row["qso_rmks"]) if "qso_rmks" in row.keys() else ""
    if qso_rmks:
        parts.append(qso_rmks.replace("\n", " / "))

    qso_freq = text(row["qso_freq_mhz"]) if "qso_freq_mhz" in row.keys() else ""
    qso_mode = text(row["qso_mode"]) if "qso_mode" in row.keys() else ""
    qso_rig = text(row["qso_rig"]) if "qso_rig" in row.keys() else ""
    qso_ant = text(row["qso_ant"]) if "qso_ant" in row.keys() else ""
    qso_pwr = text(row["qso_pwr_w"]) if "qso_pwr_w" in row.keys() else ""

    meta = []
    if qso_freq:
        meta.append(f"频率:{qso_freq}MHz")
    if qso_mode:
        meta.append(f"模式:{qso_mode}")
    if qso_rig:
        meta.append(f"设备:{qso_rig}")
    if qso_ant:
        meta.append(f"天线:{qso_ant}")
    if qso_pwr:
        meta.append(f"功率:{qso_pwr}")
    if meta:
        parts.append("；".join(meta))

    return "；".join(parts)


def ensure_unique_id(candidate: str, used: set[str]) -> str:
    if candidate not in used:
        used.add(candidate)
        return candidate

    suffix = 2
    while True:
        next_id = f"{candidate}-{suffix}"
        if next_id not in used:
            used.add(next_id)
            return next_id
        suffix += 1


def render_csv(headers: list[str], rows: list[list[str]]) -> str:
    buff = io.StringIO(newline="")
    writer = csv.writer(buff, lineterminator="\n")
    writer.writerow(headers)
    writer.writerows(rows)
    return buff.getvalue()


def extract_rows(db_path: Path, table_name: str) -> list[sqlite3.Row]:
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?", (table_name,))
    if cur.fetchone() is None:
        conn.close()
        raise ValueError(f"未找到数据表: {table_name}")

    cur.execute(f"SELECT * FROM {table_name}")
    rows = cur.fetchall()
    conn.close()
    return rows


def resolve_numeric_seed(raw_id: str, index: int) -> int:
    try:
        return int(raw_id)
    except Exception:
        return 1000 + index


def should_create_qso(row: sqlite3.Row, card_type: str) -> bool:
    # 按当前业务约束：仅 QSO/SWL 生成通联记录；EYEBALL 不生成 qso-record。
    return card_type in {"QSO", "SWL"}


def build_qso_csv_rows(rows: list[sqlite3.Row]) -> tuple[list[list[str]], dict[int, str]]:
    out: list[list[str]] = []
    row_index_to_qso_id: dict[int, str] = {}
    used_ids: set[str] = set()

    for idx, row in enumerate(rows, start=1):
        card_type = infer_card_type(row)
        if not should_create_qso(row, card_type):
            continue

        sid = first_non_empty(row, ("id",)) or str(idx)
        numeric = resolve_numeric_seed(sid, idx)
        qso_id = ensure_unique_id(f"QSO{1000 + numeric}", used_ids)
        row_index_to_qso_id[idx] = qso_id

        call_sign = norm_callsign(first_non_empty(row, ("callsign", "call_sign", "to_radio")))
        qso_date = first_non_empty(row, ("qso_date",))
        qso_time = normalize_hhmm(first_non_empty(row, ("qso_time",)))
        qso_timezone = first_non_empty(row, ("qso_timezone",)) or "UTC"
        qso_freq = first_non_empty(row, ("qso_freq_mhz",))
        qso_mode = first_non_empty(row, ("qso_mode",))
        qso_rig = first_non_empty(row, ("qso_rig",))
        qso_ant = first_non_empty(row, ("qso_ant",))
        qso_pwr = first_non_empty(row, ("qso_pwr_w",))
        qso_rst = first_non_empty(row, ("qso_rst",))
        qso_rmks = first_non_empty(row, ("qso_rmks",)).replace("\n", " / ")

        out.append([
            qso_id,
            call_sign,
            qso_date,
            qso_time,
            qso_timezone,
            qso_freq,
            qso_rig,
            qso_mode,
            qso_ant,
            qso_pwr,
            "",
            "",
            qso_rig,
            qso_ant,
            qso_pwr,
            "",
            qso_rst,
            qso_rst,
            qso_rmks,
        ])

    return out, row_index_to_qso_id


def build_card_csv_rows(rows: list[sqlite3.Row], qso_mapping: dict[int, str], card_version: str) -> list[list[str]]:
    out: list[list[str]] = []
    used_ids: set[str] = set()

    for idx, row in enumerate(rows, start=1):
        sid = first_non_empty(row, ("id",)) or str(idx)
        numeric = resolve_numeric_seed(sid, idx)

        card_id = ensure_unique_id(f"C{1000 + numeric}", used_ids)
        call_sign = norm_callsign(first_non_empty(row, ("callsign", "call_sign", "to_radio")))
        card_type = infer_card_type(row)

        qso_record_name = qso_mapping.get(idx, "")
        qso_date = first_non_empty(row, ("qso_date",))
        qso_time = normalize_hhmm(first_non_empty(row, ("qso_time",)))
        created_at = first_non_empty(row, ("created_at",))

        card_sent = to_bool(row["sent_card"]) if "sent_card" in row.keys() else False
        card_issued = to_bool(row["printed_card"]) if "printed_card" in row.keys() else False
        card_received = to_bool(row["received_card"]) if "received_card" in row.keys() else False
        # 旧库无签收字段，先用已收卡近似。
        receipt_confirmed = card_received

        card_issued_at = created_at if card_issued else ""
        sent_at = created_at if card_sent else ""
        received_at = created_at if card_received else ""

        mail_target_email = first_non_empty(row, ("email", "mail", "email_address"))
        card_remarks = build_card_remark(row)

        out.append([
            card_id,
            call_sign,
            card_type,
            card_version,
            qso_record_name,
            qso_date,
            qso_time,
            card_remarks,
            bool_to_text(card_sent),
            bool_to_text(card_issued),
            bool_to_text(card_received),
            bool_to_text(receipt_confirmed),
            card_issued_at,
            sent_at,
            received_at,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            mail_target_email,
        ])

    return out


def build_address_csv_rows(rows: list[sqlite3.Row]) -> list[list[str]]:
    out: list[list[str]] = []
    used_ids: set[str] = set()

    for idx, row in enumerate(rows, start=1):
        raw_address = first_non_empty(row, ("address", "addr", "mail_address"))
        if is_bureau_address(raw_address):
            continue

        call_sign = norm_callsign(first_non_empty(row, ("callsign", "call_sign", "to_radio")))
        sid = first_non_empty(row, ("id",)) or str(idx)
        base_id = f"{call_sign}-{sid}" if call_sign else f"ADDRESS-{sid}"
        row_id = ensure_unique_id(base_id, used_ids)

        name = first_non_empty(row, ("name", "real_name"))
        telephone = first_non_empty(row, ("phone", "telephone", "mobile"))
        postal_code = first_non_empty(row, ("zipcode", "postalCode", "postal_code"))
        address = raw_address
        email = first_non_empty(row, ("email", "mail", "email_address"))

        qso_flags = [
            f"已收卡:{to_bool_text_zh(row['received_card'])}" if "received_card" in row.keys() else "",
            f"已制卡:{to_bool_text_zh(row['printed_card'])}" if "printed_card" in row.keys() else "",
            f"已打印封面:{to_bool_text_zh(row['printed_cover'])}" if "printed_cover" in row.keys() else "",
            f"已发卡:{to_bool_text_zh(row['sent_card'])}" if "sent_card" in row.keys() else "",
        ]
        qso_flags = [item for item in qso_flags if item]

        remark_base = build_address_remark(row)
        status_tail = "；状态:" + "，".join(qso_flags) if qso_flags else ""
        remark = (remark_base + status_tail).strip("；") if remark_base or status_tail else ""

        out.append([
            row_id,
            call_sign,
            name,
            telephone,
            postal_code,
            address,
            email,
            remark,
        ])

    return out


def build_bureau_csv_rows(rows: list[sqlite3.Row]) -> list[list[str]]:
    grouped: dict[str, list[sqlite3.Row]] = {}
    for row in rows:
        raw_address = first_non_empty(row, ("address", "addr", "mail_address"))
        if not is_bureau_address(raw_address):
            continue
        grouped.setdefault(raw_address, []).append(row)

    out: list[list[str]] = []
    used_ids: set[str] = set()

    for index, (address, group_rows) in enumerate(grouped.items(), start=1):
        bureau_id = ensure_unique_id(f"BURO-{index}", used_ids)

        telephone = ""
        postal_code = ""
        source_values: set[str] = set()
        sample_callsigns: list[str] = []

        for row in group_rows:
            if not telephone:
                telephone = first_non_empty(row, ("phone", "telephone", "mobile"))
            if not postal_code:
                postal_code = first_non_empty(row, ("zipcode", "postalCode", "postal_code"))

            source = first_non_empty(row, ("source",))
            if source:
                source_values.add(source)

            call_sign = norm_callsign(first_non_empty(row, ("callsign", "call_sign", "to_radio")))
            if call_sign and call_sign not in sample_callsigns:
                sample_callsigns.append(call_sign)

        remark_parts = [
            "由地址库自动识别为卡片局地址",
            f"聚合条目数:{len(group_rows)}",
        ]
        if source_values:
            remark_parts.append("来源:" + "、".join(sorted(source_values)))
        if sample_callsigns:
            preview = "、".join(sample_callsigns[:8])
            if len(sample_callsigns) > 8:
                preview += "..."
            remark_parts.append("示例呼号:" + preview)

        out.append([
            bureau_id,
            address,
            telephone,
            postal_code,
            address,
            "；".join(remark_parts),
        ])

    return out


def convert_sqlite_to_zip(
    db_path: Path,
    table_name: str,
    out_zip: Path,
    card_version: str,
) -> tuple[int, int, int, int, Path]:
    rows = extract_rows(db_path, table_name)

    qso_rows, qso_mapping = build_qso_csv_rows(rows)
    card_rows = build_card_csv_rows(rows, qso_mapping, card_version)
    address_rows = build_address_csv_rows(rows)
    bureau_rows = build_bureau_csv_rows(rows)

    qso_csv = render_csv(QSO_HEADERS, qso_rows)
    card_csv = render_csv(CARD_HEADERS, card_rows)
    address_csv = render_csv(ADDRESS_HEADERS, address_rows)
    bureau_csv = render_csv(BUREAU_HEADERS, bureau_rows)

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(f"{QSO_DATASET}.csv", qso_csv)
        zf.writestr(f"{CARD_DATASET}.csv", card_csv)
        zf.writestr(f"{ADDRESS_DATASET}.csv", address_csv)
        zf.writestr(f"{BUREAU_DATASET}.csv", bureau_csv)

    return len(qso_rows), len(card_rows), len(address_rows), len(bureau_rows), out_zip


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="将 SQLite 地址库转换为 QSL 系统导入 ZIP")
    parser.add_argument("--db", default="tmp/addresses.db", help="SQLite 数据库路径（默认: tmp/addresses.db）")
    parser.add_argument("--table", default="addresses", help="数据表名（默认: addresses）")
    parser.add_argument("--card-version", default="", help="卡片版本写入值（默认空字符串）")
    parser.add_argument(
        "--out",
        default="tmp/qso-card-address-import.zip",
        help="输出 ZIP 路径（默认: tmp/qso-card-address-import.zip）",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    db_path = Path(args.db)
    if not db_path.exists():
        raise SystemExit(f"数据库不存在: {db_path}")

    qso_count, card_count, address_count, bureau_count, out_zip = convert_sqlite_to_zip(
        db_path=db_path,
        table_name=args.table,
        out_zip=Path(args.out),
        card_version=args.card_version.strip(),
    )

    print(f"转换完成：QSO {qso_count} 条，卡片 {card_count} 条，地址 {address_count} 条，卡片局 {bureau_count} 条。")
    print(f"输出文件: {out_zip}")
    print(
        f"ZIP 内文件: {QSO_DATASET}.csv, {CARD_DATASET}.csv, "
        f"{ADDRESS_DATASET}.csv, {BUREAU_DATASET}.csv"
    )


if __name__ == "__main__":
    main()
