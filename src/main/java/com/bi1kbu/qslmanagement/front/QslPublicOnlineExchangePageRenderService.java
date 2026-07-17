package com.bi1kbu.qslmanagement.front;

import org.springframework.stereotype.Service;

@Service
public class QslPublicOnlineExchangePageRenderService {

    public String render(
        String rawCallSign,
        String rawRemarks,
        boolean embed,
        String rawEmbedId
    ) {
        var callSign = QslPublicPageRenderSupport.normalizeCallSign(rawCallSign);
        var remarks = QslPublicPageRenderSupport.normalizeText(rawRemarks, 500);
        var embedId = QslPublicPageRenderSupport.normalizeEmbedId(rawEmbedId);
        return TEMPLATE
            .replace("__TITLE__", embed ? "QSL 线上换卡申请卡片" : "QSL 线上换卡申请页面")
            .replace("__CALL_SIGN_HTML__", QslPublicPageRenderSupport.escapeHtml(callSign))
            .replace("__REMARKS_HTML__", QslPublicPageRenderSupport.escapeHtml(remarks))
            .replace("__REMARKS_JS__", QslPublicPageRenderSupport.escapeJs(remarks))
            .replace("__EMBED_MODE__", Boolean.toString(embed))
            .replace("__EMBED_ID__", QslPublicPageRenderSupport.escapeJs(embedId));
    }

    public String renderError(String message, boolean embed) {
        return QslPublicPageRenderSupport.renderError("QSL 线上换卡页", message, embed);
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
              .qsl-subgrid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
              .qsl-display { border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 12px; background: #f9fafb; color: #374151; font-size: 13px; line-height: 1.6; }
              .qsl-display p { margin: 0; }
              .qsl-card-version-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
              .qsl-card-version-option { display: grid; grid-template-columns: auto 92px 1fr; gap: 10px; align-items: center; min-height: 112px; border: 1px solid #d1d5db; border-radius: 8px; padding: 10px; background: #fff; cursor: pointer; }
              .qsl-card-version-option.disabled { opacity: .55; cursor: not-allowed; }
              .qsl-card-version-option input { width: 16px; height: 16px; margin: 0; }
              .qsl-card-version-image { width: 92px; height: 92px; aspect-ratio: 1 / 1; border-radius: 6px; object-fit: contain; background: #eef2f7; border: 1px solid #e5e7eb; }
              .qsl-card-version-image.empty { display: flex; align-items: center; justify-content: center; color: #6b7280; font-size: 12px; }
              .qsl-card-version-title { margin: 0 0 4px; font-size: 14px; font-weight: 700; color: #111827; }
              .qsl-card-version-meta { margin: 0; color: #4b5563; font-size: 12px; line-height: 1.6; }
              .qsl-actions { margin-top: 12px; display: flex; gap: 10px; }
              .qsl-button { height: 38px; border-radius: 8px; border: 1px solid #2563eb; background: #2563eb; color: #fff; padding: 0 18px; cursor: pointer; }
              .qsl-button[disabled] { opacity: .65; cursor: not-allowed; }
              .qsl-hint { margin: 6px 0 0; color: #6b7280; font-size: 12px; }
              .qsl-hint.error { color: #dc2626; }
              .qsl-error { margin: 8px 0 0; color: #dc2626; font-size: 12px; white-space: pre-line; }
              .qsl-success { margin-top: 12px; border: 1px solid #bbf7d0; background: #f0fdf4; border-radius: 10px; padding: 12px; color: #14532d; white-space: pre-line; }
              .qsl-hidden { display: none !important; }
              @media (max-width: 640px) { .qsl-grid, .qsl-subgrid, .qsl-card-version-list { grid-template-columns: 1fr; } .qsl-page { padding: 12px; } .qsl-button { width: 100%; } }
            </style>
          </head>
          <body>
            <main id="qsl-page" class="qsl-page">
              <section class="qsl-card">
                <h1 class="qsl-title">QSL 线上换卡申请</h1>
                <p class="qsl-desc">请填写呼号和地址信息后提交换卡申请。建议填写电子邮箱用以接收状态变更通知。</p>
                <form id="qsl-form">
                  <div class="qsl-grid">
                    <label class="qsl-field full"><span class="qsl-label">呼号</span><input id="callSign" class="qsl-input" maxlength="16" value="__CALL_SIGN_HTML__" placeholder="例如 BI1KBU" /></label>
                    <div class="qsl-field full">
                      <span class="qsl-label">卡片版本（最多选择两张）</span>
                      <div id="cardVersionList" class="qsl-card-version-list"></div>
                      <p id="cardVersionHint" class="qsl-hint">正在加载卡片版本。</p>
                    </div>
                    <label class="qsl-field full">
                      <span class="qsl-label">地址类型</span>
                      <select id="addressMode" class="qsl-input">
                        <option value="">请选择</option>
                        <option value="personal">个人地址</option>
                        <option value="bureau">卡片局地址</option>
                      </select>
                    </label>
                    <label id="personal-name-field"><span class="qsl-label">姓名</span><input id="name" class="qsl-input" maxlength="60" placeholder="请输入姓名" /></label>
                    <label id="personal-tel-field"><span class="qsl-label">电话</span><input id="telephone" class="qsl-input" maxlength="30" placeholder="请输入联系电话" /></label>
                    <label id="personal-postal-field"><span class="qsl-label">邮编</span><input id="postalCode" class="qsl-input" maxlength="20" placeholder="请输入邮编" /></label>
                    <label id="personal-email-field"><span class="qsl-label">电子邮箱（可选）</span><input id="email" class="qsl-input" maxlength="120" placeholder="可选" /></label>
                    <label id="personal-address-field" class="qsl-field full"><span class="qsl-label">通信地址</span><textarea id="address" class="qsl-textarea" maxlength="200" placeholder="请输入通信地址"></textarea></label>
                    <label id="bureau-select-field" class="qsl-field full">
                      <span class="qsl-label">卡片局</span>
                      <select id="bureauSelect" class="qsl-input">
                        <option value="">请选择卡片局</option>
                        <option value="__NEW__">添加新的卡片局</option>
                      </select>
                    </label>
                    <div id="new-bureau-fields" class="qsl-field full">
                      <div class="qsl-subgrid">
                        <label><span class="qsl-label">卡片局名称</span><input id="newBureauName" class="qsl-input" maxlength="80" placeholder="请输入卡片局名称" /></label>
                        <label><span class="qsl-label">卡片局邮编</span><input id="newBureauPostalCode" class="qsl-input" maxlength="20" placeholder="请输入邮编" /></label>
                        <label class="qsl-field full"><span class="qsl-label">卡片局地址</span><textarea id="newBureauAddress" class="qsl-textarea" maxlength="200" placeholder="请输入卡片局地址"></textarea></label>
                      </div>
                    </div>
                    <div id="bureau-display-field" class="qsl-field full">
                      <span class="qsl-label">卡片局地址信息</span>
                      <div class="qsl-display">
                        <p>卡片局名称：<span id="bureauDisplayName">-</span></p>
                        <p>地址：<span id="bureauDisplayAddress">-</span></p>
                        <p>邮编：<span id="bureauDisplayPostalCode">-</span></p>
                      </div>
                    </div>
                    <label class="qsl-field full"><span class="qsl-label">备注</span><textarea id="remarks" class="qsl-textarea" maxlength="500" placeholder="可选">__REMARKS_HTML__</textarea></label>
                  </div>
                  <div class="qsl-actions"><button id="submitBtn" class="qsl-button" type="submit">提交换卡申请</button></div>
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
                const addressModeInput = document.getElementById("addressMode");
                const bureauSelect = document.getElementById("bureauSelect");
                const bureauDisplayName = document.getElementById("bureauDisplayName");
                const bureauDisplayAddress = document.getElementById("bureauDisplayAddress");
                const bureauDisplayPostalCode = document.getElementById("bureauDisplayPostalCode");
                const cardVersionList = document.getElementById("cardVersionList");
                const cardVersionHint = document.getElementById("cardVersionHint");
                const onlineBureauMap = new Map();
                const onlineStationCards = [];
                if (EMBED_MODE) { document.body.classList.add("qsl-embed"); page.classList.add("embed"); }

                const notifyParentHeight = () => {
                  if (!EMBED_MODE || window.parent === window) return;
                  const height = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
                  window.parent.postMessage({ type: "qsl-card-height", embedId: EMBED_ID, height }, "*");
                };
                const normalizeCallSign = (v) => (v || "").trim().toUpperCase();
                const normalizeId = (v) => (v || "").trim();
                const text = (id) => (document.getElementById(id)?.value || "").trim();
                const parseResult = (result) => {
                  if (!result || result.code !== "QSL-0000") throw new Error(result?.message || "接口返回异常");
                  return result.data ?? {};
                };
                const setVisible = (id, visible) => {
                  document.getElementById(id)?.classList.toggle("qsl-hidden", !visible);
                };
                const selectedCardVersions = () => {
                  if (!cardVersionList) return [];
                  return Array.from(cardVersionList.querySelectorAll("input[name='cardVersion']:checked"))
                    .map((input) => input.value)
                    .filter(Boolean);
                };
                const refreshCardVersionState = () => {
                  if (!cardVersionList || !cardVersionHint) return;
                  const selected = selectedCardVersions();
                  const maxSelected = selected.length >= 2;
                  cardVersionList.querySelectorAll("input[name='cardVersion']").forEach((input) => {
                    const remaining = Number(input.dataset.remaining || "0");
                    const qsoOnly = input.dataset.qsoOnly === "true";
                    input.disabled = qsoOnly || remaining <= 0 || (!input.checked && maxSelected);
                    input.closest(".qsl-card-version-option")?.classList.toggle("disabled", input.disabled);
                  });
                  if (!onlineStationCards.length) {
                    cardVersionHint.textContent = "暂无可选卡片版本，请联系管理员配置本台卡片。";
                    cardVersionHint.classList.add("error");
                  } else if (selected.length) {
                    cardVersionHint.textContent = `已选择 ${selected.length} 张卡片。`;
                    cardVersionHint.classList.remove("error");
                  } else {
                    cardVersionHint.textContent = "请选择至少一张卡片版本。";
                    cardVersionHint.classList.remove("error");
                  }
                  notifyParentHeight();
                };
                const renderCardVersions = (items) => {
                  if (!cardVersionList) return;
                  onlineStationCards.splice(0, onlineStationCards.length, ...items);
                  cardVersionList.innerHTML = "";
                  items.forEach((item, index) => {
                    const version = normalizeId(item.cardVersion || "");
                    if (!version) return;
                    const remaining = Number(item.remainingInventory || 0);
                    const label = document.createElement("label");
                    label.className = "qsl-card-version-option";
                    const input = document.createElement("input");
                    input.type = "checkbox";
                    input.name = "cardVersion";
                    input.value = version;
                    input.dataset.remaining = String(remaining);
                    input.dataset.qsoOnly = String(item.qsoOnly === true);
                    input.addEventListener("change", refreshCardVersionState);
                    label.appendChild(input);

                    if (item.previewUrl) {
                      const image = document.createElement("img");
                      image.className = "qsl-card-version-image";
                      image.alt = `${version} 卡片图案`;
                      image.src = item.previewUrl;
                      label.appendChild(image);
                    } else {
                      const emptyImage = document.createElement("div");
                      emptyImage.className = "qsl-card-version-image empty";
                      emptyImage.textContent = "无图案";
                      label.appendChild(emptyImage);
                    }

                    const info = document.createElement("div");
                    const titleText = document.createElement("p");
                    titleText.className = "qsl-card-version-title";
                    titleText.textContent = `${index + 1}. ${version}`;
                    const meta = document.createElement("p");
                    meta.className = "qsl-card-version-meta";
                    meta.textContent = item.qsoOnly === true
                      ? `版本总量：${Number(item.versionTotal || 0)}；库存余量：${remaining}；仅支持实体 QSL 通联卡`
                      : `版本总量：${Number(item.versionTotal || 0)}；库存余量：${remaining}`;
                    info.appendChild(titleText);
                    info.appendChild(meta);
                    label.appendChild(info);
                    cardVersionList.appendChild(label);
                  });
                  refreshCardVersionState();
                };
                const loadOnlineStationCards = async () => {
                  try {
                    const response = await fetch(`${API_BASE}/exchange-online/-/station-cards`, {
                      method: "GET",
                      credentials: "same-origin"
                    });
                    const result = await response.json();
                    if (!response.ok) {
                      throw new Error(result?.message || `HTTP ${response.status}`);
                    }
                    const data = parseResult(result);
                    renderCardVersions(Array.isArray(data) ? data : []);
                  } catch (err) {
                    onlineStationCards.splice(0, onlineStationCards.length);
                    if (cardVersionList) cardVersionList.innerHTML = "";
                    if (cardVersionHint) {
                      const message = err?.message ? `：${err.message}` : "";
                      cardVersionHint.textContent = `卡片版本加载失败，请稍后重试${message}`;
                      cardVersionHint.classList.add("error");
                    }
                    notifyParentHeight();
                  }
                };
                const setBureauDisplay = (name, address, postalCode) => {
                  if (bureauDisplayName) bureauDisplayName.textContent = name || "-";
                  if (bureauDisplayAddress) bureauDisplayAddress.textContent = address || "-";
                  if (bureauDisplayPostalCode) bureauDisplayPostalCode.textContent = postalCode || "-";
                  notifyParentHeight();
                };
                const getSelectedBureau = () => {
                  const selected = bureauSelect?.value || "";
                  if (!selected || selected === "__NEW__") return null;
                  return onlineBureauMap.get(selected) || null;
                };
                const refreshBureauPanel = () => {
                  const selected = bureauSelect?.value || "";
                  const isNew = selected === "__NEW__";
                  setVisible("new-bureau-fields", isNew);
                  setVisible("bureau-display-field", !!selected);
                  if (!selected) {
                    setBureauDisplay("", "", "");
                    return;
                  }
                  if (isNew) {
                    setBureauDisplay(text("newBureauName"), text("newBureauAddress"), text("newBureauPostalCode"));
                    return;
                  }
                  const bureau = getSelectedBureau();
                  setBureauDisplay(bureau?.bureauName || "", bureau?.address || "", bureau?.postalCode || "");
                };
                const refreshAddressMode = () => {
                  const mode = addressModeInput?.value || "";
                  const isPersonal = mode === "personal";
                  const isBureau = mode === "bureau";
                  ["personal-name-field", "personal-tel-field", "personal-postal-field", "personal-email-field", "personal-address-field"]
                    .forEach((id) => setVisible(id, isPersonal));
                  setVisible("bureau-select-field", isBureau);
                  setVisible("new-bureau-fields", isBureau && bureauSelect?.value === "__NEW__");
                  setVisible("bureau-display-field", isBureau && !!(bureauSelect?.value || ""));
                  if (isBureau) {
                    refreshBureauPanel();
                  } else {
                    setBureauDisplay("", "", "");
                  }
                  notifyParentHeight();
                };
                const loadOnlineBureaus = async () => {
                  if (!bureauSelect) return;
                  try {
                    const response = await fetch(`${API_BASE}/exchange-online/-/bureaus`, {
                      method: "GET",
                      credentials: "same-origin"
                    });
                    const result = await response.json();
                    const data = parseResult(result);
                    const items = Array.isArray(data) ? data : [];
                    onlineBureauMap.clear();
                    const addNewOption = bureauSelect.querySelector("option[value='__NEW__']");
                    bureauSelect.querySelectorAll("option[data-bureau='1']").forEach((item) => item.remove());
                    for (const item of items) {
                      const id = normalizeId(item.bureauId || item.bureauName || "");
                      const name = normalizeId(item.bureauName || "");
                      if (!id || !name) continue;
                      onlineBureauMap.set(id, {
                        bureauId: id,
                        bureauName: name,
                        postalCode: normalizeId(item.postalCode || ""),
                        address: normalizeId(item.address || "")
                      });
                      const option = document.createElement("option");
                      option.value = id;
                      option.textContent = name;
                      option.dataset.bureau = "1";
                      bureauSelect.insertBefore(option, addNewOption);
                    }
                  } catch {
                    error.textContent = "卡片局列表加载失败，可选择“添加新的卡片局”后手动填写。";
                  } finally {
                    refreshBureauPanel();
                  }
                };

                refreshAddressMode();
                loadOnlineStationCards();
                loadOnlineBureaus();
                if (addressModeInput) {
                  addressModeInput.addEventListener("change", refreshAddressMode);
                }
                if (bureauSelect) {
                  bureauSelect.addEventListener("change", refreshBureauPanel);
                }
                ["newBureauName", "newBureauPostalCode", "newBureauAddress"].forEach((id) => {
                  document.getElementById(id)?.addEventListener("input", refreshBureauPanel);
                });
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
                    const cardVersions = selectedCardVersions();
                    if (!cardVersions.length) {
                      throw new Error("请选择卡片版本。");
                    }
                    if (cardVersions.length > 2) {
                      throw new Error("最多只能选择两张卡片。");
                    }
                    const addressMode = text("addressMode");
                    if (!addressMode) {
                      throw new Error("请选择地址类型。");
                    }
                    let payload = {
                      callSign,
                      useBureau: false,
                      bureauName: "",
                      email: "",
                      name: "",
                      telephone: "",
                      postalCode: "",
                      address: "",
                      remarks: text("remarks"),
                      cardVersion: cardVersions.join("、"),
                    };
                    if (addressMode === "personal") {
                      const name = text("name");
                      const telephone = text("telephone");
                      const postalCode = text("postalCode");
                      const address = text("address");
                      if (!name || !telephone || !postalCode || !address) {
                        throw new Error("请填写姓名、电话、邮编和通信地址。");
                      }
                      payload = {
                        ...payload,
                        useBureau: false,
                        email: text("email"),
                        name,
                        telephone,
                        postalCode,
                        address,
                      };
                    } else if (addressMode === "bureau") {
                      const selected = text("bureauSelect");
                      if (!selected) {
                        throw new Error("请选择卡片局或添加新的卡片局。");
                      }
                      let bureauName = "";
                      let postalCode = "";
                      let address = "";
                      if (selected === "__NEW__") {
                        bureauName = text("newBureauName");
                        postalCode = text("newBureauPostalCode");
                        address = text("newBureauAddress");
                      } else {
                        const bureau = getSelectedBureau();
                        bureauName = bureau?.bureauName || "";
                        postalCode = bureau?.postalCode || "";
                        address = bureau?.address || "";
                      }
                      if (!bureauName || !postalCode || !address) {
                        throw new Error("请确认卡片局名称、地址和邮编。");
                      }
                      payload = {
                        ...payload,
                        useBureau: true,
                        bureauName,
                        postalCode,
                        address,
                      };
                    } else {
                      throw new Error("地址类型不正确。");
                    }
                    const response = await fetch(`${API_BASE}/exchange-online/-/requests`, {
                      method: "POST",
                      credentials: "same-origin",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify(payload),
                    });
                    const result = await response.json();
                    const data = parseResult(result);
                    const stationAddress = (data.stationAddress || "").trim();
                    success.textContent = stationAddress
                      ? `提交成功，申请编号：${data.requestName || "-"}，状态：${data.reviewStatus || "-"}\\n${stationAddress}`
                      : `提交成功，申请编号：${data.requestName || "-"}，状态：${data.reviewStatus || "-"}`;
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
