from __future__ import annotations

import json
import urllib.parse
import urllib.request
from typing import Any

from .base import JsonDataSourcePlugin


class HttpJsonPlugin(JsonDataSourcePlugin):
    def __init__(self, endpoint: str, timeout: float = 8.0) -> None:
        self._endpoint = endpoint
        self._timeout = timeout

    def name(self) -> str:
        return "http_json"

    def fetch(self, query: dict[str, Any]) -> list[dict[str, Any]]:
        params = urllib.parse.urlencode(query, doseq=True)
        url = self._endpoint if not params else f"{self._endpoint}?{params}"
        req = urllib.request.Request(url=url, method="GET")
        with urllib.request.urlopen(req, timeout=self._timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        if isinstance(data, dict):
            rows = data.get("rows")
            if isinstance(rows, list):
                return [item for item in rows if isinstance(item, dict)]
        return []
