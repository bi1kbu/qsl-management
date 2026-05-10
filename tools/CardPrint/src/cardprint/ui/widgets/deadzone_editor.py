from __future__ import annotations

from PySide6.QtCore import Signal
from PySide6.QtWidgets import QDoubleSpinBox, QFormLayout, QWidget


class DeadzoneEditor(QWidget):
    valueChanged = Signal()

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.top = self._spin()
        self.right = self._spin()
        self.bottom = self._spin()
        self.left = self._spin()

        form = QFormLayout(self)
        form.addRow("上死区(mm)", self.top)
        form.addRow("右死区(mm)", self.right)
        form.addRow("下死区(mm)", self.bottom)
        form.addRow("左死区(mm)", self.left)

    def _spin(self) -> QDoubleSpinBox:
        spin = QDoubleSpinBox(self)
        spin.setRange(0, 999)
        spin.setDecimals(2)
        spin.setSingleStep(0.1)
        spin.valueChanged.connect(lambda _: self.valueChanged.emit())
        return spin

    def value(self) -> dict[str, float]:
        return {
            "top": float(self.top.value()),
            "right": float(self.right.value()),
            "bottom": float(self.bottom.value()),
            "left": float(self.left.value()),
        }

    def set_value(self, data: dict[str, float]) -> None:
        self.top.setValue(float(data.get("top", 0)))
        self.right.setValue(float(data.get("right", 0)))
        self.bottom.setValue(float(data.get("bottom", 0)))
        self.left.setValue(float(data.get("left", 0)))
