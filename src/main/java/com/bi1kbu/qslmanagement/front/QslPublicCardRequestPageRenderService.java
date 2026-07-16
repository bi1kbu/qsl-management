package com.bi1kbu.qslmanagement.front;

import org.springframework.stereotype.Service;

@Service
public class QslPublicCardRequestPageRenderService {

    public String render() {
        return TEMPLATE;
    }

    public String renderError(String message) {
        var safeMessage = QslPublicPageRenderSupport.escapeHtml(
            message == null || message.isBlank() ? "页面加载失败" : message
        );
        return """
            <!doctype html>
            <html lang="zh-CN">
              <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>实体QSL卡申请页面加载失败 | Failed to Load Physical QSL Card Request</title></head>
              <body style="font-family:'PingFang SC','Microsoft YaHei',sans-serif;padding:20px;">
                <h1 style="font-size:18px;margin:0 0 10px;">实体QSL卡申请页面加载失败<br /><small lang="en">Failed to Load Physical QSL Card Request</small></h1>
                <p style="margin:0;color:#4b5563;">%s<br /><span lang="en">The page could not be loaded. Please try again later.</span></p>
              </body>
            </html>
            """.formatted(safeMessage);
    }

    private static final String TEMPLATE = """
        <!doctype html>
        <html lang="zh-CN">
          <head>
            <meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>申请实体QSL卡片 | Physical QSL Card Request</title>
            <style>
              :root { color-scheme: light; font-family: "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif; }
              * { box-sizing: border-box; }
              body { margin: 0; background: #f3f5f9; color: #111827; }
              .page { max-width: 1080px; margin: 0 auto; padding: 20px; }
              .panel { margin-bottom: 14px; padding: 18px; border: 1px solid #e5e7eb; border-radius: 14px; background: #fff; box-shadow: 0 5px 18px rgba(15,23,42,.04); }
              h1 { margin: 0 0 8px; font-size: 24px; }
              h2 { margin: 0 0 14px; font-size: 17px; }
              .desc, .hint { color: #64748b; font-size: 13px; line-height: 1.7; }
              .hint { white-space: pre-line; }
              .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
              .full { grid-column: 1 / -1; }
              .bilingual > .zh, .bilingual > .en { display: block; }
              .bilingual > .en { margin-top: 2px; color: #64748b; font-size: .78em; font-weight: 400; line-height: 1.35; }
              h1.bilingual > .en { font-size: 15px; }
              h2.bilingual > .en { font-size: 12px; }
              .desc.bilingual > .en { margin-top: 4px; font-size: 12px; }
              .field-label { display: block; margin-bottom: 6px; font-size: 13px; font-weight: 600; color: #334155; }
              .field-label > .en { font-size: 11px; }
              .table-label > .en { font-size: 10px; }
              .required-mark { margin-left: 3px; color: #dc2626; font-style: normal; }
              input, select, textarea { width: 100%; border: 1px solid #cbd5e1; border-radius: 9px; background: #fff; color: #111827; font: inherit; }
              input, select { height: 40px; padding: 0 11px; }
              textarea { min-height: 90px; padding: 10px 11px; resize: vertical; }
              button { min-height: 48px; border: 1px solid #2563eb; border-radius: 9px; padding: 6px 18px; background: #2563eb; color: #fff; cursor: pointer; }
              button > .zh, button > .en { display: block; line-height: 1.25; }
              button > .en { margin-top: 2px; font-size: 10px; font-weight: 400; opacity: .86; }
              button.secondary { border-color: #94a3b8; background: #fff; color: #334155; }
              button:disabled { opacity: .55; cursor: not-allowed; }
              .query-row { display: grid; grid-template-columns: 1fr auto; gap: 10px; }
              .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 10px; }
              table { width: 100%; border-collapse: collapse; min-width: 820px; }
              th, td { padding: 10px; border-bottom: 1px solid #e2e8f0; text-align: left; font-size: 13px; vertical-align: middle; }
              th { background: #f8fafc; color: #475569; }
              tr:last-child td { border-bottom: 0; }
              tr.disabled { color: #94a3b8; background: #f8fafc; }
              .row-check { width: 17px; height: 17px; }
              .card-select { min-width: 180px; }
              .reason { color: #b45309; font-weight: 600; }
              .status-text > .en { color: inherit; }
              .hidden { display: none !important; }
              .contact { margin-top: 10px; padding: 13px; border: 1px solid #bfdbfe; border-radius: 10px; background: #eff6ff; color: #1e3a8a; line-height: 1.7; }
              .contact a { color: #1d4ed8; font-weight: 700; }
              .feedback { margin-top: 12px; padding: 12px; border-radius: 10px; white-space: pre-line; font-size: 13px; }
              .feedback.error { border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; }
              .feedback.success { border: 1px solid #bbf7d0; background: #f0fdf4; color: #166534; }
              .actions { margin-top: 14px; display: flex; gap: 10px; }
              @media (max-width: 700px) { .page { padding: 12px; } .grid { grid-template-columns: 1fr; } .full { grid-column: auto; } .query-row { grid-template-columns: 1fr; } .actions button { width: 100%; } }
            </style>
          </head>
          <body>
            <main class="page">
              <section class="panel">
                <h1 class="bilingual"><span class="zh">申请实体QSL卡片</span><span class="en" lang="en">Physical QSL Card Request</span></h1>
                <p class="desc bilingual"><span class="zh">查询与本站的通联记录，选择需要申请的QSO，并为每条记录选择卡片版本。待审核或已有卡片的记录仍会显示，但不能重复申请。</span><span class="en" lang="en">Search your QSO records with this station, select the records to request, and choose a card version for each one. Records pending review or already linked to a card remain visible but cannot be requested again.</span></p>
              </section>

              <form id="requestForm">
                <section class="panel">
                  <h2 class="bilingual"><span class="zh">一、查询并选择通联记录</span><span class="en" lang="en">1. Search and Select QSO Records</span></h2>
                  <div class="query-row">
                    <label><span class="field-label bilingual"><span class="zh">呼号</span><span class="en" lang="en">Call Sign</span></span><input id="callSign" maxlength="16" autocomplete="off" placeholder="BI1KBU" required /></label>
                    <button id="queryButton" type="button"><span class="zh">查询通联记录</span><span class="en" lang="en">Search QSO Records</span></button>
                  </div>
                  <p id="qsoHint" class="hint">请输入呼号后查询。\nEnter a call sign to search.</p>
                  <div id="qsoTableWrap" class="table-wrap hidden">
                    <table>
                      <thead><tr>
                        <th><span class="table-label bilingual"><span class="zh">选择</span><span class="en" lang="en">Select</span></span></th>
                        <th><span class="table-label bilingual"><span class="zh">QSO记录编号</span><span class="en" lang="en">QSO Record ID</span></span></th>
                        <th><span class="table-label bilingual"><span class="zh">通联日期</span><span class="en" lang="en">Date</span></span></th>
                        <th><span class="table-label bilingual"><span class="zh">通联时间</span><span class="en" lang="en">Time</span></span></th>
                        <th><span class="table-label bilingual"><span class="zh">通联频率</span><span class="en" lang="en">Frequency</span></span></th>
                        <th><span class="table-label bilingual"><span class="zh">对方位置</span><span class="en" lang="en">Location</span></span></th>
                        <th><span class="table-label bilingual"><span class="zh">卡片版本</span><span class="en" lang="en">Card Version</span></span></th>
                        <th><span class="table-label bilingual"><span class="zh">状态</span><span class="en" lang="en">Status</span></span></th>
                      </tr></thead>
                      <tbody id="qsoRows"></tbody>
                    </table>
                  </div>
                </section>

                <section class="panel">
                  <h2 class="bilingual"><span class="zh">二、填写收件方式</span><span class="en" lang="en">2. Enter Delivery Information</span></h2>
                  <div class="grid">
                    <label class="full"><span class="field-label bilingual"><span class="zh">收件方式</span><span class="en" lang="en">Delivery Method</span></span><select id="addressType"><option value="PERSONAL">个人地址 / Personal Address</option><option value="BUREAU">卡片局 / QSL Bureau</option></select></label>
                    <div id="personalFields" class="full grid">
                      <label><span class="field-label bilingual"><span class="zh">收件人姓名</span><span class="en" lang="en">Recipient Name</span></span><input id="name" maxlength="60" autocomplete="name" /></label>
                      <label><span class="field-label bilingual"><span class="zh">联系电话</span><span class="en" lang="en">Telephone</span></span><input id="telephone" maxlength="30" autocomplete="tel" /></label>
                      <label><span class="field-label bilingual"><span class="zh">邮政编码<em class="required-mark">*</em></span><span class="en" lang="en">Postal Code *</span></span><input id="postalCode" maxlength="20" autocomplete="postal-code" required /></label>
                      <label class="full"><span class="field-label bilingual"><span class="zh">通信地址<em class="required-mark">*</em></span><span class="en" lang="en">Mailing Address *</span></span><input id="address" maxlength="200" autocomplete="street-address" required /></label>
                    </div>
                    <div id="bureauFields" class="full hidden">
                      <label><span class="field-label bilingual"><span class="zh">卡片局</span><span class="en" lang="en">QSL Bureau</span></span><select id="bureauId"><option value="">卡片局清单尚未加载 / Bureau list not loaded</option><option value="__CONTACT__">申请新增卡片局 / Request a New Bureau by Email</option></select></label>
                      <p id="bureauHint" class="hint">切换到卡片局收件方式后将自动加载公开清单。\nThe public bureau list is refreshed automatically when this delivery method is selected.</p>
                      <div id="bureauContact" class="contact hidden"></div>
                    </div>
                    <label class="full"><span class="field-label bilingual"><span class="zh">申请状态通知邮箱<em class="required-mark">*</em></span><span class="en" lang="en">Notification Email *</span></span><input id="notificationEmail" type="email" maxlength="120" autocomplete="email" required /></label>
                    <label class="full"><span class="field-label bilingual"><span class="zh">备注</span><span class="en" lang="en">Remarks</span></span><textarea id="remarks" maxlength="500" placeholder="选填 / Optional"></textarea></label>
                  </div>
                  <div class="actions"><button id="submitButton" type="submit"><span class="zh">提交申请</span><span class="en" lang="en">Submit Request</span></button></div>
                  <div id="feedback" class="feedback hidden"></div>
                </section>
              </form>
            </main>
            <script>
              (() => {
                const API_BASE = '/apis/api.qsl-management.bi1kbu.com/v1alpha1';
                const el = (id) => document.getElementById(id);
                const state = {
                  qsoItems: [], stationCards: [], bureaus: [], stationEmail: '',
                  bureausLoaded: false, bureausLoading: null
                };
                const text = (id) => (el(id)?.value || '').trim();
                const parseData = (payload) => payload && Object.prototype.hasOwnProperty.call(payload, 'data') ? payload.data : payload;
                const escapeHtml = (value) => String(value || '').replace(/[&<>"']/g, (ch) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
                const biText = (zh, en) => `${zh}\n${en}`;
                const optionText = (zh, en) => `${zh} / ${en}`;
                const statusEnglish = (status) => ({'待处理':'Pending','通过':'Approved','拒绝':'Rejected'}[status] || status || 'Unknown');
                const qsoStatusHtml = (item) => {
                  const zh = item.unselectableReason || '可申请';
                  const en = ({'已有卡片':'Card already exists','待审核':'Pending review','可申请':'Available'}[zh] || 'Unavailable');
                  return `<span class="status-text bilingual"><span class="zh">${escapeHtml(zh)}</span><span class="en" lang="en">${escapeHtml(en)}</span></span>`;
                };
                const setFeedback = (message, error = false) => {
                  const node = el('feedback');
                  node.textContent = message || '';
                  node.className = message ? `feedback ${error ? 'error' : 'success'}` : 'feedback hidden';
                };
                const api = async (url, options = {}) => {
                  const response = await fetch(url, { credentials: 'same-origin', ...options });
                  let payload = {};
                  try { payload = await response.json(); } catch (_) { payload = {}; }
                  if (!response.ok) throw new Error(payload?.message || payload?.data?.message || `HTTP ${response.status}`);
                  return parseData(payload);
                };
                const normalizeCallSign = () => {
                  const value = text('callSign').toUpperCase();
                  el('callSign').value = value;
                  return value;
                };
                const cardOptions = () => state.stationCards
                  .filter((item) => Number(item.remainingInventory || 0) > 0)
                  .map((item) => `<option value="${escapeHtml(item.cardVersion)}">${escapeHtml(item.cardVersion)}（余量 ${Number(item.remainingInventory || 0)} / Remaining ${Number(item.remainingInventory || 0)}）</option>`)
                  .join('');
                const renderQsoRows = () => {
                  const body = el('qsoRows');
                  body.innerHTML = '';
                  for (const item of state.qsoItems) {
                    const row = document.createElement('tr');
                    if (!item.selectable) row.className = 'disabled';
                    row.innerHTML = `
                      <td><input class="row-check" type="checkbox" data-qso="${escapeHtml(item.qsoRecordName)}" aria-label="选择 ${escapeHtml(item.qsoRecordName)} / Select ${escapeHtml(item.qsoRecordName)}" ${item.selectable ? '' : 'disabled'} /></td>
                      <td>${escapeHtml(item.qsoRecordName)}</td><td>${escapeHtml(item.date)}</td><td>${escapeHtml(item.time)}</td>
                      <td>${escapeHtml(item.freq)}</td><td>${escapeHtml(item.qth)}</td>
                      <td><select class="card-select" data-version-for="${escapeHtml(item.qsoRecordName)}" aria-label="卡片版本 / Card Version" disabled><option value="">请选择卡片版本 / Select Card Version</option>${cardOptions()}</select></td>
                      <td class="${item.selectable ? '' : 'reason'}">${qsoStatusHtml(item)}</td>`;
                    body.appendChild(row);
                  }
                  body.querySelectorAll('.row-check').forEach((box) => box.addEventListener('change', () => {
                    const select = body.querySelector(`[data-version-for="${CSS.escape(box.dataset.qso)}"]`);
                    select.disabled = !box.checked;
                    if (!box.checked) select.value = '';
                  }));
                  el('qsoTableWrap').classList.toggle('hidden', state.qsoItems.length === 0);
                };
                const loadStationCards = async () => {
                  state.stationCards = await api(`${API_BASE}/exchange-online/-/station-cards`);
                };
                const renderBureauOptions = (placeholderText = optionText('请选择卡片局', 'Select a QSL Bureau')) => {
                  const select = el('bureauId');
                  select.querySelectorAll('option[data-bureau]').forEach((item) => item.remove());
                  select.querySelector('option[value=""]').textContent = placeholderText;
                  const contact = select.querySelector('option[value="__CONTACT__"]');
                  for (const bureau of state.bureaus) {
                    const option = document.createElement('option');
                    option.value = bureau.bureauId || '';
                    const destinationCountry = bureau.destinationCountry || optionText('未设置去向国', 'Country Not Set');
                    option.textContent = `${destinationCountry}｜${bureau.bureauName || ''}｜${bureau.postalCode || ''}｜${bureau.address || ''}`;
                    option.dataset.bureau = '1';
                    select.insertBefore(option, contact);
                  }
                };
                const loadBureaus = async () => {
                  if (state.bureausLoading) return state.bureausLoading;
                  const hadLoaded = state.bureausLoaded;
                  el('bureauHint').textContent = hadLoaded
                    ? biText('正在刷新卡片局清单……', 'Refreshing the QSL bureau list...')
                    : biText('正在加载卡片局清单……', 'Loading the QSL bureau list...');
                  if (!hadLoaded) renderBureauOptions(optionText('正在加载卡片局清单……', 'Loading QSL bureau list...'));
                  const task = (async () => {
                    try {
                      const result = await api(`${API_BASE}/exchange-online/-/bureaus`);
                      state.bureaus = Array.isArray(result) ? result : [];
                      state.bureausLoaded = true;
                      renderBureauOptions(state.bureaus.length
                        ? optionText('请选择卡片局', 'Select a QSL Bureau')
                        : optionText('暂无可用卡片局', 'No QSL Bureau Available'));
                      el('bureauHint').textContent = state.bureaus.length
                        ? biText(`已加载 ${state.bureaus.length} 个公开卡片局。`, `${state.bureaus.length} public QSL bureaus loaded.`)
                        : biText('当前没有可用的公开卡片局，可通过邮件联系本台申请新增。', 'No public QSL bureau is currently available. Contact this station by email to request a new entry.');
                      return state.bureaus;
                    } catch (error) {
                      if (!hadLoaded) {
                        state.bureaus = [];
                        state.bureausLoaded = false;
                        renderBureauOptions(optionText('卡片局清单加载失败', 'Failed to Load Bureau List'));
                      }
                      el('bureauHint').textContent = hadLoaded
                        ? biText(`刷新失败，仍可使用已加载的卡片局：${error.message}`, 'Refresh failed. The previously loaded QSL bureau list remains available.')
                        : biText('卡片局清单加载失败，请切换收件方式后重试。', 'Failed to load the QSL bureau list. Switch the delivery method and try again.');
                      throw error;
                    } finally {
                      state.bureausLoading = null;
                    }
                  })();
                  state.bureausLoading = task;
                  return task;
                };
                const loadStationContact = async () => {
                  if (state.stationEmail) return state.stationEmail;
                  const result = await api(`${API_BASE}/qsl-card/-/station-contact`);
                  state.stationEmail = result?.stationEmail || '';
                  return state.stationEmail;
                };
                const queryQso = async () => {
                  const callSign = normalizeCallSign();
                  if (!callSign) { setFeedback(biText('请先填写呼号。', 'Please enter a call sign first.'), true); return; }
                  el('queryButton').disabled = true;
                  el('qsoHint').textContent = biText('正在查询通联记录……', 'Searching QSO records...');
                  setFeedback('');
                  try {
                    await loadStationCards();
                    const result = await api(`${API_BASE}/qsl-card/-/qsos?callSign=${encodeURIComponent(callSign)}`);
                    state.qsoItems = Array.isArray(result?.items) ? result.items : [];
                    renderQsoRows();
                    el('qsoHint').textContent = state.qsoItems.length
                      ? biText(`共查询到 ${state.qsoItems.length} 条QSO记录。`, `${state.qsoItems.length} QSO records found.`)
                      : biText('未查询到对应QSO记录。', 'No matching QSO records were found.');
                  } catch (error) {
                    state.qsoItems = [];
                    renderQsoRows();
                    el('qsoHint').textContent = biText('查询失败。', 'Search failed.');
                    setFeedback(biText(`查询失败：${error.message}`, 'Search failed. Please try again later.'), true);
                  } finally { el('queryButton').disabled = false; }
                };
                const refreshAddressType = () => {
                  const bureau = text('addressType') === 'BUREAU';
                  el('personalFields').classList.toggle('hidden', bureau);
                  el('bureauFields').classList.toggle('hidden', !bureau);
                  el('postalCode').required = !bureau;
                  el('address').required = !bureau;
                  el('bureauId').required = bureau;
                  if (bureau) {
                    el('postalCode').setCustomValidity('');
                    el('address').setCustomValidity('');
                  } else {
                    el('bureauId').value = '';
                    el('bureauId').setCustomValidity('');
                    el('bureauContact').classList.add('hidden');
                  }
                  if (bureau) {
                    setFeedback('');
                    loadBureaus().catch((error) => setFeedback(
                      biText(`加载卡片局失败：${error.message}`, 'Failed to load the QSL bureau list. Please switch the delivery method and try again.'),
                      true
                    ));
                  }
                };
                const refreshBureauContact = async () => {
                  const node = el('bureauContact');
                  if (text('bureauId') !== '__CONTACT__') { node.classList.add('hidden'); node.textContent = ''; return; }
                  node.classList.remove('hidden');
                  node.textContent = biText('正在读取本台联系邮箱……', 'Loading the station contact email...');
                  try {
                    const email = await loadStationContact();
                    if (!email) {
                      node.textContent = biText('本台暂未配置联系邮箱，暂时无法申请新增卡片局。', 'This station has not configured a contact email, so a new QSL bureau cannot be requested at this time.');
                      return;
                    }
                    const callSign = normalizeCallSign();
                    const subject = '实体QSL卡申请新增卡片局 / Add a QSL Bureau for Physical QSL Card Request';
                    const body = `用户呼号 / Call Sign：${callSign}\n计划使用的卡片局名称 / QSL Bureau Name：\n卡片局邮编 / Postal Code：\n卡片局地址 / Address：`;
                    node.innerHTML = `<span class="bilingual"><span class="zh">当前清单中没有计划使用的卡片局时，请先发邮件联系本台。邮件中请填写计划使用的卡片局名称、邮编和地址。</span><span class="en" lang="en">If your preferred QSL bureau is not listed, email this station with the bureau name, postal code, and address.</span></span><br /><span class="bilingual"><span class="zh">本台邮箱：<a href="mailto:${escapeHtml(email)}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}">${escapeHtml(email)}</a></span><span class="en" lang="en">Station email: ${escapeHtml(email)}</span></span><br /><span class="bilingual"><span class="zh">本台确认并新增后，请重新打开本页面选择该卡片局。</span><span class="en" lang="en">After this station adds the bureau, reopen this page and select it.</span></span>`;
                  } catch (error) {
                    node.textContent = biText(`读取本台邮箱失败：${error.message}`, 'Failed to load the station contact email.');
                  }
                };
                const selectedQsoItems = () => Array.from(document.querySelectorAll('.row-check:checked')).map((box) => {
                  const qsoRecordName = box.dataset.qso || '';
                  const select = document.querySelector(`[data-version-for="${CSS.escape(qsoRecordName)}"]`);
                  return { qsoRecordName, cardVersion: (select?.value || '').trim() };
                });
                const submit = async (event) => {
                  event.preventDefault();
                  setFeedback('');
                  const qsoItems = selectedQsoItems();
                  if (!qsoItems.length) { setFeedback(biText('请至少选择一条可申请的QSO记录。', 'Select at least one available QSO record.'), true); return; }
                  if (qsoItems.some((item) => !item.cardVersion)) { setFeedback(biText('请为每条已选择的QSO指定卡片版本。', 'Choose a card version for every selected QSO record.'), true); return; }
                  const addressType = text('addressType');
                  const bureauId = text('bureauId');
                  if (addressType === 'BUREAU' && bureauId === '__CONTACT__') {
                    setFeedback(biText('请先通过邮件联系本台新增卡片局，新增完成后再选择并提交申请。', 'Contact this station by email first. Submit the request after the new QSL bureau has been added and selected.'), true);
                    return;
                  }
                  const payload = {
                    callSign: normalizeCallSign(), qsoItems, addressType,
                    name: addressType === 'PERSONAL' ? text('name') : '',
                    telephone: addressType === 'PERSONAL' ? text('telephone') : '',
                    postalCode: addressType === 'PERSONAL' ? text('postalCode') : '',
                    address: addressType === 'PERSONAL' ? text('address') : '',
                    bureauId: addressType === 'BUREAU' ? bureauId : '',
                    notificationEmail: text('notificationEmail'), remarks: text('remarks')
                  };
                  el('submitButton').disabled = true;
                  try {
                    const result = await api(`${API_BASE}/qsl-card/-/requests`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(payload) });
                    await queryQso();
                    setFeedback(`申请提交成功。\nRequest submitted successfully.\n申请编号：${result.requestName}\nRequest ID: ${result.requestName}\n呼号：${result.callSign}\nCall Sign: ${result.callSign}\n审核状态：${result.reviewStatus}\nReview Status: ${statusEnglish(result.reviewStatus)}\nQSO数量：${result.qsoCount}\nQSO Count: ${result.qsoCount}\n提交时间：${result.submittedAt}\nSubmitted At: ${result.submittedAt}`);
                  } catch (error) { setFeedback(biText(`提交失败：${error.message}`, 'Submission failed. Please review the form and try again.'), true); }
                  finally { el('submitButton').disabled = false; }
                };
                const validationMessages = {
                  callSign: biText('请填写呼号。', 'Please enter a call sign.'),
                  postalCode: biText('请填写邮政编码。', 'Please enter a postal code.'),
                  address: biText('请填写通信地址。', 'Please enter a mailing address.'),
                  bureauId: biText('请选择卡片局。', 'Please select a QSL bureau.'),
                  notificationEmail: biText('请填写有效的电子邮箱。', 'Please enter a valid email address.')
                };
                Object.entries(validationMessages).forEach(([id, message]) => {
                  const node = el(id);
                  node.addEventListener('invalid', () => node.setCustomValidity(message));
                  node.addEventListener('input', () => node.setCustomValidity(''));
                  node.addEventListener('change', () => node.setCustomValidity(''));
                });
                el('queryButton').addEventListener('click', queryQso);
                el('callSign').addEventListener('keydown', (event) => { if (event.key === 'Enter') { event.preventDefault(); queryQso(); } });
                el('addressType').addEventListener('change', refreshAddressType);
                el('bureauId').addEventListener('change', refreshBureauContact);
                el('requestForm').addEventListener('submit', submit);
                refreshAddressType();
                loadStationCards().catch(() => {});
              })();
            </script>
          </body>
        </html>
        """;
}
