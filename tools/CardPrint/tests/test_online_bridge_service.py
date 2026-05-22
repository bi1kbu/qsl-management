from __future__ import annotations

import pytest

from cardprint.core.errors import CardPrintError
from cardprint.online import bridge_service as bs


def _sample_config() -> dict:
    cfg = bs.default_bridge_config()
    cfg["base_url"] = "http://example.test"
    cfg["auth"]["type"] = "basic"
    cfg["auth"]["username"] = "u"
    cfg["auth"]["password"] = "p"
    cfg["writeback"]["enabled"] = True
    cfg["writeback"]["datasets"]["cards"]["id_field"] = "卡片ID"
    cfg["mappings"]["cards"] = {"customKey": "customValue"}
    return cfg


def test_map_export_row_with_checkbox_rule() -> None:
    source_row = {"呼号": "BI1SZB", "UTC": "1"}
    mapped = bs.map_export_row(
        source_row,
        {
            "peerCallsign": "呼号",
            "timezone": {
                "type": "checkbox",
                "source": "UTC",
                "true_values": ["1"],
                "true_output": "⬛",
                "false_output": "",
            },
        },
    )
    assert mapped["peerCallsign"] == "BI1SZB"
    assert mapped["timezone"] == "⬛"


def test_card_mapping_defaults_to_request_return_card_and_utc() -> None:
    mapping = bs.normalize_bridge_config({})["mappings"]["cards"]

    mapped = bs.map_export_row({"spec": {"cardType": "QSO"}}, mapping)

    assert mapped["欢迎回卡"] == "⬛"
    assert mapped["回复卡片"] == "⬛"
    assert mapped["请回卡片"] == "⬛"
    assert mapped["感谢来卡"] == ""
    assert mapped["发出卡片"] == ""
    assert mapped["感谢您的卡片"] == ""
    assert mapped["感谢您的来卡"] == ""
    assert mapped["UTC"] == "⬛"
    assert mapped["UTC+8"] == ""


def test_card_mapping_keeps_return_card_and_timezone_options_exclusive() -> None:
    mapping = bs.normalize_bridge_config({})["mappings"]["cards"]

    received_utc8 = bs.map_export_row(
        {"spec": {"cardReceived": True}, "qsoInfo": {"spec": {"timezone": "UTC+8"}}},
        mapping,
    )
    unreceived_utc = bs.map_export_row(
        {"spec": {"cardReceived": False}, "qsoInfo": {"spec": {"timezone": "UTC"}}},
        mapping,
    )

    assert received_utc8["欢迎回卡"] == ""
    assert received_utc8["回复卡片"] == ""
    assert received_utc8["请回卡片"] == ""
    assert received_utc8["感谢来卡"] == "⬛"
    assert received_utc8["发出卡片"] == "⬛"
    assert received_utc8["感谢您的卡片"] == "⬛"
    assert received_utc8["感谢您的来卡"] == "⬛"
    assert received_utc8["UTC"] == ""
    assert received_utc8["UTC+8"] == "⬛"
    assert unreceived_utc["欢迎回卡"] == "⬛"
    assert unreceived_utc["回复卡片"] == "⬛"
    assert unreceived_utc["请回卡片"] == "⬛"
    assert unreceived_utc["感谢来卡"] == ""
    assert unreceived_utc["发出卡片"] == ""
    assert unreceived_utc["感谢您的卡片"] == ""
    assert unreceived_utc["感谢您的来卡"] == ""
    assert unreceived_utc["UTC"] == "⬛"
    assert unreceived_utc["UTC+8"] == ""


def test_card_mapping_treats_empty_card_received_as_request_return_card() -> None:
    mapping = bs.normalize_bridge_config({})["mappings"]["cards"]

    request_return = bs.map_export_row({"spec": {"cardReceived": ""}}, mapping)

    assert request_return["请回卡片"] == "⬛"
    assert request_return["感谢您的卡片"] == ""


def test_normalize_bridge_config_migrates_legacy_routes() -> None:
    cfg = bs.normalize_bridge_config(
        {
            "base_url": "http://localhost:8090",
            "endpoints": {
                "cards": "/apis/qsl.admin/v1/exports/cards",
                "envelopes": "/apis/qsl.admin/v1/exports/envelopes",
            },
            "writeback": {
                "enabled": True,
                "datasets": {
                    "cards": {
                        "id_field": "卡片ID",
                        "url": "/apis/qsl.admin/v1/qsl-card-records/{id}",
                        "method": "PUT",
                        "body": {},
                    },
                    "envelopes": {
                        "id_field": "卡片ID",
                        "url": "/apis/qsl.admin/v1/qsl-card-records/{id}",
                        "method": "PUT",
                        "body": {},
                    },
                },
            },
        }
    )
    assert cfg["endpoints"]["cards"] == "/apis/qsl-management.bi1kbu.com/v1alpha1/card-records"
    assert cfg["endpoints"]["envelopes"] == "/apis/qsl-management.bi1kbu.com/v1alpha1/card-records"
    assert cfg["writeback"]["datasets"]["cards"]["id_field"] == "metadata.name"
    assert cfg["writeback"]["datasets"]["cards"]["url"] == "/apis/qsl-management.bi1kbu.com/v1alpha1/card-records/{id}"
    assert cfg["mappings"]["cards"].get("peerCallsign") is not None
    assert "customKey" not in cfg["mappings"]["cards"]


def test_normalize_bridge_config_force_fixed_remote_settings() -> None:
    cfg = bs.normalize_bridge_config(
        {
            "base_url": "http://custom-host:1234",
            "endpoints": {
                "cards": "/custom/cards",
                "envelopes": "/custom/envelopes",
            },
            "filters": {
                "cards": {"only": "mine"},
                "envelopes": {"only": "mine"},
            },
            "common": {"timeout_s": 12},
        }
    )
    assert cfg["base_url"] == "http://custom-host:1234"
    assert cfg["endpoints"]["cards"] == bs.HALO_CARD_RECORDS_ENDPOINT
    assert cfg["endpoints"]["envelopes"] == bs.HALO_CARD_RECORDS_ENDPOINT
    assert cfg["filters"]["cards"] == {}
    assert cfg["filters"]["envelopes"] == {}
    assert cfg["common"]["timeout_s"] == 12.0


def test_normalize_bridge_config_includes_qrcode_path_mappings() -> None:
    cfg = bs.normalize_bridge_config(
        {
            "qrcode": {
                "path_mappings": {
                    "apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public": "/receipt-short",
                }
            }
        }
    )

    mappings = cfg["qrcode"]["path_mappings"]
    assert mappings["/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL"] == "/EYEBALL"
    assert mappings["/apis/api.qsl-management.bi1kbu.com/v1alpha1/ONLINE_EYEBALL"] == "/ONLINE_EYEBALL"
    assert mappings["/apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public"] == "/receipt-short"


def test_normalize_bridge_config_keeps_eyeball_reprint_preset() -> None:
    cfg = bs.normalize_bridge_config(
        {
            "presets": {
                "eyeball_reprint_card": "E:/preset/eyeball-reprint.json",
            }
        }
    )

    assert cfg["presets"]["eyeball_reprint_card"] == "E:/preset/eyeball-reprint.json"


def test_fetch_dataset_maps_rows_and_record_id(monkeypatch) -> None:
    json_text = """
    {
      "items": [
        {"metadata": {"name": "card-record-c1001"}, "spec": {"callSign": "BI1SZB", "cardType": "QSO"}},
        {"metadata": {"name": "card-record-c1002"}, "spec": {"callSign": "BG1AAA", "cardType": "SWL"}}
      ]
    }
    """

    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        return "application/json; charset=utf-8", json_text

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    service = bs.BridgeService()
    result = service.fetch_dataset(_sample_config(), "cards")

    assert calls[0]["method"] == "GET"
    assert result["count"] == 2
    assert result["id_field"] == "metadata.name"
    assert result["records"][0]["record_id"] == "card-record-c1001"
    assert result["records"][0]["mapped_row"]["peerCallsign"] == "BI1SZB"
    assert result["records"][0]["mapped_row"]["QSO"] == "⬛"
    assert result["records"][1]["mapped_row"]["SWL"] == "⬛"


def test_fetch_cards_enrich_qso_info_by_qso_record_name(monkeypatch) -> None:
    card_rows = """
    {
      "items": [
        {
          "metadata": {"name": "card-record-c3001"},
          "spec": {"callSign": "BI1KBU", "cardType": "QSO", "qsoRecordName": "qso-001"}
        }
      ]
    }
    """
    qso_rows = """
    {
      "items": [
        {
          "metadata": {"name": "qso-001"},
          "spec": {"freq": "144.640", "myRigMode": "FM", "rstSent": "59", "qth": "Beijing"}
        }
      ]
    }
    """

    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        endpoint = str(kwargs.get("endpoint", ""))
        if "qso-records" in endpoint:
            return "application/json; charset=utf-8", qso_rows
        return "application/json; charset=utf-8", card_rows

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    service = bs.BridgeService()
    result = service.fetch_dataset(_sample_config(), "cards")

    assert len(calls) == 2
    mapped = result["records"][0]["mapped_row"]
    assert mapped["frequency"] == "144.640"
    assert mapped["mode"] == "FM"
    assert mapped["rstSent"] == "59"
    assert mapped["qth"] == "Beijing"


def test_fetch_offline_cards_use_activity_location_as_qth(monkeypatch) -> None:
    card_rows = """
    {
      "items": [
        {
          "metadata": {"name": "card-record-c4001"},
          "spec": {
            "callSign": "BI1KBU",
            "cardType": "EYEBALL",
            "sceneType": "EYEBALL",
            "offlineActivityName": "activity-001"
          }
        }
      ]
    }
    """
    activity_rows = """
    {
      "items": [
        {
          "metadata": {"name": "activity-001"},
          "spec": {"activityName": "五五节", "activityLocation": "北京业余无线电活动现场"}
        }
      ]
    }
    """

    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        endpoint = str(kwargs.get("endpoint", ""))
        if "offline-activities" in endpoint:
            return "application/json; charset=utf-8", activity_rows
        return "application/json; charset=utf-8", card_rows

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    service = bs.BridgeService()
    result = service.fetch_dataset(_sample_config(), "cards")

    assert len(calls) == 2
    assert "card-records" in calls[0]["endpoint"]
    assert "offline-activities" in calls[1]["endpoint"]
    mapped = result["records"][0]["mapped_row"]
    assert mapped["qth"] == "北京业余无线电活动现场"


def test_fetch_envelopes_enrich_address_info(monkeypatch) -> None:
    card_rows = """
    {
      "items": [
        {
          "metadata": {"name": "card-record-c2001"},
          "spec": {"callSign": "BI1KBU", "sceneType": "ONLINE_EYEBALL", "addressEntryName": "BI1KBU-1"}
        },
        {
          "metadata": {"name": "card-record-c2002"},
          "spec": {"callSign": "BI1KBU", "sceneType": "EYEBALL", "addressEntryName": "BI1KBU-2"}
        }
      ]
    }
    """
    address_rows = """
    {
      "items": [
        {
          "metadata": {"name": "BI1KBU-1"},
          "spec": {"name": "测试台", "address": "北京市某区某路", "postalCode": "100000", "telephone": "138****0000"}
        }
      ]
    }
    """
    bureau_rows = '{"items": []}'

    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        endpoint = str(kwargs.get("endpoint", ""))
        if "address-book-entries" in endpoint:
            return "application/json; charset=utf-8", address_rows
        if "bureau-entries" in endpoint:
            return "application/json; charset=utf-8", bureau_rows
        return "application/json; charset=utf-8", card_rows

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    service = bs.BridgeService()
    result = service.fetch_dataset(_sample_config(), "envelopes")

    assert len(calls) == 4
    assert result["count"] == 1
    assert result["records"][0]["record_id"] == "card-record-c2001"
    mapped = result["records"][0]["mapped_row"]
    assert mapped["name"] == "测试台"
    assert mapped["address"] == "北京市某区某路"
    assert mapped["postCode"] == "100000"
    assert mapped["phone"] == "138****0000"


def test_fetch_address_envelopes_reads_address_and_bureau_entries(monkeypatch) -> None:
    station_rows = """
    {
      "items": [
        {
          "metadata": {"name": "qsl-station-profile-default"},
          "spec": {
            "myName": "BI1KBU",
            "myTelephone": "139****0000",
            "myPostalCode": "100001",
            "myAddress": "北京市某区本台地址"
          }
        }
      ]
    }
    """
    address_rows = """
    {
      "items": [
        {
          "metadata": {"name": "BI1KBU-1"},
          "spec": {
            "callSign": "BI1KBU",
            "name": "测试台",
            "address": "北京市某区某路",
            "postalCode": "100000",
            "telephone": "138****0000",
            "email": "bi1kbu@example.test"
          }
        }
      ]
    }
    """
    bureau_rows = """
    {
      "items": [
        {
          "metadata": {"name": "BURO-001"},
          "spec": {
            "bureauName": "北京卡片局",
            "address": "北京市某区卡片局地址",
            "postalCode": "100010",
            "telephone": "010-12345678"
          }
        }
      ]
    }
    """

    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        endpoint = str(kwargs.get("endpoint", ""))
        if "station-profiles" in endpoint:
            return "application/json; charset=utf-8", station_rows
        if "address-book-entries" in endpoint:
            return "application/json; charset=utf-8", address_rows
        if "bureau-entries" in endpoint:
            return "application/json; charset=utf-8", bureau_rows
        return "application/json; charset=utf-8", '{"items": []}'

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    service = bs.BridgeService()
    result = service.fetch_dataset(_sample_config(), bs.ADDRESS_ENVELOPE_DATASET)

    assert [item["endpoint"].split("?")[0] for item in calls] == [
        "/apis/qsl-management.bi1kbu.com/v1alpha1/station-profiles",
        "/apis/qsl-management.bi1kbu.com/v1alpha1/address-book-entries",
        "/apis/qsl-management.bi1kbu.com/v1alpha1/bureau-entries",
    ]
    assert result["count"] == 2
    assert result["id_field"] == ""
    address_mapped = result["records"][0]["mapped_row"]
    assert address_mapped["name"] == "测试台"
    assert address_mapped["address"] == "北京市某区某路"
    assert address_mapped["postCode"] == "100000"
    assert address_mapped["phone"] == "138****0000"
    assert address_mapped["my_name"] == "BI1KBU"
    bureau_mapped = result["records"][1]["mapped_row"]
    assert bureau_mapped["name"] == "北京卡片局"
    assert bureau_mapped["address"] == "北京市某区卡片局地址"
    assert bureau_mapped["postCode"] == "100010"


def test_fetch_card_versions_follow_station_card_sort_order(monkeypatch) -> None:
    station_card_rows = """
    [
      {"cardId": "station-card-b", "cardVersion": "B", "sortOrder": 20},
      {"cardId": "station-card-a", "cardVersion": "A", "sortOrder": 10},
      {"cardId": "station-card-c", "cardVersion": "C", "sortOrder": 0}
    ]
    """
    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        return "application/json; charset=utf-8", station_card_rows

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    service = bs.BridgeService()
    assert service.fetch_card_versions(_sample_config()) == ["A", "B", "C"]
    assert calls[0]["endpoint"] == bs.PUBLIC_STATION_CARDS_ENDPOINT
    assert calls[0]["auth_config"]["type"] == "bearer"
    assert calls[0]["auth_config"]["token"] == ""


def test_extension_list_rejects_login_html_response(monkeypatch) -> None:
    def fake_http_request(**kwargs):
        return "text/html; charset=utf-8", "<!doctype html><html><title>登录</title></html>"

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    service = bs.BridgeService()
    with pytest.raises(CardPrintError) as exc_info:
        service.fetch_station_sender(_sample_config())

    assert exc_info.value.code == "REMOTE_AUTH_REQUIRED"
    assert "登录页" in exc_info.value.message


def test_render_now_template_uses_server_time_text_format() -> None:
    now_text = bs.render_template_data("${now}", record_id="C1", row={})

    assert "T" not in now_text
    assert len(now_text) == len("2026-05-04 12:34:56")


def test_writeback_only_success_rows(monkeypatch) -> None:
    cfg = _sample_config()
    cfg["writeback"]["datasets"]["cards"]["body"] = {"custom": "ignored"}

    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        if str(kwargs.get("method", "")).upper() == "GET":
            return "application/json", """
            {
              "apiVersion": "qsl-management.bi1kbu.com/v1alpha1",
              "kind": "CardRecord",
              "metadata": {"name": "card-record-c1001", "version": 12},
              "spec": {"callSign": "BI1SZB", "cardIssued": false, "envelopePrinted": false},
              "status": {"flowStatus": "待处理"}
            }
            """
        return "application/json", "{}"

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    records = [
        {"source_row": {"metadata": {"name": "card-record-c1001"}, "spec": {"callSign": "BI1SZB"}}, "mapped_row": {}, "record_id": "card-record-c1001"},
        {"source_row": {"metadata": {"name": "card-record-c1002"}, "spec": {"callSign": "BG1AAA"}}, "mapped_row": {}, "record_id": "card-record-c1002"},
    ]
    print_rows = [
        {"index": 0, "status": "success"},
        {"index": 1, "status": "failed", "message": "printer error"},
    ]

    service = bs.BridgeService()
    result = service.writeback_success(cfg, "cards", records, print_rows)

    assert result["success"] == 1
    assert result["failed"] == 0
    assert result["skipped"] == 1
    assert len(calls) == 2
    assert calls[0]["endpoint"].endswith("/card-record-c1001")
    assert calls[0]["method"] == "GET"
    assert calls[1]["endpoint"].endswith("/card-record-c1001")
    assert calls[1]["method"] == "PUT"
    put_payload = calls[1]["payload"]
    assert put_payload["metadata"]["name"] == "card-record-c1001"
    assert put_payload["metadata"]["version"] == 12
    assert put_payload["spec"]["cardIssued"] is True
    assert "cardIssuedAt" in put_payload["spec"]
    assert put_payload["status"]["flowStatus"] == "已制卡"
    assert "custom" not in put_payload["spec"]


def test_writeback_refreshes_flow_status_for_envelope_print(monkeypatch) -> None:
    cfg = _sample_config()
    cfg["writeback"]["datasets"]["envelopes"]["id_field"] = "卡片ID"

    calls: list[dict] = []

    def fake_http_request(**kwargs):
        calls.append(kwargs)
        if str(kwargs.get("method", "")).upper() == "GET":
            return "application/json", """
            {
              "apiVersion": "qsl-management.bi1kbu.com/v1alpha1",
              "kind": "CardRecord",
              "metadata": {"name": "card-record-c1001", "version": 12},
              "spec": {"callSign": "BI1SZB", "cardIssued": true, "cardIssuedAt": "2026-05-04 10:00:00", "envelopePrinted": false},
              "status": {"flowStatus": "已制卡"}
            }
            """
        return "application/json", "{}"

    monkeypatch.setattr(bs, "_http_request", fake_http_request)

    records = [
        {"source_row": {"metadata": {"name": "card-record-c1001"}, "spec": {"callSign": "BI1SZB"}}, "mapped_row": {}, "record_id": "card-record-c1001"},
    ]
    print_rows = [{"index": 0, "status": "success"}]

    service = bs.BridgeService()
    result = service.writeback_success(cfg, "envelopes", records, print_rows)

    assert result["success"] == 1
    put_payload = calls[1]["payload"]
    assert put_payload["spec"]["envelopePrinted"] is True
    assert put_payload["status"]["flowStatus"] == "已打包"
