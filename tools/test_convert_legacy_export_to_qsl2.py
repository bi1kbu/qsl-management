import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))

import convert_legacy_export_to_qsl2 as converter


class ConvertLegacyExportToQsl2Test(unittest.TestCase):
    def test_filters_station_card_placeholders_before_receive_aggregation(self) -> None:
        rows = [
            {
                "id": "qsl-station-card-v1",
                "callSign": "",
                "cardType": "QSO",
                "sceneType": "QSO",
                "qsoRecordName": "",
                "cardReceived": "true",
                "receivedRecordCodes": "R99-20260519",
            },
            {
                "id": "C1",
                "callSign": "BI1AAA",
                "cardType": "QSO",
                "sceneType": "QSO",
                "qsoRecordName": "qso-1",
                "cardReceived": "true",
                "receivedRecordCodes": "R1-20260519",
            },
        ]

        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "legacy.zip"
            output_path = Path(temp_dir) / "converted.zip"
            with zipfile.ZipFile(input_path, "w", compression=zipfile.ZIP_DEFLATED) as source_zip:
                source_zip.writestr("card-record.csv", converter.render_csv(converter.CARD_HEADER, rows))

            summary = converter.convert_zip(input_path, output_path)

            with zipfile.ZipFile(output_path, "r") as target_zip:
                _, card_rows = converter.read_csv_rows(target_zip.read("card-record.csv").decode("utf-8"))
                _, receive_rows = converter.read_csv_rows(target_zip.read("receive-record.csv").decode("utf-8"))

        self.assertEqual(summary.removed_station_card_placeholders, 1)
        self.assertEqual([row["id"] for row in card_rows], ["C1"])
        self.assertEqual([row["id"] for row in receive_rows], ["R1-20260519"])


if __name__ == "__main__":
    unittest.main()
