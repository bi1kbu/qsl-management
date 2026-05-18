#!/usr/bin/env python3
"""将 2.0.0 之前的 QSL CSV ZIP 导出包转换为 2.0.0 导入包。"""

from __future__ import annotations

import argparse
import csv
import io
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


CARD_HEADER = [
    "id#card-record",
    "callSign",
    "cardType",
    "sceneType",
    "cardVersion",
    "qsoRecordName",
    "offlineActivityName",
    "addressEntryName",
    "cardDate",
    "cardTime",
    "businessRemarks",
    "createdRemarks",
    "sentRemarks",
    "receivedRemarks",
    "publicReceiptRemarks",
    "cardRemarks",
    "cardSent",
    "cardIssued",
    "envelopePrinted",
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
    "receivedRecordCodes",
    "flowStatus",
]

RECEIVE_HEADER = [
    "id#receive-record",
    "callSign",
    "cardType",
    "businessType",
    "offlineActivityName",
    "receivedDate",
    "receivedAt",
    "outboundCardNames",
    "matchStatus",
    "matchReason",
    "remarks",
    "syncStatus",
]

OFFLINE_EXCHANGE_HEADER = [
    "id#offline-exchange-card",
    "cardRecordName",
    "offlineActivityName",
    "callSign",
    "cardType",
    "cardVersion",
    "claimStatus",
    "sentStatus",
    "sentAt",
    "remarks",
    "flowStatus",
]

SYSTEM_SETTING_SEQUENCE_FIELDS = {
    "cardRecordSequence": re.compile(r"^C(\d+)$", re.IGNORECASE),
    "receiveRecordSequence": re.compile(r"^R(\d+)-\d{8}$", re.IGNORECASE),
}


@dataclass
class ConversionSummary:
    card_rows_before: int = 0
    card_rows_after: int = 0
    removed_auto_receive_cards: int = 0
    removed_station_card_placeholders: int = 0
    receive_records: int = 0
    matched_receive_records: int = 0
    unmatched_receive_records: int = 0
    offline_exchange_cards: int = 0
    adjusted_card_sequence: int = 0
    adjusted_receive_sequence: int = 0


def read_csv_rows(content: str) -> tuple[list[str], list[dict[str, str]]]:
    reader = csv.reader(io.StringIO(content))
    rows = list(reader)
    if not rows:
        return [], []

    raw_header = rows[0]
    normalized_header = [
        cell.split("#", 1)[0].strip() if index == 0 else cell.strip()
        for index, cell in enumerate(raw_header)
    ]

    records: list[dict[str, str]] = []
    for line in rows[1:]:
        if not any(cell.strip() for cell in line):
            continue
        row: dict[str, str] = {}
        for index, header in enumerate(normalized_header):
            row[header or f"field{index}"] = line[index].strip() if index < len(line) else ""
        records.append(row)
    return raw_header, records


def render_csv(header: list[str], rows: Iterable[dict[str, str]]) -> str:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(header)
    normalized_header = [
        cell.split("#", 1)[0].strip() if index == 0 else cell
        for index, cell in enumerate(header)
    ]
    for row in rows:
        writer.writerow([row.get(field, "") for field in normalized_header])
    return output.getvalue()


def truthy(value: str) -> bool:
    return value.strip().lower() in {"true", "1", "yes", "y", "是"}


def split_codes(value: str) -> list[str]:
    return [item.strip() for item in re.split(r"[,，、;；\s]+", value) if item.strip()]


def is_legacy_auto_receive_card(row: dict[str, str]) -> bool:
    return (
        row.get("sceneType", "").strip().upper() == "EYEBALL"
        and row.get("businessRemarks", "").strip() == "自动创建EYEBALL卡片"
        and truthy(row.get("cardReceived", ""))
        and not truthy(row.get("cardIssued", ""))
        and not truthy(row.get("cardSent", ""))
        and not truthy(row.get("receiptConfirmed", ""))
    )


def is_station_card_placeholder(row: dict[str, str]) -> bool:
    return (
        row.get("id", "").strip().startswith("qsl-station-card-")
        and not row.get("callSign", "").strip()
        and not row.get("qsoRecordName", "").strip()
        and row.get("cardType", "").strip().upper() in {"QSO", "SWL"}
        and row.get("sceneType", "").strip().upper() in {"QSO", "SWL"}
    )


def resolve_business_type(row: dict[str, str]) -> str:
    scene_type = row.get("sceneType", "").strip().upper()
    card_type = row.get("cardType", "").strip().upper()
    if scene_type == "EYEBALL":
        return "OFFLINE_EYEBALL"
    if scene_type == "ONLINE_EYEBALL":
        return "ONLINE_EYEBALL"
    if card_type == "SWL" or scene_type == "SWL":
        return "SWL"
    if card_type == "QSO" or scene_type == "QSO":
        return "QSO"
    return scene_type or card_type or "UNKNOWN"


def date_from_receive_code(code: str) -> str:
    match = re.search(r"(\d{4})(\d{2})(\d{2})$", code)
    if not match:
        return ""
    return f"{match.group(1)}-{match.group(2)}-{match.group(3)}"


def date_from_timestamp(value: str) -> str:
    match = re.match(r"^(\d{4})[-/](\d{1,2})[-/](\d{1,2})", value.strip())
    if not match:
        return ""
    return f"{match.group(1)}-{int(match.group(2)):02d}-{int(match.group(3)):02d}"


def build_receive_records(card_rows: list[dict[str, str]]) -> list[dict[str, str]]:
    grouped: dict[str, dict[str, object]] = {}
    for card in card_rows:
        if not truthy(card.get("cardReceived", "")):
            continue
        for code in split_codes(card.get("receivedRecordCodes", "")):
            auto_receive = is_legacy_auto_receive_card(card)
            group = grouped.setdefault(
                code,
                {
                    "cards": [],
                    "auto_cards": [],
                    "first_card": card,
                    "remarks": [],
                },
            )
            if auto_receive:
                group["auto_cards"].append(card)
            else:
                group["cards"].append(card)
            if card.get("receivedRemarks", "").strip():
                group["remarks"].append(card.get("receivedRemarks", "").strip())

    receive_rows: list[dict[str, str]] = []
    for code, group in grouped.items():
        linked_cards = group["cards"]
        all_cards = linked_cards or group["auto_cards"]
        first_card = all_cards[0] if all_cards else group["first_card"]
        outbound_names = [card.get("id", "") for card in linked_cards if card.get("id", "").strip()]
        received_date = date_from_receive_code(code) or first_non_empty_date(all_cards)
        received_at = first_non_empty([card.get("receivedAt", "") for card in all_cards])
        if not received_at and received_date:
            received_at = f"{received_date} 00:00:00"
        offline_activity_names = sorted(
            {
                card.get("offlineActivityName", "").strip()
                for card in all_cards
                if card.get("offlineActivityName", "").strip()
            }
        )

        receive_rows.append(
            {
                "id": code,
                "callSign": first_card.get("callSign", ""),
                "cardType": first_card.get("cardType", "") or "QSO",
                "businessType": resolve_business_type(first_card),
                "offlineActivityName": "、".join(offline_activity_names),
                "receivedDate": received_date,
                "receivedAt": received_at,
                "outboundCardNames": ", ".join(outbound_names),
                "matchStatus": "自动匹配" if outbound_names else "未匹配",
                "matchReason": "历史收卡编号聚合关联" if outbound_names else "历史自动收卡记录，已转为独立收卡事实",
                "remarks": "；".join(dict.fromkeys(group["remarks"])),
                "syncStatus": "MIGRATED",
            }
        )
    return receive_rows


def first_non_empty(values: Iterable[str]) -> str:
    for value in values:
        if value and value.strip():
            return value.strip()
    return ""


def first_non_empty_date(card_rows: Iterable[dict[str, str]]) -> str:
    for card in card_rows:
        date_value = date_from_timestamp(card.get("receivedAt", ""))
        if date_value:
            return date_value
    return ""


def strip_extracted_receive_fields(row: dict[str, str]) -> dict[str, str]:
    cleaned = dict(row)
    cleaned["cardReceived"] = "false"
    cleaned["receivedAt"] = ""
    cleaned["receivedRemarks"] = ""
    cleaned["receivedMailStatus"] = ""
    cleaned["receivedMailSentAt"] = ""
    cleaned["receivedMailLastError"] = ""
    cleaned["receivedRecordCodes"] = ""
    if cleaned.get("flowStatus", "").strip() == "已收卡片":
        cleaned["flowStatus"] = "已发卡片" if truthy(cleaned.get("cardSent", "")) else "已制卡"
    return cleaned


def build_offline_exchange_cards(card_rows: list[dict[str, str]]) -> list[dict[str, str]]:
    offline_rows: list[dict[str, str]] = []
    for card in card_rows:
        if card.get("sceneType", "").strip().upper() != "EYEBALL":
            continue
        if not card.get("offlineActivityName", "").strip():
            continue
        if is_legacy_auto_receive_card(card):
            continue

        call_sign = card.get("callSign", "")
        receipt_confirmed = truthy(card.get("receiptConfirmed", ""))
        claim_status = "待认领" if not call_sign.strip() else ("已认领" if receipt_confirmed else "人工绑定")
        sent_status = "已发出" if truthy(card.get("cardSent", "")) or receipt_confirmed else "待发出"
        remarks = join_non_empty(
            [
                card.get("businessRemarks", ""),
                card.get("cardRemarks", ""),
                card.get("publicReceiptRemarks", ""),
            ]
        )

        offline_rows.append(
            {
                "id": f"OEC-{card.get('id', '')}",
                "cardRecordName": card.get("id", ""),
                "offlineActivityName": card.get("offlineActivityName", ""),
                "callSign": call_sign,
                "cardType": card.get("cardType", "") or "EYEBALL",
                "cardVersion": card.get("cardVersion", ""),
                "claimStatus": claim_status,
                "sentStatus": sent_status,
                "sentAt": card.get("sentAt", ""),
                "remarks": remarks,
                "flowStatus": card.get("flowStatus", ""),
            }
        )
    return offline_rows


def join_non_empty(values: Iterable[str]) -> str:
    return "；".join(value.strip() for value in values if value and value.strip())


def update_system_setting(content: str, max_card_sequence: int, max_receive_sequence: int) -> str:
    header, rows = read_csv_rows(content)
    if not header:
        return content
    for row in rows:
        if "cardRecordSequence" in row:
            row["cardRecordSequence"] = str(max_card_sequence)
        if "receiveRecordSequence" in row:
            row["receiveRecordSequence"] = str(max_receive_sequence)
    return render_csv(header, rows)


def max_sequence(rows: Iterable[dict[str, str]], field: str) -> int:
    pattern = SYSTEM_SETTING_SEQUENCE_FIELDS[field]
    max_value = 0
    for row in rows:
        match = pattern.match(row.get("id", "").strip())
        if match:
            max_value = max(max_value, int(match.group(1)))
    return max_value


def convert_zip(input_path: Path, output_path: Path) -> ConversionSummary:
    summary = ConversionSummary()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(input_path, "r") as source_zip:
        entries = {name: source_zip.read(name) for name in source_zip.namelist() if not name.endswith("/")}

    card_entry_name = next((name for name in entries if name.lower().endswith("card-record.csv")), "")
    if not card_entry_name:
        raise ValueError("旧导出包缺少 card-record.csv，无法生成 2.0.0 收卡迁移数据。")

    card_header, card_rows = read_csv_rows(entries[card_entry_name].decode("utf-8-sig"))
    summary.card_rows_before = len(card_rows)

    business_card_rows = [row for row in card_rows if not is_station_card_placeholder(row)]
    summary.removed_station_card_placeholders = summary.card_rows_before - len(business_card_rows)

    receive_rows = build_receive_records(business_card_rows)
    retained_card_rows = [
        strip_extracted_receive_fields(row)
        for row in business_card_rows
        if not is_legacy_auto_receive_card(row)
    ]
    offline_exchange_rows = build_offline_exchange_cards(retained_card_rows)

    summary.card_rows_after = len(retained_card_rows)
    summary.removed_auto_receive_cards = len(business_card_rows) - summary.card_rows_after
    summary.receive_records = len(receive_rows)
    summary.matched_receive_records = sum(1 for row in receive_rows if row.get("matchStatus") == "自动匹配")
    summary.unmatched_receive_records = sum(1 for row in receive_rows if row.get("matchStatus") == "未匹配")
    summary.offline_exchange_cards = len(offline_exchange_rows)
    summary.adjusted_card_sequence = max_sequence(retained_card_rows, "cardRecordSequence")
    summary.adjusted_receive_sequence = max_sequence(receive_rows, "receiveRecordSequence")

    converted_entries: dict[str, str] = {}
    for name, raw_content in entries.items():
        if not name.lower().endswith(".csv"):
            continue
        content = raw_content.decode("utf-8-sig")
        if name == card_entry_name:
            converted_entries[name] = render_csv(card_header or CARD_HEADER, retained_card_rows)
        elif name.lower().endswith("system-setting.csv"):
            converted_entries[name] = update_system_setting(
                content,
                summary.adjusted_card_sequence,
                summary.adjusted_receive_sequence,
            )
        else:
            converted_entries[name] = content

    converted_entries["receive-record.csv"] = render_csv(RECEIVE_HEADER, receive_rows)
    converted_entries["offline-exchange-card.csv"] = render_csv(OFFLINE_EXCHANGE_HEADER, offline_exchange_rows)

    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as target_zip:
        for name in sorted(converted_entries):
            target_zip.writestr(name, converted_entries[name])

    return summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="转换旧版 QSL 导出 ZIP 为 2.0.0 导入 ZIP。")
    parser.add_argument("input", nargs="?", default="tmp/all-1779030456244.zip", help="旧版导出 ZIP 路径")
    parser.add_argument("-o", "--output", help="输出 ZIP 路径，默认在原文件名后追加 -qsl2")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    if not input_path.exists():
        print(f"输入文件不存在：{input_path}", file=sys.stderr)
        return 1

    output_path = Path(args.output) if args.output else input_path.with_name(f"{input_path.stem}-qsl2.zip")
    summary = convert_zip(input_path, output_path)

    print(f"已生成：{output_path}")
    print(f"card-record：{summary.card_rows_before} -> {summary.card_rows_after}")
    print(f"移除本台卡片版本占位记录：{summary.removed_station_card_placeholders}")
    print(f"移除旧版自动收卡卡片：{summary.removed_auto_receive_cards}")
    print(
        "receive-record："
        f"{summary.receive_records}（自动匹配 {summary.matched_receive_records}，未匹配 {summary.unmatched_receive_records}）"
    )
    print(f"offline-exchange-card：{summary.offline_exchange_cards}")
    print(f"序列号：cardRecordSequence={summary.adjusted_card_sequence}，receiveRecordSequence={summary.adjusted_receive_sequence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
