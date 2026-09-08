/* ==========================================================================
   NotifyLine Admin — SPA
   ==========================================================================
   Session cookie 認證（見 AdminSecurityConfig）。寫入請求附 CSRF token。
   全部 DOM 寫入走 textContent，不用 innerHTML。
   ========================================================================== */

(() => {
    'use strict';

    const API = '/admin/api';
    const $ = (id) => document.getElementById(id);
    const el = (tag, cls, text) => {
        const n = document.createElement(tag);
        if (cls) n.className = cls;
        if (text !== undefined && text !== null) n.textContent = String(text);
        return n;
    };

    // ---------------------------------------------------------------- CSRF

    const csrfToken = () => {
        const hit = document.cookie.split('; ').find((r) => r.startsWith('XSRF-TOKEN='));
        return hit ? decodeURIComponent(hit.slice('XSRF-TOKEN='.length)) : null;
    };

    async function call(path, options = {}) {
        const headers = { Accept: 'application/json', ...(options.headers || {}) };
        if (options.body) {
            headers['Content-Type'] = 'application/json';
            const t = csrfToken();
            if (t) headers['X-XSRF-TOKEN'] = t;
        } else if (options.method && options.method !== 'GET') {
            const t = csrfToken();
            if (t) headers['X-XSRF-TOKEN'] = t;
        }

        let response;
        try {
            response = await fetch(API + path, {
                ...options, headers, redirect: 'error', credentials: 'same-origin',
            });
        } catch (e) {
            // redirect:'error' throws when session expired and server 302s to login
            location.href = '/admin/login.html';
            throw new Error('未登入');
        }
        if (response.status === 401) {
            location.href = '/admin/login.html';
            throw new Error('未登入');
        }
        const payload = await response.json().catch(() => null);
        if (!response.ok || !payload || payload.success === false) {
            const err = (payload && payload.error) || {};
            throw new Error(err.message || `HTTP ${response.status}`);
        }
        return payload.data;
    }

    // --------------------------------------------------------------- 狀態

    const state = { clients: [], users: [], monitors: [], sessions: [], logins: [], loaded: {} };

    // --------------------------------------------------------------- 提示

    function toast(message, isError = false) {
        const item = el('div', 'toast__item' + (isError ? ' toast__item--error' : ''), message);
        $('toast').append(item);
        setTimeout(() => item.remove(), 4000);
    }
    function pageError(message) {
        const box = $('pageError');
        box.textContent = message || '';
        box.hidden = !message;
    }
    function fmtTime(iso) {
        if (!iso) return '—';
        const d = new Date(iso);
        const p = (n) => String(n).padStart(2, '0');
        return `${d.getMonth() + 1}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
    }

    // ------------------------------------------------------- 共用：對照表

    const TARGET_LABEL = { OWNER: '管理者', SELF: '綁定的使用者', USER: '指定使用者', ALL: '全體好友' };
    const STATUS_TAG = { ACTIVE: 'tag--active', DISABLED: 'tag--warning', REVOKED: 'tag--danger' };
    const NOTIF_TAG = { QUEUED: 'tag--muted', SENDING: 'tag--warning', SUCCEEDED: 'tag--active', PARTIAL: 'tag--warning', FAILED: 'tag--danger' };

    function displayName(lineUserId) {
        const u = state.users.find((x) => x.lineUserId === lineUserId);
        return u && u.displayName ? u.displayName : lineUserId;
    }

    // ================================================================ 路由

    const VIEWS = {
        dashboard: { title: '總覽', sub: '服務狀態一覽', load: loadDashboard },
        clients: { title: '憑證', sub: '建立、作廢、設定預設對象', load: loadClients },
        send: { title: '發送', sub: '從後台直接發通知，或排到之後', load: loadSend },
        scheduled: { title: '排程', sub: '還沒到派送時間，可以取消', load: loadScheduled },
        history: { title: '發送紀錄', sub: '最新 100 筆', load: loadHistory },
        users: { title: '使用者', sub: '管理 owner 標記', load: loadUsers },
        monitors: { title: '監控', sub: '定時打 API、有變更才通知', load: loadMonitors },
        logins: { title: '站台登入', sub: '帳密自動登入，取得會過期的 token', load: loadLogins },
        sessions: { title: '登入狀態', sub: '依 host 共用的 cookie jar', load: loadSessions },
    };

    let current = 'dashboard';

    function show(view) {
        if (!VIEWS[view]) view = 'dashboard';
        current = view;
        for (const name of Object.keys(VIEWS)) {
            $('view-' + name).hidden = name !== view;
        }
        document.querySelectorAll('.nav a[data-view]').forEach((a) => {
            if (a.getAttribute('data-view') === view) a.setAttribute('aria-current', 'page');
            else a.removeAttribute('aria-current');
        });
        $('viewTitle').textContent = VIEWS[view].title;
        $('viewSubtitle').textContent = VIEWS[view].sub;
        pageError('');
        VIEWS[view].load();
    }

    function routeFromHash() {
        show((location.hash || '#dashboard').slice(1));
    }

    // ============================================================ 總覽

    async function loadDashboard() {
        const grid = $('statGrid');
        grid.replaceChildren(...Array.from({ length: 4 }, () => {
            const c = el('div', 'stat');
            c.append(el('div', 'skeleton'), el('div', 'skeleton'));
            c.lastChild.style.marginTop = '10px';
            c.lastChild.style.width = '60%';
            return c;
        }));
        $('quotaBody').replaceChildren(el('div', 'skeleton'));
        $('quotaSub').textContent = '載入中…';

        try {
            const [stats, recent] = await Promise.all([call('/stats'), call('/notifications')]);
            renderStats(stats);
            renderDashRecent(recent.slice(0, 10));
        } catch (e) { pageError('載入失敗：' + e.message); }

        // 獨立 try：LINE 配額查詢失敗不該讓上面已經渲染好的內容跟著消失
        try {
            renderQuota(await call('/line-quota'));
        } catch (e) {
            $('quotaSub').textContent = '拿不到（' + e.message + '）';
            $('quotaBody').replaceChildren();
        }
    }

    function renderQuota(q) {
        if (!q.available) {
            $('quotaSub').textContent = '目前拿不到用量資訊。';
            $('quotaBody').replaceChildren();
            return;
        }
        if (q.unlimited) {
            $('quotaSub').textContent = '這個方案沒有月上限。';
            $('quotaBody').replaceChildren(el('div', 'stat__value', q.used + ' 則'), el('div', 'stat__sub', '本月已發送'));
            return;
        }
        const pct = q.limit > 0 ? Math.min(100, Math.round((q.used / q.limit) * 100)) : 0;
        $('quotaSub').textContent = `本月已用 ${q.used} / ${q.limit}（${pct}%）`;
        const bar = el('div');
        bar.style.cssText = 'height:8px;border-radius:999px;background:var(--c-surface-2);overflow:hidden';
        const fill = el('div');
        fill.style.cssText = `height:100%;width:${pct}%;background:${pct >= 90 ? 'var(--c-danger)' : pct >= 70 ? 'var(--c-warning)' : 'var(--c-primary)'};transition:width 300ms`;
        bar.append(fill);
        $('quotaBody').replaceChildren(bar);
    }

    function statCard(label, value, sub, cls) {
        const c = el('div', 'stat');
        c.append(el('div', 'stat__label', label));
        c.append(el('div', 'stat__value' + (cls ? ' ' + cls : ''), value));
        if (sub) c.append(el('div', 'stat__sub', sub));
        return c;
    }

    function renderStats(s) {
        const rate = s.notifications24h > 0
            ? Math.round((s.succeeded24h / s.notifications24h) * 100) + '%'
            : '—';
        $('statGrid').replaceChildren(
            statCard('憑證', s.clientsTotal, `${s.clientsActive} 個 ACTIVE`),
            statCard('活躍使用者', s.usersActive, `${s.owners} 位 owner`),
            statCard('近 24 小時發送', s.notifications24h, `成功率 ${rate}`),
            statCard('失敗（24h）', s.failed24h + s.partial24h,
                `${s.failed24h} 全失敗 · ${s.partial24h} 部分`,
                (s.failed24h + s.partial24h) > 0 ? 'stat__value--warn' : 'stat__value--ok'),
        );
    }

    function renderDashRecent(rows) {
        const body = $('dashRecent').querySelector('tbody');
        if (!rows.length) {
            body.replaceChildren(rowMessage(3, '還沒有任何發送紀錄。'));
            return;
        }
        body.replaceChildren(...rows.map((n) => {
            const tr = el('tr');
            tr.append(td(fmtTime(n.createdAt)));
            const t = el('td'); t.append(el('span', 'tag tag--target', TARGET_LABEL[n.targetType] || n.targetType)); tr.append(t);
            const s = el('td'); s.append(el('span', 'tag ' + (NOTIF_TAG[n.status] || 'tag--muted'), n.status)); tr.append(s);
            return tr;
        }));
    }

    // ============================================================ 憑證

    async function loadClients() {
        try {
            [state.clients, state.users] = await Promise.all([call('/clients'), call('/line-users')]);
            renderClients();
        } catch (e) { pageError('載入失敗：' + e.message); }
    }

    function renderClients() {
        const body = $('clientRows');
        body.replaceChildren(...state.clients.map(clientRow));
        $('clientsEmpty').hidden = state.clients.length !== 0;
    }

    function clientRow(c) {
        const tr = el('tr');

        const name = el('td');
        name.append(el('div', 'cell-strong', c.name));
        name.append(el('div', 'cell-mono', c.clientId));
        tr.append(name);

        const st = el('td');
        st.append(el('span', 'tag ' + (STATUS_TAG[c.status] || 'tag--muted'), c.status));
        tr.append(st);

        tr.append(targetCell(c));

        const sc = el('td');
        const list = el('ul', 'tag-set');
        c.scopes.forEach((s) => list.append(el('li', 'scope', s)));
        sc.append(list);
        tr.append(sc);

        const act = el('td');
        const wrap = el('div', 'row-actions');
        if (c.status === 'ACTIVE') {
            wrap.append(iconBtn('設定對象', () => openTarget(c)));
            wrap.append(iconBtn('作廢', () => revokeClient(c), 'btn--danger'));
        }
        act.append(wrap);
        tr.append(act);
        return tr;
    }

    function targetCell(c) {
        const cell = el('td');
        if (!c.defaultTargetType) { cell.append(el('span', 'tag tag--muted', '未設定')); return cell; }
        cell.append(el('span', 'tag tag--target', TARGET_LABEL[c.defaultTargetType] || c.defaultTargetType));
        if (c.defaultTargetType === 'USER' && c.defaultTargetUserIds && c.defaultTargetUserIds.length) {
            const names = el('ul', 'tag-set');
            names.style.marginTop = '6px';
            c.defaultTargetUserIds.forEach((id) => names.append(el('li', 'scope', displayName(id))));
            cell.append(names);
        }
        return cell;
    }

    async function revokeClient(c) {
        if (!confirm(`作廢憑證「${c.name}」？此動作不可回復，之後它的請求一律 401。`)) return;
        try {
            await call('/clients/' + encodeURIComponent(c.clientId), { method: 'DELETE' });
            toast(`已作廢 ${c.name}`);
            await loadClients();
        } catch (e) { toast(e.message, true); }
    }

    // ---- 建立憑證 ----

    function openCreate() {
        $('createError').hidden = true;
        $('createForm').reset();
        document.querySelector('input[name="createKind"][value="SERVICE"]').checked = true;
        syncCreateKind();
        fillUserSelect($('createUser'));
        $('createDialog').showModal();
    }

    function syncCreateKind() {
        const kind = document.querySelector('input[name="createKind"]:checked').value;
        $('createUserField').hidden = kind !== 'OWNER';
    }

    async function submitCreate(event) {
        event.preventDefault();
        const kind = document.querySelector('input[name="createKind"]:checked').value;
        const quotaRaw = $('createQuota').value.trim();
        const body = {
            name: $('createName').value.trim(),
            kind,
            lineUserId: kind === 'OWNER' ? $('createUser').value : null,
            dailyQuota: quotaRaw ? Number(quotaRaw) : null,
        };
        const btn = $('createSubmit');
        btn.disabled = true; btn.textContent = '建立中…';
        try {
            const created = await call('/clients', { method: 'POST', body: JSON.stringify(body) });
            $('createDialog').close();
            showSecret(created);
            await loadClients();
        } catch (e) {
            const box = $('createError'); box.textContent = e.message; box.hidden = false;
        } finally { btn.disabled = false; btn.textContent = '建立'; }
    }

    function showSecret(created) {
        $('newClientId').textContent = created.clientId;
        $('newSecret').textContent = created.secret;
        $('secretDialog').showModal();
    }

    // ---- 設定預設對象 ----

    let editingClient = null;

    function openTarget(c) {
        editingClient = c;
        $('targetSubtitle').textContent = `${c.name} · ${c.clientId}`;
        $('targetError').hidden = true;
        const type = c.defaultTargetType || '';
        document.querySelectorAll('input[name="targetType"]').forEach((i) => { i.checked = i.value === type; });
        buildUserPicker($('targetUserPicker'), c.defaultTargetUserIds || [], syncTargetPicker);
        syncTargetPicker();
        $('targetDialog').showModal();
    }

    function syncTargetPicker() {
        const isUser = selectedRadio('targetType') === 'USER';
        $('targetUserField').hidden = !isUser;
        const count = checkedValues($('targetUserPicker')).length;
        $('targetUserHint').textContent = `已選 ${count} 人。`;
        $('targetSave').disabled = isUser && count === 0;
    }

    async function submitTarget(event) {
        event.preventDefault();
        const type = selectedRadio('targetType');
        const userIds = type === 'USER' ? checkedValues($('targetUserPicker')) : [];
        const btn = $('targetSave');
        btn.disabled = true; btn.textContent = '儲存中…';
        try {
            const updated = await call(
                '/clients/' + encodeURIComponent(editingClient.clientId) + '/default-target',
                { method: 'PUT', body: JSON.stringify({ type: type || null, userIds }) });
            const i = state.clients.findIndex((c) => c.clientId === updated.clientId);
            if (i >= 0) state.clients[i] = updated;
            renderClients();
            $('targetDialog').close();
            toast(`已更新 ${updated.name} 的預設對象`);
        } catch (e) {
            const box = $('targetError'); box.textContent = e.message; box.hidden = false;
        } finally { btn.disabled = false; btn.textContent = '儲存'; syncTargetPicker(); }
    }

    // ============================================================ 發送

    async function loadSend() {
        try {
            [state.clients, state.users] = await Promise.all([call('/clients'), call('/line-users')]);
        } catch (e) { pageError('載入失敗：' + e.message); return; }
        const sel = $('sendClient');
        sel.replaceChildren(...state.clients.filter((c) => c.status === 'ACTIVE').map((c) => {
            const o = el('option', null, `${c.name} (${c.clientId})`);
            o.value = c.clientId;
            return o;
        }));
        buildUserPicker($('sendUserPicker'), [], () => {});
        document.querySelectorAll('input[name="sendType"]').forEach((i) => { i.checked = i.value === ''; });
        document.querySelectorAll('input[name="sendWhen"]').forEach((i) => { i.checked = i.value === 'now'; });
        $('sendScheduleAt').value = '';
        syncSendPicker();
        syncSendWhen();
    }

    function syncSendPicker() {
        $('sendUserPickerField').hidden = selectedRadio('sendType') !== 'USER';
    }

    function syncSendWhen() {
        const later = selectedRadio('sendWhen') === 'later';
        $('sendScheduleField').hidden = !later;
        if (later && !$('sendScheduleAt').value) {
            // 預先帶入「現在 + 5 分鐘」，省得每次都要自己算
            const d = new Date(Date.now() + 5 * 60 * 1000);
            d.setSeconds(0, 0);
            $('sendScheduleAt').value = toLocalInputValue(d);
        }
    }

    /** datetime-local 要的格式是不帶時區的 YYYY-MM-DDTHH:mm，用本機時間。 */
    function toLocalInputValue(date) {
        const pad = (n) => String(n).padStart(2, '0');
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
            + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
    }

    async function submitSend(event) {
        event.preventDefault();
        $('sendError').hidden = true; $('sendOk').hidden = true;
        const type = selectedRadio('sendType');

        let scheduledAt = null;
        if (selectedRadio('sendWhen') === 'later') {
            const raw = $('sendScheduleAt').value;
            if (!raw) {
                const box = $('sendError'); box.textContent = '請選擇排程時間。'; box.hidden = false;
                return;
            }
            // datetime-local 的值沒有時區資訊，new Date(...) 會當成本機時間解讀 —— 正是我們要的。
            scheduledAt = new Date(raw).toISOString();
        }

        const body = {
            clientId: $('sendClient').value,
            type: type || null,
            userIds: type === 'USER' ? checkedValues($('sendUserPicker')) : [],
            title: $('sendTitle').value.trim() || null,
            text: $('sendText').value,
            scheduledAt,
        };
        const btn = $('sendSubmit');
        btn.disabled = true; btn.textContent = '送出中…';
        try {
            const accepted = await call('/notifications/test', { method: 'POST', body: JSON.stringify(body) });
            const ok = $('sendOk');
            ok.textContent = scheduledAt
                ? `已排程：${accepted.recipientCount} 位收件人、${accepted.batchCount} 批。到「排程」頁查看或取消。`
                : `已受理：${accepted.recipientCount} 位收件人、${accepted.batchCount} 批。到「紀錄」查最終狀態。`;
            ok.hidden = false;
            $('sendText').value = '';
            toast(scheduledAt ? '已排程' : '已送出');
        } catch (e) {
            const box = $('sendError'); box.textContent = e.message; box.hidden = false;
        } finally { btn.disabled = false; btn.textContent = '送出'; }
    }

    // ============================================================ 排程

    async function loadScheduled() {
        try {
            const rows = await call('/notifications/scheduled');
            $('scheduledRows').replaceChildren(...rows.map(scheduledRow));
            $('scheduledEmpty').hidden = rows.length !== 0;
        } catch (e) { pageError('載入失敗：' + e.message); }
    }

    function scheduledRow(s) {
        const tr = el('tr');
        tr.append(td(fmtTime(s.scheduledAt)));
        tr.append(td(s.clientName));
        const t = el('td'); t.append(el('span', 'tag tag--target', TARGET_LABEL[s.targetType] || s.targetType)); tr.append(t);
        tr.append(tdNum(s.recipientCount));
        const act = el('td');
        const wrap = el('div', 'row-actions');
        wrap.append(iconBtn('取消', () => cancelScheduled(s), 'btn--danger'));
        act.append(wrap);
        tr.append(act);
        return tr;
    }

    async function cancelScheduled(s) {
        if (!confirm(`取消排到 ${fmtTime(s.scheduledAt)} 的通知（${s.clientName}）？`)) return;
        try {
            await call('/notifications/' + encodeURIComponent(s.notificationId) + '/schedule', { method: 'DELETE' });
            toast('已取消');
            await loadScheduled();
        } catch (e) { toast(e.message, true); }
    }

    // ============================================================ 紀錄

    async function loadHistory() {
        try {
            const rows = await call('/notifications');
            const body = $('historyRows');
            body.replaceChildren(...rows.map(historyRow));
            $('historyEmpty').hidden = rows.length !== 0;
        } catch (e) { pageError('載入失敗：' + e.message); }
    }

    function historyRow(n) {
        const tr = el('tr');
        tr.style.cursor = 'pointer';
        tr.addEventListener('click', () => openDetail(n));
        tr.append(td(fmtTime(n.createdAt)));
        tr.append(td(n.clientName));
        const t = el('td'); t.append(el('span', 'tag tag--target', TARGET_LABEL[n.targetType] || n.targetType)); tr.append(t);
        tr.append(previewCell(n.preview));
        const s = el('td'); s.append(el('span', 'tag ' + (NOTIF_TAG[n.status] || 'tag--muted'), n.status)); tr.append(s);
        tr.append(tdNum(n.recipientCount));
        tr.append(tdNum(`${n.successCount} / ${n.failureCount}`));
        return tr;
    }

    /** 內容節錄。null = 內容已清空或解析不出來 —— 原因由明細對話框說明，一列的寬度放不下。 */
    function previewCell(preview) {
        if (!preview) return el('td', 'cell-preview cell-preview--empty', '—');
        const cell = el('td', 'cell-preview', preview);
        cell.title = preview; // CSS 會把過長的節錄截成一行，滑鼠移上去看得到全部
        return cell;
    }

    async function openDetail(n) {
        $('detailSubtitle').textContent = `${n.clientName} · ${fmtTime(n.createdAt)}`;
        const body = $('detailBody');
        body.replaceChildren(el('div', 'loading', '載入中…'));
        $('detailDialog').showModal();
        try {
            const d = await call('/notifications/' + encodeURIComponent(n.notificationId));
            renderDetail(d);
        } catch (e) { body.replaceChildren(el('div', 'notice notice--error', e.message)); }
    }

    /** 內容看不到時的說明。鍵對應後端的 AdminDto.ContentStatus。 */
    const CONTENT_UNAVAILABLE = {
        CLEARED: '內容未保存：這則通知指定了 persistPayload=false（送出後即清空），或已過 90 天保留期。其餘欄位仍然有效。',
        UNREADABLE: '內容無法解析：儲存的格式與現行的訊息信封對不起來。其餘欄位仍然有效。',
    };

    function renderDetail(d) {
        const body = $('detailBody');
        body.replaceChildren();

        const summary = el('div', 'notice notice--info');
        summary.textContent = `狀態 ${d.status}｜收件 ${d.recipientCount}｜成功 ${d.successCount}｜失敗 ${d.failureCount}`;
        body.append(summary);

        body.append(renderDetailContent(d.content));

        body.append(el('p', 'fieldset-label detail-section', '批次'));
        const table = el('table', 'grid');
        const thead = el('thead');
        const htr = el('tr');
        ['批次', '收件', '狀態', '嘗試', 'LINE Request Id', '錯誤'].forEach((h) => htr.append(th(h)));
        thead.append(htr); table.append(thead);
        const tb = el('tbody');
        (d.batches || []).forEach((b) => {
            const tr = el('tr');
            tr.append(td('#' + b.batchNo));
            tr.append(tdNum(b.recipientCount));
            const s = el('td'); s.append(el('span', 'tag ' + (b.status === 'SENT' ? 'tag--active' : b.status === 'FAILED' ? 'tag--danger' : 'tag--muted'), b.status)); tr.append(s);
            tr.append(tdNum(b.attemptCount));
            tr.append(tdMono(b.lineRequestId || '—'));
            tr.append(td(b.errorCode || '—'));
            tb.append(tr);
        });
        table.append(tb);
        const wrap = el('div', 'table-wrap'); wrap.append(table); body.append(wrap);
    }

    /**
     * 發送內容區塊：這則通知實際送出去的訊息。
     *
     * <p>文字訊息直接顯示內文（那是收件人看到的東西）；其餘型別（flex、template…）
     * 沒有單一「內文」可言，只有原始 message object 看得出全貌，所以那些預設展開 JSON。
     */
    function renderDetailContent(content) {
        const section = el('section', 'detail-content');
        const head = el('div', 'field-row-between');
        head.append(el('p', 'fieldset-label', '發送內容'));
        if (content && content.status === 'AVAILABLE' && content.notificationDisabled) {
            head.append(el('span', 'tag tag--muted', '靜音發送'));
        }
        section.append(head);

        if (!content) {
            section.append(el('div', 'notice notice--warning', '這筆紀錄沒有內容欄位。'));
            return section;
        }
        if (content.status !== 'AVAILABLE') {
            section.append(el('div', 'notice notice--warning',
                CONTENT_UNAVAILABLE[content.status] || '內容無法顯示。'));
            return section;
        }
        if (!content.messages || content.messages.length === 0) {
            section.append(el('div', 'notice notice--warning', '這則通知沒有任何訊息物件。'));
            return section;
        }

        content.messages.forEach((m) => section.append(messageBlock(m, content.messages.length)));
        return section;
    }

    function messageBlock(message, total) {
        // text 只有文字訊息有（後端保證：其他型別一律 null）
        const hasText = message.text !== null && message.text !== undefined;
        const box = el('div', 'msg');

        const head = el('div', 'msg__head');
        head.append(el('span', 'tag tag--muted',
            total > 1 ? `第 ${message.index + 1} 則 · ${message.type}` : message.type));
        head.append(copyBtn(hasText ? message.text : message.json));
        box.append(head);

        if (hasText) {
            box.append(el('pre', 'test-message', message.text));
        }

        // 文字訊息的 JSON 是補充資料，預設收起來；其他型別的 JSON 就是內容本身，預設展開
        const raw = document.createElement('details');
        raw.className = 'msg__raw';
        raw.open = !hasText;
        const summary = document.createElement('summary');
        summary.textContent = '原始 message object';
        raw.append(summary);
        raw.append(el('pre', 'code-block', message.json));
        box.append(raw);

        return box;
    }

    function copyBtn(text) {
        const btn = el('button', 'btn btn--ghost btn--sm', '複製');
        btn.type = 'button';
        btn.addEventListener('click', () => navigator.clipboard.writeText(text).then(
            () => toast('已複製'),
            () => toast('複製失敗，請手動選取', true)));
        return btn;
    }

    // ============================================================ 使用者

    async function loadUsers() {
        try {
            state.users = await call('/line-users');
            const body = $('userRows');
            body.replaceChildren(...state.users.map(userRow));
            $('usersEmpty').hidden = state.users.length !== 0;
        } catch (e) { pageError('載入失敗：' + e.message); }
    }

    function userRow(u) {
        const tr = el('tr');
        const name = el('td');
        name.append(el('div', 'cell-strong', u.displayName || '(未同步顯示名稱)'));
        tr.append(name);
        tr.append(tdMono(u.lineUserId));
        const own = el('td');
        own.append(el('span', 'tag ' + (u.owner ? 'tag--target' : 'tag--muted'), u.owner ? 'owner' : '一般'));
        tr.append(own);
        const act = el('td');
        const wrap = el('div', 'row-actions');
        wrap.append(iconBtn(u.owner ? '取消 owner' : '設為 owner', () => toggleOwner(u)));
        act.append(wrap);
        tr.append(act);
        return tr;
    }

    async function toggleOwner(u) {
        try {
            const updated = await call('/line-users/' + encodeURIComponent(u.lineUserId) + '/owner',
                { method: 'PUT', body: JSON.stringify({ owner: !u.owner }) });
            const i = state.users.findIndex((x) => x.lineUserId === updated.lineUserId);
            if (i >= 0) state.users[i] = updated;
            await loadUsers();
            toast(updated.owner ? `已設為 owner` : `已取消 owner`);
        } catch (e) { toast(e.message, true); }
    }

    // ============================================================ 監控

    const COMPARE_MODE_LABEL = { WHOLE_BODY: '整包比對', EXTRACTED: '指定欄位', NEW_ITEMS: '只通知新項目' };
    const RUN_OUTCOME_TAG = { CHANGED: 'tag--active', UNCHANGED: 'tag--muted', FAILED: 'tag--danger', SKIPPED: 'tag--warning' };

    async function loadMonitors() {
        try {
            [state.monitors, state.clients] = await Promise.all([call('/monitors'), call('/clients')]);
            renderMonitors();
        } catch (e) { pageError('載入失敗：' + e.message); }
    }

    function renderMonitors() {
        const body = $('monitorRows');
        body.replaceChildren(...state.monitors.map(monitorRow));
        $('monitorsEmpty').hidden = state.monitors.length !== 0;
    }

    function monitorRow(m) {
        const tr = el('tr');

        const name = el('td');
        name.append(el('div', 'cell-strong', m.name));
        name.append(el('div', 'cell-mono', m.host || m.url));
        tr.append(name);

        tr.append(td(m.host || '—'));
        tr.append(td(m.intervalSeconds + 's'));

        const modeTd = el('td');
        modeTd.append(el('span', 'tag tag--target', COMPARE_MODE_LABEL[m.compareMode] || m.compareMode));
        tr.append(modeTd);

        tr.append(monitorStatusCell(m));
        tr.append(td(fmtTime(m.lastRunAt)));

        const act = el('td');
        const wrap = el('div', 'row-actions');
        wrap.append(iconBtn('紀錄', () => openMonitorRuns(m)));
        wrap.append(iconBtn(m.enabled ? '停用' : '啟用', () => toggleMonitorEnabled(m)));
        wrap.append(iconBtn('編輯', () => openMonitorEdit(m)));
        wrap.append(iconBtn('刪除', () => deleteMonitor(m), 'btn--danger'));
        act.append(wrap);
        tr.append(act);
        return tr;
    }

    function monitorStatusCell(m) {
        const cell = el('td');
        if (!m.enabled) {
            cell.append(el('span', 'tag tag--muted', '停用'));
        } else if (m.consecutiveFailures > 0) {
            cell.append(el('span', 'tag tag--danger', `失敗 ${m.consecutiveFailures} 次`));
        } else {
            cell.append(el('span', 'tag tag--active', '正常'));
        }
        return cell;
    }

    async function toggleMonitorEnabled(m) {
        try {
            const updated = await call('/monitors/' + m.id + '/enabled',
                { method: 'POST', body: JSON.stringify({ enabled: !m.enabled }) });
            const i = state.monitors.findIndex((x) => x.id === updated.id);
            if (i >= 0) state.monitors[i] = updated;
            renderMonitors();
            toast(updated.enabled ? '已啟用' : '已停用');
        } catch (e) { toast(e.message, true); }
    }

    async function deleteMonitor(m) {
        if (!confirm(`刪除監控「${m.name}」？此動作不可回復。`)) return;
        try {
            await call('/monitors/' + m.id, { method: 'DELETE' });
            toast(`已刪除 ${m.name}`);
            await loadMonitors();
        } catch (e) { toast(e.message, true); }
    }

    // ---- 匯入（貼上 cURL / fetch / 自訂 JSON） ----

    /** 給使用者拿去餵 AI 的自訂 JSON schema，逐字對應 Docs/plan/12-API監控易用性升級.md §2.4。 */
    const MONITOR_IMPORT_SCHEMA = '{\n'
        + '  "version": 1,\n'
        + '  "name": "範例監控",\n'
        + '  "request": {\n'
        + '    "url": "https://example.com/api/orders?since={{now-1h.iso8601}}",\n'
        + '    "method": "GET",\n'
        + '    "headers": { "accept": "application/json" },\n'
        + '    "body": null\n'
        + '  },\n'
        + '  "intervalSeconds": 300,\n'
        + '  "compare": {\n'
        + '    "mode": "NEW_ITEMS",\n'
        + '    "itemPointer": "/data/orders",\n'
        + '    "itemKeyPointer": "/id",\n'
        + '    "rules": [{ "name": "amount", "pointer": "/amount" }]\n'
        + '  },\n'
        + '  "messageTemplate": "新訂單 {{item.amount}} 元"\n'
        + '}';

    function clearImportState() {
        $('monImportRaw').value = '';
        $('monImportError').hidden = true;
    }

    function showImportError(message) {
        const box = $('monImportError');
        box.textContent = message;
        box.hidden = false;
    }

    /** 把 /monitors/import 的回應（url/method/headers/body）套進表單。不動名稱、比對模式等填表欄位。 */
    function applyImportedRequest(result) {
        $('monUrl').value = result.url || '';
        $('monMethod').value = result.method || 'GET';
        $('monBody').value = result.body || '';
        const headerKeys = Object.keys(result.headers || {});
        $('monHeaders').value = headerKeys.length ? JSON.stringify(result.headers, null, 2) : '';
        syncMonitorMethod();
    }

    async function importMonitorRaw() {
        const raw = $('monImportRaw').value;
        if (!raw.trim()) { showImportError('請先貼上內容。'); return; }
        $('monImportError').hidden = true;

        const btn = $('monImportBtn');
        btn.disabled = true; btn.textContent = '解析中…';
        try {
            const result = await call('/monitors/import', { method: 'POST', body: JSON.stringify({ raw }) });
            applyImportedRequest(result);
            // 貼上框內容含 cookie／token，帶入表單後盡快從畫面上清掉，不留在 DOM 裡。
            $('monImportRaw').value = '';
            toast('已解析並帶入下面的欄位');
        } catch (e) {
            showImportError(e.message);
        } finally { btn.disabled = false; btn.textContent = '解析並帶入'; }
    }

    function copyImportSchema() {
        navigator.clipboard.writeText(MONITOR_IMPORT_SCHEMA).then(
            () => toast('已複製 JSON 格式'),
            () => toast('複製失敗，請手動選取', true));
    }

    // ---- 新增/編輯抽屜 ----

    let editingMonitor = null; // null = 新增模式
    /** 訊息模板是否仍是「還沒被使用者動過」的狀態——只有這樣才允許自動改寫預設模板（doc 14 §2.2）。 */
    let templatePristine = true;

    function openMonitorCreate() {
        editingMonitor = null;
        $('monitorTitle').textContent = '新增監控';
        $('monitorError').hidden = true;
        $('monitorForm').reset();
        clearImportState();
        fillClientSelect($('monClient'));
        $('monInterval').value = 60;
        $('monCompareMode').value = 'WHOLE_BODY';
        $('monEnabled').checked = true;
        $('monNotifyOnFailure').checked = true;
        $('monHeaders').value = '';
        renderHeadersCurrentHint(null);
        fillLoginSelect(null);
        templatePristine = true;
        setRuleRows([]);
        setSecretsExisting([]);
        setSecretsNewRows([]);
        setComputedFieldRows([]);
        refreshDefaultTemplateIfPristine();
        clearTestResult();
        syncMonitorMethod();
        syncMonitorCompareMode();
        $('monitorDialog').showModal();
    }

    function openMonitorEdit(m) {
        editingMonitor = m;
        templatePristine = false; // 編輯既有監控不套用任何預設，一律顯示已存的值（doc 14 §2.2 末段）
        $('monitorTitle').textContent = '編輯監控：' + m.name;
        $('monitorError').hidden = true;
        clearImportState();
        $('monName').value = m.name;
        fillClientSelect($('monClient'));
        $('monClient').value = m.clientId;
        $('monMethod').value = m.method;
        $('monUrl').value = m.url;
        $('monBody').value = m.requestBody || '';
        $('monHeaders').value = '';
        renderHeadersCurrentHint(m);
        $('monInterval').value = m.intervalSeconds;
        $('monCompareMode').value = m.compareMode;
        setRuleRows(m.extractRules || []);
        setSecretsExisting(m.secretNames || []);
        setSecretsNewRows([]);
        setComputedFieldRows(m.computedFields || []);
        $('monItemPointer').value = m.itemPointer || '';
        $('monItemKeyPointer').value = m.itemKeyPointer || '';
        $('monTemplate').value = m.messageTemplate;
        $('monCooldown').value = m.cooldownSeconds;
        $('monMaxPerDay').value = m.maxNotificationsPerDay != null ? m.maxNotificationsPerDay : '';
        $('monEnabled').checked = m.enabled;
        $('monNotifyOnFailure').checked = m.notifyOnFailure;
        fillLoginSelect(m.loginId);
        clearTestResult();
        syncMonitorMethod();
        syncMonitorCompareMode();
        $('monitorDialog').showModal();
    }

    /**
     * 更新 header 欄位上方「目前已設定：...」的提示與「顯示目前值」按鈕的顯示與否。
     *
     * <p>{@code m} 為 {@code null}（新增模式）時沒有「既有值」可言，整排隱藏。編輯模式下
     * 只看 {@code headerNames}（見 AdminDto.MonitorSummary 的說明，儲存後一定會回傳這個
     * 欄位）——這是這次修正的重點：儲存後如果這裡沒有列出剛剛存的名稱，代表真的沒存進去，
     * 而不是像過去那樣，欄位單純被清空成看不出兩種情況的差異。
     */
    function renderHeadersCurrentHint(m) {
        const row = $('monHeadersCurrentRow');
        const hint = $('monHeadersCurrent');
        const showBtn = $('monHeadersShowBtn');
        if (!m) {
            row.hidden = true;
            hint.textContent = '';
            showBtn.hidden = true;
            return;
        }
        const names = m.headerNames || [];
        row.hidden = false;
        showBtn.hidden = names.length === 0;
        hint.textContent = names.length
            ? `目前已設定：${names.join(', ')}（留空 = 不變更）`
            : '目前沒有設定自訂 header。';
    }

    /** 「顯示目前值」：解密回傳一次，填進 textarea 讓使用者可以編輯後直接存檔。不落地、不快取。 */
    async function showCurrentHeaders() {
        if (!editingMonitor) return;
        const btn = $('monHeadersShowBtn');
        btn.disabled = true;
        try {
            const headers = await call('/monitors/' + editingMonitor.id + '/headers');
            $('monHeaders').value = Object.keys(headers).length ? JSON.stringify(headers, null, 2) : '';
            toast('已帶入目前的 header 值');
        } catch (e) {
            toast(e.message, true);
        } finally {
            btn.disabled = false;
        }
    }

    function fillClientSelect(sel) {
        sel.replaceChildren(...state.clients.filter((c) => c.status === 'ACTIVE').map((c) => {
            const o = el('option', null, `${c.name} (${c.clientId})`);
            o.value = c.clientId;
            return o;
        }));
    }

    function syncMonitorMethod() {
        $('monBodyField').hidden = $('monMethod').value !== 'POST';
    }

    function syncMonitorCompareMode() {
        $('monItemPointerField').hidden = $('monCompareMode').value !== 'NEW_ITEMS';
    }

    function clearTestResult() {
        const box = $('monTestResult');
        box.hidden = true;
        box.replaceChildren();
        $('monRetestBtn').hidden = true;
        lastMonitorTest = null;
        resetFieldVolatility();
    }

    // ---- 抽取欄位（extractRules）編輯 ----

    function setRuleRows(rules) {
        $('monRulesRows').replaceChildren();
        (rules || []).forEach((r) => addRuleRow(r.name, r.pointer));
    }

    function addRuleRow(name, pointer) {
        const row = el('div', 'rule-row');
        const nameInput = document.createElement('input');
        nameInput.type = 'text';
        nameInput.placeholder = '名稱（value.NAME）';
        nameInput.className = 'rule-row__name';
        nameInput.value = name || '';
        const pointerInput = document.createElement('input');
        pointerInput.type = 'text';
        pointerInput.placeholder = 'JsonPointer，例如 /data/0/status';
        pointerInput.className = 'rule-row__pointer';
        pointerInput.value = pointer || '';
        const removeBtn = el('button', 'btn btn--sm btn--ghost', '移除');
        removeBtn.type = 'button';
        removeBtn.addEventListener('click', () => { row.remove(); refreshDefaultTemplateIfPristine(); });
        row.append(nameInput, pointerInput, removeBtn);
        $('monRulesRows').append(row);
        refreshDefaultTemplateIfPristine();
    }

    function collectRuleRows() {
        return Array.from($('monRulesRows').querySelectorAll('.rule-row'))
            .map((row) => ({
                name: row.querySelector('.rule-row__name').value.trim(),
                pointer: row.querySelector('.rule-row__pointer').value.trim(),
            }))
            .filter((r) => r.name || r.pointer);
    }

    // ---- 預設訊息模板：doc 14 §2 ----
    //
    // 欄位名一律動態取自使用者自己的解析規則，絕不可寫死——寫死等於對其他監控都是錯的
    // （doc 14 §2.2）。用 pristine 旗標而非「內容等於預設字串」判斷是否還能自動覆寫：
    // 使用者可能手動改回一模一樣的內容，仍然算「已經動過」，之後不再自動覆寫。

    function ruleNames() {
        return Array.from($('monRulesRows').querySelectorAll('.rule-row__name'))
            .map((i) => i.value.trim())
            .filter(Boolean);
    }

    function firstRuleName() {
        return ruleNames()[0] || 'NAME';
    }

    function defaultMessageTemplate(fieldName) {
        return `{{monitor.name}}\n原本：{{old.${fieldName}}}\n現在：{{value.${fieldName}}}\n時間：{{now}}`;
    }

    /** 只在「新增監控」且模板仍是 pristine 時才覆寫；編輯既有監控一律不套用（doc 14 §2.2 末段）。 */
    function refreshDefaultTemplateIfPristine() {
        if (editingMonitor || !templatePristine) return;
        $('monTemplate').value = defaultMessageTemplate(firstRuleName());
    }

    // ---- 密鑰（monitor_secret）編輯：doc 13 §7 ----

    /** 已存在的密鑰只顯示名稱＋刪除鈕，絕不顯示值——理由同 monHeadersHint。 */
    function setSecretsExisting(names) {
        const box = $('monSecretsExisting');
        box.replaceChildren();
        (names || []).forEach((name) => box.append(existingSecretRow(name)));
    }

    function existingSecretRow(name) {
        const row = el('div', 'secret-row--existing');
        const label = el('span');
        label.append(el('span', 'cell-mono', name), document.createTextNode(' '), el('span', 'tag tag--active', '已設定'));
        row.append(label);
        const removeBtn = el('button', 'btn btn--sm btn--danger', '刪除');
        removeBtn.type = 'button';
        removeBtn.addEventListener('click', () => deleteExistingSecret(name, row));
        row.append(removeBtn);
        return row;
    }

    /** 刪除是獨立端點、立刻生效（不透過「儲存」）——理由同 sessions 頁的 clearSession，見 doc 13 §7。 */
    async function deleteExistingSecret(name, row) {
        if (!editingMonitor) return;
        if (!confirm(`刪除密鑰「${name}」？此動作不可回復，任何引用它的計算欄位下次輪詢會失敗。`)) return;
        try {
            await call('/monitors/' + editingMonitor.id + '/secrets/' + encodeURIComponent(name), { method: 'DELETE' });
            row.remove();
            toast(`已刪除密鑰 ${name}`);
        } catch (e) { toast(e.message, true); }
    }

    function setSecretsNewRows(rows) {
        $('monSecretsNewRows').replaceChildren();
        (rows || []).forEach((r) => addSecretRow(r.name, r.value));
    }

    function addSecretRow(name, value) {
        const row = el('div', 'secret-row');
        const nameInput = document.createElement('input');
        nameInput.type = 'text';
        nameInput.placeholder = '名稱（secret.NAME）';
        nameInput.className = 'secret-row__name';
        nameInput.value = name || '';
        const valueInput = document.createElement('input');
        valueInput.type = 'password';
        valueInput.placeholder = '值';
        valueInput.className = 'secret-row__value';
        valueInput.autocomplete = 'off';
        valueInput.value = value || '';
        const removeBtn = el('button', 'btn btn--sm btn--ghost', '移除');
        removeBtn.type = 'button';
        removeBtn.addEventListener('click', () => row.remove());
        row.append(nameInput, valueInput, removeBtn);
        $('monSecretsNewRows').append(row);
    }

    /** 只保留有填名稱的列——名稱有填但值留空，代表「不變更既有值」（同 header 的既有慣例，由後端判斷）。 */
    function collectSecretRows() {
        const result = {};
        Array.from($('monSecretsNewRows').querySelectorAll('.secret-row')).forEach((row) => {
            const name = row.querySelector('.secret-row__name').value.trim();
            if (!name) return;
            result[name] = row.querySelector('.secret-row__value').value;
        });
        return result;
    }

    // ---- 計算欄位（computed_fields）編輯：doc 13 §2.2、§4、§7 ----

    const HASH_ALGORITHMS = ['MD5', 'SHA1', 'SHA256', 'SHA512', 'HMAC_SHA1', 'HMAC_SHA256'];
    const HASH_ENCODINGS = ['HEX_UPPER', 'HEX_LOWER', 'BASE64'];

    function setComputedFieldRows(fields) {
        $('monComputedRows').replaceChildren();
        (fields || []).forEach((f) => addComputedFieldBlock(f));
    }

    function selectEl(cls, options, selected) {
        const sel = document.createElement('select');
        sel.className = cls;
        options.forEach((opt) => {
            const o = el('option', null, opt);
            o.value = opt;
            if (opt === selected) o.selected = true;
            sel.append(o);
        });
        return sel;
    }

    function addComputedFieldBlock(field) {
        const block = el('div', 'computed-field');

        const head = el('div', 'computed-field__head');
        const nameInput = document.createElement('input');
        nameInput.type = 'text';
        nameInput.placeholder = '名稱（computed.NAME）';
        nameInput.className = 'computed-field__name';
        nameInput.value = (field && field.name) || '';
        head.append(nameInput);
        const removeFieldBtn = el('button', 'btn btn--sm btn--ghost', '移除欄位');
        removeFieldBtn.type = 'button';
        removeFieldBtn.addEventListener('click', () => block.remove());
        head.append(removeFieldBtn);
        block.append(head);

        const inputField = document.createElement('input');
        inputField.type = 'text';
        inputField.placeholder = '輸入模板，例如 {{secret.appsecret}}{{now.epochSeconds}}{{secret.deviceid}}';
        inputField.className = 'computed-field__input';
        inputField.value = (field && field.input) || '';
        wireAutocomplete(inputField, computedInputVarEntries);
        block.append(inputField);

        const stepsBox = el('div', 'computed-field__steps');
        block.append(stepsBox);
        ((field && field.steps) || []).forEach((s) => addStepRow(stepsBox, s));

        const addStepBtn = el('button', 'btn btn--sm', '新增步驟');
        addStepBtn.type = 'button';
        addStepBtn.style.marginTop = 'var(--sp-2)';
        addStepBtn.addEventListener('click', () => addStepRow(stepsBox, null));
        block.append(addStepBtn);

        $('monComputedRows').append(block);
    }

    function syncStepKeySecretVisibility(row) {
        const algorithm = row.querySelector('.step-row__algorithm').value;
        row.querySelector('.step-row__key-secret').hidden = !algorithm.startsWith('HMAC_');
    }

    function addStepRow(stepsBox, step) {
        const row = el('div', 'step-row');

        const algorithmSelect = selectEl('step-row__algorithm', HASH_ALGORITHMS, step && step.algorithm);
        algorithmSelect.addEventListener('change', () => syncStepKeySecretVisibility(row));
        row.append(algorithmSelect);

        const encodingSelect = selectEl('step-row__encoding', HASH_ENCODINGS, step && step.encoding);
        row.append(encodingSelect);

        const keySecretInput = document.createElement('input');
        keySecretInput.type = 'text';
        keySecretInput.placeholder = 'HMAC 金鑰的密鑰名稱';
        keySecretInput.className = 'step-row__key-secret';
        keySecretInput.value = (step && step.keySecret) || '';
        row.append(keySecretInput);

        const upBtn = el('button', 'btn btn--sm btn--ghost', '↑');
        upBtn.type = 'button';
        upBtn.title = '上移';
        upBtn.addEventListener('click', () => {
            const prev = row.previousElementSibling;
            if (prev) stepsBox.insertBefore(row, prev);
        });
        row.append(upBtn);

        const downBtn = el('button', 'btn btn--sm btn--ghost', '↓');
        downBtn.type = 'button';
        downBtn.title = '下移';
        downBtn.addEventListener('click', () => {
            const next = row.nextElementSibling;
            if (next) stepsBox.insertBefore(next, row);
        });
        row.append(downBtn);

        const removeBtn = el('button', 'btn btn--sm btn--ghost', '移除');
        removeBtn.type = 'button';
        removeBtn.addEventListener('click', () => row.remove());
        row.append(removeBtn);

        stepsBox.append(row);
        syncStepKeySecretVisibility(row);
    }

    function collectComputedFieldRows() {
        return Array.from($('monComputedRows').querySelectorAll('.computed-field'))
            .map((block) => {
                const steps = Array.from(block.querySelectorAll('.step-row')).map((row) => {
                    const algorithm = row.querySelector('.step-row__algorithm').value;
                    const keySecretRaw = row.querySelector('.step-row__key-secret').value.trim();
                    return {
                        algorithm,
                        encoding: row.querySelector('.step-row__encoding').value,
                        keySecret: algorithm.startsWith('HMAC_') && keySecretRaw ? keySecretRaw : null,
                    };
                });
                return {
                    name: block.querySelector('.computed-field__name').value.trim(),
                    input: block.querySelector('.computed-field__input').value,
                    steps,
                };
            })
            .filter((f) => f.name || f.input || f.steps.length);
    }

    // ---- {{ 自動完成：doc 14 §4 ----
    //
    // 三組變數集合彼此獨立，絕不能混用——給錯清單比沒有更糟，使用者會信任選單，選到一個
    // 在當下情境無效的變數，換來 —（訊息模板）或存檔 400（請求模板／計算欄位輸入），見 doc 14 §4.1。
    // 全部依「當下表單狀態」現算，不快取，見 doc 14 §4.2。

    function computedFieldNames() {
        return Array.from($('monComputedRows').querySelectorAll('.computed-field__name'))
            .map((i) => i.value.trim())
            .filter(Boolean);
    }

    function existingSecretNames() {
        return Array.from($('monSecretsExisting').querySelectorAll('.cell-mono'), (n) => n.textContent);
    }

    function newSecretNames() {
        return Array.from($('monSecretsNewRows').querySelectorAll('.secret-row__name'))
            .map((i) => i.value.trim())
            .filter(Boolean);
    }

    /** 既有的與這次新填的密鑰都要列入（doc 14 §4.2）。 */
    function allSecretNames() {
        return [...new Set([...existingSecretNames(), ...newSecretNames()])];
    }

    /** 訊息模板（monTemplate）專用變數集合：doc 14 §3.1。 */
    function messageVarEntries() {
        const names = ruleNames();
        const entries = [];
        names.forEach((n) => entries.push({ text: `value.${n}`, desc: '本次抓到的值' }));
        names.forEach((n) => entries.push({ text: `old.${n}`, desc: '上一次的值（試算時為 —）' }));
        if ($('monCompareMode').value === 'NEW_ITEMS') {
            names.forEach((n) => entries.push({ text: `item.${n}`, desc: '該筆新項目的欄位（僅 NEW_ITEMS 模式）' }));
        }
        entries.push({ text: 'monitor.name', desc: '監控名稱' });
        entries.push({ text: 'now', desc: '現在時間 MM/dd HH:mm（台北）' });
        return entries;
    }

    /** 請求模板（URL／header／body）專用變數集合：doc 14 §3.2。 */
    function requestTemplateVarEntries() {
        const entries = [
            { text: 'now.epochSeconds', desc: 'Unix 秒' },
            { text: 'now.epochMillis', desc: 'Unix 毫秒' },
            { text: 'now.iso8601', desc: 'ISO 8601（UTC，秒精度）' },
            { text: 'now.format:yyyy-MM-dd HH:mm:ss', desc: '自訂格式（台北時區），可自行修改 pattern' },
            { text: 'now-1h.format:yyyy-MM-dd', desc: '位移範例，可改天數與單位（s/m/h/d）' },
            { text: 'uuid', desc: '隨機 UUID（同一次請求內同值）' },
        ];
        computedFieldNames().forEach((n) => entries.push({ text: `computed.${n}`, desc: '計算欄位' }));
        return entries;
    }

    /** 計算欄位「輸入」專用變數集合：doc 14 §3.3——請求模板全部變數，外加只能用在這裡的 secret.*。 */
    function computedInputVarEntries() {
        const entries = requestTemplateVarEntries();
        allSecretNames().forEach((n) => entries.push({
            text: `secret.${n}`,
            desc: '密鑰（只能用在計算欄位的輸入，不能出現在 URL／header／body）',
        }));
        return entries;
    }

    let autoMenuState = null; // { input, entriesFn, start, filtered, index } | null

    /** 從游標往前找最近的 {{：中間出現 }}／{／換行都代表不是「觸發中」的片段，回傳 null。 */
    function findTriggerStart(value, caret) {
        const before = value.slice(0, caret);
        const idx = before.lastIndexOf('{{');
        if (idx === -1) return null;
        const between = before.slice(idx + 2);
        if (/[{}\n]/.test(between)) return null;
        return idx;
    }

    const CARET_MIRROR_PROPS = [
        'boxSizing', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
        'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
        'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'letterSpacing', 'lineHeight',
        'textTransform', 'wordSpacing', 'textIndent', 'tabSize',
    ];

    /** 量測 <textarea>/<input> 目前游標的 viewport 座標——原生表單元素沒有 API 可以直接問，
     *  標準做法是建一個套用同一份排版相關 CSS 的隱藏鏡像元素，量測游標位置的 <span>。 */
    function getCaretViewportRect(input) {
        const rect = input.getBoundingClientRect();
        const style = getComputedStyle(input);
        const isTextarea = input.tagName === 'TEXTAREA';
        const mirror = document.createElement('div');
        mirror.style.position = 'fixed';
        mirror.style.visibility = 'hidden';
        mirror.style.left = rect.left + 'px';
        mirror.style.top = rect.top + 'px';
        mirror.style.width = rect.width + 'px';
        mirror.style.whiteSpace = isTextarea ? 'pre-wrap' : 'pre';
        mirror.style.wordWrap = 'break-word';
        mirror.style.overflowWrap = 'break-word';
        CARET_MIRROR_PROPS.forEach((prop) => { mirror.style[prop] = style[prop]; });

        const caret = input.selectionStart;
        const marker = document.createElement('span');
        marker.textContent = '\u200b';
        mirror.append(
            document.createTextNode(input.value.slice(0, caret)),
            marker,
            document.createTextNode(input.value.slice(caret) || '\u200b'),
        );
        document.body.append(mirror);
        const markerRect = marker.getBoundingClientRect();
        mirror.remove();
        return {
            left: markerRect.left - input.scrollLeft,
            top: markerRect.top - input.scrollTop,
            bottom: markerRect.bottom - input.scrollTop,
        };
    }

    /** 選單開著時任何捲動事件的統一處理（捲動事件不冒泡，靠掛在 document 上的 capture
     *  監聽器才能收到任何後代元素的捲動——見下方註冊處）。三種情況：
     *  1. 捲動源頭在選單自己身上（它自己就有 max-height + overflow-y:auto）：放行給瀏覽器
     *     原生處理，不關閉也不重新定位，否則選單長度一超過可視範圍就永遠捲不動。
     *  2. 捲動源頭是外層的 .dialog__body：位置會失準，但游標本身沒動，追上去比關掉更好，
     *     直接呼叫既有的 positionAutoMenu() 重新換算一次。
     *  3. 錨定的輸入框本身已經被捲出 .dialog__body 的可視範圍：跟著游標定位已經沒有意義
     *     （游標根本不在畫面上），這時才真的關閉。 */
    function autoMenuScrollHandler(e) {
        if (!autoMenuState) return;
        const menu = $('varMenu');
        if (menu.contains(e.target)) return; // 選單自己的捲動：不是要關閉/重新定位的訊號
        const input = autoMenuState.input;
        const inputRect = input.getBoundingClientRect();
        const container = input.closest('.dialog__body');
        const bound = container
            ? container.getBoundingClientRect()
            : { top: 0, left: 0, right: window.innerWidth, bottom: window.innerHeight };
        const outOfView = inputRect.bottom <= bound.top || inputRect.top >= bound.bottom
            || inputRect.right <= bound.left || inputRect.left >= bound.right;
        if (outOfView) { closeAutoMenu(); return; }
        positionAutoMenu();
    }

    function openAutoMenu(input, entriesFn, start, filterText) {
        const entries = entriesFn();
        const filtered = filterText
            ? entries.filter((en) => en.text.toLowerCase().includes(filterText.toLowerCase()))
            : entries;
        if (!filtered.length) { closeAutoMenu(); return; }
        const wasOpen = !!autoMenuState;
        autoMenuState = { input, entriesFn, start, filtered, index: 0 };
        renderAutoMenu();
        positionAutoMenu();
        if (!wasOpen) document.addEventListener('scroll', autoMenuScrollHandler, true);
    }

    function closeAutoMenu() {
        if (!autoMenuState) return;
        const input = autoMenuState.input;
        document.removeEventListener('scroll', autoMenuScrollHandler, true);
        autoMenuState = null;
        input.removeAttribute('aria-expanded');
        input.removeAttribute('aria-activedescendant');
        const menu = $('varMenu');
        menu.hidden = true;
        menu.replaceChildren();
    }

    function renderAutoMenu() {
        const { input, filtered, index } = autoMenuState;
        const menu = $('varMenu');
        let activeOpt = null;
        menu.replaceChildren(...filtered.map((entry, i) => {
            const opt = el('div', 'var-menu__option' + (i === index ? ' is-active' : ''));
            opt.id = 'varMenuOpt' + i;
            opt.setAttribute('role', 'option');
            opt.setAttribute('aria-selected', i === index ? 'true' : 'false');
            opt.append(el('span', 'var-menu__var', '{{' + entry.text + '}}'));
            opt.append(el('span', 'var-menu__desc', entry.desc));
            opt.addEventListener('mousedown', (e) => {
                e.preventDefault(); // 別讓輸入框先失焦——失焦會在點擊生效前就把選單關掉
                acceptAutoOption(i);
            });
            // 滑鼠移到選項上也要讓它變成 active——鍵盤高亮跟滑鼠 hover 狀態不能互相打架。
            opt.addEventListener('mouseenter', () => setActiveIndex(i));
            if (i === index) activeOpt = opt;
            return opt;
        }));
        menu.hidden = false;
        input.setAttribute('aria-expanded', 'true');
        input.setAttribute('aria-activedescendant', 'varMenuOpt' + index);
        // block:'nearest' 只捲動選單自己這個捲動容器（真的被夾住才動，已可見時是 no-op），
        // 不會牽動 .dialog__body 或整個頁面——這正是選用它而不是 'center'/'start' 的原因。
        if (activeOpt) activeOpt.scrollIntoView({ block: 'nearest' });
    }

    /** 設定目前 active 的選項索引並重繪——鍵盤上下鍵與滑鼠 hover 共用同一個入口，確保兩者
     *  的高亮狀態永遠一致。索引沒變就不重繪，避免滑鼠在同一格內小幅移動時反覆重建 DOM。 */
    function setActiveIndex(index) {
        if (!autoMenuState || autoMenuState.index === index) return;
        autoMenuState.index = index;
        renderAutoMenu();
    }

    function moveAutoSelection(delta) {
        if (!autoMenuState) return;
        const n = autoMenuState.filtered.length;
        setActiveIndex((autoMenuState.index + delta + n) % n);
    }

    /** #varMenu 是 position:absolute，containing block 是它的直接父節點 <dialog>（唯一「已
     *  定位」的祖先——dialog{ position:fixed }）。getCaretViewportRect() 回傳的是真正的視窗
     *  座標，所以要先在視窗座標系裡夾住（不被右邊界／下邊界推出畫面），再換算成「相對
     *  <dialog> 自己的內距框」的座標才能指定給 style.left/top——兩個座標系一旦搞混，數值
     *  在 JS 端看起來完全正確，卻會被瀏覽器畫到畫面外（這正是這次修的那個 bug，
     *  見 app.css 的 .var-menu 註解）。scrollLeft/scrollTop 理論上一律是 0（.dialog__body
     *  才是真正會捲動的容器，見 app.css 的 `dialog > form` 註解），這裡仍然扣掉是防禦性
     *  寫法，不假設以後的 CSS 改動不會讓 <dialog> 自己又變回會捲動。 */
    function positionAutoMenu() {
        const { input } = autoMenuState;
        const caretRect = getCaretViewportRect(input); // 視窗座標
        const menu = $('varMenu');
        const menuRect = menu.getBoundingClientRect();
        const vw = window.innerWidth;
        const vh = window.innerHeight;

        // 先在視窗座標系裡夾住：右邊界溢出就左移，下邊界溢出就翻到游標上方；兩種夾法各自
        // 還可能把選單推出另一側（例如翻到上方後仍超出頂端），所以再各補一次下限，確保
        // 無論欄位多長、容器怎麼捲、視窗多窄都夾在畫面內。
        let left = caretRect.left;
        let top = caretRect.bottom + 4;
        if (left + menuRect.width > vw - 8) left = vw - 8 - menuRect.width;
        if (left < 8) left = 8;
        if (top + menuRect.height > vh - 8) top = caretRect.top - menuRect.height - 4;
        if (top < 8) top = 8;

        // 換算成 <dialog> 的 containing block 座標（padding box 起點 + 目前的捲動量）。
        const dialog = menu.parentElement;
        const dialogRect = dialog.getBoundingClientRect();
        const dialogStyle = getComputedStyle(dialog);
        const borderLeft = parseFloat(dialogStyle.borderLeftWidth) || 0;
        const borderTop = parseFloat(dialogStyle.borderTopWidth) || 0;
        menu.style.left = (left - dialogRect.left - borderLeft + dialog.scrollLeft) + 'px';
        menu.style.top = (top - dialogRect.top - borderTop + dialog.scrollTop) + 'px';
    }

    function acceptAutoOption(index) {
        if (!autoMenuState) return;
        const { input, start, filtered } = autoMenuState;
        const entry = filtered[index != null ? index : autoMenuState.index];
        const value = input.value;
        const caret = input.selectionStart;
        const insertText = '{{' + entry.text + '}}';
        input.value = value.slice(0, start) + insertText + value.slice(caret);
        const newCaret = start + insertText.length;
        input.setSelectionRange(newCaret, newCaret);
        closeAutoMenu();
        input.focus();
        // 選單插入也算「使用者動過模板」——不然選完馬上被下一次的自動預設覆寫掉（doc 14 §2.2）。
        if (input === $('monTemplate')) templatePristine = false;
    }

    /** 掛上 {{ 自動完成：輸入 {{ 後即時觸發並依打字內容過濾，或按 Ctrl+Space 手動叫出（doc 14 §4.3）。
     *  entriesFn 現算，永遠反映當下表單狀態，見本節開頭的說明。 */
    function wireAutocomplete(input, entriesFn) {
        input.setAttribute('aria-autocomplete', 'list');
        input.setAttribute('aria-controls', 'varMenu');
        input.addEventListener('input', () => {
            const caret = input.selectionStart;
            const start = findTriggerStart(input.value, caret);
            if (start === null) { closeAutoMenu(); return; }
            openAutoMenu(input, entriesFn, start, input.value.slice(start + 2, caret));
        });
        input.addEventListener('keydown', (e) => {
            if (autoMenuState && autoMenuState.input === input) {
                if (e.key === 'ArrowDown') { e.preventDefault(); moveAutoSelection(1); return; }
                if (e.key === 'ArrowUp') { e.preventDefault(); moveAutoSelection(-1); return; }
                if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); acceptAutoOption(); return; }
                if (e.key === 'Escape') { e.preventDefault(); closeAutoMenu(); return; }
                return;
            }
            if (e.ctrlKey && e.code === 'Space') {
                e.preventDefault();
                const caret = input.selectionStart;
                const start = findTriggerStart(input.value, caret);
                if (start !== null) {
                    openAutoMenu(input, entriesFn, start, input.value.slice(start + 2, caret));
                } else {
                    openAutoMenu(input, entriesFn, caret, '');
                }
            }
        });
        input.addEventListener('blur', () => {
            // 失焦關閉（doc 14 §4.3）。用 setTimeout 讓點擊選項的 mousedown/click 序列先跑完
            // 再檢查——選項的 mousedown 已 preventDefault，正常點選不會真的觸發這裡的失焦。
            setTimeout(() => { if (autoMenuState && autoMenuState.input === input) closeAutoMenu(); }, 0);
        });
    }

    /** 讀取表單，回傳完整設定物件。header 欄位若不是合法 JSON 會直接丟例外，呼叫端要接住。 */
    function collectMonitorForm() {
        const headersRaw = $('monHeaders').value.trim();
        return {
            name: $('monName').value.trim(),
            clientId: $('monClient').value,
            url: $('monUrl').value.trim(),
            method: $('monMethod').value,
            requestBody: $('monBody').value.trim() || null,
            headers: headersRaw ? JSON.parse(headersRaw) : null,
            secrets: collectSecretRows(),
            intervalSeconds: Number($('monInterval').value),
            enabled: $('monEnabled').checked,
            compareMode: $('monCompareMode').value,
            extractRules: collectRuleRows(),
            itemPointer: $('monItemPointer').value.trim() || null,
            itemKeyPointer: $('monItemKeyPointer').value.trim() || null,
            computedFields: collectComputedFieldRows(),
            messageTemplate: $('monTemplate').value,
            notifyOnFailure: $('monNotifyOnFailure').checked,
            cooldownSeconds: Number($('monCooldown').value || 0),
            maxNotificationsPerDay: $('monMaxPerDay').value.trim() ? Number($('monMaxPerDay').value) : null,
            // 空字串 = 「不需要登入」，要送 null 而不是 ''——後端收到 '' 會當成格式錯誤
            loginId: $('monLogin').value ? Number($('monLogin').value) : null,
        };
    }

    function showMonitorFormError(message) {
        const box = $('monitorError');
        box.textContent = message;
        box.hidden = false;
    }

    async function submitMonitor(event) {
        event.preventDefault();
        $('monitorError').hidden = true;
        let form;
        try {
            form = collectMonitorForm();
        } catch (e) {
            showMonitorFormError('自訂 header 不是合法的 JSON：' + e.message);
            return;
        }

        const btn = $('monitorSave');
        btn.disabled = true; btn.textContent = '儲存中…';
        try {
            if (editingMonitor) {
                const updated = await call('/monitors/' + editingMonitor.id,
                    { method: 'PUT', body: JSON.stringify(form) });
                toast(`已更新 ${updated.name}`);
            } else {
                const created = await call('/monitors', { method: 'POST', body: JSON.stringify(form) });
                toast(`已建立 ${created.name}`);
            }
            $('monitorDialog').close();
            await loadMonitors();
        } catch (e) {
            showMonitorFormError(e.message);
        } finally { btn.disabled = false; btn.textContent = '儲存'; }
    }

    // ---- 立即測試：關鍵 UX，沒有它設 JsonPointer 等於盲猜 ----

    /** 上一次成功的試跑結果 { result, compareMode }，供「再抓一次比對」當基準；null = 還沒測試過。 */
    let lastMonitorTest = null;
    /** 抽取值／項目欄位的「易變動」追蹤：key -> { changed, total }。NEW_ITEMS 模式下 key 是 itemKey::name。 */
    let fieldVolatility = new Map();
    /** body 全樹（依 JsonPointer 路徑）的「易變動」追蹤，見「再抓一次」§4.3 的樹狀延伸。 */
    let bodyVolatility = new Map();
    /** 目前畫面上樹狀檢視使用的 body 級別 diff，{changed:Set, volatile:Set} | null——重繪樹（例如點選設定
     *  itemPointer 後）時要沿用，不能讓 diff 標記在重繪後憑空消失。 */
    let lastBodyDiffInfo = null;

    function resetFieldVolatility() {
        fieldVolatility = new Map();
        bodyVolatility = new Map();
        lastBodyDiffInfo = null;
    }

    function bumpVolatility(map, key, didChange) {
        const v = map.get(key) || { changed: 0, total: 0 };
        v.total += 1;
        if (didChange) v.changed += 1;
        map.set(key, v);
        return v;
    }

    /** 每次都變＝連續比對至少兩次、每次都不一樣——只變過一次不算，避免把「剛好變了一次」誤判成雜訊欄位。 */
    function isAlwaysChanging(v) {
        return v.total >= 2 && v.changed === v.total;
    }

    /** 比較兩次試跑結果，回傳 { changed:Set, volatile:Set, itemLevel }。只比對「兩次都有」的欄位／項目。 */
    function computeTestDiff(prevResult, nextResult, compareMode) {
        const changed = new Set();
        const volatile = new Set();
        if (compareMode === 'NEW_ITEMS') {
            const prevByKey = new Map((prevResult.items || []).map((i) => [i.itemKey, i.fields || {}]));
            (nextResult.items || []).forEach((item) => {
                const prevFields = prevByKey.get(item.itemKey);
                if (!prevFields) return; // 只在兩次都出現的項目上比對欄位有沒有變
                Object.keys(item.fields || {}).forEach((name) => {
                    const key = item.itemKey + '::' + name;
                    const didChange = prevFields[name] !== item.fields[name];
                    const v = bumpVolatility(fieldVolatility, key, didChange);
                    if (didChange) {
                        changed.add(key);
                        if (isAlwaysChanging(v)) volatile.add(key);
                    }
                });
            });
            return { changed, volatile, itemLevel: true };
        }
        const prevValues = prevResult.values || {};
        const nextValues = nextResult.values || {};
        new Set([...Object.keys(prevValues), ...Object.keys(nextValues)]).forEach((name) => {
            const didChange = prevValues[name] !== nextValues[name];
            const v = bumpVolatility(fieldVolatility, name, didChange);
            if (didChange) {
                changed.add(name);
                if (isAlwaysChanging(v)) volatile.add(name);
            }
        });
        return { changed, volatile, itemLevel: false };
    }

    /** 一次比對能安全攤平的路徑數上限——防禦性上限，避免病態的巨大回應把「再抓一次」的 diff 運算拖到卡頓。 */
    const BODY_DIFF_MAX_ENTRIES = 5000;

    /** 把 JSON 樹攤平成 { JsonPointer 路徑: 值 } 的 Map，遞迴深度與攤平筆數都設上限（同一個道理：見樹狀渲染的上限）。 */
    function flattenJson(node, pointer, out, depth) {
        if (out.size >= BODY_DIFF_MAX_ENTRIES || depth > 200) return;
        if (node !== null && typeof node === 'object') {
            const entries = Array.isArray(node) ? node.map((v, i) => [String(i), v]) : Object.entries(node);
            if (!entries.length) { out.set(pointer, Array.isArray(node) ? '[]' : '{}'); return; }
            for (const [key, value] of entries) {
                if (out.size >= BODY_DIFF_MAX_ENTRIES) return;
                flattenJson(value, pointer + '/' + escapePointerSegment(key), out, depth + 1);
            }
        } else {
            out.set(pointer, rawLeafText(node));
        }
    }

    /** 攤平／diff 比對用的完整值（不截斷），跟畫面顯示用的 {@link formatLeafValue} 分開——
     *  截斷後的預覽字串拿去比對，超過截斷長度之後的差異會被漏掉，變成假的「沒有變動」。
     *  前綴型別標籤是為了不讓字串 "42" 跟數字 42、或字串 "null" 跟 JSON null 互相撞成同一個 key。 */
    function rawLeafText(value) {
        if (value === null) return 'null:';
        return (typeof value === 'string' ? 'str:' + value : 'raw:' + JSON.stringify(value));
    }

    function tryParseJson(text) {
        if (typeof text !== 'string' || !text) return undefined;
        try { return JSON.parse(text); } catch (e) { return undefined; }
    }

    /** body 全樹的 diff：只比對兩次都存在的路徑（新增/消失的路徑不算「變動」），回傳 null 代表至少一份不是合法 JSON。 */
    function computeBodyDiff(prevBodyText, nextBodyText) {
        const prevTree = tryParseJson(prevBodyText);
        const nextTree = tryParseJson(nextBodyText);
        if (prevTree === undefined || nextTree === undefined) return null;
        const prevFlat = new Map(); flattenJson(prevTree, '', prevFlat, 0);
        const nextFlat = new Map(); flattenJson(nextTree, '', nextFlat, 0);
        const changed = new Set();
        const volatile = new Set();
        new Set([...prevFlat.keys(), ...nextFlat.keys()]).forEach((pointer) => {
            if (!prevFlat.has(pointer) || !nextFlat.has(pointer)) return;
            const didChange = prevFlat.get(pointer) !== nextFlat.get(pointer);
            const v = bumpVolatility(bodyVolatility, pointer, didChange);
            if (didChange) {
                changed.add(pointer);
                if (isAlwaysChanging(v)) volatile.add(pointer);
            }
        });
        return { changed, volatile };
    }

    /** /monitors/test 不吃 clientId/interval/enabled 等排程/發送欄位——試跑不建立排程、不綁定 client、更不會發送，只帶抓取＋解析＋渲染需要的部分。
     *  monitorId：編輯既有監控時帶上，讓後端在計算欄位需要、但這次沒有重新輸入值的 secret 上，改用已存好的值——
     *  不這樣做的話，每次試算都得把 secret 重新貼一次（見 doc 13 §7 對 MonitorTestRequest 的說明）。 */
    function monitorTestBody(form) {
        return {
            name: form.name || null,
            url: form.url,
            method: form.method,
            requestBody: form.requestBody,
            headers: form.headers,
            secrets: form.secrets,
            compareMode: form.compareMode,
            extractRules: form.extractRules,
            itemPointer: form.itemPointer,
            itemKeyPointer: form.itemKeyPointer,
            computedFields: form.computedFields,
            messageTemplate: form.messageTemplate,
            monitorId: editingMonitor ? editingMonitor.id : null,
        };
    }

    async function testMonitorNow() {
        let form;
        try {
            form = collectMonitorForm();
        } catch (e) {
            showMonitorFormError('自訂 header 不是合法的 JSON：' + e.message);
            return;
        }
        const body = monitorTestBody(form);

        const btn = $('monTestBtn');
        btn.disabled = true; btn.textContent = '測試中…';
        $('monRetestBtn').hidden = true;
        const box = $('monTestResult');
        box.hidden = false;
        box.replaceChildren(el('div', 'loading', '測試中…'));
        try {
            const result = await call('/monitors/test', { method: 'POST', body: JSON.stringify(body) });
            resetFieldVolatility();
            lastMonitorTest = result.ok ? { result, compareMode: form.compareMode } : null;
            renderMonitorTestResult(result, form.compareMode, null, form.messageTemplate);
        } catch (e) {
            box.replaceChildren(el('div', 'notice notice--error', e.message));
        } finally { btn.disabled = false; btn.textContent = '立即測試'; }
    }

    /** 「再抓一次」：跟上一次成功結果比對，標出變動欄位——設定監控最花時間的一步，見 doc 12 §4.3。 */
    async function retestMonitorNow() {
        if (!lastMonitorTest) return;
        let form;
        try {
            form = collectMonitorForm();
        } catch (e) {
            showMonitorFormError('自訂 header 不是合法的 JSON：' + e.message);
            return;
        }
        const body = monitorTestBody(form);

        const btn = $('monRetestBtn');
        btn.disabled = true; btn.textContent = '再抓取中…';
        try {
            const result = await call('/monitors/test', { method: 'POST', body: JSON.stringify(body) });
            let diffInfo = null;
            if (result.ok) {
                const valueDiff = computeTestDiff(lastMonitorTest.result, result, form.compareMode);
                const bodyDiff = computeBodyDiff(lastMonitorTest.result.body, result.body);
                diffInfo = { valueDiff, bodyDiff };
                lastMonitorTest = { result, compareMode: form.compareMode };
            }
            renderMonitorTestResult(result, form.compareMode, diffInfo, form.messageTemplate);
            if (result.ok) toast('已再抓一次並比對');
        } catch (e) {
            toast(e.message, true);
        } finally { btn.disabled = false; btn.textContent = '再抓一次比對'; }
    }

    function renderMonitorTestResult(result, compareMode, diffInfo, messageTemplate) {
        const box = $('monTestResult');
        box.replaceChildren();

        if (!result.ok) {
            const detail = `${result.failureReason || '失敗'}`
                + (result.httpStatus ? `（HTTP ${result.httpStatus}）` : '')
                + `：${result.failureDetail || ''}`;
            box.append(el('div', 'notice notice--error', detail));
            $('monRetestBtn').hidden = !lastMonitorTest;
            return;
        }

        const okMsg = '抓取成功' + (result.httpStatus ? `（HTTP ${result.httpStatus}）` : '') + '。';
        box.append(el('div', 'notice notice--success', okMsg));

        const valueDiff = diffInfo ? diffInfo.valueDiff : null;

        const computedKeys = Object.keys(result.computedValues || {});
        if (computedKeys.length) {
            box.append(el('p', 'fieldset-label', '計算欄位（拿去跟瀏覽器實際送出的值比對）'));
            const list = el('ul', 'tag-set');
            computedKeys.forEach((k) => list.append(valueChip(k, result.computedValues[k], null, k)));
            box.append(list);
        }

        const valueKeys = Object.keys(result.values || {});
        if (valueKeys.length) {
            box.append(el('p', 'fieldset-label', '抽取到的值'));
            const list = el('ul', 'tag-set');
            valueKeys.forEach((k) => list.append(valueChip(k, result.values[k], valueDiff, k)));
            box.append(list);
        }

        if (result.items && result.items.length) {
            box.append(el('p', 'fieldset-label', `項目預覽（共 ${result.items.length} 筆，最多顯示前 5 筆）`));
            const table = el('table', 'grid');
            const thead = el('thead'); const htr = el('tr');
            htr.append(th('鍵'), th('欄位'));
            thead.append(htr); table.append(thead);
            const tb = el('tbody');
            result.items.slice(0, 5).forEach((item) => {
                const tr = el('tr');
                tr.append(tdMono(item.itemKey));
                const fieldsTd = el('td');
                const fieldKeys = Object.keys(item.fields || {});
                if (fieldKeys.length) {
                    const list = el('ul', 'tag-set');
                    fieldKeys.forEach((k) => list.append(
                        valueChip(k, item.fields[k], valueDiff, item.itemKey + '::' + k)));
                    fieldsTd.append(list);
                } else {
                    fieldsTd.textContent = '—';
                }
                tr.append(fieldsTd);
                tb.append(tr);
            });
            table.append(tb);
            const wrap = el('div', 'table-wrap'); wrap.append(table);
            box.append(wrap);
        }

        box.append(el('p', 'fieldset-label', '欄位選取（點節點插入 JsonPointer）'));
        box.append(el('p', 'hint', compareMode === 'NEW_ITEMS'
            ? '先點一個陣列節點設定「項目陣列」；再點該陣列元素裡的欄位設定「項目鍵」。之後點其他欄位可插入成項目欄位（模板用 {{item.NAME}} 引用）。'
            : '點任一節點把它的 JsonPointer 插入下面的抽取欄位（模板用 {{value.NAME}} 引用），名稱可再自行修改。'));
        lastBodyDiffInfo = diffInfo ? diffInfo.bodyDiff : null;
        box.append(fieldPickerTree(result, compareMode, lastBodyDiffInfo));

        templateCaveatNotices(messageTemplate, compareMode).forEach((msg) => {
            box.append(el('div', 'notice notice--warning', msg));
        });

        box.append(el('p', 'fieldset-label', '渲染後的訊息'));
        const pre = el('pre', 'test-message');
        pre.textContent = result.renderedMessage || '（空）';
        box.append(pre);

        $('monRetestBtn').hidden = false;
    }

    /** 試算的兩個已知坑（doc 14 §1、§5）：試算沒有「上一次」可比、比對模式非 NEW_ITEMS 卻用了
     *  {{item.*}}。都不是 bug，是使用者無從得知——直接把原因標在結果旁邊。 */
    function templateCaveatNotices(messageTemplate, compareMode) {
        const notices = [];
        const template = messageTemplate || '';
        if (/\{\{old\./.test(template)) {
            notices.push('試算沒有「上一次」可比，{{old.*}} 一律顯示 —。正式通知時會有值。');
        }
        if (compareMode !== 'NEW_ITEMS' && /\{\{item\./.test(template)) {
            notices.push('比對模式不是 NEW_ITEMS，{{item.*}} 在此模式下永遠是 —（僅 NEW_ITEMS 逐筆渲染時才有值）。');
        }
        return notices;
    }

    /** name=value 的 chip，diffInfo 非空時附加「有變動」／「每次都變」標記，見 doc 12 §4.3。 */
    function valueChip(name, value, diffInfo, diffKey) {
        const li = el('li', 'diff-chip');
        li.append(el('span', 'scope', `${name} = ${value == null ? '—' : value}`));
        if (diffInfo && diffInfo.changed.has(diffKey)) {
            const isVolatile = diffInfo.volatile.has(diffKey);
            li.append(el('span', 'tag ' + (isVolatile ? 'tag--danger' : 'tag--warning'),
                isVolatile ? '每次都變·不建議監控' : '有變動'));
        }
        return li;
    }

    // ---- 欄位選取樹：從完整回應 body 建樹，點節點插入 JsonPointer（見 doc 12 §4.2） ----
    //
    // 試跑回應現在會帶目標 API 的原始 body（見 AdminDto.MonitorTestResult 的類別註解：
    // 這是刻意放寬的例外，只限這個不落地、帶 Cache-Control: no-store 的試跑端點），
    // 所以樹直接從 body 整包建出來，不再需要使用者先手動填一個「種子」pointer 才能展開。
    //
    // 安全：body 是第三方 API 回應內容，全部走 textContent / el()，不用 innerHTML，見檔案開頭
    // 的慣例。另外兩層防禦，避免病態回應（極深巢狀、超大陣列）把畫面卡死：
    //   TREE_MAX_DEPTH    ── 超過這個深度不再往下展開，只顯示一列摘要
    //   TREE_MAX_CHILDREN ── 每個節點最多畫這麼多個子項，其餘顯示「還有 N 筆未顯示」

    const TREE_MAX_DEPTH = 12;
    const TREE_MAX_CHILDREN = 200;

    /** NEW_ITEMS 模式下，把 pointer 換算成「相對於 itemPointer 指到的元素」的相對路徑；
     *  不是該元素底下的欄位（例如是元素本身，或跟 itemPointer 無關）時回傳 null。 */
    function relativeToItem(pointer, itemPointer) {
        if (!itemPointer || pointer === itemPointer) return null;
        const prefix = itemPointer + '/';
        if (!pointer.startsWith(prefix)) return null;
        const rest = pointer.slice(prefix.length); // 例如 "0/id"，第一段是陣列索引
        const slashIdx = rest.indexOf('/');
        if (slashIdx === -1) return null; // 指到元素本身，不是元素「裡面的欄位」——JsonPointer 不允許空字串子路徑
        return '/' + rest.slice(slashIdx + 1);
    }

    /** 從 result.body 建立可展開的欄位選取樹；body 不是合法 JSON（或因截斷而不完整）時顯示提示而不是報錯。 */
    function fieldPickerTree(result, compareMode, bodyDiff) {
        const wrap = el('div', 'json-tree');

        if (result.bodyTruncated) {
            wrap.append(el('div', 'notice notice--warning',
                `回應已被截斷：原始 ${result.bodyOriginalLength} bytes，只保留前 256 KB。`
                + '下面的樹可能不完整（尤其是尾端），也可能因此解析失敗。'));
        }

        const parsed = tryParseJson(result.body);
        if (parsed === undefined) {
            wrap.append(el('p', 'hint', result.body
                ? '回應內容不是合法 JSON（或因為被截斷而不完整），無法建立欄位選取樹。'
                : '這次測試沒有回應內容可以展開。'));
            return wrap;
        }

        const ctx = {
            compareMode,
            itemPointer: compareMode === 'NEW_ITEMS' ? $('monItemPointer').value.trim() : '',
            itemKeyPointer: compareMode === 'NEW_ITEMS' ? $('monItemKeyPointer').value.trim() : '',
            bodyDiff,
        };
        if (parsed !== null && typeof parsed === 'object') {
            renderTreeChildren(wrap, parsed, '', 0, ctx);
        } else {
            wrap.append(treeLeafRow('(root)', formatLeafValue(parsed), '', parsed, ctx));
        }
        return wrap;
    }

    function renderTreeChildren(container, node, pointer, depth, ctx) {
        const entries = Array.isArray(node) ? node.map((v, i) => [String(i), v]) : Object.entries(node);
        const shown = entries.slice(0, TREE_MAX_CHILDREN);
        shown.forEach(([key, value]) => {
            container.append(renderTreeNode(key, pointer + '/' + escapePointerSegment(key), value, depth, ctx));
        });
        if (entries.length > shown.length) {
            container.append(el('p', 'hint', `還有 ${entries.length - shown.length} 筆未顯示。`));
        }
    }

    function renderTreeNode(key, pointer, value, depth, ctx) {
        if (depth >= TREE_MAX_DEPTH) {
            const label = (value !== null && typeof value === 'object') ? containerLabel(value) : formatLeafValue(value);
            return treeLeafRow(key, `結構過深，未展開（${label}）`, pointer, value, ctx);
        }
        if (value !== null && typeof value === 'object') {
            const details = document.createElement('details');
            details.className = 'json-tree__node';
            const summary = document.createElement('summary');
            summary.append(el('span', null, `${key}（${containerLabel(value)}）`));
            appendPickButtons(summary, pointer, value, ctx);
            details.append(summary);
            const children = el('div', 'json-tree__children');
            renderTreeChildren(children, value, pointer, depth + 1, ctx);
            details.append(children);
            return details;
        }
        return treeLeafRow(key, formatLeafValue(value), pointer, value, ctx);
    }

    function treeLeafRow(key, valueText, pointer, value, ctx) {
        const row = el('div', 'json-tree__leaf');
        row.append(el('span', 'json-tree__key', key));
        row.append(el('span', 'json-tree__value', valueText));
        markBodyDiff(row, pointer, ctx);
        appendPickButtons(row, pointer, value, ctx);
        return row;
    }

    /** 依模式決定節點旁要放哪些「插入」按鈕。NEW_ITEMS 模式下絕不能出現一般「插入」按鈕——
     *  該模式的抽取欄位一律是相對於項目的路徑，塞一個從根算起的絕對 pointer 進去會是錯的設定。 */
    function appendPickButtons(container, pointer, value, ctx) {
        if (!pointer) return; // 根節點本身不是合法的 JsonPointer 目標（RFC 6901：非根路徑不可為空字串）

        if (ctx.compareMode !== 'NEW_ITEMS') {
            container.append(pickBtn('插入', () => insertExtractRule(pointer)));
            return;
        }

        if (Array.isArray(value)) {
            if (pointer === ctx.itemPointer) {
                container.append(el('span', 'tag tag--active', '項目陣列'));
            } else {
                container.append(pickBtn('設為項目陣列', () => setItemPointer(pointer)));
            }
        }

        const rel = relativeToItem(pointer, ctx.itemPointer);
        if (rel) {
            if (rel === ctx.itemKeyPointer) {
                container.append(el('span', 'tag tag--active', '項目鍵'));
            } else if (!ctx.itemKeyPointer) {
                container.append(pickBtn('設為項目鍵', () => setItemKeyPointer(rel)));
            }
            container.append(pickBtn('插入為項目欄位', () => insertItemExtractRule(rel, pointer)));
        }
    }

    function markBodyDiff(row, pointer, ctx) {
        if (!ctx.bodyDiff || !pointer || !ctx.bodyDiff.changed.has(pointer)) return;
        const isVolatile = ctx.bodyDiff.volatile.has(pointer);
        row.append(el('span', 'tag ' + (isVolatile ? 'tag--danger' : 'tag--warning'),
            isVolatile ? '每次都變·不建議監控' : '有變動'));
    }

    function pickBtn(label, onClick) {
        const btn = el('button', 'btn btn--sm btn--ghost json-tree__pick', label);
        btn.type = 'button';
        btn.addEventListener('click', (e) => {
            e.preventDefault(); e.stopPropagation(); // 別讓點擊冒泡到 <summary>，變成順便展開/收合節點
            onClick();
        });
        return btn;
    }

    function insertExtractRule(pointer) {
        const name = sanitizeRuleName(lastPointerSegment(pointer));
        addRuleRow(name, pointer);
        toast(`已插入欄位「${name}」：${pointer}`);
    }

    function insertItemExtractRule(relativePointer, absolutePointer) {
        const name = sanitizeRuleName(lastPointerSegment(absolutePointer));
        addRuleRow(name, relativePointer);
        toast(`已插入項目欄位「${name}」：${relativePointer}`);
    }

    function setItemPointer(pointer) {
        $('monItemPointer').value = pointer;
        $('monItemKeyPointer').value = ''; // 換了陣列，舊的項目鍵不再有意義，清空要求重新選
        toast(`已設定項目陣列：${pointer}（接著點陣列元素裡的欄位設定項目鍵）`);
        refreshFieldPickerTree();
    }

    function setItemKeyPointer(relativePointer) {
        $('monItemKeyPointer').value = relativePointer;
        toast(`已設定項目鍵：${relativePointer}`);
        refreshFieldPickerTree();
    }

    /** itemPointer／itemKeyPointer 變動會影響整棵樹的按鈕狀態，重繪只換掉樹本身，不動上面的值／訊息區塊。 */
    function refreshFieldPickerTree() {
        if (!lastMonitorTest) return;
        const existing = $('monTestResult').querySelector('.json-tree');
        if (!existing) return;
        existing.replaceWith(fieldPickerTree(lastMonitorTest.result, lastMonitorTest.compareMode, lastBodyDiffInfo));
    }

    function containerLabel(node) {
        return Array.isArray(node) ? `陣列 · ${node.length} 筆` : `物件 · ${Object.keys(node).length} 個欄位`;
    }

    /** 顯示用的截斷版本——跟攤平／diff 比對用的 {@link rawLeafText} 分開，避免長字串把畫面撐爆。 */
    function formatLeafValue(value) {
        if (value === null) return '—';
        const text = typeof value === 'string' ? value : JSON.stringify(value);
        return text.length > 300 ? text.slice(0, 300) + '…' : text;
    }

    function escapePointerSegment(seg) {
        return seg.replace(/~/g, '~0').replace(/\//g, '~1');
    }

    function unescapePointerSegment(seg) {
        return seg.replace(/~1/g, '/').replace(/~0/g, '~');
    }

    function lastPointerSegment(pointerSuffix) {
        if (!pointerSuffix) return 'field';
        const idx = pointerSuffix.lastIndexOf('/');
        return unescapePointerSegment(pointerSuffix.slice(idx + 1));
    }

    /** 後端要求規則名稱是 [A-Za-z0-9_]{1,32}，把 pointer 最後一段轉成合法名稱（可再手動編輯）。 */
    function sanitizeRuleName(raw) {
        const cleaned = (raw || '').replace(/[^A-Za-z0-9_]/g, '_').slice(0, 32);
        return cleaned || 'field';
    }

    // ---- 執行紀錄 ----

    async function openMonitorRuns(m) {
        $('monitorRunsSubtitle').textContent = m.name;
        const body = $('monitorRunsBody');
        body.replaceChildren(el('div', 'loading', '載入中…'));
        $('monitorRunsDialog').showModal();
        try {
            const rows = await call('/monitors/' + m.id + '/runs');
            renderMonitorRuns(rows);
        } catch (e) {
            body.replaceChildren(el('div', 'notice notice--error', e.message));
        }
    }

    function renderMonitorRuns(rows) {
        const body = $('monitorRunsBody');
        body.replaceChildren();
        if (!rows.length) {
            body.append(el('div', 'empty', '還沒有任何執行紀錄。'));
            return;
        }
        const table = el('table', 'grid');
        const thead = el('thead'); const htr = el('tr');
        ['時間', '結果', 'HTTP', '耗時', '錯誤'].forEach((h) => htr.append(th(h)));
        thead.append(htr); table.append(thead);
        const tb = el('tbody');
        rows.forEach((r) => {
            const tr = el('tr');
            tr.append(td(fmtTime(r.startedAt)));
            const s = el('td');
            s.append(el('span', 'tag ' + (RUN_OUTCOME_TAG[r.outcome] || 'tag--muted'), r.outcome));
            tr.append(s);
            tr.append(tdNum(r.httpStatus != null ? r.httpStatus : '—'));
            tr.append(tdNum(r.durationMs != null ? r.durationMs + 'ms' : '—'));
            tr.append(td(r.errorMessage || '—'));
            tb.append(tr);
        });
        table.append(tb);
        const wrap = el('div', 'table-wrap'); wrap.append(table);
        body.append(wrap);
    }


    // ============================================================ 站台登入
    //
    // 見 Docs/plan/15-監控站台登入設計.md。這一頁管理的是「帳密」，跟隔壁的
    // 「登入狀態」（cookie jar）是兩件事：cookie jar 是使用者自己貼進來的、過期要重貼；
    // 這裡的登入會自己去換新的 token，不需要人介入。

    let editingLogin = null;

    async function loadLogins() {
        try {
            state.logins = await call('/logins');
            renderLogins();
        } catch (e) { pageError('載入失敗：' + e.message); }
    }

    function renderLogins() {
        const body = $('loginRows');
        body.replaceChildren(...state.logins.map(loginRow));
        $('loginsEmpty').hidden = state.logins.length !== 0;
    }

    /**
     * token 狀態徽章。刻意不只用顏色分辨（§1 color-not-only）——文字本身就說明了狀態，
     * 顏色只是加強。
     */
    function tokenStateCell(l) {
        const cell = el('td');
        const span = el('span', 'token-state');
        if (!l.tokenExpiresAt) {
            span.classList.add('token-state--none');
            span.textContent = '尚未登入';
        } else {
            const expires = new Date(l.tokenExpiresAt).getTime();
            const minutes = Math.round((expires - Date.now()) / 60000);
            if (minutes > 0) {
                span.classList.add('token-state--ok');
                span.textContent = '有效，剩 ' + minutes + ' 分';
            } else {
                span.classList.add('token-state--stale');
                span.textContent = '已過期，下次抓取時自動重登';
            }
        }
        cell.append(span);
        return cell;
    }

    function loginRow(l) {
        const tr = el('tr');
        tr.append(td(l.name));
        tr.append(tdMono(l.username));
        tr.append(tokenStateCell(l));
        tr.append(tdNum(l.monitorCount));
        tr.append(td(fmtTime(l.lastLoginAt)));

        const stateTd = el('td');
        if (l.enabled) {
            stateTd.append(el('span', 'tag tag--active', '啟用中'));
        } else {
            // 停用幾乎都是密碼錯造成的自動停用，把原因直接顯示出來，不要讓使用者去猜
            const wrap = el('div');
            wrap.append(el('span', 'tag tag--muted', '已停用'));
            if (l.lastError) wrap.append(el('p', 'hint', l.lastError));
            stateTd.append(wrap);
        }
        tr.append(stateTd);

        const act = el('td');
        const wrap = el('div', 'row-actions');
        wrap.append(iconBtn('編輯', () => openLoginEdit(l)));
        wrap.append(iconBtn('測試登入', () => testLogin(l.id)));
        wrap.append(iconBtn('刪除', () => deleteLogin(l), 'btn--danger'));
        act.append(wrap);
        tr.append(act);
        return tr;
    }

    function openLoginCreate() {
        editingLogin = null;
        $('loginTitle').textContent = '新增站台登入';
        $('loginError').hidden = true;
        $('loginForm').reset();
        $('loginHeaderName').value = 'Authorization';
        $('loginHeaderTemplate').value = '{token}';
        $('loginEnabled').checked = true;
        $('loginPassword').required = true;
        $('loginPasswordHint').textContent = '建立時必填。';
        // 還沒有 id 就沒有東西可以測——按鈕留著但停用，比整個藏起來更好懂（§8 disabled-states）
        $('loginTestBtn').disabled = true;
        $('loginTestHint').textContent = '先儲存才能測試。';
        $('loginTestResult').hidden = true;
        $('loginDialog').showModal();
    }

    function openLoginEdit(l) {
        editingLogin = l;
        $('loginTitle').textContent = '編輯站台登入：' + l.name;
        $('loginError').hidden = true;
        $('loginName').value = l.name;
        $('loginRegion').value = l.region || '';
        $('loginUserPoolId').value = l.userPoolId || '';
        $('loginClientId').value = l.clientId || '';
        $('loginUsername').value = l.username || '';
        $('loginPassword').value = '';
        $('loginPassword').required = false;
        $('loginPasswordHint').textContent = l.hasPassword
            ? '已設定。留空 = 不變更；填入新密碼會同時作廢目前的 token。'
            : '尚未設定，請填入密碼。';
        $('loginHeaderName').value = l.headerName || 'Authorization';
        $('loginHeaderTemplate').value = l.headerValueTemplate || '{token}';
        $('loginEnabled').checked = l.enabled;
        $('loginTestBtn').disabled = false;
        $('loginTestHint').textContent = '會實際登入一次，不吃 token 快取。';
        $('loginTestResult').hidden = true;
        $('loginDialog').showModal();
    }

    async function submitLogin(event) {
        event.preventDefault();
        $('loginError').hidden = true;

        const payload = {
            name: $('loginName').value.trim(),
            region: $('loginRegion').value.trim(),
            userPoolId: $('loginUserPoolId').value.trim(),
            clientId: $('loginClientId').value.trim(),
            username: $('loginUsername').value.trim(),
            password: $('loginPassword').value,
            headerName: $('loginHeaderName').value.trim() || 'Authorization',
            headerValueTemplate: $('loginHeaderTemplate').value.trim() || '{token}',
        };

        try {
            if (editingLogin) {
                payload.enabled = $('loginEnabled').checked;
                await call('/logins/' + editingLogin.id, { method: 'PUT', body: JSON.stringify(payload) });
                toast('已更新');
            } else {
                if (!payload.password) {
                    showLoginFormError('建立時必須填密碼。');
                    return;
                }
                await call('/logins', { method: 'POST', body: JSON.stringify(payload) });
                toast('已建立');
            }
            $('loginDialog').close();
            await loadLogins();
        } catch (e) {
            showLoginFormError(e.message);
        }
    }

    function showLoginFormError(message) {
        const box = $('loginError');
        box.textContent = message;
        box.hidden = false;
    }

    async function testLogin(id) {
        const box = $('loginTestResult');
        const btn = $('loginTestBtn');
        btn.disabled = true;
        box.hidden = false;
        box.className = 'notice notice--info';
        box.textContent = '登入中…';
        try {
            const result = await call('/logins/' + id + '/test', { method: 'POST' });
            if (result.success) {
                box.className = 'notice notice--success';
                box.textContent = '登入成功。Token 有效至 ' + fmtTime(result.tokenExpiresAt) + '。';
            } else {
                box.className = 'notice notice--error';
                box.textContent = '登入失敗：' + result.error;
            }
            await loadLogins();
        } catch (e) {
            box.className = 'notice notice--error';
            box.textContent = '測試失敗：' + e.message;
        } finally {
            btn.disabled = false;
        }
    }

    async function deleteLogin(l) {
        const affected = l.monitorCount > 0
            ? `\n\n有 ${l.monitorCount} 個監控正在用它，刪除後那些監控會因為拿不到 token 而開始失敗。`
            : '';
        if (!confirm(`刪除「${l.name}」？此動作不可回復。${affected}`)) return;
        try {
            await call('/logins/' + l.id, { method: 'DELETE' });
            toast('已刪除');
            await loadLogins();
        } catch (e) { toast(e.message, true); }
    }

    /**
     * 填入監控表單的「站台登入」下拉。
     *
     * <p>清單可能還沒載入過（使用者直接開監控頁就按新增），所以這裡自己抓一次；
     * 失敗時保留「不需要登入」這個選項就好，不要讓整個監控表單開不起來。
     */
    async function fillLoginSelect(selectedId) {
        const select = $('monLogin');
        const render = () => {
            select.replaceChildren();
            select.append(new Option('不需要登入', ''));
            state.logins.forEach((l) => {
                const label = l.enabled ? l.name : l.name + '（已停用）';
                select.append(new Option(label, String(l.id)));
            });
            select.value = selectedId != null ? String(selectedId) : '';
        };

        if (!state.logins.length) {
            try {
                state.logins = await call('/logins');
            } catch (e) {
                render();
                return;
            }
        }
        render();
    }

    // ============================================================ 登入狀態

    async function loadSessions() {
        try {
            state.sessions = await call('/sessions');
            renderSessions();
        } catch (e) { pageError('載入失敗：' + e.message); }
    }

    function renderSessions() {
        const body = $('sessionRows');
        body.replaceChildren(...state.sessions.map(sessionRow));
        $('sessionsEmpty').hidden = state.sessions.length !== 0;
    }

    function sessionRow(s) {
        const tr = el('tr');
        tr.append(tdMono(s.host));

        const namesTd = el('td');
        if (s.cookieNames && s.cookieNames.length) {
            const list = el('ul', 'tag-set');
            s.cookieNames.forEach((n) => list.append(el('li', 'scope', n)));
            namesTd.append(list);
        } else {
            namesTd.append(el('span', 'tag tag--muted', '無'));
        }
        tr.append(namesTd);

        tr.append(tdNum(s.cookieCount));
        tr.append(td(fmtTime(s.lastRefreshedAt)));
        tr.append(td(fmtTime(s.createdAt)));

        const act = el('td');
        const wrap = el('div', 'row-actions');
        wrap.append(iconBtn('清除', () => clearSession(s), 'btn--danger'));
        act.append(wrap);
        tr.append(act);
        return tr;
    }

    async function clearSession(s) {
        if (!confirm(`清除 ${s.host} 的登入狀態？之後這個 host 底下的監控會開始 401，要重新貼一次含 cookie 的請求才會復活。`)) return;
        try {
            await call('/sessions/' + encodeURIComponent(s.host), { method: 'DELETE' });
            toast(`已清除 ${s.host}`);
            await loadSessions();
        } catch (e) { toast(e.message, true); }
    }

    // ============================================================ 對話框：關閉與拖曳調寬
    //
    // 套用在每一個 <dialog>，不是只有監控那個——一致的行為才不會讓使用者猜哪個抽屜可以
    // 點外面關、哪個不行，或哪個可以拖寬、哪個不行。

    const DIALOG_MIN_WIDTH = 420;
    const DIALOG_MAX_WIDTH_RATIO = 0.95; // 對應 app.css 的 95vw，一律以目前視窗寬度換算

    function dialogWidthStorageKey(dialog) {
        return 'notifyline-admin:dialog-width:' + dialog.id;
    }

    /** localStorage 在無痕視窗、或站台資料被封鎖時讀寫都可能丟例外——一律吞掉，退回預設寬度。 */
    function readStoredDialogWidth(dialog) {
        try {
            const raw = localStorage.getItem(dialogWidthStorageKey(dialog));
            const n = raw ? Number(raw) : NaN;
            return Number.isFinite(n) && n > 0 ? n : null;
        } catch (e) { return null; }
    }

    function writeStoredDialogWidth(dialog, px) {
        try { localStorage.setItem(dialogWidthStorageKey(dialog), String(Math.round(px))); }
        catch (e) { /* 無痕視窗／被封鎖：改不到就算了，不影響這次操作本身 */ }
    }

    function clampDialogWidth(px) {
        const max = Math.max(DIALOG_MIN_WIDTH, window.innerWidth * DIALOG_MAX_WIDTH_RATIO);
        return Math.min(Math.max(px, DIALOG_MIN_WIDTH), max);
    }

    /** 點 <dialog> 自己（也就是 ::backdrop——瀏覽器沒辦法把事件掛在 pseudo-element 上，
     *  點 backdrop 一律回報成點在 <dialog> 本身）關閉抽屜；點到面板內任何實際節點時
     *  e.target 會是那個子節點、不是 dialog，藉此分辨「點外面」跟「點裡面」，不需要另外
     *  疊一層遮罩元素（那會壞掉 <dialog> 原生的 top layer 行為）。選單開著時先收掉選單、
     *  不關對話框——跟 Esc 的既有行為一致（見 wireAutocomplete 的 keydown 處理）。 */
    function wireDialogBackdropClose(dialog) {
        dialog.addEventListener('click', (e) => {
            if (e.target !== dialog) return;
            if (autoMenuState) { closeAutoMenu(); return; }
            const r = dialog.getBoundingClientRect();
            const insidePanel = e.clientX >= r.left && e.clientX <= r.right
                && e.clientY >= r.top && e.clientY <= r.bottom;
            if (!insidePanel) dialog.close();
        });
    }

    /** 抽屜釘在畫面右邊，所以拖曳把手長在它的左邊界；往左拖（clientX 變小）＝變寬。
     *  用 Pointer Events 而不是 mouse-only，觸控裝置也能拖。寬度存 localStorage，
     *  下次開啟同一個 <dialog> 沿用上次的選擇。 */
    function wireDialogResize(dialog) {
        const handle = el('div', 'dialog__resize-handle');
        handle.setAttribute('aria-hidden', 'true');
        dialog.prepend(handle);

        const stored = readStoredDialogWidth(dialog);
        if (stored != null) dialog.style.setProperty('--dialog-w', clampDialogWidth(stored) + 'px');

        let dragging = false;
        let startX = 0;
        let startWidth = 0;

        function onPointerMove(e) {
            if (!dragging) return;
            const dx = e.clientX - startX;
            dialog.style.setProperty('--dialog-w', clampDialogWidth(startWidth - dx) + 'px');
        }

        function endDrag(e) {
            if (!dragging) return;
            dragging = false;
            handle.classList.remove('is-dragging');
            document.removeEventListener('pointermove', onPointerMove);
            document.removeEventListener('pointerup', endDrag);
            document.removeEventListener('pointercancel', endDrag);
            try { handle.releasePointerCapture(e.pointerId); } catch (e2) { /* 已釋放或不支援：無妨 */ }
            writeStoredDialogWidth(dialog, dialog.getBoundingClientRect().width);
        }

        handle.addEventListener('pointerdown', (e) => {
            if (e.button !== 0) return; // 只認滑鼠左鍵／單指觸控（觸控的 button 也回報 0）
            dragging = true;
            startX = e.clientX;
            startWidth = dialog.getBoundingClientRect().width;
            handle.classList.add('is-dragging');
            try { handle.setPointerCapture(e.pointerId); } catch (e2) { /* 不支援就退回一般事件流程 */ }
            document.addEventListener('pointermove', onPointerMove);
            document.addEventListener('pointerup', endDrag);
            document.addEventListener('pointercancel', endDrag);
            e.preventDefault();
        });
    }

    /** 視窗被縮小到比先前存的寬度還窄時，把目前有自訂寬度的 <dialog> 重新夾一次——不然
     *  使用者在大螢幕拖寬存起來的值，換到小螢幕會直接把抽屜推出畫面外。 */
    function reclampDialogWidths() {
        document.querySelectorAll('dialog').forEach((dialog) => {
            if (!dialog.style.getPropertyValue('--dialog-w')) return;
            const current = parseFloat(getComputedStyle(dialog).width);
            if (Number.isFinite(current)) dialog.style.setProperty('--dialog-w', clampDialogWidth(current) + 'px');
        });
    }
    window.addEventListener('resize', reclampDialogWidths);

    // ------------------------------------------------------------ 小工具

    function td(text) { return el('td', null, text); }
    function tdNum(text) { const c = el('td', 'num', text); return c; }
    function tdMono(text) { return el('td', 'cell-mono', text); }
    function th(text) { return el('th', null, text); }
    function rowMessage(cols, text) {
        const tr = el('tr'); const c = el('td', 'empty', text); c.colSpan = cols; tr.append(c); return tr;
    }
    function iconBtn(label, onClick, extra) {
        const b = el('button', 'btn btn--sm' + (extra ? ' ' + extra : ''), label);
        b.type = 'button';
        b.addEventListener('click', (e) => { e.stopPropagation(); onClick(); });
        return b;
    }
    function selectedRadio(name) {
        const c = document.querySelector(`input[name="${name}"]:checked`);
        return c ? c.value : '';
    }
    function checkedValues(container) {
        return Array.from(container.querySelectorAll('input[type="checkbox"]:checked'), (i) => i.value);
    }
    function fillUserSelect(sel) {
        sel.replaceChildren(...state.users.map((u) => {
            const o = el('option', null, `${u.displayName || u.lineUserId}${u.owner ? '（owner）' : ''}`);
            o.value = u.lineUserId;
            return o;
        }));
    }
    function buildUserPicker(container, selectedIds, onChange) {
        if (!state.users.length) {
            container.replaceChildren(el('div', 'empty', '沒有可選的使用者。'));
            return;
        }
        container.replaceChildren(...state.users.map((u) => {
            const row = el('label', 'user-row');
            const cb = document.createElement('input');
            cb.type = 'checkbox'; cb.value = u.lineUserId;
            cb.checked = selectedIds.includes(u.lineUserId);
            cb.addEventListener('change', onChange);
            row.append(cb);
            const text = el('span');
            const nameLine = el('span', 'user-row__name', u.displayName || '(未同步)');
            if (u.owner) { const t = el('span', 'tag tag--target', 'owner'); t.style.marginInlineStart = '6px'; nameLine.append(t); }
            text.append(nameLine, el('div', 'user-row__id', u.lineUserId));
            row.append(text);
            return row;
        }));
    }

    // ------------------------------------------------------------ 綁定

    window.addEventListener('hashchange', routeFromHash);
    document.querySelectorAll('.nav a[data-view], a[data-view]').forEach((a) => {
        a.addEventListener('click', () => { /* hashchange handles it */ });
    });

    $('refresh').addEventListener('click', () => VIEWS[current].load());

    $('newClientBtn').addEventListener('click', openCreate);
    document.querySelectorAll('input[name="createKind"]').forEach((i) => i.addEventListener('change', syncCreateKind));
    $('createForm').addEventListener('submit', submitCreate);
    $('createCancel').addEventListener('click', () => $('createDialog').close());

    $('secretClose').addEventListener('click', () => $('secretDialog').close());
    $('copySecret').addEventListener('click', () => {
        const text = `clientId: ${$('newClientId').textContent}\nsecret: ${$('newSecret').textContent}`;
        navigator.clipboard.writeText(text).then(() => toast('已複製到剪貼簿'), () => toast('複製失敗，請手動選取', true));
    });

    document.querySelectorAll('input[name="targetType"]').forEach((i) => i.addEventListener('change', syncTargetPicker));
    $('targetForm').addEventListener('submit', submitTarget);
    $('targetCancel').addEventListener('click', () => $('targetDialog').close());

    document.querySelectorAll('input[name="sendType"]').forEach((i) => i.addEventListener('change', syncSendPicker));
    document.querySelectorAll('input[name="sendWhen"]').forEach((i) => i.addEventListener('change', syncSendWhen));
    $('sendForm').addEventListener('submit', submitSend);

    $('detailClose').addEventListener('click', () => $('detailDialog').close());

    $('newMonitorBtn').addEventListener('click', openMonitorCreate);
    $('monImportBtn').addEventListener('click', importMonitorRaw);
    $('monCopySchemaBtn').addEventListener('click', copyImportSchema);
    $('monHeadersShowBtn').addEventListener('click', showCurrentHeaders);
    $('monAddRule').addEventListener('click', () => addRuleRow('', ''));
    $('monRulesRows').addEventListener('input', (e) => {
        if (e.target.classList.contains('rule-row__name')) refreshDefaultTemplateIfPristine();
    });
    $('monAddSecret').addEventListener('click', () => addSecretRow('', ''));
    $('monAddComputedField').addEventListener('click', () => addComputedFieldBlock(null));
    $('monMethod').addEventListener('change', syncMonitorMethod);
    $('monCompareMode').addEventListener('change', syncMonitorCompareMode);
    $('monitorForm').addEventListener('submit', submitMonitor);
    $('monitorCancel').addEventListener('click', () => $('monitorDialog').close());
    $('monTestBtn').addEventListener('click', testMonitorNow);
    $('monRetestBtn').addEventListener('click', retestMonitorNow);
    $('monitorRunsClose').addEventListener('click', () => $('monitorRunsDialog').close());
    $('addLogin').addEventListener('click', openLoginCreate);
    $('loginForm').addEventListener('submit', submitLogin);
    $('loginCancel').addEventListener('click', () => $('loginDialog').close());
    $('loginTestBtn').addEventListener('click', () => { if (editingLogin) testLogin(editingLogin.id); });
    $('monTemplate').addEventListener('input', () => { templatePristine = false; });
    wireAutocomplete($('monTemplate'), messageVarEntries);
    wireAutocomplete($('monUrl'), requestTemplateVarEntries);
    wireAutocomplete($('monHeaders'), requestTemplateVarEntries);
    wireAutocomplete($('monBody'), requestTemplateVarEntries);

    document.querySelectorAll('dialog').forEach((dialog) => {
        wireDialogBackdropClose(dialog);
        wireDialogResize(dialog);
    });

    $('logoutForm').addEventListener('submit', (e) => {
        const t = csrfToken();
        if (!t) return;
        const f = document.createElement('input');
        f.type = 'hidden'; f.name = '_csrf'; f.value = t;
        e.currentTarget.append(f);
    });

    routeFromHash();
})();
