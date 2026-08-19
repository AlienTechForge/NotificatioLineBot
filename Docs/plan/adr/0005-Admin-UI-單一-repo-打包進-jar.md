# ADR-0005 — Admin UI 置於同一 repo、打包進 jar

**狀態**：Accepted ｜ 2026-08-18（Phase 2 實作）

## 背景

[初步SPEC.md](../../初步SPEC.md) §8 規劃「後續可以在同一個 Spring Boot 專案增加 Web Admin Panel」，用途是管理 Client、管理 LINE User、查看通知紀錄、手動發送、設定定時通知。

決策時的兩個明確約束：

1. **不想多管理一個專案**（多一個 repo、多一套部署、多一組 CI）
2. **不想用 Thymeleaf**——伺服器端模板做出好看的 UI 太費工

這兩點看似衝突：想要單一部署物，又想要現代前端的開發體驗與現成元件。

## 決策

**React + Vite + Ant Design，放在同一個 repo 的 `admin-ui/` 目錄，CI 打包時併入 jar 的 `static/admin/`。**

具體安排：

| 項目 | 做法 |
|---|---|
| 目錄 | `server/` 與 `admin-ui/` 平行，各自獨立的 build |
| 本機開發（後端） | 完全不需要 Node。Maven profile `with-admin-ui` **預設不啟用** |
| 本機開發（前端） | `npm run dev`，Vite dev server proxy 到 `localhost:8080` |
| 打包 | CI 啟用 `-Pwith-admin-ui`，frontend-maven-plugin build `../admin-ui` → `target/classes/static/admin/` |
| 部署 | **一個 jar、一個容器** |

## 理由

### 為什麼不用 Thymeleaf

伺服器端模板要做出可用的後台，得自己處理表格排序分頁、表單驗證回饋、Modal、載入狀態、篩選器。這些在 Ant Design 都是現成元件（`Table`、`Form`、`Modal`、`DatePicker`），組起來就能用。

「後台 UI 幾乎不用自己設計」正是選 Ant Design 的原因——它的預設樣式就是為管理介面設計的。

### 為什麼不拆成獨立 repo

拆開會帶來：兩個 repo 的版本同步、兩套 CI、兩個部署目標、CORS 設定、以及「前端是哪個 commit 對應後端哪個 commit」的追蹤問題。

對一個內部管理後台，這些成本換不到對應的好處。同 repo 讓「改一個 API 順便改對應的畫面」變成一個 PR。

### 為什麼 profile 預設不啟用

**後端開發者不該因為專案裡有前端而被迫裝 Node。** `mvn verify` 應該在只有 JDK 的環境下就能跑完（CI 的 PR 驗證也是如此）。

只有實際要產生部署物時才需要 Node，那時 CI 的 Docker multi-stage build 會處理。

### 為什麼仍算「兩個專案」

使用者的原話是「在 GitHub 上當兩個專案看待/處理」。這正是此設計的效果：

- `admin-ui/` 有自己的 `package.json`、自己的 lint/test、自己的依賴樹
- 前端開發者可以只開 `admin-ui/` 工作，不碰 Java
- 後端開發者可以完全不知道 `admin-ui/` 存在

**共用的只有 repo 與最終的部署物**，開發時是獨立的。

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **Thymeleaf** | 使用者明確排除；後台需要的互動元件都要自己刻 |
| **JTE + Tailwind + daisyUI** | 比 Thymeleaf 好（型別安全、有現成 component class），但複雜互動（即時紀錄查詢、多步驟表單）仍比 React 痛 |
| **Vaadin Flow** | 純 Java 寫 UI，完全不碰前端，很吸引「討厭前端」的需求。但框架綁定深、jar 明顯變大、客製版面時彈性差，且團隊需學一套專屬 API |
| **獨立 repo 的 SPA** | 版本同步、兩套 CI、CORS、部署複雜度。內部後台不值得 |
| **只做 REST API，不做 UI** | 日常維運（查紀錄、停用 client）用 curl 太痛苦 |
| **Nginx 另外服務靜態檔** | 多一個部署元件與一組路徑設定，違背「單一部署物」 |

## 後果

**正面**

- 一個 jar、一個容器、一次部署
- 後端開發不需要 Node
- 前後端在同一個 PR 中保持一致
- Ant Design 現成元件，UI 開發量最小
- 沒有 CORS 問題（同源）

**負面**

| 後果 | 緩解 |
|---|---|
| 前端改動要重新 build 整個 jar | 內部後台，部署頻率不高 |
| jar 變大（Ant Design 的 bundle 不小） | Vite 的 tree-shaking + code splitting；且這只影響映像檔大小，不影響執行 |
| Docker build 需要 Node stage | multi-stage，且 `WITH_ADMIN_UI=false` 時該 stage 幾乎是 no-op |
| 前端無法獨立於後端發布 | 內部工具，沒有這個需求 |
| 需要在 `SecurityConfig` 處理 SPA 的 fallback 路由 | Phase 2 加一個 controller 把 `/admin/**` 未匹配的路徑轉回 `index.html` |

**Phase 2 的前提條件**

Admin UI 需要一套與 HMAC 完全不同的認證（人不可能手算簽章）。方案見 [ADR-0009](0009-Admin-認證採-LINE-Login.md)。`SecurityConfig` 的兩條 filter chain 分界在 Phase 1 就要預留（[03 §6](../03-權限與認證設計.md#6-securityconfig-路徑分流)）。
