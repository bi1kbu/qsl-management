from __future__ import annotations

from typing import Tuple

from .models import Calibration, PointMM


def normalize_rotation(direction: str, degree: int) -> int:
    degree = degree % 360
    if direction == "right":
        return degree
    if direction == "left":
        return (360 - degree) % 360
    raise ValueError(f"Unsupported direction: {direction}")


def get_rotated_page_size(width_mm: float, height_mm: float, cw_degree: int) -> Tuple[float, float]:
    if cw_degree in {0, 180}:
        return width_mm, height_mm
    if cw_degree in {90, 270}:
        return height_mm, width_mm
    raise ValueError(f"Unsupported cw_degree: {cw_degree}")


def rotate_point_cw(x_mm: float, y_mm: float, width_mm: float, height_mm: float, cw_degree: int) -> PointMM:
    if cw_degree == 0:
        return PointMM(x=x_mm, y=y_mm)
    if cw_degree == 90:
        return PointMM(x=height_mm - y_mm, y=x_mm)
    if cw_degree == 180:
        return PointMM(x=width_mm - x_mm, y=height_mm - y_mm)
    if cw_degree == 270:
        return PointMM(x=y_mm, y=width_mm - x_mm)
    raise ValueError(f"Unsupported cw_degree: {cw_degree}")


def transform_logical_to_physical(
    x_mm: float,
    y_mm: float,
    paper_width_mm: float,
    paper_height_mm: float,
    calibration: Calibration,
) -> PointMM:
    if not calibration.layout_rotation_enabled:
        return PointMM(
            x=x_mm + calibration.origin_offset_mm.x,
            y=y_mm + calibration.origin_offset_mm.y,
        )

    cw_degree = normalize_rotation(
        direction=calibration.rotation_direction,
        degree=calibration.rotation_degree,
    )
    rotated = rotate_point_cw(
        x_mm=x_mm,
        y_mm=y_mm,
        width_mm=paper_width_mm,
        height_mm=paper_height_mm,
        cw_degree=cw_degree,
    )
    return PointMM(
        x=rotated.x + calibration.origin_offset_mm.x,
        y=rotated.y + calibration.origin_offset_mm.y,
    )
