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

    const state = { clients: [], users: [], monitors: [], loaded: {} };

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
        const s = el('td'); s.append(el('span', 'tag ' + (NOTIF_TAG[n.status] || 'tag--muted'), n.status)); tr.append(s);
        tr.append(tdNum(n.recipientCount));
        tr.append(tdNum(`${n.successCount} / ${n.failureCount}`));
        return tr;
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

    function renderDetail(d) {
        const body = $('detailBody');
        body.replaceChildren();

        const summary = el('div', 'notice notice--info');
        summary.textContent = `狀態 ${d.status}｜收件 ${d.recipientCount}｜成功 ${d.successCount}｜失敗 ${d.failureCount}`;
        body.append(summary);

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

    // ---- 新增/編輯抽屜 ----

    let editingMonitor = null; // null = 新增模式

    function openMonitorCreate() {
        editingMonitor = null;
        $('monitorTitle').textContent = '新增監控';
        $('monitorError').hidden = true;
        $('monitorForm').reset();
        fillClientSelect($('monClient'));
        $('monInterval').value = 60;
        $('monCompareMode').value = 'WHOLE_BODY';
        $('monEnabled').checked = true;
        $('monNotifyOnFailure').checked = true;
        $('monHeaders').value = '';
        setRuleRows([]);
        clearTestResult();
        syncMonitorMethod();
        syncMonitorCompareMode();
        $('monitorDialog').showModal();
    }

    function openMonitorEdit(m) {
        editingMonitor = m;
        $('monitorTitle').textContent = '編輯監控：' + m.name;
        $('monitorError').hidden = true;
        $('monName').value = m.name;
        fillClientSelect($('monClient'));
        $('monClient').value = m.clientId;
        $('monMethod').value = m.method;
        $('monUrl').value = m.url;
        $('monBody').value = m.requestBody || '';
        $('monHeaders').value = '';
        $('monInterval').value = m.intervalSeconds;
        $('monCompareMode').value = m.compareMode;
        setRuleRows(m.extractRules || []);
        $('monItemPointer').value = m.itemPointer || '';
        $('monItemKeyPointer').value = m.itemKeyPointer || '';
        $('monTemplate').value = m.messageTemplate;
        $('monCooldown').value = m.cooldownSeconds;
        $('monMaxPerDay').value = m.maxNotificationsPerDay != null ? m.maxNotificationsPerDay : '';
        $('monEnabled').checked = m.enabled;
        $('monNotifyOnFailure').checked = m.notifyOnFailure;
        clearTestResult();
        syncMonitorMethod();
        syncMonitorCompareMode();
        $('monitorDialog').showModal();
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
        removeBtn.addEventListener('click', () => row.remove());
        row.append(nameInput, pointerInput, removeBtn);
        $('monRulesRows').append(row);
    }

    function collectRuleRows() {
        return Array.from($('monRulesRows').querySelectorAll('.rule-row'))
            .map((row) => ({
                name: row.querySelector('.rule-row__name').value.trim(),
                pointer: row.querySelector('.rule-row__pointer').value.trim(),
            }))
            .filter((r) => r.name || r.pointer);
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
            intervalSeconds: Number($('monInterval').value),
            enabled: $('monEnabled').checked,
            compareMode: $('monCompareMode').value,
            extractRules: collectRuleRows(),
            itemPointer: $('monItemPointer').value.trim() || null,
            itemKeyPointer: $('monItemKeyPointer').value.trim() || null,
            messageTemplate: $('monTemplate').value,
            notifyOnFailure: $('monNotifyOnFailure').checked,
            cooldownSeconds: Number($('monCooldown').value || 0),
            maxNotificationsPerDay: $('monMaxPerDay').value.trim() ? Number($('monMaxPerDay').value) : null,
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

    async function testMonitorNow() {
        let form;
        try {
            form = collectMonitorForm();
        } catch (e) {
            showMonitorFormError('自訂 header 不是合法的 JSON：' + e.message);
            return;
        }
        // /monitors/test 不吃 clientId/interval/enabled 等排程/發送欄位——試跑不建立
        // 排程、不綁定 client、更不會發送，只帶抓取＋解析＋渲染需要的部分。
        const body = {
            name: form.name || null,
            url: form.url,
            method: form.method,
            requestBody: form.requestBody,
            headers: form.headers,
            compareMode: form.compareMode,
            extractRules: form.extractRules,
            itemPointer: form.itemPointer,
            itemKeyPointer: form.itemKeyPointer,
            messageTemplate: form.messageTemplate,
        };

        const btn = $('monTestBtn');
        btn.disabled = true; btn.textContent = '測試中…';
        const box = $('monTestResult');
        box.hidden = false;
        box.replaceChildren(el('div', 'loading', '測試中…'));
        try {
            const result = await call('/monitors/test', { method: 'POST', body: JSON.stringify(body) });
            renderMonitorTestResult(result);
        } catch (e) {
            box.replaceChildren(el('div', 'notice notice--error', e.message));
        } finally { btn.disabled = false; btn.textContent = '立即測試'; }
    }

    function renderMonitorTestResult(result) {
        const box = $('monTestResult');
        box.replaceChildren();

        if (!result.ok) {
            const detail = `${result.failureReason || '失敗'}`
                + (result.httpStatus ? `（HTTP ${result.httpStatus}）` : '')
                + `：${result.failureDetail || ''}`;
            box.append(el('div', 'notice notice--error', detail));
            return;
        }

        const okMsg = '抓取成功' + (result.httpStatus ? `（HTTP ${result.httpStatus}）` : '') + '。';
        box.append(el('div', 'notice notice--success', okMsg));

        const valueKeys = Object.keys(result.values || {});
        if (valueKeys.length) {
            box.append(el('p', 'fieldset-label', '抽取到的值'));
            const list = el('ul', 'tag-set');
            valueKeys.forEach((k) => list.append(
                el('li', 'scope', `${k} = ${result.values[k] == null ? '—' : result.values[k]}`)));
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
                const fieldKeys = Object.keys(item.fields || {});
                const text = fieldKeys.map((k) => `${k}=${item.fields[k]}`).join(', ');
                tr.append(td(text || '—'));
                tb.append(tr);
            });
            table.append(tb);
            const wrap = el('div', 'table-wrap'); wrap.append(table);
            box.append(wrap);
        }

        box.append(el('p', 'fieldset-label', '渲染後的訊息'));
        const pre = el('pre', 'test-message');
        pre.textContent = result.renderedMessage || '（空）';
        box.append(pre);
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
    $('monAddRule').addEventListener('click', () => addRuleRow('', ''));
    $('monMethod').addEventListener('change', syncMonitorMethod);
    $('monCompareMode').addEventListener('change', syncMonitorCompareMode);
    $('monitorForm').addEventListener('submit', submitMonitor);
    $('monitorCancel').addEventListener('click', () => $('monitorDialog').close());
    $('monTestBtn').addEventListener('click', testMonitorNow);
    $('monitorRunsClose').addEventListener('click', () => $('monitorRunsDialog').close());

    $('logoutForm').addEventListener('submit', (e) => {
        const t = csrfToken();
        if (!t) return;
        const f = document.createElement('input');
        f.type = 'hidden'; f.name = '_csrf'; f.value = t;
        e.currentTarget.append(f);
    });

    routeFromHash();
})();
