from __future__ import annotations

import json
from pathlib import Path

from cardprint.cli import build_parser, run


def _sample_preset() -> dict:
    return {
        "version": "1.0",
        "name": "cli-test",
        "paper": {"name": "Card", "width_mm": 85.6, "height_mm": 54.0},
        "calibration": {
            "rotation_direction": "right",
            "rotation_degree": 90,
            "origin_offset_mm": {"x": 0, "y": 0},
            "deadzone_mm": {"top": 1, "right": 1, "bottom": 1, "left": 1},
        },
        "fields": [
            {
                "key": "name",
                "label_zh": "姓名",
                "x_mm": 10,
                "y_mm": 10,
                "font_family": "SimSun",
                "font_size_pt": 11,
                "bold": False,
                "italic": False,
                "max_len": 20,
            }
        ],
        "ui_schema": [],
    }


def test_cli_printer_list(capsys) -> None:
    code = run(["printer", "list"])
    output = capsys.readouterr().out
    payload = json.loads(output)
    assert code == 0
    assert payload["ok"] is True
    assert "items" in payload["data"]


def test_cli_preset_validate(tmp_path: Path, capsys) -> None:
    preset_path = tmp_path / "preset.json"
    preset_path.write_text(json.dumps(_sample_preset(), ensure_ascii=False), encoding="utf-8")
    code = run(["preset", "validate", "--preset", str(preset_path)])
    output = capsys.readouterr().out
    payload = json.loads(output)
    assert code == 0
    assert payload["ok"] is True
    assert payload["data"]["name"] == "cli-test"


def test_cli_render_preview(tmp_path: Path, capsys) -> None:
    preset_path = tmp_path / "preset.json"
    preset_path.write_text(json.dumps(_sample_preset(), ensure_ascii=False), encoding="utf-8")
    code = run(
        [
            "render",
            "preview",
            "--preset",
            str(preset_path),
            "--row",
            json.dumps({"name": "张三"}, ensure_ascii=False),
        ]
    )
    output = capsys.readouterr().out
    payload = json.loads(output)
    assert code == 0
    assert payload["ok"] is True
    assert payload["scene"]["items"][0]["text"] == "张三"


def test_cli_print_run(tmp_path: Path, capsys) -> None:
    preset_path = tmp_path / "preset.json"
    preset_path.write_text(json.dumps(_sample_preset(), ensure_ascii=False), encoding="utf-8")
    job_path = tmp_path / "job.json"
    job_path.write_text(
        json.dumps(
            {
                "preset_path": str(preset_path),
                "rows": [{"name": "张三"}],
                "printer_name": "MockPrinter",
                "paper_name": "CustomCard",
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    code = run(["print", "run", "--job", str(job_path)])
    output = capsys.readouterr().out
    payload = json.loads(output)
    assert code == 0
    assert payload["ok"] is True
    assert payload["data"]["adapter"]["rows"][0]["status"] == "success"


def test_cli_ui_online_registered() -> None:
    parser = build_parser()
    args = parser.parse_args(["ui", "online"])
    assert args.ui_command == "online"
    assert callable(args.func)
