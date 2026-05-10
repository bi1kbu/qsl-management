from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

from cardprint.core.errors import CardPrintError


def run_cli_json(args: list[str], timeout_s: float = 30.0) -> dict[str, Any]:
    src_path = str(Path(__file__).resolve().parents[2])
    env = dict(os.environ)
    existing_pythonpath = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = src_path if not existing_pythonpath else f"{src_path}{os.pathsep}{existing_pythonpath}"
    env["PYTHONIOENCODING"] = "utf-8"

    cmd = [sys.executable, "-m", "cardprint.cli", *args]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=timeout_s,
        check=False,
        env=env,
    )
    stdout = proc.stdout.strip()
    stderr = proc.stderr.strip()
    if not stdout:
        raise CardPrintError(
            code="CLI_EMPTY_OUTPUT",
            message="CLI 没有返回输出。",
            details={"args": args, "stderr": stderr, "return_code": proc.returncode},
        )
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise CardPrintError(
            code="CLI_INVALID_JSON",
            message="CLI 返回的不是合法 JSON。",
            details={"args": args, "stdout": stdout, "stderr": stderr, "return_code": proc.returncode},
        ) from exc

    if proc.returncode != 0:
        if isinstance(payload, dict) and "error" in payload:
            error = payload["error"]
            raise CardPrintError(
                code=str(error.get("code", "CLI_ERROR")),
                message=str(error.get("message", "CLI 执行失败。")),
                details=error.get("details", {}),
            )
        raise CardPrintError(
            code="CLI_ERROR",
            message="CLI 执行失败。",
            details={"payload": payload, "stderr": stderr, "return_code": proc.returncode},
        )
    return payload
