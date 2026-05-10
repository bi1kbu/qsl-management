from __future__ import annotations


MM_PER_INCH = 25.4
PT_PER_INCH = 72.0


def mm_to_inch(mm: float) -> float:
    return mm / MM_PER_INCH


def inch_to_mm(inch: float) -> float:
    return inch * MM_PER_INCH


def mm_to_dots(mm: float, dpi: float) -> int:
    return int(round(mm_to_inch(mm) * dpi))


def pt_to_pixels(pt: float, dpi: float) -> int:
    # Win32 logical font height is negative pixel size for character height.
    return int(round((pt / PT_PER_INCH) * dpi))
