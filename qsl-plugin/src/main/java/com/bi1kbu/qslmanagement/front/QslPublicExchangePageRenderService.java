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

    public String render(String rawCallSign, String rawSceneType, boolean embed, String rawEmbedId) {
        var callSign = normalizeCallSign(rawCallSign);
        var sceneType = normalizeSceneType(rawSceneType);
        var embedId = normalizeEmbedId(rawEmbedId);
        var title = "EYEBALL".equals(sceneType) ? "QSL 线下换卡申请" : "QSL 线上换卡申请";

        return BASE_TEMPLATE
            .replace("__TITLE__", embed ? title + "卡片" : title + "页面")
            .replace("__PAGE_TITLE__", title)
            .replace("__PAGE_DESC__", "填写呼号和地址信息后提交申请，后台审核通过后自动生成卡片。")
            .replace("__CALL_SIGN_HTML__", escapeHtml(callSign))
            .replace("__CALL_SIGN_JS__", escapeJs(callSign))
            .replace("__SCENE_TYPE_JS__", escapeJs(sceneType))
            .replace("__EMBED_MODE__", Boolean.toString(embed))
            .replace("__EMBED_ID__", escapeJs(embedId));
    }

    public String renderError(String message, boolean embed) {
        var safeMessage = escapeHtml(message == null || message.isBlank() ? "页面加载失败" : message);
        var safeTitle = embed ? "QSL 换卡申请加载失败" : "QSL 前台换卡申请页加载失败";
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
              .qsl-error { margin: 8px 0 0; color: #dc2626; font-size: 12px; }
              .qsl-success { margin-top: 12px; border: 1px solid #bbf7d0; background: #f0fdf4; border-radius: 10px; padding: 12px; color: #14532d; }
              @media (max-width: 640px) { .qsl-grid { grid-template-columns: 1fr; } .qsl-page { padding: 12px; } .qsl-button { width: 100%; } }
            </style>
          </head>
          <body>
            <main id="qsl-page" class="qsl-page">
              <section class="qsl-card">
                <h1 class="qsl-title">__PAGE_TITLE__</h1>
                <p class="qsl-desc">__PAGE_DESC__</p>
                <form id="qsl-form">
                  <div class="qsl-grid">
                    <label><span class="qsl-label">呼号</span><input id="callSign" class="qsl-input" maxlength="16" value="__CALL_SIGN_HTML__" placeholder="例如 BG7ABC" /></label>
                    <label><span class="qsl-label">联系人姓名</span><input id="name" class="qsl-input" maxlength="60" placeholder="可选" /></label>
                    <label><span class="qsl-label">邮箱</span><input id="email" class="qsl-input" maxlength="120" placeholder="可选" /></label>
                    <label><span class="qsl-label">联系电话</span><input id="telephone" class="qsl-input" maxlength="30" placeholder="可选" /></label>
                    <label><span class="qsl-label">邮编</span><input id="postalCode" class="qsl-input" maxlength="20" placeholder="可选" /></label>
                    <label><span class="qsl-label">卡片局名称</span><input id="bureauName" class="qsl-input" maxlength="80" placeholder="可选，填写时默认按卡局模式" /></label>
                    <label class="qsl-field full"><span class="qsl-label">通信地址</span><textarea id="address" class="qsl-textarea" maxlength="200" placeholder="可选"></textarea></label>
                    <label class="qsl-field full"><span class="qsl-label">备注</span><textarea id="remarks" class="qsl-textarea" maxlength="500" placeholder="可选"></textarea></label>
                  </div>
                  <div class="qsl-actions"><button id="submitBtn" class="qsl-button" type="submit">提交换卡申请</button></div>
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
                const page = document.getElementById("qsl-page");
                const form = document.getElementById("qsl-form");
                const submitBtn = document.getElementById("submitBtn");
                const error = document.getElementById("error");
                const success = document.getElementById("success");
                if (EMBED_MODE) { document.body.classList.add("qsl-embed"); page.classList.add("embed"); }

                const notifyParentHeight = () => {
                  if (!EMBED_MODE || window.parent === window) return;
                  const height = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
                  window.parent.postMessage({ type: "qsl-card-height", embedId: EMBED_ID, height }, "*");
                };
                const normalizeCallSign = (v) => (v || "").trim().toUpperCase();
                const text = (id) => (document.getElementById(id)?.value || "").trim();
                const payload = () => {
                  const bureauName = text("bureauName");
                  return {
                    sceneType: SCENE_TYPE,
                    callSign: normalizeCallSign(text("callSign")),
                    useBureau: !!bureauName,
                    bureauName,
                    email: text("email"),
                    name: text("name"),
                    telephone: text("telephone"),
                    postalCode: text("postalCode"),
                    address: text("address"),
                    remarks: text("remarks")
                  };
                };
                const parseResult = (result) => {
                  if (!result || result.code !== "QSL-0000") throw new Error(result?.message || "接口返回异常");
                  return result.data ?? {};
                };
                form.addEventListener("submit", async (event) => {
                  event.preventDefault();
                  error.textContent = "";
                  success.style.display = "none";
                  const body = payload();
                  if (!body.callSign) { error.textContent = "请填写呼号。"; notifyParentHeight(); return; }
                  submitBtn.disabled = true;
                  submitBtn.textContent = "提交中...";
                  try {
                    const response = await fetch(`${API_BASE}/exchange-public/-/requests`, {
                      method: "POST",
                      credentials: "same-origin",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify(body)
                    });
                    const result = await response.json();
                    const data = parseResult(result);
                    success.textContent = `提交成功，申请编号：${data.requestName || "-"}，状态：${data.reviewStatus || "-"}`;
                    success.style.display = "";
                  } catch (e) {
                    error.textContent = e?.message || "提交失败";
                  } finally {
                    submitBtn.disabled = false;
                    submitBtn.textContent = "提交换卡申请";
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

