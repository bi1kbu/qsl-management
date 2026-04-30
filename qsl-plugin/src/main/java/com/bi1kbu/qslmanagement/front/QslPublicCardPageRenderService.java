package com.bi1kbu.qslmanagement.front;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class QslPublicCardPageRenderService {

    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SCENE_TYPE_PATTERN = Pattern.compile("^(QSO|SWL|ONLINE_EYEBALL|EYEBALL)?$");

    public String render(String rawCallSign, String rawSceneType, boolean embed, String rawEmbedId) {
        var callSign = normalizeCallSign(rawCallSign);
        var sceneType = normalizeSceneType(rawSceneType);
        var embedId = normalizeEmbedId(rawEmbedId);

        return BASE_TEMPLATE
            .replace("__TITLE__", embed ? "QSL 卡片查询" : "QSL 前台查询")
            .replace("__CALL_SIGN_HTML__", escapeHtml(callSign))
            .replace("__CALL_SIGN_JS__", escapeJs(callSign))
            .replace("__SCENE_TYPE_JS__", escapeJs(sceneType))
            .replace("__EMBED_MODE__", Boolean.toString(embed))
            .replace("__EMBED_ID__", escapeJs(embedId));
    }

    public String renderError(String message, boolean embed) {
        var safeMessage = escapeHtml(message == null || message.isBlank() ? "页面加载失败" : message);
        var safeTitle = embed ? "QSL 卡片查询失败" : "QSL 前台页面加载失败";

        return """
            <!doctype html>
            <html lang="zh-CN">
              <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>%s</title>
                <style>
                  :root {
                    color-scheme: light;
                    font-family: "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif;
                  }
                  body {
                    margin: 0;
                    padding: 24px;
                    background: #f4f6fb;
                    color: #111827;
                  }
                  .qsl-error-box {
                    max-width: 720px;
                    margin: 0 auto;
                    background: #ffffff;
                    border: 1px solid #e5e7eb;
                    border-radius: 12px;
                    padding: 20px;
                  }
                  .qsl-error-title {
                    margin: 0 0 10px;
                    font-size: 18px;
                    font-weight: 600;
                  }
                  .qsl-error-message {
                    margin: 0;
                    font-size: 14px;
                    color: #4b5563;
                  }
                </style>
              </head>
              <body>
                <section class="qsl-error-box">
                  <h1 class="qsl-error-title">%s</h1>
                  <p class="qsl-error-message">%s</p>
                </section>
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

    private String normalizeEmbedId(String rawEmbedId) {
        var normalized = rawEmbedId == null ? "" : rawEmbedId.trim();
        if (!EMBED_ID_PATTERN.matcher(normalized).matches()) {
            return "qsl-card-default";
        }
        return normalized;
    }

    private String normalizeSceneType(String rawSceneType) {
        if (rawSceneType == null) {
            return "";
        }
        var normalized = rawSceneType.trim().toUpperCase(Locale.ROOT);
        if (!SCENE_TYPE_PATTERN.matcher(normalized).matches()) {
            return "";
        }
        return normalized;
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
              :root {
                color-scheme: light;
                font-family: "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif;
              }
              body {
                margin: 0;
                background: #f3f5f9;
                color: #111827;
              }
              body.qsl-embed {
                background: transparent;
              }
              .qsl-page {
                box-sizing: border-box;
                max-width: 980px;
                margin: 0 auto;
                padding: 20px;
              }
              .qsl-page.embed {
                max-width: 100%;
                padding: 12px;
              }
              .qsl-card {
                background: #ffffff;
                border: 1px solid #e5e7eb;
                border-radius: 12px;
                box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
                padding: 16px;
              }
              .qsl-page.embed .qsl-card {
                background: transparent;
              }
              .qsl-header {
                margin-bottom: 14px;
              }
              .qsl-title {
                margin: 0 0 6px;
                font-size: 20px;
                font-weight: 700;
                line-height: 1.3;
              }
              .qsl-page.embed .qsl-title {
                font-size: 16px;
              }
              .qsl-desc {
                margin: 0;
                color: #4b5563;
                font-size: 13px;
              }
              .qsl-form {
                display: grid;
                grid-template-columns: 1fr auto;
                gap: 10px;
                margin-bottom: 14px;
              }
              .qsl-page.embed .qsl-form {
                margin-bottom: 10px;
              }
              .qsl-input {
                width: 100%;
                height: 38px;
                border-radius: 8px;
                border: 1px solid #d1d5db;
                padding: 0 12px;
                box-sizing: border-box;
                font-size: 14px;
                outline: none;
              }
              .qsl-input:focus {
                border-color: #3b82f6;
                box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
              }
              .qsl-button {
                height: 38px;
                border-radius: 8px;
                border: 1px solid #2563eb;
                background: #2563eb;
                color: #ffffff;
                padding: 0 18px;
                font-size: 14px;
                cursor: pointer;
              }
              .qsl-button:hover {
                background: #1d4ed8;
              }
              .qsl-overview {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(128px, 1fr));
                gap: 10px;
                margin-bottom: 14px;
              }
              .qsl-overview-item {
                border: 1px solid #e5e7eb;
                border-radius: 8px;
                background: #f9fafb;
                padding: 10px;
              }
              .qsl-overview-label {
                color: #6b7280;
                font-size: 12px;
              }
              .qsl-overview-value {
                margin-top: 4px;
                color: #111827;
                font-size: 18px;
                font-weight: 700;
              }
              .qsl-section {
                margin-top: 12px;
              }
              .qsl-section-title {
                margin: 0 0 8px;
                font-size: 15px;
                font-weight: 600;
                color: #111827;
              }
              .qsl-table-wrap {
                border: 1px solid #e5e7eb;
                border-radius: 8px;
                overflow-x: auto;
                background: #ffffff;
              }
              .qsl-table {
                width: 100%;
                border-collapse: collapse;
                font-size: 13px;
              }
              .qsl-table thead {
                background: #f9fafb;
              }
              .qsl-table th,
              .qsl-table td {
                text-align: left;
                padding: 10px 12px;
                border-bottom: 1px solid #f3f4f6;
                white-space: nowrap;
              }
              .qsl-table tbody tr:last-child td {
                border-bottom: none;
              }
              .qsl-empty {
                margin: 0;
                padding: 14px 0;
                color: #6b7280;
                font-size: 13px;
              }
              .qsl-error {
                margin: 8px 0 0;
                color: #dc2626;
                font-size: 12px;
              }
              @media (max-width: 640px) {
                .qsl-page {
                  padding: 12px;
                }
                .qsl-card {
                  padding: 12px;
                }
                .qsl-form {
                  grid-template-columns: 1fr;
                }
                .qsl-button {
                  width: 100%;
                }
              }
            </style>
          </head>
          <body>
            <main id="qsl-page" class="qsl-page">
              <section class="qsl-card">
                <header class="qsl-header">
                  <h1 class="qsl-title">QSL 前台查询</h1>
                  <p class="qsl-desc">支持按呼号查询公开通联与卡片状态，数据来自 QSL 管理插件公开接口。</p>
                </header>

                <form id="qsl-search-form" class="qsl-form">
                  <input
                    id="qsl-call-sign-input"
                    class="qsl-input"
                    type="text"
                    value="__CALL_SIGN_HTML__"
                    maxlength="16"
                    placeholder="请输入呼号，例如 BG7ABC"
                  />
                  <button class="qsl-button" type="submit">查询</button>
                </form>
                <p id="qsl-search-error" class="qsl-error"></p>

                <div id="qsl-overview" class="qsl-overview"></div>
                <p id="qsl-overview-error" class="qsl-error"></p>

                <section class="qsl-section">
                  <h2 class="qsl-section-title">通联记录</h2>
                  <div class="qsl-table-wrap">
                    <table class="qsl-table">
                      <thead>
                        <tr>
                          <th>记录编号</th>
                          <th>日期</th>
                          <th>时间</th>
                          <th>频率</th>
                          <th>位置</th>
                        </tr>
                      </thead>
                      <tbody id="qsl-qso-body"></tbody>
                    </table>
                  </div>
                  <p id="qsl-qso-empty" class="qsl-empty">请输入呼号后开始查询。</p>
                </section>

                <section class="qsl-section">
                  <h2 class="qsl-section-title">卡片记录</h2>
                  <div class="qsl-table-wrap">
                    <table class="qsl-table">
                      <thead>
                        <tr>
                          <th>卡片编号</th>
                          <th>卡片类型</th>
                          <th>已发卡片</th>
                          <th>已收卡片</th>
                          <th>签收确认</th>
                          <th>卡片日期</th>
                        </tr>
                      </thead>
                      <tbody id="qsl-card-body"></tbody>
                    </table>
                  </div>
                  <p id="qsl-card-empty" class="qsl-empty">请输入呼号后开始查询。</p>
                </section>
              </section>
            </main>

            <script>
              (() => {
                const API_BASE = "/apis/api.qsl-management.halo.run/v1alpha1";
                const EMBED_MODE = __EMBED_MODE__;
                const EMBED_ID = "__EMBED_ID__";
                const state = {
                  callSign: "__CALL_SIGN_JS__",
                  sceneType: "__SCENE_TYPE_JS__"
                };

                const page = document.getElementById("qsl-page");
                const form = document.getElementById("qsl-search-form");
                const input = document.getElementById("qsl-call-sign-input");
                const searchError = document.getElementById("qsl-search-error");
                const overview = document.getElementById("qsl-overview");
                const overviewError = document.getElementById("qsl-overview-error");
                const qsoBody = document.getElementById("qsl-qso-body");
                const cardBody = document.getElementById("qsl-card-body");
                const qsoEmpty = document.getElementById("qsl-qso-empty");
                const cardEmpty = document.getElementById("qsl-card-empty");

                if (EMBED_MODE) {
                  document.body.classList.add("qsl-embed");
                  page.classList.add("embed");
                }

                const normalizeCallSign = (value) => (value || "").trim().toUpperCase();
                const safeText = (value) => String(value ?? "");
                const truthText = (value, trueLabel, falseLabel) => (value ? trueLabel : falseLabel);

                const getApiData = (result) => {
                  if (!result || result.code !== "QSL-0000") {
                    throw new Error(result?.message || "接口返回异常");
                  }
                  return result.data ?? {};
                };

                const notifyParentHeight = () => {
                  if (!EMBED_MODE || window.parent === window) {
                    return;
                  }
                  const height = Math.max(
                    document.body.scrollHeight,
                    document.documentElement.scrollHeight
                  );
                  window.parent.postMessage(
                    {
                      type: "qsl-card-height",
                      embedId: EMBED_ID,
                      height
                    },
                    "*"
                  );
                };

                const renderOverview = (summary) => {
                  const items = [
                    { label: "QSO 总数", value: summary.qsoTotal ?? 0 },
                    { label: "眼球总数", value: summary.eyeballTotal ?? 0 },
                    { label: "卡片总数", value: summary.cardTotal ?? 0 },
                    { label: "待发卡片", value: summary.pendingSendTotal ?? 0 },
                    { label: "已发卡片", value: summary.sentTotal ?? 0 },
                    { label: "发卡签收", value: summary.deliverySignedTotal ?? 0 },
                    { label: "已收卡片", value: summary.receivedTotal ?? 0 }
                  ];
                  overview.innerHTML = items
                    .map(
                      (item) =>
                        `<article class="qsl-overview-item"><div class="qsl-overview-label">${item.label}</div><div class="qsl-overview-value">${item.value}</div></article>`
                    )
                    .join("");
                  notifyParentHeight();
                };

                const renderQsoRows = (rows) => {
                  if (!rows.length) {
                    qsoBody.innerHTML = "";
                    qsoEmpty.textContent = "未查询到通联记录。";
                    notifyParentHeight();
                    return;
                  }
                  qsoBody.innerHTML = rows
                    .map(
                      (item) =>
                        `<tr><td>${safeText(item.id)}</td><td>${safeText(item.date)}</td><td>${safeText(item.time)}</td><td>${safeText(item.freq)}</td><td>${safeText(item.qth)}</td></tr>`
                    )
                    .join("");
                  qsoEmpty.textContent = "";
                  notifyParentHeight();
                };

                const renderCardRows = (rows) => {
                  if (!rows.length) {
                    cardBody.innerHTML = "";
                    cardEmpty.textContent = "未查询到卡片记录。";
                    notifyParentHeight();
                    return;
                  }
                  cardBody.innerHTML = rows
                    .map(
                      (item) =>
                        `<tr><td>${safeText(item.id)}</td><td>${safeText(item.cardType)}</td><td>${truthText(item.cardSent, "是", "否")}</td><td>${truthText(item.cardReceived, "是", "否")}</td><td>${truthText(item.receiptConfirmed, "是", "否")}</td><td>${safeText(item.cardDate)}</td></tr>`
                    )
                    .join("");
                  cardEmpty.textContent = "";
                  notifyParentHeight();
                };

                const loadOverview = async () => {
                  overviewError.textContent = "";
                  try {
                    const response = await fetch(`${API_BASE}/overview-public/summary`, {
                      method: "GET",
                      credentials: "same-origin"
                    });
                    const result = await response.json();
                    renderOverview(getApiData(result));
                  } catch (error) {
                    overviewError.textContent = error?.message || "总览加载失败";
                    notifyParentHeight();
                  }
                };

                const loadRecords = async (callSign) => {
                  searchError.textContent = "";
                  if (!callSign) {
                    qsoBody.innerHTML = "";
                    cardBody.innerHTML = "";
                    qsoEmpty.textContent = "请输入呼号后开始查询。";
                    cardEmpty.textContent = "请输入呼号后开始查询。";
                    notifyParentHeight();
                    return;
                  }

                  try {
                    const params = new URLSearchParams();
                    params.set("callSign", callSign);
                    if (state.sceneType) {
                      params.set("sceneType", state.sceneType);
                    }
                    const response = await fetch(
                      `${API_BASE}/qso-public/records?${params.toString()}`,
                      {
                        method: "GET",
                        credentials: "same-origin"
                      }
                    );
                    const result = await response.json();
                    const data = getApiData(result);
                    renderQsoRows(Array.isArray(data.qsoItems) ? data.qsoItems : []);
                    renderCardRows(Array.isArray(data.cardItems) ? data.cardItems : []);
                  } catch (error) {
                    searchError.textContent = error?.message || "查询失败";
                    qsoBody.innerHTML = "";
                    cardBody.innerHTML = "";
                    qsoEmpty.textContent = "查询失败，请稍后重试。";
                    cardEmpty.textContent = "查询失败，请稍后重试。";
                    notifyParentHeight();
                  }
                };

                form.addEventListener("submit", (event) => {
                  event.preventDefault();
                  const callSign = normalizeCallSign(input.value);
                  input.value = callSign;
                  state.callSign = callSign;
                  loadRecords(callSign);
                });

                if (window.ResizeObserver) {
                  const resizeObserver = new ResizeObserver(() => notifyParentHeight());
                  resizeObserver.observe(document.body);
                }
                window.addEventListener("load", notifyParentHeight);
                window.addEventListener("resize", notifyParentHeight);

                loadOverview();
                if (state.callSign) {
                  loadRecords(state.callSign);
                } else {
                  notifyParentHeight();
                }
              })();
            </script>
          </body>
        </html>
        """;
}
