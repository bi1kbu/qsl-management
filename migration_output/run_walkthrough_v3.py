import json
from pathlib import Path
import requests

root = Path(r"e:/Python/QSL_Management_System")
out = root / "migration_output"
base = "http://127.0.0.1:8090"
cookie = "device_id=0f4a0042a35544bb81bbb13a1386a5ce; language=zh-CN; XSRF-TOKEN=1a3ff8e3-c423-4946-a1cf-4c95910fc553; SESSION=2a1787b6-7392-4edf-931f-41439879c868"

sess = requests.Session()
common_headers = {
    "Cookie": cookie,
    "Referer": "http://127.0.0.1:8090/console/qsl/data/import-export",
    "Origin": "http://127.0.0.1:8090",
}

def req(method, path, body=None, headers=None):
    h = dict(common_headers)
    if headers:
        h.update(headers)
    url = base + path
    if body is None:
        r = sess.request(method, url, headers=h, timeout=30)
    else:
        h["Content-Type"] = "application/json"
        r = sess.request(method, url, headers=h, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), timeout=30)
    if not r.ok:
        raise RuntimeError(f"{method} {path} -> {r.status_code}: {r.text[:300]}")
    ct = r.headers.get("Content-Type", "")
    if "application/json" in ct:
        return r.json()
    txt = r.text.strip()
    if txt.startswith("{") or txt.startswith("["):
        return json.loads(txt)
    if "<html" in txt.lower() or "<!doctype" in txt.lower():
        raise RuntimeError(f"{method} {path} got html")
    return txt

def as_list(x):
    return x if isinstance(x, list) else ([] if x is None else [x])

def key(c, d, t, f, m):
    return "|".join([(str(v) if v is not None else "").strip().lower() for v in [c, d, t, f, m]])

qso_records = json.loads((out / "qso_records.json").read_text(encoding="utf-8"))
address_books = json.loads((out / "address_books.json").read_text(encoding="utf-8"))
card_seeds = json.loads((out / "card_seeds.json").read_text(encoding="utf-8"))

def norm_date(v):
    s = (v or "").strip()
    if not s:
        return "2026-03-15"
    if len(s) >= 10 and s[4] == "-" and s[7] == "-":
        return s[:10]
    nums = []
    cur = ""
    for ch in s:
        if ch.isdigit():
            cur += ch
        elif cur:
            nums.append(cur)
            cur = ""
    if cur:
        nums.append(cur)
    if len(nums) >= 3:
        y = nums[0].zfill(4)
        m = nums[1].zfill(2)
        d = nums[2].zfill(2)
        return f"{y}-{m}-{d}"
    return "2026-03-15"

for q in qso_records:
    q["qsoDate"] = norm_date(q.get("qsoDate"))
for c in card_seeds:
    c["cardDate"] = norm_date(c.get("cardDate"))
    c["qsoDate"] = norm_date(c.get("qsoDate"))

for c in as_list(req("GET", "/apis/qsl.admin/v1/qsl-card-records")):
    if c.get("id"):
        req("DELETE", f"/apis/qsl.admin/v1/qsl-card-records/{c['id']}")
for q in as_list(req("GET", "/apis/qsl.admin/v1/qso-records")):
    if q.get("id"):
        req("DELETE", f"/apis/qsl.admin/v1/qso-records/{q['id']}")
for a in as_list(req("GET", "/apis/qsl.admin/v1/address-books")):
    if a.get("id"):
        req("DELETE", f"/apis/qsl.admin/v1/address-books/{a['id']}")
for r in as_list(req("GET", "/apis/qsl.admin/v1/exchange-requests")):
    if r.get("id") and r.get("status") != "CANCELED":
        req("POST", f"/apis/qsl.user/v1/my/exchange-requests/{r['id']}/cancel", headers={"X-Operator": "ham-user"})
for b in as_list(req("GET", "/apis/qsl.admin/v1/callsign-bindings")):
    if b.get("id"):
        req("POST", f"/apis/qsl.admin/v1/callsign-bindings/{b['id']}/reject", {"reason": "cleanup"})

import1 = req("POST", "/apis/qsl.admin/v1/backup/import", {"qsoRecords": qso_records, "addressBooks": address_books})
qso_after = as_list(req("GET", "/apis/qsl.admin/v1/qso-records"))
qso_idx = {key(q.get("peerCallsign"), q.get("qsoDate"), q.get("qsoTime"), q.get("frequency"), q.get("mode")): q.get("id") for q in qso_after}

cards = []
for s in card_seeds:
    item = {
        "peerCallsign": s.get("peerCallsign"), "cardType": s.get("cardType"), "cardDate": s.get("cardDate"), "cardTime": s.get("cardTime"),
        "productionStatus": s.get("productionStatus"), "sentStatus": s.get("sentStatus"), "receivedStatus": s.get("receivedStatus"),
        "sentAt": s.get("sentAt"), "receivedAt": s.get("receivedAt"), "reissueCount": int(s.get("reissueCount") or 0)
    }
    if (s.get("cardType") or "").upper() in {"QSO", "LISTEN"}:
        qid = qso_idx.get(key(s.get("peerCallsign"), s.get("qsoDate"), s.get("qsoTime"), s.get("frequency"), s.get("mode")))
        if qid:
            item["qsoRecordId"] = qid
    cards.append(item)

import2 = req("POST", "/apis/qsl.admin/v1/backup/import", {"qslCardRecords": cards})

q1 = qso_after[0]
q2 = qso_after[1] if len(qso_after) > 1 else qso_after[0]
qso_card = req("POST", "/apis/qsl.admin/v1/qsl-card-records", {"peerCallsign": q1["peerCallsign"], "cardType": "QSO", "cardDate": q1["qsoDate"], "cardTime": q1["qsoTime"], "qsoRecordId": q1["id"]})
listen_card = req("POST", "/apis/qsl.admin/v1/qsl-card-records", {"peerCallsign": q2["peerCallsign"], "cardType": "LISTEN", "cardDate": q2["qsoDate"], "cardTime": q2["qsoTime"], "qsoRecordId": q2["id"]})
req("POST", "/apis/qsl.admin/v1/qsl-card-records", {"peerCallsign": "BI1KBU", "cardType": "EYEBALL", "cardDate": "2026-04-01", "cardTime": "22:30:00"})

send1 = req("POST", "/apis/qsl.admin/v1/qsl-card-records/send-confirm", {"cardIds": [qso_card["id"], listen_card["id"]], "batchNo": "BATCH-WALK-001", "isReissue": False})
recv1 = req("POST", "/apis/qsl.admin/v1/qsl-card-records/receive-confirm", {"cardIds": [qso_card["id"]], "receiveRemark": "walkthrough"})
prep = req("POST", "/apis/qsl.admin/v1/qsl-card-records/reissue-prepare", {"cardId": qso_card["id"]})
send2 = req("POST", "/apis/qsl.admin/v1/qsl-card-records/send-confirm", {"cardIds": [qso_card["id"]], "batchNo": "BATCH-WALK-REISSUE", "isReissue": True})

bind1 = req("POST", "/apis/qsl.user/v1/my/callsign-bindings", {"callsign": "BI1TEST", "verifyMethod": "EVIDENCE", "evidenceUrl": "https://example.com/license.png"}, {"X-User-Id": "u-walk-1", "X-Operator": "ham-user"})
bind2 = req("POST", "/apis/qsl.user/v1/my/callsign-bindings", {"callsign": "BI1AUTO", "verifyMethod": "PHONE", "phone": "13800000000"}, {"X-User-Id": "u-walk-1", "X-Operator": "ham-user"})
bind_approve = req("POST", f"/apis/qsl.admin/v1/callsign-bindings/{bind1['id']}/approve")

r1 = req("POST", "/apis/qsl.user/v1/my/exchange-requests", {"requestType": "NORMAL", "bindCallsign": "BH1WALK", "note": "eyeball"}, {"X-Role": "HAM", "X-Operator": "ham-user"})
r2 = req("POST", "/apis/qsl.user/v1/my/exchange-requests", {"requestType": "REISSUE", "qslCardRecordId": qso_card["id"], "reason": "mail lost"}, {"X-Role": "HAM", "X-Operator": "ham-user"})
r3 = req("POST", "/apis/qsl.user/v1/my/exchange-requests", {"requestType": "NORMAL", "bindCallsign": "BH1REJECT", "note": "reject"}, {"X-Role": "HAM", "X-Operator": "ham-user"})
r1a = req("POST", f"/apis/qsl.admin/v1/exchange-requests/{r1['id']}/approve")
r2a = req("POST", f"/apis/qsl.admin/v1/exchange-requests/{r2['id']}/approve")
r3r = req("POST", f"/apis/qsl.admin/v1/exchange-requests/{r3['id']}/reject", {"reason": "incomplete"})

req("PUT", "/apis/qsl.admin/v1/system-config", {"queryLimitPerMin": 100, "reissueIntervalDays": 7, "reissueEnabled": True, "requestNeedReview": True})
public_sample = as_list(req("GET", "/apis/qsl.public/v1/query/cards?callsign=BG"))

req("PUT", "/apis/qsl.admin/v1/system-config", {"queryLimitPerMin": 3, "reissueIntervalDays": 7, "reissueEnabled": True, "requestNeedReview": True})
rate = []
rate_user = "rate-test-20260401"
for i in range(1, 6):
    try:
        req("GET", "/apis/qsl.public/v1/query/cards?callsign=BI", headers={"X-User-Id": rate_user})
        rate.append({"seq": i, "ok": True})
    except Exception as e:
        rate.append({"seq": i, "ok": False, "error": str(e)})

summary = req("GET", "/apis/qsl.admin/v1/reports/summary")
trend = as_list(req("GET", "/apis/qsl.admin/v1/reports/trend/monthly"))
dist = as_list(req("GET", "/apis/qsl.admin/v1/reports/card-type-distribution"))
audit = as_list(req("GET", "/apis/qsl.admin/v1/audit-logs?operationType=send_confirm"))

cards_csv = requests.post(base + "/apis/qsl.admin/v1/exports/cards", headers={**common_headers, "Content-Type": "application/json"}, data=json.dumps({"cardIds": []}).encode("utf-8"), timeout=30)
cards_csv.raise_for_status()
(out / "walk_cards.csv").write_bytes(cards_csv.content)

env_csv = requests.post(base + "/apis/qsl.admin/v1/exports/envelopes", headers={**common_headers, "Content-Type": "application/json"}, data=json.dumps({"cardIds": []}).encode("utf-8"), timeout=30)
env_csv.raise_for_status()
(out / "walk_envelopes.csv").write_bytes(env_csv.content)

dash_csv = requests.get(base + "/apis/qsl.admin/v1/dashboard/export", headers={"Cookie": cookie, "Referer": "http://127.0.0.1:8090/console/qsl/dashboard"}, timeout=30)
dash_csv.raise_for_status()
(out / "walk_dashboard.csv").write_bytes(dash_csv.content)

cards_final = as_list(req("GET", "/apis/qsl.admin/v1/qsl-card-records"))
qso_final = as_list(req("GET", "/apis/qsl.admin/v1/qso-records"))
addr_final = as_list(req("GET", "/apis/qsl.admin/v1/address-books"))
req_final = as_list(req("GET", "/apis/qsl.admin/v1/exchange-requests"))
bind_final = as_list(req("GET", "/apis/qsl.admin/v1/callsign-bindings"))

report = {
    "executedAt": "2026-04-01",
    "importStage1": import1,
    "importStage2": import2,
    "counts": {"qso": len(qso_final), "address": len(addr_final), "card": len(cards_final), "request": len(req_final), "binding": len(bind_final), "auditSendConfirm": len(audit)},
    "workflow": {"send1": send1.get("count"), "receive1": recv1.get("count"), "reissuePrepared": prep.get("prepared"), "sendReissue": send2.get("count"), "bindingApprovedStatus": bind_approve.get("status"), "phoneBindingStatus": bind2.get("status"), "req1Status": r1a.get("status"), "req2Status": r2a.get("status"), "req3Status": r3r.get("status")},
    "publicQuerySampleCount": len(public_sample),
    "rateLimit": rate,
    "reportSummary": summary,
    "reportTrendSize": len(trend),
    "reportTypeSize": len(dist),
    "exports": {"cardsCsv": str(out / "walk_cards.csv"), "envelopesCsv": str(out / "walk_envelopes.csv"), "dashboardCsv": str(out / "walk_dashboard.csv")}
}

(out / "walkthrough_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print("DONE", out / "walkthrough_report.json")
