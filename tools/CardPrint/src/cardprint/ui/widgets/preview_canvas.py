from __future__ import annotations

from typing import Any

from PySide6.QtCore import QPoint, QPointF, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import QWidget


class PreviewCanvas(QWidget):
    fieldMoved = Signal(str, float, float)
    fieldAreaChanged = Signal(str, float, float)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._scene: dict[str, Any] = {}
        self._editable = False
        self._page_left = 0.0
        self._page_top = 0.0
        self._scale = 1.0
        self._paper_w_mm = 0.0
        self._paper_h_mm = 0.0
        self._printable_bounds = {"left": 0.0, "top": 0.0, "right": 0.0, "bottom": 0.0}
        self._field_geometries: list[dict[str, Any]] = []
        self._drag_mode: str | None = None
        self._drag_key: str = ""
        self._drag_offset_x_mm = 0.0
        self._drag_offset_y_mm = 0.0
        self.setMinimumSize(380, 240)
        self.setMouseTracking(True)

    def set_scene(self, scene: dict[str, Any]) -> None:
        self._scene = scene
        self.update()

    def set_editable(self, editable: bool) -> None:
        self._editable = bool(editable)
        self.setCursor(Qt.ArrowCursor)

    def _pt_to_mm(self, pt: float) -> float:
        return float(pt) * 25.4 / 72.0

    def _line_height_mm(self, font_size_pt: float) -> float:
        return max(0.1, self._pt_to_mm(font_size_pt) * 1.2)

    def _estimate_text_width_mm(self, text: str, font_size_pt: float) -> float:
        if not text:
            return self._pt_to_mm(font_size_pt) * 3.0
        return max(4.0, len(text) * self._pt_to_mm(font_size_pt) * 0.55)

    def _draw_preview_text(
        self,
        painter: QPainter,
        x: float,
        y: float,
        text: str,
        font_size_pt: float,
        digit_raise_ratio: float,
        scale: float,
    ) -> None:
        digit_raise_px = self._pt_to_mm(font_size_pt) * scale * max(0.0, digit_raise_ratio)
        if digit_raise_px <= 0 or not any(ch.isdigit() for ch in text):
            painter.drawText(QPointF(x, y), text)
            return
        cursor_x = x
        metrics = painter.fontMetrics()
        for ch in text:
            painter.drawText(QPointF(cursor_x, y - digit_raise_px if ch.isdigit() else y), ch)
            cursor_x += float(metrics.horizontalAdvance(ch))

    def _to_px(self, mm_x: float, mm_y: float) -> tuple[float, float]:
        return self._page_left + (mm_x * self._scale), self._page_top + (mm_y * self._scale)

    def _to_mm(self, pos: QPoint | QPointF) -> tuple[float, float]:
        x_mm = (float(pos.x()) - self._page_left) / max(1e-6, self._scale)
        y_mm = (float(pos.y()) - self._page_top) / max(1e-6, self._scale)
        return x_mm, y_mm

    def _clamp(self, value: float, min_value: float, max_value: float) -> float:
        return max(min_value, min(max_value, value))

    def _get_field_box_mm(self, field: dict[str, Any]) -> tuple[float, float]:
        x_mm = float(field.get("x_mm", 0.0))
        y_mm = float(field.get("y_mm", 0.0))
        font_size_pt = float(field.get("font_size_pt", 11))
        width_mm = float(field.get("print_width_mm", 0.0))
        height_mm = float(field.get("print_height_mm", 0.0))
        if width_mm <= 0:
            sample_text = str(field.get("sample_text", ""))
            width_mm = self._estimate_text_width_mm(sample_text, font_size_pt)
        if height_mm <= 0:
            height_mm = self._line_height_mm(font_size_pt)
        width_limit = max(0.0, self._paper_w_mm - x_mm)
        height_limit = max(0.0, self._paper_h_mm - y_mm)
        return min(width_mm, width_limit), min(height_mm, height_limit)

    def _hit_field(self, pos: QPoint | QPointF) -> tuple[str, str] | None:
        x = float(pos.x())
        y = float(pos.y())
        for geo in reversed(self._field_geometries):
            handle = geo["handle_px"]
            if handle.adjusted(-4, -4, 4, 4).contains(x, y):
                return "resize", str(geo["key"])
        for geo in reversed(self._field_geometries):
            box = geo["box_px"]
            anchor = geo["anchor_px"]
            if box.adjusted(-3, -3, 3, 3).contains(x, y):
                return "move", str(geo["key"])
            dx = x - float(anchor.x())
            dy = y - float(anchor.y())
            if (dx * dx + dy * dy) <= 49.0:  # 半径 7 px
                return "move", str(geo["key"])
        return None

    def _find_geo(self, key: str) -> dict[str, Any] | None:
        target = key.strip().casefold()
        for geo in self._field_geometries:
            if str(geo.get("key", "")).strip().casefold() == target:
                return geo
        return None

    def paintEvent(self, event) -> None:  # noqa: N802
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing, True)
        painter.fillRect(self.rect(), QColor("#F4F6F8"))

        paper = self._scene.get("paper_mm") or {}
        width_mm = float(paper.get("width", 85.6))
        height_mm = float(paper.get("height", 54.0))
        if width_mm <= 0 or height_mm <= 0:
            return

        margin = 16
        avail_w = max(1, self.width() - margin * 2)
        avail_h = max(1, self.height() - margin * 2)
        scale = min(avail_w / width_mm, avail_h / height_mm)

        page_w = width_mm * scale
        page_h = height_mm * scale
        page_left = (self.width() - page_w) / 2
        page_top = (self.height() - page_h) / 2

        page_rect = QRectF(page_left, page_top, page_w, page_h)
        painter.setPen(QPen(QColor("#1F2937"), 1.0))
        painter.setBrush(QColor("#FFFFFF"))
        painter.drawRect(page_rect)

        self._page_left = page_left
        self._page_top = page_top
        self._scale = scale
        self._paper_w_mm = width_mm
        self._paper_h_mm = height_mm
        bounds = self._scene.get("printable_bounds_mm") or {}
        self._printable_bounds = {
            "left": float(bounds.get("left", 0.0)),
            "top": float(bounds.get("top", 0.0)),
            "right": float(bounds.get("right", width_mm)),
            "bottom": float(bounds.get("bottom", height_mm)),
        }

        self._draw_deadzone(painter, page_left, page_top, scale, width_mm, height_mm)
        self._draw_items(painter, page_left, page_top, scale)
        self._draw_field_boxes(painter, page_left, page_top, scale)

    def _draw_deadzone(
        self,
        painter: QPainter,
        page_left: float,
        page_top: float,
        scale: float,
        width_mm: float,
        height_mm: float,
    ) -> None:
        dz = self._scene.get("deadzone_mm") or {}
        top = float(dz.get("top", 0))
        right = float(dz.get("right", 0))
        bottom = float(dz.get("bottom", 0))
        left = float(dz.get("left", 0))
        if top == 0 and right == 0 and bottom == 0 and left == 0:
            return

        dead_color = QColor(220, 38, 38, 65)
        painter.setPen(Qt.NoPen)
        painter.setBrush(dead_color)

        page_w = width_mm * scale
        page_h = height_mm * scale
        if top > 0:
            painter.drawRect(QRectF(page_left, page_top, page_w, top * scale))
        if bottom > 0:
            painter.drawRect(
                QRectF(page_left, page_top + page_h - bottom * scale, page_w, bottom * scale)
            )
        if left > 0:
            painter.drawRect(QRectF(page_left, page_top, left * scale, page_h))
        if right > 0:
            painter.drawRect(
                QRectF(page_left + page_w - right * scale, page_top, right * scale, page_h)
            )

    def _draw_items(self, painter: QPainter, page_left: float, page_top: float, scale: float) -> None:
        items = self._scene.get("items") or []
        painter.setPen(QPen(QColor("#0F172A"), 1.0))
        for item in items:
            x = float(item.get("logical_x_mm", 0)) * scale + page_left
            y = float(item.get("logical_y_mm", 0)) * scale + page_top
            render_type = str(item.get("render_type", "text")).strip().lower()
            label = str(item.get("label_zh") or item.get("key") or "").strip()
            line_index = int(item.get("line_index", 0))
            text = str(item.get("text", ""))
            if render_type == "qrcode":
                width_mm = float(item.get("print_width_mm", 0.0))
                height_mm = float(item.get("print_height_mm", 0.0))
                if width_mm > 0 and height_mm > 0:
                    rect = QRectF(x, y, width_mm * scale, height_mm * scale)
                    painter.setPen(QPen(QColor("#2563EB"), 1.0, Qt.DashLine))
                    painter.drawRect(rect)
                    painter.setPen(QPen(QColor("#0F172A"), 1.0))
                    painter.drawText(QPointF(x + 4, y + 14), f"{label}: 二维码")
                    continue
            if line_index == 0:
                display_text = f"{label}: {text}" if text.strip() else label
            else:
                display_text = text
            painter.drawEllipse(QPointF(x, y), 2, 2)
            digit_raise_ratio = float(item.get("digit_raise_ratio", 0.0) or 0.0)
            font_size_pt = float(item.get("font_size_pt", 11) or 11)
            self._draw_preview_text(
                painter,
                x + 4,
                y - 3,
                display_text,
                font_size_pt,
                digit_raise_ratio,
                scale,
            )

    def _draw_field_boxes(self, painter: QPainter, page_left: float, page_top: float, scale: float) -> None:
        self._field_geometries = []
        fields = self._scene.get("fields") or []
        if not isinstance(fields, list):
            return

        border_pen = QPen(QColor("#2563EB"), 1.0, Qt.DashLine)
        handle_brush = QColor("#2563EB")
        painter.setPen(border_pen)
        for field in fields:
            key = str(field.get("key", "")).strip()
            if not key:
                continue
            x_mm = float(field.get("x_mm", 0.0))
            y_mm = float(field.get("y_mm", 0.0))
            box_w_mm, box_h_mm = self._get_field_box_mm(field)
            left_px, top_px = self._to_px(x_mm, y_mm)
            box_px = QRectF(left_px, top_px, box_w_mm * scale, box_h_mm * scale)
            painter.drawRect(box_px)
            painter.drawEllipse(QPointF(left_px, top_px), 2.5, 2.5)
            handle_size = 7.0
            handle_px = QRectF(
                box_px.right() - handle_size / 2.0,
                box_px.bottom() - handle_size / 2.0,
                handle_size,
                handle_size,
            )
            painter.fillRect(handle_px, handle_brush)

            self._field_geometries.append(
                {
                    "key": key,
                    "x_mm": x_mm,
                    "y_mm": y_mm,
                    "box_w_mm": box_w_mm,
                    "box_h_mm": box_h_mm,
                    "anchor_px": QPointF(left_px, top_px),
                    "box_px": box_px,
                    "handle_px": handle_px,
                }
            )

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if not self._editable or event.button() != Qt.LeftButton:
            return super().mousePressEvent(event)
        hit = self._hit_field(event.position())
        if not hit:
            return super().mousePressEvent(event)
        mode, key = hit
        geo = self._find_geo(key)
        if geo is None:
            return super().mousePressEvent(event)

        self._drag_mode = mode
        self._drag_key = key
        mouse_x_mm, mouse_y_mm = self._to_mm(event.position())
        if mode == "move":
            self._drag_offset_x_mm = mouse_x_mm - float(geo["x_mm"])
            self._drag_offset_y_mm = mouse_y_mm - float(geo["y_mm"])
            self.setCursor(Qt.SizeAllCursor)
        else:
            self.setCursor(Qt.SizeFDiagCursor)
        event.accept()

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if not self._editable:
            return super().mouseMoveEvent(event)

        if self._drag_mode and self._drag_key:
            geo = self._find_geo(self._drag_key)
            if geo is None:
                return super().mouseMoveEvent(event)
            mouse_x_mm, mouse_y_mm = self._to_mm(event.position())
            bounds = self._printable_bounds
            if self._drag_mode == "move":
                box_w_mm = float(geo["box_w_mm"])
                box_h_mm = float(geo["box_h_mm"])
                new_x = mouse_x_mm - self._drag_offset_x_mm
                new_y = mouse_y_mm - self._drag_offset_y_mm
                new_x = self._clamp(new_x, float(bounds["left"]), float(bounds["right"]) - box_w_mm)
                new_y = self._clamp(new_y, float(bounds["top"]), float(bounds["bottom"]) - box_h_mm)
                self.fieldMoved.emit(self._drag_key, round(new_x, 2), round(new_y, 2))
            elif self._drag_mode == "resize":
                anchor_x = float(geo["x_mm"])
                anchor_y = float(geo["y_mm"])
                max_w = max(0.0, float(bounds["right"]) - anchor_x)
                max_h = max(0.0, float(bounds["bottom"]) - anchor_y)
                width_mm = self._clamp(mouse_x_mm - anchor_x, 0.0, max_w)
                height_mm = self._clamp(mouse_y_mm - anchor_y, 0.0, max_h)
                self.fieldAreaChanged.emit(self._drag_key, round(width_mm, 2), round(height_mm, 2))
            event.accept()
            return

        hit = self._hit_field(event.position())
        if hit is None:
            self.setCursor(Qt.ArrowCursor)
        elif hit[0] == "resize":
            self.setCursor(Qt.SizeFDiagCursor)
        else:
            self.setCursor(Qt.SizeAllCursor)
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if self._editable and event.button() == Qt.LeftButton:
            self._drag_mode = None
            self._drag_key = ""
            self._drag_offset_x_mm = 0.0
            self._drag_offset_y_mm = 0.0
            self.setCursor(Qt.ArrowCursor)
            event.accept()
            return
        super().mouseReleaseEvent(event)
