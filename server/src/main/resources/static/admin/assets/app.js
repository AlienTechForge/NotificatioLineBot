/* ==========================================================================
   NotifyLine Admin
   ==========================================================================

   認證走 session cookie（見 AdminSecurityConfig），所以這裡不碰任何金鑰。
   寫入請求必須附上 CSRF token —— 有 cookie 就有 CSRF 風險。
   ========================================================================== */

(() => {
    'use strict';

    const API = '/admin/api';

    /** 全部 DOM 寫入都經過 textContent，不用 innerHTML。 */
    const el = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = String(text);
        return node;
    };

    const $ = (id) => document.getElementById(id);

    // ---------------------------------------------------------------- CSRF

    /**
     * Spring Security 把 token 放在 XSRF-TOKEN cookie，要回填成 X-XSRF-TOKEN header。
     * 少了它每個寫入請求都會 403，而錯誤訊息不會提到 CSRF。
     */
    const csrfToken = () => {
        const hit = document.cookie
            .split('; ')
            .find((row) => row.startsWith('XSRF-TOKEN='));
        return hit ? decodeURIComponent(hit.slice('XSRF-TOKEN='.length)) : null;
    };

    // ----------------------------------------------------------------- API

    async function call(path, options = {}) {
        const headers = { 'Accept': 'application/json', ...(options.headers || {}) };
        if (options.body) {
            headers['Content-Type'] = 'application/json';
            const token = csrfToken();
            if (token) headers['X-XSRF-TOKEN'] = token;
        }

        const response = await fetch(API + path, {
            ...options,
            headers,
            // 未登入時要拿到 401 而不是被導去登入頁的 HTML
            redirect: 'error',
            credentials: 'same-origin',
        });

        if (response.status === 401) {
            // session 過期。導回登入頁比讓使用者對著空畫面猜要好。
            location.href = '/admin/login.html';
            throw new Error('未登入');
        }

        const payload = await response.json().catch(() => null);
        if (!response.ok || !payload || payload.success === false) {
            const error = payload && payload.error ? payload.error : {};
            throw new Error(error.message || `HTTP ${response.status}`);
        }
        return payload.data;
    }

    // --------------------------------------------------------------- 狀態

    let clients = [];
    let lineUsers = [];
    let editing = null;

    // --------------------------------------------------------------- 提示

    function toast(message, isError = false) {
        const item = el('div', 'toast__item' + (isError ? ' toast__item--error' : ''), message);
        $('toast').append(item);
        // 3–5 秒自動消失
        setTimeout(() => item.remove(), 4000);
    }

    function pageError(message) {
        const box = $('pageError');
        box.textContent = message;
        box.hidden = !message;
    }

    // --------------------------------------------------------------- 渲染

    const TARGET_LABEL = {
        OWNER: '管理者',
        SELF: '綁定的使用者',
        USER: '指定使用者',
        ALL: '全體好友',
    };

    const STATUS_TAG = {
        ACTIVE: 'tag--active',
        DISABLED: 'tag--warning',
        REVOKED: 'tag--danger',
    };

    function displayName(lineUserId) {
        const user = lineUsers.find((u) => u.lineUserId === lineUserId);
        return user && user.displayName ? user.displayName : lineUserId;
    }

    function targetCell(client) {
        const cell = el('td');

        if (!client.defaultTargetType) {
            cell.append(el('span', 'tag tag--muted', '未設定'));
            return cell;
        }

        const tag = el('span', 'tag tag--target', TARGET_LABEL[client.defaultTargetType]);
        cell.append(tag);

        if (client.defaultTargetType === 'USER') {
            const names = el('ul', 'tag-set');
            names.style.marginTop = '6px';
            client.defaultTargetUserIds.forEach((id) => {
                names.append(el('li', 'scope', displayName(id)));
            });
            cell.append(names);
        }
        return cell;
    }

    function renderRow(client) {
        const row = el('tr');

        const nameCell = el('td');
        nameCell.append(el('div', 'client-name', client.name));
        nameCell.append(el('div', 'client-id', client.clientId));
        row.append(nameCell);

        const statusCell = el('td');
        statusCell.append(el('span',
            'tag ' + (STATUS_TAG[client.status] || 'tag--muted'), client.status));
        row.append(statusCell);

        row.append(targetCell(client));

        const scopeCell = el('td');
        const scopeList = el('ul', 'tag-set');
        client.scopes.forEach((scope) => scopeList.append(el('li', 'scope', scope)));
        scopeCell.append(scopeList);
        row.append(scopeCell);

        const actionCell = el('td');
        const button = el('button', 'btn btn--sm', '設定');
        button.type = 'button';
        // 每個按鈕都要有能單獨辨識的名稱，否則螢幕閱讀器只會聽到一整排「設定」
        button.setAttribute('aria-label', `設定 ${client.name} 的預設通知對象`);
        button.addEventListener('click', () => openDialog(client));
        actionCell.append(button);
        row.append(actionCell);

        return row;
    }

    function render() {
        const body = $('clientRows');
        body.replaceChildren(...clients.map(renderRow));

        $('loading').hidden = true;
        $('tableWrap').hidden = clients.length === 0;
        $('empty').hidden = clients.length !== 0;
    }

    // ------------------------------------------------------------- 對話框

    function selectedType() {
        const checked = document.querySelector('input[name="targetType"]:checked');
        return checked ? checked.value : '';
    }

    function selectedUserIds() {
        return Array.from(
            $('userPicker').querySelectorAll('input[type="checkbox"]:checked'),
            (input) => input.value);
    }

    function syncUserPicker() {
        const isUser = selectedType() === 'USER';
        $('userPickerField').hidden = !isUser;
        const count = selectedUserIds().length;
        $('userPickerHint').textContent = `已選 ${count} 人。`;
        $('saveBtn').disabled = isUser && count === 0;
    }

    function buildUserPicker(selectedIds) {
        const picker = $('userPicker');

        if (lineUsers.length === 0) {
            picker.replaceChildren(
                el('div', 'empty', '沒有可選的使用者。請先讓對方加 Bot 好友。'));
            return;
        }

        picker.replaceChildren(...lineUsers.map((user) => {
            const row = el('label', 'user-row');

            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.value = user.lineUserId;
            checkbox.checked = selectedIds.includes(user.lineUserId);
            checkbox.addEventListener('change', syncUserPicker);
            row.append(checkbox);

            const text = el('span');
            const nameLine = el('span', 'user-row__name', user.displayName || '(未同步顯示名稱)');
            text.append(nameLine);
            if (user.owner) {
                const ownerTag = el('span', 'tag tag--target', 'owner');
                ownerTag.style.marginInlineStart = '6px';
                nameLine.append(ownerTag);
            }
            text.append(el('div', 'user-row__id', user.lineUserId));
            row.append(text);

            return row;
        }));
    }

    function openDialog(client) {
        editing = client;

        $('dialogSubtitle').textContent = `${client.name} · ${client.clientId}`;
        $('dialogError').hidden = true;

        const type = client.defaultTargetType || '';
        document.querySelectorAll('input[name="targetType"]').forEach((input) => {
            input.checked = input.value === type;
        });

        buildUserPicker(client.defaultTargetUserIds || []);
        syncUserPicker();

        $('editDialog').showModal();
    }

    async function save(event) {
        event.preventDefault();

        const type = selectedType();
        const userIds = type === 'USER' ? selectedUserIds() : [];

        const button = $('saveBtn');
        button.disabled = true;
        button.textContent = '儲存中…';

        try {
            const updated = await call(
                `/clients/${encodeURIComponent(editing.clientId)}/default-target`,
                {
                    method: 'PUT',
                    body: JSON.stringify({ type: type || null, userIds }),
                });

            const index = clients.findIndex((c) => c.clientId === updated.clientId);
            if (index >= 0) clients[index] = updated;

            render();
            $('editDialog').close();
            toast(`已更新 ${updated.name} 的預設通知對象`);

        } catch (error) {
            // 錯誤留在對話框裡，緊鄰造成問題的欄位，不要丟到頁面頂端讓人找
            const box = $('dialogError');
            box.textContent = error.message;
            box.hidden = false;
        } finally {
            button.disabled = false;
            button.textContent = '儲存';
            syncUserPicker();
        }
    }

    // --------------------------------------------------------------- 載入

    async function load() {
        pageError('');
        $('loading').hidden = false;
        try {
            // 兩個都要，一起等比較快；任何一個失敗就整體視為失敗
            [clients, lineUsers] = await Promise.all([
                call('/clients'),
                call('/line-users'),
            ]);
            render();
        } catch (error) {
            $('loading').hidden = true;
            pageError(`載入失敗：${error.message}`);
        }
    }

    // --------------------------------------------------------------- 綁定

    document.querySelectorAll('input[name="targetType"]')
        .forEach((input) => input.addEventListener('change', syncUserPicker));

    $('editForm').addEventListener('submit', save);
    $('cancelBtn').addEventListener('click', () => $('editDialog').close());
    $('refresh').addEventListener('click', load);

    $('logoutForm').addEventListener('submit', (event) => {
        // 登出是 POST，同樣要 CSRF token
        const token = csrfToken();
        if (!token) return;
        const field = document.createElement('input');
        field.type = 'hidden';
        field.name = '_csrf';
        field.value = token;
        event.currentTarget.append(field);
    });

    load();
})();
