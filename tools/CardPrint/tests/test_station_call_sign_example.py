from types import SimpleNamespace
import pytest
from cardprint.online import bridge_service as bs
from cardprint.ui import online_print_app as app


class CallSignInput:
    def __init__(self):
        self.value = "BG9OLD"
        self.placeholder = ""

    def setPlaceholderText(self, text):
        self.placeholder = text


@pytest.mark.parametrize("configured,expected", [(" bg1abc ", "例如：BG1ABC"), ("", "例如：BI1KBU"), ("   ", "例如：BI1KBU")])
def test_call_sign_example_only_changes_placeholder(configured, expected):
    edit = CallSignInput()
    app.EyeballReprintPage.set_example_call_sign(SimpleNamespace(call_sign_edit=edit), configured)
    assert edit.placeholder == expected
    assert edit.value == "BG9OLD"


def test_switching_station_resets_example(monkeypatch):
    monkeypatch.setattr(app, "normalize_bridge_config", lambda config: config)
    monkeypatch.setattr(app, "_load_preset_meta", lambda path: {})
    resets = []
    page = SimpleNamespace(
        _example_base_url="https://old.example",
        set_example_call_sign=resets.append,
        preset_edit=SimpleNamespace(setText=lambda value: None),
        _apply_printer_and_paper_from_preset=lambda path: None,
    )
    app.EyeballReprintPage.set_config(page, {"base_url": "https://new.example/"})
    assert resets == [""]
    assert page._example_base_url == "https://new.example"
    app.EyeballReprintPage.set_config(page, {"base_url": "https://new.example"})
    assert resets == [""]


def test_window_ignores_call_sign_from_previous_station():
    received = []
    window = SimpleNamespace(config={"base_url": "https://new.example/"},
        eyeball_reprint_page=SimpleNamespace(set_example_call_sign=received.append))
    app.OnlinePrintWindow._on_station_call_sign_loaded(window, "https://old.example", "BG1OLD")
    assert received == []
    app.OnlinePrintWindow._on_station_call_sign_loaded(window, "https://new.example", "BG1NEW")
    assert received == ["BG1NEW"]


def test_config_ignores_late_previous_station_response():
    page = SimpleNamespace(base_url_edit=SimpleNamespace(text=lambda: "https://new.example/"))
    app.OnlineConfigPage._handle_fetch_versions_success(page, {"base_url": "https://old.example", "sender": {"my_call_sign": "BG1OLD"}})


def test_station_sender_contains_call_sign_without_changing_sender_fields(monkeypatch):
    monkeypatch.setattr(bs, "_fetch_station_profile", lambda config: {"myCallSign": " BG1ABC ", "myName": "张三", "myTelephone": "123", "myPostalCode": "100000", "myAddress": "测试地址"})
    sender = bs.BridgeService().fetch_station_sender({"base_url": "https://station.example"})
    assert sender == {"my_call_sign": "BG1ABC", "my_name": "张三", "my_phone": "123", "my_postCode": "100000", "my_address": "测试地址"}
