from __future__ import annotations

from cardprint.core.models import Calibration, OriginOffset
from cardprint.core.transform import (
    normalize_rotation,
    rotate_point_cw,
    transform_logical_to_physical,
)


def test_normalize_rotation() -> None:
    assert normalize_rotation("right", 90) == 90
    assert normalize_rotation("left", 90) == 270
    assert normalize_rotation("left", 270) == 90


def test_rotate_point_cw_90() -> None:
    point = rotate_point_cw(x_mm=10, y_mm=5, width_mm=100, height_mm=60, cw_degree=90)
    assert point.x == 55
    assert point.y == 10


def test_rotate_point_cw_180() -> None:
    point = rotate_point_cw(x_mm=10, y_mm=5, width_mm=100, height_mm=60, cw_degree=180)
    assert point.x == 90
    assert point.y == 55


def test_rotate_point_cw_270() -> None:
    point = rotate_point_cw(x_mm=10, y_mm=5, width_mm=100, height_mm=60, cw_degree=270)
    assert point.x == 5
    assert point.y == 90


def test_transform_with_origin_offset() -> None:
    calibration = Calibration(
        rotation_direction="right",
        rotation_degree=90,
        layout_rotation_enabled=True,
        origin_offset_mm=OriginOffset(x=2, y=3),
    )
    point = transform_logical_to_physical(
        x_mm=10,
        y_mm=5,
        paper_width_mm=100,
        paper_height_mm=60,
        calibration=calibration,
    )
    assert point.x == 57
    assert point.y == 13


def test_transform_without_layout_rotation() -> None:
    calibration = Calibration(
        rotation_direction="right",
        rotation_degree=90,
        origin_offset_mm=OriginOffset(x=2, y=3),
    )
    point = transform_logical_to_physical(
        x_mm=10,
        y_mm=5,
        paper_width_mm=100,
        paper_height_mm=60,
        calibration=calibration,
    )
    assert point.x == 12
    assert point.y == 8
