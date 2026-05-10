from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

from cardprint.core.errors import CardPrintError
from cardprint.core.models import PrintJob
from cardprint.core.preset_service import load_json, load_preset, validate_preset_file
from cardprint.preview.preview_renderer import render_preview_payload
from cardprint.printer.printer_service import PrinterService


def _print_json(payload: dict[str, Any]) -> None:
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    encoding = getattr(sys.stdout, "encoding", None) or "utf-8"
    try:
        sys.stdout.write(text + "\n")
    except UnicodeEncodeError:
        # 某些控制台编码（如 GBK）无法输出部分字符（例如 ⬛），
        # 回退为可编码的 \uXXXX 转义，避免命令失败。
        safe_text = text.encode(encoding, errors="backslashreplace").decode(encoding)
        sys.stdout.write(safe_text + "\n")


def _ok(data: dict[str, Any]) -> dict[str, Any]:
    return {"ok": True, "data": data}


def _parse_row_value(row: str | None) -> dict[str, Any]:
    if not row:
        return {}
    try:
        parsed = json.loads(row)
    except json.JSONDecodeError as exc:
        raise CardPrintError(
            code="INVALID_ROW_JSON",
            message="--row 需要合法 JSON 对象。",
            details={"line": exc.lineno, "column": exc.colno},
        ) from exc
    if not isinstance(parsed, dict):
        raise CardPrintError(
            code="INVALID_ROW_JSON_TYPE",
            message="--row 必须是 JSON 对象。",
            details={"type": type(parsed).__name__},
        )
    return parsed


def _cmd_printer_list(args: argparse.Namespace) -> dict[str, Any]:
    service = PrinterService()
    return _ok(service.list_printers())


def _cmd_printer_papers(args: argparse.Namespace) -> dict[str, Any]:
    service = PrinterService()
    return _ok(service.list_papers(args.printer))


def _cmd_calibration_print_cross(args: argparse.Namespace) -> dict[str, Any]:
    service = PrinterService()
    data = service.print_calibration_cross(
        printer_name=args.printer,
        paper_name=args.paper,
        width_mm=float(args.width_mm),
        height_mm=float(args.height_mm),
        cross_offset_x_mm=float(args.cross_offset_x_mm),
        cross_offset_y_mm=float(args.cross_offset_y_mm),
    )
    return _ok(data)


def _cmd_preset_validate(args: argparse.Namespace) -> dict[str, Any]:
    data = validate_preset_file(args.preset)
    return _ok(data)


def _cmd_render_preview(args: argparse.Namespace) -> dict[str, Any]:
    preset = load_preset(args.preset)
    row_data = _parse_row_value(args.row)
    return render_preview_payload(preset, row_data)


def _cmd_print_run(args: argparse.Namespace) -> dict[str, Any]:
    raw = load_json(args.job)
    if not isinstance(raw, dict):
        raise CardPrintError(
            code="INVALID_JOB_JSON",
            message="job 文件必须是 JSON 对象。",
            details={"job": args.job},
        )
    job = PrintJob.from_dict(raw)
    service = PrinterService()
    data = service.print_job(job, cwd=Path(args.job).parent.resolve())
    return _ok(data)


def _cmd_ui_calibrator(args: argparse.Namespace) -> dict[str, Any]:
    from cardprint.ui.calibrator_app import main as calibrator_main

    return_code = calibrator_main()
    return _ok({"return_code": return_code})


def _cmd_ui_printer(args: argparse.Namespace) -> dict[str, Any]:
    from cardprint.ui.printer_app import main as printer_main

    return_code = printer_main()
    return _ok({"return_code": return_code})


def _cmd_ui_online(args: argparse.Namespace) -> dict[str, Any]:
    from cardprint.ui.online_print_app import main as online_main

    return_code = online_main()
    return _ok({"return_code": return_code})


def _cmd_ui_halo(args: argparse.Namespace) -> dict[str, Any]:
    from cardprint.ui.halo_issue_app import main as halo_main

    return_code = halo_main()
    return _ok({"return_code": return_code})


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cardprint")
    sub = parser.add_subparsers(dest="command")

    parser_printer = sub.add_parser("printer")
    printer_sub = parser_printer.add_subparsers(dest="printer_command")

    printer_list = printer_sub.add_parser("list")
    printer_list.set_defaults(func=_cmd_printer_list)

    printer_papers = printer_sub.add_parser("papers")
    printer_papers.add_argument("--printer", required=True)
    printer_papers.set_defaults(func=_cmd_printer_papers)

    parser_calibrate = sub.add_parser("calibrate")
    calibrate_sub = parser_calibrate.add_subparsers(dest="calibrate_command")
    calibrate_print_cross = calibrate_sub.add_parser("print-cross")
    calibrate_print_cross.add_argument("--printer", required=True)
    calibrate_print_cross.add_argument("--paper", required=True)
    calibrate_print_cross.add_argument("--width-mm", type=float, default=85.6)
    calibrate_print_cross.add_argument("--height-mm", type=float, default=54.0)
    calibrate_print_cross.add_argument("--cross-offset-x-mm", type=float, default=0.0)
    calibrate_print_cross.add_argument("--cross-offset-y-mm", type=float, default=0.0)
    calibrate_print_cross.set_defaults(func=_cmd_calibration_print_cross)

    parser_preset = sub.add_parser("preset")
    preset_sub = parser_preset.add_subparsers(dest="preset_command")
    preset_validate = preset_sub.add_parser("validate")
    preset_validate.add_argument("--preset", required=True)
    preset_validate.set_defaults(func=_cmd_preset_validate)

    parser_render = sub.add_parser("render")
    render_sub = parser_render.add_subparsers(dest="render_command")
    render_preview = render_sub.add_parser("preview")
    render_preview.add_argument("--preset", required=True)
    render_preview.add_argument("--row", required=False, default="{}")
    render_preview.set_defaults(func=_cmd_render_preview)

    parser_print = sub.add_parser("print")
    print_sub = parser_print.add_subparsers(dest="print_command")
    print_run = print_sub.add_parser("run")
    print_run.add_argument("--job", required=True)
    print_run.set_defaults(func=_cmd_print_run)

    parser_ui = sub.add_parser("ui")
    ui_sub = parser_ui.add_subparsers(dest="ui_command")
    ui_calibrator = ui_sub.add_parser("calibrator")
    ui_calibrator.set_defaults(func=_cmd_ui_calibrator)
    ui_printer = ui_sub.add_parser("printer")
    ui_printer.set_defaults(func=_cmd_ui_printer)
    ui_online = ui_sub.add_parser("online")
    ui_online.set_defaults(func=_cmd_ui_online)
    ui_halo = ui_sub.add_parser("halo")
    ui_halo.set_defaults(func=_cmd_ui_halo)

    return parser


def run(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "func"):
        parser.print_help()
        return 2

    try:
        payload = args.func(args)
        _print_json(payload)
        return 0
    except CardPrintError as exc:
        _print_json(exc.to_payload())
        return exc.exit_code
    except Exception as exc:  # pragma: no cover
        generic = CardPrintError(
            code="UNEXPECTED_ERROR",
            message="发生未处理异常。",
            details={"error": str(exc)},
            exit_code=1,
        )
        _print_json(generic.to_payload())
        return 1


def main() -> None:
    raise SystemExit(run(sys.argv[1:]))


if __name__ == "__main__":
    main()
