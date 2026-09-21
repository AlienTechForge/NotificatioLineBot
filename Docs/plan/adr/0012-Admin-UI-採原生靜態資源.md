# ADR-0012 — Admin UI 採原生靜態資源

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對。

**狀態**：Accepted ｜ 2026-09-21 追認既有實作

**取代**：[ADR-0005](0005-Admin-UI-單一-repo-打包進-jar.md) 中 React、Vite、Ant Design 與 `admin-ui/` build 的部分。單一 repo、單一 jar 的部署決策保留。

## 背景

原方案預計把 React 前端建置後複製進 jar。實際管理介面已完成於 `server/src/main/resources/static/admin/`，由 `index.html`、`login.html`、`assets/app.js` 與 `assets/app.css` 組成；`admin-ui/` 沒有 `package.json` 或可執行的 build。

## 決策

管理介面維持原生 HTML、CSS、JavaScript，由 Spring Boot 直接以靜態資源服務。Maven 是唯一建置流程，不引入 Node、bundler、第三方前端套件或 CDN。頁面以 hash 切換 view，DOM 內容以 `createElement`、`textContent` 與 `replaceChildren` 建立；寫入管理 API 時帶 session cookie 與 CSRF header。

## 理由

- 現有介面已涵蓋 client、使用者、通知、監控、session 與 Cognito 登入管理。
- 零前端供應鏈與 CDN 相依，部署仍是一個 jar／image。
- 後台規模可由單一維運團隊掌握，引入第二套工具鏈的成本目前高於收益。

## 已考慮的替代方案

| 方案 | 取捨 |
|---|---|
| React + Vite + Ant Design | 元件與型別生態較完整，但需要 Node 建置、依賴更新與前端測試管線 |
| Thymeleaf | 同一建置單位，但互動式表格、對話框與即時測試仍需大量 JavaScript |
| 獨立前端服務 | 可獨立發布，但增加 repo、部署物與跨來源安全設定 |

## 後果

- 正面：建置與部署簡單；無第三方前端 runtime；靜態檔直接隨後端版本發布。
- 代價：`app.js` 體積持續成長，沒有靜態型別、模組邊界與瀏覽器行為測試；重構需格外依賴人工審查與整合測試。
- 重新評估門檻：多人並行維護前端、需要可重用複雜元件，或 UI 缺陷開始無法以現有測試控制時，應開新 ADR。
