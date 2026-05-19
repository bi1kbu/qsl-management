#!/usr/bin/env python3
"""统一 QSL 导入导出数据包内的日期时间文本格式。"""

from __future__ import annotations

import argparse
import copy
import csv
import io
import json
import re
import zipfile
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any


DATE_FIELDS = {
    "date",
    "cardDate",
    "receivedDate",
    "activityDate",
}

DATETIME_FIELDS = {
    "cardIssuedAt",
    "sentAt",
    "receivedAt",
    "createdMailSentAt",
    "sentMailSentAt",
    "receivedMailSentAt",
    "reviewedAt",
    "reviewMailSentAt",
    "lastModifiedAt",
    "occurredAt",
    "startedAt",
    "finishedAt",
}

TIME_FIELDS = {
    "time",
    "cardTime",
}

TIME_RANGE_FIELDS = {
    "activityTime",
}

DATE_FORMAT = "%Y-%m-%d"
DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"
TIME_FORMAT = "%H%M"


@dataclass
class FieldReport:
    field: str
    changed: int = 0
    unchanged: int = 0
    empty: int = 0
    failed: int = 0
    examples: list[dict[str, str]] = field(default_factory=list)


@dataclass
class FileReport:
    file: str
    fields: dict[str, FieldReport] = field(default_factory=dict)


def base_field_name(name: str) -> str:
    return name.split("#", 1)[0].strip()


def classify_field(name: str) -> str:
    field_name = base_field_name(name)
    if field_name in DATE_FIELDS:
        return "date"
    if field_name in DATETIME_FIELDS or field_name.endswith("At"):
        return "datetime"
    if field_name in TIME_FIELDS:
        return "time"
    if field_name in TIME_RANGE_FIELDS:
        return "time_range"
    return ""


def parse_date(value: str) -> str | None:
    parsed = parse_datetime(value, allow_date_only=True)
    if parsed is not None:
        return parsed.strftime(DATE_FORMAT)

    normalized = value.strip().replace("/", "-").replace(".", "-")
    for fmt in ("%Y-%m-%d", "%Y-%m", "%Y%m%d"):
        try:
            return datetime.strptime(normalized, fmt).strftime(DATE_FORMAT)
        except ValueError:
            continue
    return None


def parse_datetime(value: str, allow_date_only: bool = False) -> datetime | None:
    normalized = value.strip()
    if not normalized:
        return None

    normalized = normalized.replace("T", " ")
    normalized = re.sub(r"(Z|[+-]\d{2}:?\d{2})$", "", normalized).strip()
    normalized = re.sub(r"\.\d+", "", normalized)
    normalized = normalized.replace("/", "-")

    formats = [
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d %H:%M",
        "%Y-%m-%d %H%M%S",
        "%Y-%m-%d %H%M",
        "%Y%m%d %H%M%S",
        "%Y%m%d %H%M",
    ]
    if allow_date_only:
        formats.extend(["%Y-%m-%d", "%Y%m%d"])

    for fmt in formats:
        try:
            return datetime.strptime(normalized, fmt)
        except ValueError:
            continue
    return None


def parse_time(value: str) -> str | None:
    normalized = value.strip()
    if not normalized:
        return None

    if re.fullmatch(r"\d{1,2}", normalized):
        hour = int(normalized)
        if 0 <= hour <= 23:
            return f"{hour:02d}00"
        return None

    normalized = normalized.replace("：", ":")
    for fmt in ("%H%M", "%H:%M", "%H:%M:%S", "%H%M%S"):
        try:
            return datetime.strptime(normalized, fmt).strftime(TIME_FORMAT)
        except ValueError:
            continue
    return None


def parse_time_range(value: str) -> str | None:
    normalized = value.strip().replace("－", "-").replace("—", "-").replace("~", "-").replace("～", "-")
    parts = [part.strip() for part in normalized.split("-")]
    if len(parts) != 2:
        return parse_time(normalized)
    start = parse_time(parts[0])
    end = parse_time(parts[1])
    if start is None or end is None:
        return None
    return f"{start}-{end}"


def normalize_value(value: str, field_type: str) -> str | None:
    if field_type == "date":
        return parse_date(value)
    if field_type == "datetime":
        parsed = parse_datetime(value, allow_date_only=True)
        return parsed.strftime(DATETIME_FORMAT) if parsed is not None else None
    if field_type == "time":
        return parse_time(value) or parse_time_range(value)
    if field_type == "time_range":
        return parse_time_range(value)
    return value


def update_report(report: FileReport, field_name: str, original: str, normalized: str | None) -> None:
    field_report = report.fields.setdefault(field_name, FieldReport(field=field_name))
    if original == "":
        field_report.empty += 1
        return
    if normalized is None:
        field_report.failed += 1
        if len(field_report.examples) < 10:
            field_report.examples.append({"value": original})
        return
    if normalized != original:
        field_report.changed += 1
        if len(field_report.examples) < 10:
            field_report.examples.append({"from": original, "to": normalized})
    else:
        field_report.unchanged += 1


def normalize_csv(content: bytes, file_name: str) -> tuple[bytes, FileReport]:
    text = content.decode("utf-8-sig")
    newline = "\r\n" if "\r\n" in text else "\n"
    reader = csv.DictReader(io.StringIO(text))
    headers = reader.fieldnames or []
    rows = list(reader)
    report = FileReport(file=file_name)

    field_types = {header: classify_field(header) for header in headers}
    for row in rows:
        for header, field_type in field_types.items():
            if not field_type:
                continue
            original = (row.get(header) or "").strip()
            normalized = normalize_value(original, field_type) if original else ""
            update_report(report, base_field_name(header), original, normalized)
            if original and normalized is not None:
                row[header] = normalized

    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=headers, lineterminator=newline)
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue().encode("utf-8"), report


def normalize_json_value(value: Any, key: str, report: FileReport, path: str) -> Any:
    if isinstance(value, dict):
        return {item_key: normalize_json_value(item_value, item_key, report, f"{path}.{item_key}" if path else item_key)
                for item_key, item_value in value.items()}
    if isinstance(value, list):
        return [normalize_json_value(item, key, report, f"{path}[{index}]") for index, item in enumerate(value)]
    if isinstance(value, str):
        field_type = classify_field(key)
        if not field_type:
            return value
        original = value.strip()
        normalized = normalize_value(original, field_type) if original else ""
        update_report(report, path or key, original, normalized)
        return normalized if original and normalized is not None else value
    return value


def normalize_json(content: bytes, file_name: str) -> tuple[bytes, FileReport]:
    text = content.decode("utf-8-sig")
    data = json.loads(text)
    report = FileReport(file=file_name)
    normalized = normalize_json_value(data, "", report, "")
    return (json.dumps(normalized, ensure_ascii=False, indent=2) + "\n").encode("utf-8"), report


def copy_zip_info(info: zipfile.ZipInfo) -> zipfile.ZipInfo:
    copied = zipfile.ZipInfo(info.filename, info.date_time)
    copied.compress_type = info.compress_type
    copied.comment = info.comment
    copied.extra = info.extra
    copied.internal_attr = info.internal_attr
    copied.external_attr = info.external_attr
    return copied


def normalize_zip(input_path: Path, output_path: Path) -> list[FileReport]:
    reports: list[FileReport] = []
    with zipfile.ZipFile(input_path, "r") as src, zipfile.ZipFile(output_path, "w") as dst:
        for info in src.infolist():
            if info.is_dir():
                dst.writestr(copy_zip_info(info), b"")
                continue
            content = src.read(info.filename)
            lower_name = info.filename.lower()
            if lower_name.endswith(".csv"):
                content, report = normalize_csv(content, info.filename)
                reports.append(report)
            elif lower_name.endswith(".json"):
                content, report = normalize_json(content, info.filename)
                reports.append(report)
            dst.writestr(copy_zip_info(info), content)
    return reports


def report_to_json(reports: list[FileReport]) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for report in reports:
        fields = []
        for field_report in report.fields.values():
            if (
                field_report.changed == 0
                and field_report.unchanged == 0
                and field_report.empty == 0
                and field_report.failed == 0
            ):
                continue
            fields.append(copy.deepcopy(field_report.__dict__))
        if fields:
            output.append({"file": report.file, "fields": fields})
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="统一 QSL 数据包内日期时间格式")
    parser.add_argument("input_zip", type=Path, help="原始 zip 路径")
    parser.add_argument("-o", "--output", type=Path, help="输出 zip 路径")
    parser.add_argument("--report", type=Path, help="输出 JSON 报告路径")
    args = parser.parse_args()

    input_path = args.input_zip
    output_path = args.output or input_path.with_name(f"{input_path.stem}-normalized-datetime{input_path.suffix}")
    report_path = args.report or output_path.with_suffix(".report.json")

    reports = normalize_zip(input_path, output_path)
    report_payload = {
        "input": str(input_path),
        "output": str(output_path),
        "standardFormats": {
            "date": "yyyy-MM-dd",
            "datetime": "yyyy-MM-dd HH:mm:ss",
            "time": "HHmm",
            "timeRange": "HHmm-HHmm",
        },
        "files": report_to_json(reports),
    }
    report_path.write_text(json.dumps(report_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report_payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
