from __future__ import annotations

from typing import Any

from PySide6.QtWidgets import QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget


class DataEntryPanel(QWidget):
    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.table = QTableWidget(0, 2, self)
        self.table.setHorizontalHeaderLabels(["字段", "值"])
        layout = QVBoxLayout(self)
        layout.addWidget(self.table)

    def set_keys(self, keys: list[str]) -> None:
        self.table.setRowCount(0)
        for key in keys:
            row = self.table.rowCount()
            self.table.insertRow(row)
            self.table.setItem(row, 0, QTableWidgetItem(key))
            self.table.setItem(row, 1, QTableWidgetItem(""))

    def get_row(self) -> dict[str, Any]:
        data: dict[str, Any] = {}
        for row in range(self.table.rowCount()):
            key_item = self.table.item(row, 0)
            value_item = self.table.item(row, 1)
            key = key_item.text().strip() if key_item else ""
            if not key:
                continue
            data[key] = value_item.text() if value_item else ""
        return data

    def set_row(self, data: dict[str, Any]) -> None:
        self.set_keys(list(data.keys()))
        for row in range(self.table.rowCount()):
            key = self.table.item(row, 0).text()
            self.table.item(row, 1).setText(str(data.get(key, "")))
