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

    const state = { clients: [], users: [], loaded: {} };

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
        send: { title: '發送', sub: '從後台直接發測試通知', load: loadSend },
        history: { title: '發送紀錄', sub: '最新 100 筆', load: loadHistory },
        users: { title: '使用者', sub: '管理 owner 標記', load: loadUsers },
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
        try {
            const [stats, recent] = await Promise.all([call('/stats'), call('/notifications')]);
            renderStats(stats);
            renderDashRecent(recent.slice(0, 10));
        } catch (e) { pageError('載入失敗：' + e.message); }
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
        syncSendPicker();
    }

    function syncSendPicker() {
        $('sendUserPickerField').hidden = selectedRadio('sendType') !== 'USER';
    }

    async function submitSend(event) {
        event.preventDefault();
        $('sendError').hidden = true; $('sendOk').hidden = true;
        const type = selectedRadio('sendType');
        const body = {
            clientId: $('sendClient').value,
            type: type || null,
            userIds: type === 'USER' ? checkedValues($('sendUserPicker')) : [],
            title: $('sendTitle').value.trim() || null,
            text: $('sendText').value,
        };
        const btn = $('sendSubmit');
        btn.disabled = true; btn.textContent = '送出中…';
        try {
            const accepted = await call('/notifications/test', { method: 'POST', body: JSON.stringify(body) });
            const ok = $('sendOk');
            ok.textContent = `已受理：${accepted.recipientCount} 位收件人、${accepted.batchCount} 批。到「紀錄」查最終狀態。`;
            ok.hidden = false;
            $('sendText').value = '';
            toast('已送出');
        } catch (e) {
            const box = $('sendError'); box.textContent = e.message; box.hidden = false;
        } finally { btn.disabled = false; btn.textContent = '送出'; }
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
    $('sendForm').addEventListener('submit', submitSend);

    $('detailClose').addEventListener('click', () => $('detailDialog').close());

    $('logoutForm').addEventListener('submit', (e) => {
        const t = csrfToken();
        if (!t) return;
        const f = document.createElement('input');
        f.type = 'hidden'; f.name = '_csrf'; f.value = t;
        e.currentTarget.append(f);
    });

    routeFromHash();
})();
