from __future__ import annotations

from cardprint.online import bridge_service as bs
from cardprint.ui.online_print_app import OnlineConfigPage, OnlineDatasetPage
from cardprint.ui import online_print_app as app


class FakeLineEdit:
    def __init__(self, text: str = "") -> None:
        self._text = text

    def text(self) -> str:
        return self._text

    def setText(self, text: str) -> None:
        self._text = text


class FakeComboBox:
    def __init__(self, data: str = "") -> None:
        self._data = data

    def currentData(self) -> str:
        return self._data


def test_build_qrcode_url_uses_short_path_mapping_for_offline_eyeball() -> None:
    cfg = bs.normalize_bridge_config({"base_url": "https://example.test"})
    page = OnlineDatasetPage.__new__(OnlineDatasetPage)

    url = page._build_qrcode_url(
        {
            "metadata": {"name": "C1001"},
            "spec": {"callSign": "BI1KBU", "cardType": "EYEBALL", "sceneType": "EYEBALL"},
        },
        cfg,
    )

    assert url == "https://example.test/EYEBALL/C1001"


def test_build_qrcode_url_uses_receipt_path_mapping_for_online_eyeball() -> None:
    cfg = bs.normalize_bridge_config({"base_url": "https://example.test"})
    page = OnlineDatasetPage.__new__(OnlineDatasetPage)

    url = page._build_qrcode_url(
        {
            "metadata": {"name": "C1002"},
            "spec": {"callSign": "BI1KBU", "cardType": "EYEBALL", "sceneType": "ONLINE_EYEBALL"},
        },
        cfg,
    )

    assert url == "https://example.test/rp/C1002?cs=BI1KBU"


def test_build_qrcode_url_uses_short_path_mapping_for_receipt() -> None:
    cfg = bs.normalize_bridge_config({"base_url": "https://example.test"})
    page = OnlineDatasetPage.__new__(OnlineDatasetPage)

    url = page._build_qrcode_url(
        {
            "metadata": {"name": "C1003"},
            "spec": {"callSign": "BI1KBU", "cardType": "QSO", "sceneType": "QSO"},
        },
        cfg,
    )

    assert url == "https://example.test/rp/C1003?cs=BI1KBU"


def test_config_page_collects_configurable_qrcode_path_mappings() -> None:
    page = OnlineConfigPage.__new__(OnlineConfigPage)
    page.qrcode_offline_eyeball_path_edit = FakeLineEdit("EYEBALL")
    page.qrcode_online_eyeball_path_edit = FakeLineEdit("/online")
    page.qrcode_receipt_path_edit = FakeLineEdit("rp")

    mappings = page._qrcode_path_mappings_from_form()
    normalized = bs.normalize_bridge_config({"qrcode": {"path_mappings": mappings}})

    assert normalized["qrcode"]["path_mappings"][bs.PUBLIC_EYEBALL_ENDPOINT] == "/EYEBALL"
    assert normalized["qrcode"]["path_mappings"][bs.PUBLIC_ONLINE_EYEBALL_ENDPOINT] == "/online"
    assert normalized["qrcode"]["path_mappings"][bs.PUBLIC_RECEIPT_ENDPOINT] == "/rp"


def test_config_page_only_fills_blank_sender_fields() -> None:
    page = OnlineConfigPage.__new__(OnlineConfigPage)
    name_edit = FakeLineEdit("本地姓名")
    phone_edit = FakeLineEdit("")

    page._fill_sender_if_blank(name_edit, "远端姓名")
    page._fill_sender_if_blank(phone_edit, "远端电话")

    assert name_edit.text() == "本地姓名"
    assert phone_edit.text() == "远端电话"


def test_offline_card_page_defaults_post_card_status_checked() -> None:
    page = OnlineDatasetPage.__new__(OnlineDatasetPage)
    page.dataset = "cards"
    page.card_business = app.CARD_BUSINESS_OFFLINE
    mapped_row = {"postCardStatus": "", "returnCardStatus": "", "UTC": "⬛", "UTC+8": ""}

    page._apply_business_defaults_to_mapped_row(mapped_row)

    assert mapped_row["postCardStatus"] == "⬛"
    assert mapped_row["returnCardStatus"] == ""
    assert mapped_row["UTC"] == ""
    assert mapped_row["UTC+8"] == "⬛"


def test_offline_card_page_filters_by_activity_name() -> None:
    page = OnlineDatasetPage.__new__(OnlineDatasetPage)
    page.dataset = "cards"
    page.card_business = app.CARD_BUSINESS_OFFLINE
    page._enable_card_version_filter = True
    page._enable_activity_filter = True
    page.card_version_filter_combo = FakeComboBox("202604")
    page.activity_filter_combo = FakeComboBox("五五节")

    matched = {
        "spec": {
            "sceneType": "EYEBALL",
            "cardVersion": "202604",
            "offlineActivityName": "五五节",
            "cardIssued": False,
        }
    }
    unmatched = {
        "spec": {
            "sceneType": "EYEBALL",
            "cardVersion": "202604",
            "offlineActivityName": "其他活动",
            "cardIssued": False,
        }
    }

    assert page._matches_queue_rule(matched) is True
    assert page._matches_queue_rule(unmatched) is False


def test_offline_card_page_activity_filter_all_keeps_all_activities() -> None:
    page = OnlineDatasetPage.__new__(OnlineDatasetPage)
    page.dataset = "cards"
    page.card_business = app.CARD_BUSINESS_OFFLINE
    page._enable_card_version_filter = True
    page._enable_activity_filter = True
    page.card_version_filter_combo = FakeComboBox("202604")
    page.activity_filter_combo = FakeComboBox("")

    row = {
        "spec": {
            "sceneType": "EYEBALL",
            "cardVersion": "202604",
            "offlineActivityName": "五五节",
            "cardIssued": False,
        }
    }

    assert page._matches_queue_rule(row) is True


def test_address_envelope_page_filters_by_call_sign_or_resource_name() -> None:
    page = OnlineDatasetPage.__new__(OnlineDatasetPage)
    page.dataset = bs.ADDRESS_ENVELOPE_DATASET
    page.card_business = ""
    page._enable_card_version_filter = False
    page._enable_activity_filter = False
    page._enable_address_envelope_filter = True
    page.address_envelope_filter_edit = FakeLineEdit("ba3")

    address_row = {
        "metadata": {"name": "BA3NZH-1"},
        "spec": {"callSign": "BA3NZH", "name": "张三"},
    }
    bureau_row = {
        "metadata": {"name": "BURO-001"},
        "spec": {"bureauName": "北京卡片局"},
    }

    assert page._matches_queue_rule(address_row) is True
    assert page._matches_queue_rule(bureau_row) is False

    page.address_envelope_filter_edit.setText("buro-001")
    assert page._matches_queue_rule(bureau_row) is True
