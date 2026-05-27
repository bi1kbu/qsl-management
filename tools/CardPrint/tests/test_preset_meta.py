from __future__ import annotations

import json
from pathlib import Path

from cardprint.ui.online_print_app import _load_preset_meta


def test_load_preset_meta_skips_fixed_text_fields(tmp_path: Path) -> None:
    preset_path = tmp_path / "preset.json"
    preset_path.write_text(
        json.dumps(
            {
                "paper": {"name": "Card"},
                "preferred_printer": "Printer",
                "fields": [
                    {"key": "callsign", "label_zh": "呼号"},
                    {"key": "helper", "label_zh": "辅助说明", "fixed_text": "固定辅助说明"},
                ],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    meta = _load_preset_meta(str(preset_path))

    assert meta["field_keys"] == ["callsign"]
