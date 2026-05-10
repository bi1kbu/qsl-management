from __future__ import annotations

from pathlib import Path
from typing import Any

from cardprint.core.layout_engine import build_layout_items
from cardprint.core.models import PrintJob
from cardprint.core.preset_service import load_preset
from cardprint.printer.win32_adapter import Win32PrinterAdapter


class PrinterService:
    def __init__(self, adapter: Win32PrinterAdapter | None = None) -> None:
        self.adapter = adapter or Win32PrinterAdapter()

    def list_printers(self) -> dict[str, Any]:
        names = self.adapter.list_printers()
        return {
            "count": len(names),
            "items": names,
            "dry_run": self.adapter.is_dry_run,
        }

    def list_papers(self, printer_name: str) -> dict[str, Any]:
        options = self.adapter.list_paper_options(printer_name)
        names = [str(item.get("name", "")).strip() for item in options if str(item.get("name", "")).strip()]
        return {
            "printer_name": printer_name,
            "count": len(names),
            "items": names,
            "options": options,
            "dry_run": self.adapter.is_dry_run,
        }

    def print_calibration_cross(
        self,
        printer_name: str,
        paper_name: str,
        width_mm: float,
        height_mm: float,
        cross_offset_x_mm: float = 0.0,
        cross_offset_y_mm: float = 0.0,
    ) -> dict[str, Any]:
        return self.adapter.print_calibration_cross(
            printer_name=printer_name,
            paper_name=paper_name,
            width_mm=width_mm,
            height_mm=height_mm,
            cross_offset_x_mm=cross_offset_x_mm,
            cross_offset_y_mm=cross_offset_y_mm,
        )

    def print_job(self, job: PrintJob, cwd: str | Path | None = None) -> dict[str, Any]:
        preset_path = job.preset_resolved_path(cwd)
        preset = load_preset(preset_path)

        row_layouts = [build_layout_items(preset, row) for row in job.rows]
        paper_name = job.paper_name or preset.paper.name

        adapter_result = self.adapter.print_layout_rows(
            printer_name=job.printer_name,
            paper_name=paper_name,
            rows=row_layouts,
            job_name=f"CardPrint:{preset.name}",
        )
        return {
            "job": job.to_dict(),
            "preset_name": preset.name,
            "preset_path": str(preset_path),
            "adapter": adapter_result,
        }
