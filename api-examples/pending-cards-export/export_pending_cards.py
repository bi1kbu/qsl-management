#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import base64
import csv
import json
import os
from typing import Any

import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="通过 Halo QSL 管理接口导出待发卡片为 CSV。"
    )
    parser.add_argument("--base-url", default="http://localhost:8090", help="Halo 站点地址")
    parser.add_argument(
        "--auth",
        choices=["basic", "bearer"],
        default="basic",
        help="鉴权方式：basic 或 bearer",
    )
    parser.add_argument("--username", default="admin", help="Basic Auth 用户名")
    parser.add_argument("--password", default="admin", help="Basic Auth 密码")
    parser.add_argument("--token", default=os.getenv("HALO_TOKEN", ""), help="Bearer Token")
    parser.add_argument("--operator", default="admin", help="X-Operator 请求头")
    parser.add_argument("--output", default="pending_cards.csv", help="输出 CSV 路径")
    parser.add_argument(
        "--include-deleted",
        action="store_true",
        help="包含逻辑删除记录（默认过滤 deleted=true）",
    )
    return parser.parse_args()


def build_headers(args: argparse.Namespace) -> dict[str, str]:
    headers = {
        "Accept": "application/json",
        "X-Operator": args.operator,
    }
    if args.auth == "basic":
        raw = f"{args.username}:{args.password}".encode("utf-8")
        headers["Authorization"] = "Basic " + base64.b64encode(raw).decode("ascii")
        return headers
    if not args.token.strip():
        raise ValueError("使用 bearer 鉴权时，必须提供 --token 或 HALO_TOKEN")
    headers["Authorization"] = f"Bearer {args.token.strip()}"
    return headers


def request_cards(base_url: str, headers: dict[str, str]) -> list[dict[str, Any]]:
    url = base_url.rstrip("/") + "/apis/qsl.admin/v1/qsl-card-records"
    resp = requests.get(url, headers=headers, timeout=30)
    if resp.status_code in (401, 403):
        raise PermissionError(
            f"鉴权失败（HTTP {resp.status_code}）。请检查账号权限或 token。"
        )
    resp.raise_for_status()
    data = resp.json()
    if not isinstance(data, list):
        raise TypeError(f"接口返回非列表结构，实际类型: {type(data).__name__}")
    return [item for item in data if isinstance(item, dict)]


def is_pending_card(card: dict[str, Any]) -> bool:
    return str(card.get("sentStatus", "")).upper() == "NOT_SENT"


def flatten_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (str, int, float, bool)):
        return str(value)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def resolve_fields(rows: list[dict[str, Any]]) -> list[str]:
    priority = [
        "id",
        "peerCallsign",
        "cardType",
        "cardDate",
        "cardTime",
        "timezone",
        "frequency",
        "mode",
        "sentStatus",
        "confirmStatus",
        "returnCardStatus",
        "productionStatus",
        "printCount",
        "qsoRecordId",
        "addressId",
        "name",
        "phone",
        "postcode",
        "address",
        "remark",
        "createdAt",
        "updatedAt",
    ]
    if not rows:
        return priority
    all_keys: set[str] = set()
    for row in rows:
        all_keys.update(row.keys())
    ordered = [key for key in priority if key in all_keys]
    tail = sorted([key for key in all_keys if key not in set(ordered)])
    return ordered + tail


def write_csv(path: str, rows: list[dict[str, Any]]) -> None:
    fields = resolve_fields(rows)
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({key: flatten_value(row.get(key)) for key in fields})


def main() -> int:
    args = parse_args()
    headers = build_headers(args)
    cards = request_cards(args.base_url, headers)
    pending_cards = [card for card in cards if is_pending_card(card)]
    if not args.include_deleted:
        pending_cards = [card for card in pending_cards if not bool(card.get("deleted", False))]
    write_csv(args.output, pending_cards)
    print(f"总卡片数: {len(cards)}")
    print(f"待发卡片数: {len(pending_cards)}")
    print(f"CSV 已生成: {os.path.abspath(args.output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
