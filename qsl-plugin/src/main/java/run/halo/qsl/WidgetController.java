package run.halo.qsl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestController
@RequestMapping("/plugins/qsl-management/widgets")
public class WidgetController {

    private final QslDataService dataService;
    private final RateLimitService rateLimitService;

    public WidgetController(QslDataService dataService, RateLimitService rateLimitService) {
        this.dataService = dataService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping(value = "/query", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> queryWidget() {
        return html("""
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>QSL 查询卡片</title>
              <style>
                html,body{height:100%;overflow:hidden;}
                body{margin:0;padding:12px;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Microsoft YaHei,sans-serif;background:#f8fafc;box-sizing:border-box;}
                .card{height:calc(100% - 24px);background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px;display:flex;flex-direction:column;box-sizing:border-box;overflow:hidden;}
                .title{font-size:14px;font-weight:600;margin:0 0 10px;}
                .row{display:flex;gap:8px;}
                input{flex:1;height:34px;border:1px solid #d1d5db;border-radius:6px;padding:0 10px;}
                button{height:34px;border:1px solid #2563eb;background:#2563eb;color:#fff;border-radius:6px;padding:0 12px;cursor:pointer;}
                .table-wrap{margin-top:10px;border:1px solid #e5e7eb;border-radius:8px;overflow-y:auto;overflow-x:hidden;background:#fff;flex:1;min-height:0;}
                table{width:100%;border-collapse:collapse;font-size:12px;line-height:1.5;table-layout:fixed;}
                th,td{padding:8px 10px;border-bottom:1px solid #e5e7eb;text-align:left;white-space:nowrap;}
                th{background:#f8fafc;font-weight:600;color:#334155;position:sticky;top:0;z-index:1;}
                tbody tr:last-child td{border-bottom:none;}
                td.status-cell{white-space:normal;min-width:260px;}
                .status-pills{display:flex;flex-wrap:wrap;gap:6px;align-items:flex-start;}
                .status-pill{
                  display:inline-flex;align-items:center;gap:4px;
                  border-radius:999px;padding:2px 10px;font-size:12px;font-weight:600;
                  border:1px solid transparent;
                }
                .status-pill.is-yes{background:#ecfdf3;color:#027a48;border-color:#abefc6;}
                .status-pill.is-no{background:#fef3f2;color:#b42318;border-color:#fecdca;}
                @media (max-width: 768px){
                  td.status-cell{min-width:0;}
                  .status-pills{flex-direction:column;}
                }
                .msg{margin-top:8px;font-size:12px;color:#334155;}
                .msg.error{color:#b91c1c;}
              </style>
            </head>
            <body>
              <div class="card">
                <p class="title">QSL 查询</p>
                <div class="row">
                  <input id="callsign" placeholder="输入呼号，例如 BG2MBC" />
                  <button id="btn">查询</button>
                </div>
                <div id="msg" class="msg"></div>
                <div class="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>呼号</th>
                        <th>卡片类型</th>
                        <th>状态</th>
                        <th>通联时间</th>
                      </tr>
                    </thead>
                    <tbody id="list"></tbody>
                  </table>
                </div>
              </div>
              <script>
                const list = document.getElementById('list');
                const msg = document.getElementById('msg');
                const btn = document.getElementById('btn');
                const cardTypeDict = {
                  EYEBALL: '眼球卡',
                  QSO: '通联卡',
                  LISTEN: '收听卡',
                };
                const sentStatusDict = {
                  SENT: '本台已发卡',
                  NOT_SENT: '待发卡',
                };
                const confirmStatusDict = {
                  CONFIRMED: '已确认收卡',
                  UNCONFIRMED: '待确认收卡',
                };
                const returnStatusDict = {
                  RECEIVED: '已收回卡',
                  NOT_RECEIVED: '待收回卡',
                };

                function formatStatus(item) {
                  const sentCode = item.sentStatus || '';
                  const confirmCode = item.confirmStatus || '';
                  const returnCode = item.returnCardStatus || '';
                  const prodCode = item.productionStatus || '';
                  const reissueCount = Number(item.reissueCount || 0);

                  let sentText = sentStatusDict[sentCode] || sentCode || '未知';
                  if (sentCode === 'NOT_SENT' && prodCode === 'PENDING_PRINT') {
                    sentText = '待重发';
                  }
                  if (sentCode === 'SENT' && reissueCount > 0) {
                    sentText = '本台已发卡(补卡)';
                  }

                  const confirmText = confirmStatusDict[confirmCode] || '待确认收卡';
                  const returnText = returnStatusDict[returnCode] || '待收回卡';
                  return [
                    { text: sentText, yes: sentCode === 'SENT' },
                    { text: confirmText, yes: confirmCode === 'CONFIRMED' },
                    { text: returnText, yes: returnCode === 'RECEIVED' },
                  ];
                }

                async function search() {
                  try {
                    const callsign = document.getElementById('callsign').value.trim();
                    list.innerHTML = '';
                    msg.className = 'msg';
                    msg.textContent = '查询中...';
                    const res = await fetch('/plugins/qsl-management/widgets/public-api/query/cards?callsign=' + encodeURIComponent(callsign));
                    const data = await res.json();
                    if (!res.ok) {
                      msg.className = 'msg error';
                      msg.textContent = data.detail || data.message || ('查询失败（HTTP ' + res.status + '）');
                      return;
                    }
                    if (!Array.isArray(data) || data.length === 0) {
                      msg.textContent = callsign ? '没有匹配记录' : '暂无公开卡片记录';
                      return;
                    }
                    msg.textContent = '共 ' + data.length + ' 条记录';
                    data.forEach(item => {
                      const tr = document.createElement('tr');
                      const cardType = cardTypeDict[item.cardType] || item.cardType || '未知类型';
                      const statusParts = formatStatus(item);
                      const statusHtml = statusParts.map(part =>
                        `<span class="status-pill ${part.yes ? 'is-yes' : 'is-no'}">${part.text}</span>`
                      ).join('');
                      const dt = `${item.cardDate || ''} ${item.cardTime || ''}`.trim();
                      tr.innerHTML = `
                        <td>${item.peerCallsign || ''}</td>
                        <td>${cardType}</td>
                        <td class="status-cell"><div class="status-pills">${statusHtml}</div></td>
                        <td>${dt}</td>
                      `;
                      list.appendChild(tr);
                    });
                  } catch (_) {
                    msg.className = 'msg error';
                    msg.textContent = '查询失败，请稍后重试';
                  }
                }
                btn.addEventListener('click', search);
              </script>
            </body>
            </html>
            """);
    }

    @GetMapping(value = "/reissue", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> reissueWidget() {
        return html("""
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>QSL 补卡申请卡片</title>
              <style>
                body{margin:0;padding:12px;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Microsoft YaHei,sans-serif;background:#f8fafc;}
                .card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px;}
                .title{font-size:14px;font-weight:600;margin:0 0 10px;}
                .grid{display:grid;gap:8px;}
                input,textarea{width:100%;box-sizing:border-box;border:1px solid #d1d5db;border-radius:6px;padding:8px;font-size:12px;}
                textarea{min-height:66px;}
                button{height:34px;border:1px solid #2563eb;background:#2563eb;color:#fff;border-radius:6px;padding:0 12px;cursor:pointer;}
                .msg{margin-top:8px;font-size:12px;color:#0f172a;}
              </style>
            </head>
            <body>
              <div class="card">
                <p class="title">QSL 补卡申请</p>
                <div class="grid">
                  <input id="cardId" placeholder="卡片ID（必填）" />
                  <input id="callsign" placeholder="呼号（选填）" />
                  <textarea id="reason" placeholder="补卡原因，例如平信丢失"></textarea>
                  <button id="btn">提交补卡申请</button>
                </div>
                <div class="msg" id="msg"></div>
              </div>
              <script>
                const msg = document.getElementById('msg');
                document.getElementById('btn').addEventListener('click', async () => {
                  const cardId = Number(document.getElementById('cardId').value);
                  const callsign = document.getElementById('callsign').value.trim();
                  const reason = document.getElementById('reason').value.trim();
                  const qs = new URLSearchParams({
                    qslCardRecordId: String(cardId || ''),
                    callsign,
                    reason
                  });
                  const res = await fetch('/plugins/qsl-management/widgets/public-api/actions/reissue-request?' + qs.toString());
                  const data = await res.json();
                  msg.textContent = res.ok ? `申请已提交，ID: ${data.id}` : (data.detail || '提交失败');
                });
              </script>
            </body>
            </html>
            """);
    }

    @GetMapping(value = "/receive-confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> receiveConfirmWidget() {
        return html("""
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>QSL 收信确认卡片</title>
              <style>
                body{margin:0;padding:12px;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Microsoft YaHei,sans-serif;background:#f8fafc;}
                .card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px;}
                .title{font-size:14px;font-weight:600;margin:0 0 10px;}
                .grid{display:grid;gap:8px;}
                input,textarea{width:100%;box-sizing:border-box;border:1px solid #d1d5db;border-radius:6px;padding:8px;font-size:12px;}
                textarea{min-height:66px;}
                button{height:34px;border:1px solid #2563eb;background:#2563eb;color:#fff;border-radius:6px;padding:0 12px;cursor:pointer;}
                .msg{margin-top:8px;font-size:12px;color:#0f172a;}
              </style>
            </head>
            <body>
              <div class="card">
                <p class="title">QSL 收信确认</p>
                <div class="grid">
                  <input id="cardId" placeholder="卡片ID（必填）" />
                  <input id="callsign" placeholder="呼号（选填，用于校验）" />
                  <textarea id="remark" placeholder="备注（选填）"></textarea>
                  <button id="btn">提交收信确认</button>
                </div>
                <div class="msg" id="msg"></div>
              </div>
              <script>
                const msg = document.getElementById('msg');
                document.getElementById('btn').addEventListener('click', async () => {
                  const cardId = Number(document.getElementById('cardId').value);
                  const callsign = document.getElementById('callsign').value.trim();
                  const remark = document.getElementById('remark').value.trim();
                  const qs = new URLSearchParams({
                    cardId: String(cardId || ''),
                    callsign,
                    remark
                  });
                  const res = await fetch('/plugins/qsl-management/widgets/public-api/actions/receive-confirm?' + qs.toString());
                  const data = await res.json();
                  msg.textContent = res.ok ? '收信确认提交成功' : (data.detail || '提交失败');
                });
              </script>
            </body>
            </html>
            """);
    }

    @GetMapping(value = "/stats", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> statsWidget() {
        return html("""
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>QSL 统计卡片</title>
              <style>
                body{margin:0;padding:12px;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Microsoft YaHei,sans-serif;background:#f8fafc;}
                .card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px;}
                .title{font-size:14px;font-weight:600;margin:0 0 10px;}
                .grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;}
                .item{border:1px solid #e5e7eb;border-radius:8px;padding:8px 12px;background:#fafafa;display:flex;justify-content:space-between;align-items:stretch;min-height:54px;}
                .item.total{grid-row:1 / span 2;}
                .item.progress{
                  background:
                    linear-gradient(90deg, rgba(137,99,235,.14) var(--pct,0%), #fafafa var(--pct,0%));
                }
                .k{font-size:12px;color:#64748b;}
                .v{font-size:42px;font-weight:700;line-height:1;color:#0f172a;display:flex;align-items:center;justify-content:flex-end;}
              </style>
            </head>
            <body>
              <div class="card">
                <p class="title">QSL 统计数据</p>
                <div class="grid" id="grid"></div>
              </div>
              <script>
                (async () => {
                  const res = await fetch('/plugins/qsl-management/widgets/public-api/reports/public-summary');
                  const data = await res.json();
                  const fields = [
                    ['total', '总数', data.total || 0],
                    ['sent', '已发卡', data.sentCount || 0],
                    ['confirmed', '已确认', data.confirmedCount || data.receivedCount || 0],
                    ['received', '已收卡', data.returnedCount || 0],
                    ['pendingSend', '待发卡', data.pendingSendCount || 0],
                  ];
                  const grid = document.getElementById('grid');
                  const total = Number(data.total || 0);
                  fields.forEach(([id, k, v]) => {
                    const num = Number(v || 0);
                    const pct = total > 0 ? Math.max(0, Math.min(100, (num / total) * 100)) : 0;
                    const el = document.createElement('div');
                    el.className = id === 'total' ? 'item total' : 'item progress';
                    if (id !== 'total') {
                      el.style.setProperty('--pct', pct.toFixed(2) + '%');
                    }
                    el.innerHTML = `<div class="k">${k}</div><div class="v">${v}</div>`;
                    grid.appendChild(el);
                  });
                })();
              </script>
            </body>
            </html>
            """);
    }

    @GetMapping(value = "/public-api/query/cards", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> publicQueryCards(
        @RequestParam(value = "callsign", required = false) String callsign,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        ServerWebExchange exchange) {
        var limit = ((Number) dataService.getSystemConfig().getOrDefault("queryLimitPerMin", 5)).intValue();
        var remoteIp = exchange.getRequest().getRemoteAddress() == null
            ? "unknown" : String.valueOf(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
        var rateKey = (userId != null && !userId.isBlank()) ? "USER:" + userId : "IP:" + remoteIp;
        if (limit > 0 && !rateLimitService.allow(rateKey, limit)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "query rate limit exceeded");
        }
        return dataService.queryCardsByCallsign(callsign);
    }

    @GetMapping(value = "/public-api/reports/public-summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> publicSummary() {
        return dataService.reportSummary();
    }

    @PostMapping(value = "/public-api/actions/reissue-request", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createReissueRequest(@RequestBody Map<String, Object> payload) {
        var cardIdObj = payload.get("qslCardRecordId");
        if (cardIdObj == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qslCardRecordId is required");
        }
        var cardId = Long.parseLong(String.valueOf(cardIdObj));
        var card = dataService.get("card", cardId);
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "card not found");
        }
        var request = Map.<String, Object>of(
            "requestType", "REISSUE",
            "status", "PENDING",
            "qslCardRecordId", cardId,
            "bindCallsign", Objects.toString(payload.getOrDefault("callsign", card.getOrDefault("peerCallsign", ""))),
            "note", Objects.toString(payload.getOrDefault("reason", ""))
        );
        return dataService.create("request", request, "public-widget");
    }

    @GetMapping(value = "/public-api/actions/reissue-request", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createReissueRequestByGet(
        @RequestParam(value = "qslCardRecordId") Long qslCardRecordId,
        @RequestParam(value = "callsign", required = false) String callsign,
        @RequestParam(value = "reason", required = false) String reason) {
        return createReissueRequest(Map.of(
            "qslCardRecordId", qslCardRecordId,
            "callsign", Objects.toString(callsign, ""),
            "reason", Objects.toString(reason, "")
        ));
    }

    @PostMapping(value = "/public-api/actions/receive-confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> receiveConfirm(@RequestBody Map<String, Object> payload) {
        var cardIdObj = payload.get("cardId");
        if (cardIdObj == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cardId is required");
        }
        var cardId = Long.parseLong(String.valueOf(cardIdObj));
        var callsign = Objects.toString(payload.getOrDefault("callsign", "")).trim();
        var card = dataService.get("card", cardId);
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "card not found");
        }
        var peer = Objects.toString(card.getOrDefault("peerCallsign", "")).trim();
        if (!callsign.isBlank() && !peer.equalsIgnoreCase(callsign)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "callsign does not match card");
        }
        return dataService.confirmByPeer(
            Map.of("cardIds", List.of(cardId), "confirmRemark", Objects.toString(payload.getOrDefault("remark", ""))),
            "public-widget"
        );
    }

    @GetMapping(value = "/public-api/actions/receive-confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> receiveConfirmByGet(
        @RequestParam(value = "cardId") Long cardId,
        @RequestParam(value = "callsign", required = false) String callsign,
        @RequestParam(value = "remark", required = false) String remark) {
        return receiveConfirm(Map.of(
            "cardId", cardId,
            "callsign", Objects.toString(callsign, ""),
            "remark", Objects.toString(remark, "")
        ));
    }

    private ResponseEntity<String> html(String body) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .body(body);
    }
}
