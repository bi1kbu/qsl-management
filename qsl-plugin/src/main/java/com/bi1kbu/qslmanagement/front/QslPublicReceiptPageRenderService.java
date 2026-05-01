package com.bi1kbu.qslmanagement.front;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class QslPublicReceiptPageRenderService {

    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public String render(
        String rawCallSign,
        String rawCardId,
        String rawRemarks,
        boolean embed,
        String rawEmbedId
    ) {
        var callSign = normalizeCallSign(rawCallSign);
        var cardId = normalizeCardId(rawCardId);
        var remarks = normalizeRemarks(rawRemarks);
        var embedId = normalizeEmbedId(rawEmbedId);

        return BASE_TEMPLATE
            .replace("__TITLE__", embed ? "QSL 卡片签收" : "QSL 前台签收")
            .replace("__CALL_SIGN_HTML__", escapeHtml(callSign))
            .replace("__CARD_ID_HTML__", escapeHtml(cardId))
            .replace("__REMARKS_HTML__", escapeHtml(remarks))
            .replace("__CALL_SIGN_JS__", escapeJs(callSign))
            .replace("__CARD_ID_JS__", escapeJs(cardId))
            .replace("__REMARKS_JS__", escapeJs(remarks))
            .replace("__EMBED_MODE__", Boolean.toString(embed))
            .replace("__EMBED_ID__", escapeJs(embedId));
    }

    public String renderError(String message, boolean embed) {
        var safeMessage = escapeHtml(message == null || message.isBlank() ? "页面加载失败" : message);
        var safeTitle = embed ? "QSL 签收页加载失败" : "QSL 前台签收页加载失败";

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

    private String normalizeCardId(String rawCardId) {
        if (rawCardId == null) {
            return "";
        }
        var normalized = rawCardId.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 128) {
            return normalized.substring(0, 128);
        }
        return normalized;
    }

    private String normalizeEmbedId(String rawEmbedId) {
        var normalized = rawEmbedId == null ? "" : rawEmbedId.trim();
        if (!EMBED_ID_PATTERN.matcher(normalized).matches()) {
            return "qsl-receipt-default";
        }
        return normalized;
    }

    private String normalizeRemarks(String rawRemarks) {
        if (rawRemarks == null) {
            return "";
        }
        var normalized = rawRemarks.trim();
        if (normalized.length() > 500) {
            return normalized.substring(0, 500);
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
                max-width: 860px;
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
              .qsl-form-grid {
                display: grid;
                grid-template-columns: repeat(2, minmax(0, 1fr));
                gap: 12px;
              }
              .qsl-field {
                display: flex;
                flex-direction: column;
                gap: 6px;
              }
              .qsl-field.full {
                grid-column: 1 / -1;
              }
              .qsl-label {
                font-size: 13px;
                color: #374151;
                font-weight: 600;
              }
              .qsl-input,
              .qsl-textarea {
                width: 100%;
                border-radius: 8px;
                border: 1px solid #d1d5db;
                box-sizing: border-box;
                font-size: 14px;
                outline: none;
              }
              .qsl-input {
                height: 38px;
                padding: 0 12px;
              }
              .qsl-textarea {
                min-height: 88px;
                padding: 10px 12px;
                resize: vertical;
              }
              .qsl-input:focus,
              .qsl-textarea:focus {
                border-color: #3b82f6;
                box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
              }
              .qsl-actions {
                margin-top: 14px;
                display: flex;
                gap: 10px;
                flex-wrap: wrap;
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
              .qsl-button[disabled] {
                opacity: 0.65;
                cursor: not-allowed;
              }
              .qsl-hint {
                margin: 8px 0 0;
                font-size: 12px;
                color: #6b7280;
              }
              .qsl-error {
                margin: 8px 0 0;
                color: #dc2626;
                font-size: 12px;
              }
              .qsl-success {
                margin-top: 14px;
                border: 1px solid #bbf7d0;
                background: #f0fdf4;
                border-radius: 10px;
                padding: 12px;
              }
              .qsl-success-title {
                margin: 0 0 8px;
                color: #166534;
                font-size: 14px;
                font-weight: 700;
              }
              .qsl-success-grid {
                display: grid;
                grid-template-columns: repeat(2, minmax(0, 1fr));
                gap: 8px;
              }
              .qsl-success-item {
                font-size: 13px;
                color: #14532d;
              }
              .qsl-success-item span {
                color: #065f46;
                font-weight: 600;
              }
              @media (max-width: 640px) {
                .qsl-page {
                  padding: 12px;
                }
                .qsl-card {
                  padding: 12px;
                }
                .qsl-form-grid,
                .qsl-success-grid {
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
                  <h1 class="qsl-title">QSL 卡片签收</h1>
                  <p class="qsl-desc">请填写呼号与卡片编号提交签收，签收后将回写卡片签收状态。</p>
                </header>

                <form id="qsl-receipt-form">
                  <div class="qsl-form-grid">
                    <label class="qsl-field">
                      <span class="qsl-label">呼号（Call Sign）</span>
                      <input id="qsl-call-sign-input" class="qsl-input" type="text" value="__CALL_SIGN_HTML__" maxlength="16" placeholder="例如 BI1KBU" />
                    </label>
                    <label class="qsl-field">
                      <span class="qsl-label">卡片编号（Card ID）</span>
                      <input id="qsl-card-id-input" class="qsl-input" type="text" value="__CARD_ID_HTML__" maxlength="128" placeholder="例如 C1001" />
                    </label>
                    <label class="qsl-field full">
                      <span class="qsl-label">签收备注（可选）</span>
                      <textarea id="qsl-remarks-input" class="qsl-textarea" maxlength="500" placeholder="可填写签收说明，最多 500 字">__REMARKS_HTML__</textarea>
                    </label>
                  </div>
                  <div class="qsl-actions">
                    <button id="qsl-submit-button" class="qsl-button" type="submit">提交签收</button>
                  </div>
                  <p class="qsl-hint">说明：签收提交接口为 POST，页面仅负责参数预填与提交。</p>
                  <p id="qsl-submit-error" class="qsl-error"></p>
                </form>

                <section id="qsl-success-box" class="qsl-success" style="display: none;">
                  <p class="qsl-success-title">签收成功</p>
                  <div class="qsl-success-grid">
                    <p class="qsl-success-item">卡片记录：<span id="qsl-success-card-name">-</span></p>
                    <p class="qsl-success-item">呼号：<span id="qsl-success-call-sign">-</span></p>
                    <p class="qsl-success-item">卡片类型：<span id="qsl-success-card-type">-</span></p>
                    <p class="qsl-success-item">确认时间：<span id="qsl-success-confirmed-at">-</span></p>
                  </div>
                </section>
              </section>
            </main>

            <script>
              (() => {
                const API_BASE = "/apis/api.qsl-management.halo.run/v1alpha1";
                const EMBED_MODE = __EMBED_MODE__;
                const EMBED_ID = "__EMBED_ID__";
                const CALL_SIGN_PATTERN = /^[A-Z0-9/-]{3,16}$/;

                const page = document.getElementById("qsl-page");
                const form = document.getElementById("qsl-receipt-form");
                const callSignInput = document.getElementById("qsl-call-sign-input");
                const cardIdInput = document.getElementById("qsl-card-id-input");
                const remarksInput = document.getElementById("qsl-remarks-input");
                const submitButton = document.getElementById("qsl-submit-button");
                const submitError = document.getElementById("qsl-submit-error");
                const successBox = document.getElementById("qsl-success-box");
                const successCardName = document.getElementById("qsl-success-card-name");
                const successCallSign = document.getElementById("qsl-success-call-sign");
                const successCardType = document.getElementById("qsl-success-card-type");
                const successConfirmedAt = document.getElementById("qsl-success-confirmed-at");

                if (EMBED_MODE) {
                  document.body.classList.add("qsl-embed");
                  page.classList.add("embed");
                }

                const normalizeCallSign = (value) => (value || "").trim().toUpperCase();
                const normalizeCardId = (value) => (value || "").trim().toUpperCase();
                const normalizeRemarks = (value) => (value || "").trim();
                const safeText = (value) => String(value ?? "");

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

                const clearError = () => {
                  submitError.textContent = "";
                };

                const setError = (message) => {
                  submitError.textContent = message || "签收失败，请稍后重试。";
                  notifyParentHeight();
                };

                const setLoading = (loading) => {
                  submitButton.disabled = loading;
                  submitButton.textContent = loading ? "提交中..." : "提交签收";
                };

                const renderSuccess = (data, fallbackCallSign) => {
                  successCardName.textContent = safeText(data.cardRecordName);
                  successCallSign.textContent = safeText(data.callSign || fallbackCallSign);
                  successCardType.textContent = safeText(data.cardType);
                  successConfirmedAt.textContent = safeText(data.confirmedAt);
                  successBox.style.display = "";
                  notifyParentHeight();
                };

                const parseResult = (result) => {
                  if (!result || result.code !== "QSL-0000") {
                    throw new Error(result?.message || "接口返回异常");
                  }
                  return result.data ?? {};
                };

                const parseResponse = async (response) => {
                  const contentType = (response.headers.get("content-type") || "").toLowerCase();
                  if (contentType.includes("application/json")) {
                    try {
                      return await response.json();
                    } catch (error) {
                      throw new Error("签收接口返回 JSON 解析失败。");
                    }
                  }

                  const responseText = await response.text();
                  if (response.redirected || response.url.includes("/login")) {
                    throw new Error("签收接口被重定向到登录页，请刷新页面后重试。");
                  }
                  if (responseText && responseText.includes("authentication_required")) {
                    throw new Error("签收接口要求认证，请检查匿名提交权限。");
                  }
                  throw new Error(`签收接口返回了非 JSON 响应（HTTP ${response.status}）。`);
                };

                form.addEventListener("submit", async (event) => {
                  event.preventDefault();
                  clearError();
                  successBox.style.display = "none";

                  const callSign = normalizeCallSign(callSignInput.value);
                  const cardId = normalizeCardId(cardIdInput.value);
                  const remarks = normalizeRemarks(remarksInput.value);
                  callSignInput.value = callSign;
                  cardIdInput.value = cardId;

                  if (!callSign) {
                    setError("请填写呼号。");
                    return;
                  }
                  if (!CALL_SIGN_PATTERN.test(callSign)) {
                    setError("呼号格式不合法。");
                    return;
                  }
                  if (!cardId) {
                    setError("请填写卡片编号。");
                    return;
                  }

                  setLoading(true);
                  try {
                    const response = await fetch(`${API_BASE}/receipt-public/-/confirm`, {
                      method: "POST",
                      credentials: "same-origin",
                      headers: {
                        "Content-Type": "application/json"
                      },
                      body: JSON.stringify({
                        callSign,
                        cardId,
                        remarks
                      })
                    });
                    const result = await parseResponse(response);
                    const data = parseResult(result);
                    renderSuccess(data, callSign);
                  } catch (error) {
                    setError(error?.message || "签收失败，请稍后重试。");
                  } finally {
                    setLoading(false);
                    notifyParentHeight();
                  }
                });

                callSignInput.value = normalizeCallSign("__CALL_SIGN_JS__");
                cardIdInput.value = normalizeCardId("__CARD_ID_JS__");
                remarksInput.value = "__REMARKS_JS__";

                if (window.ResizeObserver) {
                  const resizeObserver = new ResizeObserver(() => notifyParentHeight());
                  resizeObserver.observe(document.body);
                }
                window.addEventListener("load", notifyParentHeight);
                window.addEventListener("resize", notifyParentHeight);
                notifyParentHeight();
              })();
            </script>
          </body>
        </html>
        """;
}
