package com.bi1kbu.qslmanagement.front;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class QslPublicExchangePageRenderService {

    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SCENE_TYPE_PATTERN = Pattern.compile("^(ONLINE_EYEBALL|EYEBALL)?$");

    public String render(
        String rawCallSign,
        String rawCardId,
        String rawActivityId,
        String rawSceneType,
        boolean embed,
        String rawEmbedId,
        String rawStationAddress,
        String rawStationEmail
    ) {
        var callSign = normalizeCallSign(rawCallSign);
        var cardId = normalizeText(rawCardId, 128).toUpperCase(Locale.ROOT);
        var activityId = normalizeText(rawActivityId, 64);
        var sceneType = normalizeSceneType(rawSceneType);
        var embedId = normalizeEmbedId(rawEmbedId);
        var stationAddress = normalizeText(rawStationAddress, 200);
        var stationEmail = normalizeText(rawStationEmail, 120);
        var offlineMode = "EYEBALL".equals(sceneType);
        var title = offlineMode ? "线下换卡确认" : "QSL 线上换卡申请";

        return BASE_TEMPLATE
            .replace("__TITLE__", embed ? title + "卡片" : title + "页面")
            .replace("__PAGE_TITLE__", title)
            .replace("__PAGE_DESC__", offlineMode
                ? "仅需填写呼号、卡片ID、活动ID即可提交确认。"
                : "填写呼号和地址信息后提交申请，后台审核通过后自动生成卡片。")
            .replace("__CALL_SIGN_HTML__", escapeHtml(callSign))
            .replace("__CARD_ID_HTML__", escapeHtml(cardId))
            .replace("__ACTIVITY_ID_HTML__", escapeHtml(activityId))
            .replace("__CALL_SIGN_JS__", escapeJs(callSign))
            .replace("__CARD_ID_JS__", escapeJs(cardId))
            .replace("__ACTIVITY_ID_JS__", escapeJs(activityId))
            .replace("__STATION_ADDRESS_JS__", escapeJs(stationAddress))
            .replace("__STATION_EMAIL_JS__", escapeJs(stationEmail))
            .replace("__SCENE_TYPE_JS__", escapeJs(sceneType))
            .replace("__OFFLINE_MODE__", Boolean.toString(offlineMode))
            .replace("__EMBED_MODE__", Boolean.toString(embed))
            .replace("__EMBED_ID__", escapeJs(embedId));
    }

    public String renderError(String message, boolean embed) {
        var safeMessage = escapeHtml(message == null || message.isBlank() ? "页面加载失败" : message);
        var safeTitle = embed ? "QSL 换卡页加载失败" : "QSL 前台换卡页加载失败";
        return """
            <!doctype html>
            <html lang="zh-CN">
              <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>%s</title></head>
              <body style="font-family:'PingFang SC','Microsoft YaHei',sans-serif;padding:20px;">
                <h1 style="font-size:18px;margin:0 0 10px;">%s</h1><p style="margin:0;color:#4b5563;">%s</p>
              </body>
            </html>
            """.formatted(safeTitle, safeTitle, safeMessage);
    }

    private String normalizeCallSign(String rawCallSign) {
        var normalized = QslApiSupport.normalizeCallSign(rawCallSign);
        if (!CALL_SIGN_PATTERN.matcher(normalized).matches()) {
            return "";
        }
        return normalized;
    }

    private String normalizeSceneType(String rawSceneType) {
        if (rawSceneType == null) {
            return "ONLINE_EYEBALL";
        }
        var normalized = rawSceneType.trim().toUpperCase(Locale.ROOT);
        if (!SCENE_TYPE_PATTERN.matcher(normalized).matches() || normalized.isBlank()) {
            return "ONLINE_EYEBALL";
        }
        return normalized;
    }

    private String normalizeEmbedId(String rawEmbedId) {
        var normalized = rawEmbedId == null ? "" : rawEmbedId.trim();
        if (!EMBED_ID_PATTERN.matcher(normalized).matches()) {
            return "qsl-exchange-default";
        }
        return normalized;
    }

    private String normalizeText(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        var text = raw.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String escapeJs(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private static final String BASE_TEMPLATE = """
        <!doctype html>
        <html lang="zh-CN">
          <head>
            <meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>__TITLE__</title>
            <style>
              :root { color-scheme: light; font-family: "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif; }
              body { margin: 0; background: #f3f5f9; color: #111827; }
              body.qsl-embed { background: transparent; }
              .qsl-page { box-sizing: border-box; max-width: 860px; margin: 0 auto; padding: 20px; }
              .qsl-page.embed { max-width: 100%; padding: 12px; }
              .qsl-card { background: #ffffff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 16px; }
              .qsl-page.embed .qsl-card { background: transparent; }
              .qsl-title { margin: 0 0 6px; font-size: 20px; font-weight: 700; }
              .qsl-desc { margin: 0 0 12px; color: #4b5563; font-size: 13px; }
              .qsl-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
              .qsl-field.full { grid-column: 1 / -1; }
              .qsl-label { display: block; margin-bottom: 6px; font-size: 13px; color: #374151; font-weight: 600; }
              .qsl-input, .qsl-textarea { width: 100%; border-radius: 8px; border: 1px solid #d1d5db; box-sizing: border-box; font-size: 14px; }
              .qsl-input { height: 38px; padding: 0 12px; }
              .qsl-textarea { min-height: 88px; padding: 10px 12px; resize: vertical; }
              .qsl-actions { margin-top: 12px; display: flex; gap: 10px; }
              .qsl-button { height: 38px; border-radius: 8px; border: 1px solid #2563eb; background: #2563eb; color: #fff; padding: 0 18px; cursor: pointer; }
              .qsl-button[disabled] { opacity: .65; cursor: not-allowed; }
              .qsl-hint { margin: 6px 0 0; color: #6b7280; font-size: 12px; }
              .qsl-hint.error { color: #dc2626; }
              .qsl-error { margin: 8px 0 0; color: #dc2626; font-size: 12px; white-space: pre-line; }
              .qsl-success { margin-top: 12px; border: 1px solid #bbf7d0; background: #f0fdf4; border-radius: 10px; padding: 12px; color: #14532d; white-space: pre-line; }
              .qsl-hidden { display: none !important; }
              @media (max-width: 640px) { .qsl-grid { grid-template-columns: 1fr; } .qsl-page { padding: 12px; } .qsl-button { width: 100%; } }
            </style>
          </head>
          <body>
            <main id="qsl-page" class="qsl-page">
              <section class="qsl-card">
                <h1 id="qsl-title" class="qsl-title">__PAGE_TITLE__</h1>
                <p id="qsl-desc" class="qsl-desc">__PAGE_DESC__</p>
                <form id="qsl-form">
                  <div class="qsl-grid">
                    <label id="offline-call-sign-field" class="qsl-field full"><span class="qsl-label">呼号</span><input id="callSign" class="qsl-input" maxlength="16" value="__CALL_SIGN_HTML__" placeholder="例如 BG7ABC" /></label>
                    <label id="offline-card-id-field" class="qsl-field"><span class="qsl-label">卡片ID</span><input id="cardId" class="qsl-input" maxlength="128" value="__CARD_ID_HTML__" placeholder="例如 C1001" /></label>
                    <label id="offline-activity-id-field" class="qsl-field">
                      <span class="qsl-label">活动ID</span>
                      <input id="activityId" class="qsl-input" maxlength="64" value="__ACTIVITY_ID_HTML__" placeholder="例如 202604ACT01" list="offlineActivityList" />
                      <datalist id="offlineActivityList"></datalist>
                      <p id="offlineActivityNameHint" class="qsl-hint">活动名称：-</p>
                    </label>

                    <label id="online-name-field"><span class="qsl-label">联系人姓名</span><input id="name" class="qsl-input" maxlength="60" placeholder="可选" /></label>
                    <label id="online-email-field"><span class="qsl-label">邮箱</span><input id="email" class="qsl-input" maxlength="120" placeholder="可选" /></label>
                    <label id="online-tel-field"><span class="qsl-label">联系电话</span><input id="telephone" class="qsl-input" maxlength="30" placeholder="可选" /></label>
                    <label id="online-postal-field"><span class="qsl-label">邮编</span><input id="postalCode" class="qsl-input" maxlength="20" placeholder="可选" /></label>
                    <label id="online-bureau-field"><span class="qsl-label">卡片局名称</span><input id="bureauName" class="qsl-input" maxlength="80" placeholder="可选，填写时默认按卡局模式" /></label>
                    <label id="online-address-field" class="qsl-field full"><span class="qsl-label">通信地址</span><textarea id="address" class="qsl-textarea" maxlength="200" placeholder="可选"></textarea></label>

                    <label class="qsl-field full"><span class="qsl-label">备注</span><textarea id="remarks" class="qsl-textarea" maxlength="500" placeholder="可选"></textarea></label>
                  </div>
                  <div class="qsl-actions"><button id="submitBtn" class="qsl-button" type="submit">提交</button></div>
                  <p id="error" class="qsl-error"></p>
                </form>
                <div id="success" class="qsl-success" style="display:none;"></div>
              </section>
            </main>
            <script>
              (() => {
                const API_BASE = "/apis/api.qsl-management.halo.run/v1alpha1";
                const EMBED_MODE = __EMBED_MODE__;
                const EMBED_ID = "__EMBED_ID__";
                const SCENE_TYPE = "__SCENE_TYPE_JS__" || "ONLINE_EYEBALL";
                const OFFLINE_MODE = __OFFLINE_MODE__;
                const STATION_ADDRESS = "__STATION_ADDRESS_JS__";
                const STATION_EMAIL = "__STATION_EMAIL_JS__";
                const page = document.getElementById("qsl-page");
                const form = document.getElementById("qsl-form");
                const title = document.getElementById("qsl-title");
                const desc = document.getElementById("qsl-desc");
                const submitBtn = document.getElementById("submitBtn");
                const error = document.getElementById("error");
                const success = document.getElementById("success");
                const activityIdInput = document.getElementById("activityId");
                const activityList = document.getElementById("offlineActivityList");
                const activityNameHint = document.getElementById("offlineActivityNameHint");
                const offlineActivityMap = new Map();
                if (EMBED_MODE) { document.body.classList.add("qsl-embed"); page.classList.add("embed"); }

                const notifyParentHeight = () => {
                  if (!EMBED_MODE || window.parent === window) return;
                  const height = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
                  window.parent.postMessage({ type: "qsl-card-height", embedId: EMBED_ID, height }, "*");
                };
                const normalizeCallSign = (v) => (v || "").trim().toUpperCase();
                const normalizeId = (v) => (v || "").trim();
                const normalizeActivityKey = (v) => (v || "").trim().toUpperCase();
                const text = (id) => (document.getElementById(id)?.value || "").trim();
                const parseResult = (result) => {
                  if (!result || result.code !== "QSL-0000") throw new Error(result?.message || "接口返回异常");
                  return result.data ?? {};
                };
                const setActivityNameHint = (message, isError = false) => {
                  if (!activityNameHint) return;
                  activityNameHint.textContent = message;
                  activityNameHint.classList.toggle("error", !!isError);
                  notifyParentHeight();
                };
                const refreshActivityNameHint = () => {
                  if (!OFFLINE_MODE || !activityIdInput) return;
                  const key = normalizeActivityKey(activityIdInput.value);
                  if (!key) {
                    setActivityNameHint("活动名称：-");
                    return;
                  }
                  const matched = offlineActivityMap.get(key);
                  if (matched) {
                    setActivityNameHint(`活动名称：${matched}`);
                    return;
                  }
                  setActivityNameHint("活动名称：未匹配（可继续提交）");
                };
                const loadOfflineActivities = async () => {
                  if (!OFFLINE_MODE) return;
                  if (!activityList) return;
                  try {
                    const response = await fetch(`${API_BASE}/exchange-public/-/activities`, {
                      method: "GET",
                      credentials: "same-origin"
                    });
                    const result = await response.json();
                    const data = parseResult(result);
                    const items = Array.isArray(data) ? data : [];
                    offlineActivityMap.clear();
                    activityList.innerHTML = "";
                    for (const item of items) {
                      const id = normalizeId(item.activityId || "");
                      if (!id) continue;
                      const displayName = normalizeId(item.displayName || item.activityName || id);
                      offlineActivityMap.set(normalizeActivityKey(id), displayName);
                      const option = document.createElement("option");
                      option.value = id;
                      option.label = displayName;
                      activityList.appendChild(option);
                    }
                    refreshActivityNameHint();
                  } catch (err) {
                    setActivityNameHint("活动名称加载失败，可直接填写活动ID提交。", true);
                  }
                };

                const onlineFields = [
                  "online-name-field",
                  "online-email-field",
                  "online-tel-field",
                  "online-postal-field",
                  "online-bureau-field",
                  "online-address-field",
                ];
                const offlineFields = ["offline-card-id-field", "offline-activity-id-field"];

                if (OFFLINE_MODE) {
                  title.textContent = "线下换卡确认";
                  desc.textContent = "仅需填写呼号、卡片ID、活动ID即可提交确认。";
                  submitBtn.textContent = "提交线下换卡确认";
                  onlineFields.forEach((id) => document.getElementById(id)?.classList.add("qsl-hidden"));
                  refreshActivityNameHint();
                  loadOfflineActivities();
                } else {
                  offlineFields.forEach((id) => document.getElementById(id)?.classList.add("qsl-hidden"));
                  submitBtn.textContent = "提交换卡申请";
                }
                if (activityIdInput) {
                  activityIdInput.addEventListener("input", refreshActivityNameHint);
                }

                form.addEventListener("submit", async (event) => {
                  event.preventDefault();
                  error.textContent = "";
                  success.style.display = "none";

                  const callSign = normalizeCallSign(text("callSign"));
                  if (!callSign) {
                    error.textContent = "请填写呼号。";
                    notifyParentHeight();
                    return;
                  }

                  submitBtn.disabled = true;
                  submitBtn.textContent = OFFLINE_MODE ? "提交中..." : "提交中...";
                  try {
                    if (OFFLINE_MODE) {
                      const cardId = normalizeId(text("cardId")).toUpperCase();
                      const activityId = normalizeId(text("activityId"));
                      if (!cardId) {
                        throw new Error("请填写卡片ID。");
                      }
                      if (!activityId) {
                        throw new Error("请填写活动ID。");
                      }
                      const response = await fetch(`${API_BASE}/exchange-public/-/offline-confirm`, {
                        method: "POST",
                        credentials: "same-origin",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                          callSign,
                          cardId,
                          activityId,
                          remarks: text("remarks"),
                        }),
                      });
                      const result = await response.json();
                      const data = parseResult(result);
                      const stationAddress = (data.stationAddress || STATION_ADDRESS || "").trim();
                      success.textContent = stationAddress
                        ? `提交成功。\\n本台通信地址\\n${stationAddress}`
                        : "提交成功。";
                      success.style.display = "";
                    } else {
                      const bureauName = text("bureauName");
                      const response = await fetch(`${API_BASE}/exchange-public/-/requests`, {
                        method: "POST",
                        credentials: "same-origin",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                          sceneType: SCENE_TYPE,
                          callSign,
                          useBureau: !!bureauName,
                          bureauName,
                          email: text("email"),
                          name: text("name"),
                          telephone: text("telephone"),
                          postalCode: text("postalCode"),
                          address: text("address"),
                          remarks: text("remarks"),
                        }),
                      });
                      const result = await response.json();
                      const data = parseResult(result);
                      success.textContent = `提交成功，申请编号：${data.requestName || "-"}，状态：${data.reviewStatus || "-"}`;
                      success.style.display = "";
                    }
                  } catch (e) {
                    const message = e?.message || "提交失败";
                    if (OFFLINE_MODE && STATION_EMAIL) {
                      error.textContent = `${message}\\n本台电子邮件：${STATION_EMAIL}`;
                    } else {
                      error.textContent = message;
                    }
                  } finally {
                    submitBtn.disabled = false;
                    submitBtn.textContent = OFFLINE_MODE ? "提交线下换卡确认" : "提交换卡申请";
                    notifyParentHeight();
                  }
                });
                if (window.ResizeObserver) { new ResizeObserver(() => notifyParentHeight()).observe(document.body); }
                window.addEventListener("load", notifyParentHeight);
                window.addEventListener("resize", notifyParentHeight);
                notifyParentHeight();
              })();
            </script>
          </body>
        </html>
        """;
}
