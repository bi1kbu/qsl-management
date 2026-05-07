package com.bi1kbu.qslmanagement.front;

import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class QslPublicOfflineExchangePageRenderService {

    public String render(
        String rawCallSign,
        String rawCardId,
        String rawActivityId,
        String rawRemarks,
        boolean embed,
        String rawEmbedId
    ) {
        var callSign = QslPublicPageRenderSupport.normalizeCallSign(rawCallSign);
        var cardId = QslPublicPageRenderSupport.normalizeText(rawCardId, 128).toUpperCase(Locale.ROOT);
        var activityId = QslPublicPageRenderSupport.normalizeText(rawActivityId, 64);
        var remarks = QslPublicPageRenderSupport.normalizeText(rawRemarks, 500);
        var embedId = QslPublicPageRenderSupport.normalizeEmbedId(rawEmbedId);
        return TEMPLATE
            .replace("__TITLE__", embed ? "线下换卡确认卡片" : "线下换卡确认页面")
            .replace("__CALL_SIGN_HTML__", QslPublicPageRenderSupport.escapeHtml(callSign))
            .replace("__CARD_ID_HTML__", QslPublicPageRenderSupport.escapeHtml(cardId))
            .replace("__ACTIVITY_ID_HTML__", QslPublicPageRenderSupport.escapeHtml(activityId))
            .replace("__REMARKS_HTML__", QslPublicPageRenderSupport.escapeHtml(remarks))
            .replace("__REMARKS_JS__", QslPublicPageRenderSupport.escapeJs(remarks))
            .replace("__EMBED_MODE__", Boolean.toString(embed))
            .replace("__EMBED_ID__", QslPublicPageRenderSupport.escapeJs(embedId));
    }

    public String renderError(String message, boolean embed) {
        return QslPublicPageRenderSupport.renderError("QSL 线下换卡页", message, embed);
    }

    private static final String TEMPLATE = """
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
              .qsl-page { box-sizing: border-box; max-width: 720px; margin: 0 auto; padding: 20px; }
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
              @media (max-width: 640px) { .qsl-grid { grid-template-columns: 1fr; } .qsl-page { padding: 12px; } .qsl-button { width: 100%; } }
            </style>
          </head>
          <body>
            <main id="qsl-page" class="qsl-page">
              <section class="qsl-card">
                <h1 class="qsl-title">线下换卡确认</h1>
                <p class="qsl-desc">请填写呼号、卡片ID、活动ID，并提交确认。</p>
                <form id="qsl-form">
                  <div class="qsl-grid">
                    <label class="qsl-field full"><span class="qsl-label">呼号</span><input id="callSign" class="qsl-input" maxlength="16" value="__CALL_SIGN_HTML__" placeholder="例如 BI1KBU" /></label>
                    <label class="qsl-field"><span class="qsl-label">卡片ID</span><input id="cardId" class="qsl-input" maxlength="128" value="__CARD_ID_HTML__" placeholder="例如 C1001" /></label>
                    <label class="qsl-field">
                      <span class="qsl-label">活动ID</span>
                      <input id="activityId" class="qsl-input" maxlength="64" value="__ACTIVITY_ID_HTML__" placeholder="例如 202604ACT01" list="offlineActivityList" />
                      <datalist id="offlineActivityList"></datalist>
                      <p id="offlineActivityNameHint" class="qsl-hint">活动名称：-</p>
                    </label>
                    <label class="qsl-field full"><span class="qsl-label">备注</span><textarea id="remarks" class="qsl-textarea" maxlength="500" placeholder="可选">__REMARKS_HTML__</textarea></label>
                  </div>
                  <div class="qsl-actions"><button id="submitBtn" class="qsl-button" type="submit">提交线下换卡确认</button></div>
                  <p id="error" class="qsl-error"></p>
                </form>
                <div id="success" class="qsl-success" style="display:none;"></div>
              </section>
            </main>
            <script>
              (() => {
                const API_BASE = "/apis/api.qsl-management.bi1kbu.com/v1alpha1";
                const EMBED_MODE = __EMBED_MODE__;
                const EMBED_ID = "__EMBED_ID__";
                const page = document.getElementById("qsl-page");
                const form = document.getElementById("qsl-form");
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
                  if (!activityIdInput) return;
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
                  if (!activityList) return;
                  try {
                    const response = await fetch(`${API_BASE}/exchange-offline/-/activities`, {
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
                  } catch {
                    setActivityNameHint("活动名称加载失败，可直接填写活动ID提交。", true);
                  }
                };

                refreshActivityNameHint();
                loadOfflineActivities();
                if (activityIdInput) {
                  activityIdInput.addEventListener("input", refreshActivityNameHint);
                }
                const remarksInput = document.getElementById("remarks");
                if (remarksInput) {
                  remarksInput.value = "__REMARKS_JS__";
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
                  submitBtn.textContent = "提交中...";
                  try {
                    const cardId = normalizeId(text("cardId")).toUpperCase();
                    const activityId = normalizeId(text("activityId"));
                    if (!cardId) {
                      throw new Error("请填写卡片ID。");
                    }
                    if (!activityId) {
                      throw new Error("请填写活动ID。");
                    }
                    const response = await fetch(`${API_BASE}/exchange-offline/-/confirm`, {
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
                    const stationAddress = (data.stationAddress || "").trim();
                    success.textContent = stationAddress
                      ? `提交成功。\\n${stationAddress}`
                      : "提交成功。";
                    success.style.display = "";
                  } catch (e) {
                    error.textContent = e?.message || "提交失败";
                  } finally {
                    submitBtn.disabled = false;
                    submitBtn.textContent = "提交线下换卡确认";
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
