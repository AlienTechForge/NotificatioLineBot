# NotifyLine — 統一 LINE Notification Service

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

讓所有內部服務共用同一套 LINE 通知管道。呼叫端只負責
**組通知內容 → 用自己的 secret 簽章 → 打一個 API**，其餘（LINE Token、SDK、
User 名單、發送 routing、權限、通知紀錄）全部由這個服務收斂。

```http
POST /api/v1/notifications
```

---

## 為什麼需要它

每個專案各自接 LINE SDK 會造成四個問題：

| 問題 | 後果 |
|---|---|
| Channel Token 四散 | 輪替一次要改 N 個地方，漏改一個就靜默失敗 |
| User 名單各自維護 | 使用者封鎖 Bot 後各專案不會知道，繼續對空氣發送 |
| 無法統一停用 | 某服務洗版，只能進去改它的程式再重新部署 |
| 無紀錄可查 | 「上週那則通知到底送出去沒有」無法回答 |

---

## 現況

| 階段 | 內容 | 狀態 |
|---|---|---|
| T1 | 地基：Docker、Flyway schema、SDK 驗證 | ✅ |
| T2 | 加密與 HMAC 簽章基礎 | ✅ |
| T3 | Client 領域 + Bootstrap CLI | ✅ |
| T4 | HMAC 認證、速率限制、統一錯誤處理 | ✅ |
| T5 | LINE User 管理 + Webhook（含冪等閘門） | ✅ |
| T6 | 自助申請金鑰的一次性連結 | ⏳ 未動工 |
| T7 | `POST /api/v1/notifications` 本體 | ✅ |
| T8 | 非同步分批派送（500 人一批、重試、斷路器） | ✅ |
| T9 | 查詢 API、稽核、指標、資料保留 | 🚧 部分 |
| T10 | CI/CD | ✅ |
| ＋ | 管理後台 + [API 監控子系統](#api-監控)（規劃外新增） | ✅ |

兩個「不是 ✅」的細節，先講清楚免得踩雷：

- **T6 真的還沒開始**。`enrollment/` 套件只有 `.gitkeep`，`GET /enroll/{token}` **不存在**
  （雖然 `SecurityConfig` 已放行 `/enroll/**`，打進去是 404）。目前憑證只能靠
  Bootstrap CLI 或管理後台建立。Webhook 的「申請金鑰」/「重設金鑰」指令目前只會回「即將開放（T6）」。
- **T9 只做了一半**：`GET /api/v1/notifications/{id}` 與 health liveness/readiness 分層已完成；
  **稽核（`audit_log` 建了表但零讀寫）、自訂指標（一個都沒有）、資料保留期清理排程（方法寫好了但沒人呼叫）
  都還沒接線**。`/actuator/prometheus` 目前也被 `SecurityConfig` 擋在外面。

資料表：Flyway `V1`~`V7`，共 **15 張應用資料表**。

測試與覆蓋率以 `mvn -f server/pom.xml verify` 的報告為準；原始碼有 **64 個測試類別**
（44 個 `*Test`、20 個 `*IT`，不含共用基底），含參數化案例的實際執行數見
[測試計畫](Docs/plan/08-測試計畫.md)。覆蓋率門檻：整體 80%／`auth` 95%。

---

## API 監控

原本的規劃只有「別人打 API 進來、我幫你發通知」。實際做下去多長出一整套反向的東西：
**服務自己定時去打別人的 API，發現變化就發 LINE 通知**，程式位於 `monitor/`。

流程是：排程 claim 一筆監控 → 抓取（**只允許 https、不跟隨轉址**）→ 用 JSON path 取值
→ 比對（EXTRACTED 缺值／null 視為失敗，保留上次基準）→ 套訊息模板 → 走同一條派送管線送出 → 結果寫進 `api_monitor_run`。

- **三種比對模式**：`WHOLE_BODY`（整包 body）、`EXTRACTED`（取出的值）、
  `NEW_ITEMS`（清單新增項，用 `api_monitor_seen_item` 去重）
- **設定用貼的**：可以直接貼瀏覽器複製的 `curl` 指令或 `fetch` 程式碼，也支援 JSON bundle 匯入
- **需要登入的站台**：`site_session` 存 cookie jar；`monitor_login` 提供 Cognito SRP 登入與 token 快取，機密均以 AES-256-GCM 加密
- **計算欄位**：`{{computed.*}}` 可做雜湊串接，餵給需要簽章的請求
- 全部在管理後台操作（`/admin/` 的監控頁），**沒有對外 API**

設計文件：[11](Docs/plan/11-API監控輪詢設計.md)（輪詢機制）、
[12](Docs/plan/12-API監控易用性升級.md)（後台操作）、
[13](Docs/plan/13-監控計算欄位設計.md)（計算欄位）、
[14](Docs/plan/14-模板輸入輔助.md)（模板輸入輔助）、
[15](Docs/plan/15-監控站台登入設計.md)（Cognito SRP）。

---

## 快速上手

先用隔離資料庫與測試 LINE 帳號填寫 `.env`，不要覆蓋已有的正式設定：

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml --env-file .env up -d --build
curl -fsS http://127.0.0.1:19080/actuator/health
```

若只要驗證程式，執行下方 Maven verify 即可，不需要正式憑證。
歷史 [`poc/poc.sh`](poc/README.md) 只覆蓋 T1～T5，migration／表數斷言已過時，
且會寫入測試資料，請勿指向正式環境。

---

## 文件

| 想知道什麼 | 看哪裡 |
|---|---|
| **我要寫一個 client 來發通知** | [`Docs/AI-接入指南.md`](Docs/AI-接入指南.md) |
| 設定某組憑證要通知誰 | 管理介面 `https://<你的網域>/admin/` |
| 這專案要解決什麼、範圍到哪 | [`Docs/plan/01-PRD.md`](Docs/plan/01-PRD.md) |
| 系統長什麼樣、用什麼版本 | [`Docs/plan/02-架構設計.md`](Docs/plan/02-架構設計.md) |
| API 的設計理由與完整契約 | [`Docs/plan/05-API契約.md`](Docs/plan/05-API契約.md) |
| 誰能發給誰、金鑰怎麼發 | [`Docs/plan/03-權限與認證設計.md`](Docs/plan/03-權限與認證設計.md) |
| LINE 平台有哪些硬限制 | [`Docs/plan/06-LINE整合設計.md`](Docs/plan/06-LINE整合設計.md) |
| 大量發送怎麼不掉單、不重複 | [`Docs/plan/07-非同步與可靠性設計.md`](Docs/plan/07-非同步與可靠性設計.md) |
| **API 監控怎麼輪詢、怎麼比對** | [`Docs/plan/11-API監控輪詢設計.md`](Docs/plan/11-API監控輪詢設計.md) |
| 監控在後台怎麼設定（貼 curl、點欄位） | [`Docs/plan/12-API監控易用性升級.md`](Docs/plan/12-API監控易用性升級.md) |
| 監控的計算欄位與請求簽章 | [`Docs/plan/13-監控計算欄位設計.md`](Docs/plan/13-監控計算欄位設計.md) |
| 模板輸入輔助（`{{` 自動完成、變數說明） | [`Docs/plan/14-模板輸入輔助.md`](Docs/plan/14-模板輸入輔助.md) |
| 實作照什麼順序做、每步怎麼驗 | [`Docs/plan/10-任務拆解.md`](Docs/plan/10-任務拆解.md) |
| 部署到 server | [`deploy/README.md`](deploy/README.md) |
| **加密金鑰怎麼備份** | [`deploy/金鑰管理.md`](deploy/金鑰管理.md) |
| 當初為什麼這樣選 | [`Docs/plan/adr/`](Docs/plan/adr/README.md) |
| 站台自動登入與 token 更新 | [`Docs/plan/15-監控站台登入設計.md`](Docs/plan/15-監控站台登入設計.md) |
| main 隱私與敏感資訊檢查 | [`Docs/隱私與敏感資訊檢查.md`](Docs/隱私與敏感資訊檢查.md) |
| 完整索引 | [`Docs/plan/README.md`](Docs/plan/README.md) |

---

## 開發

```bash
mvn -f server/pom.xml test      # 單元測試（快，TDD 迴圈用）
mvn -f server/pom.xml verify    # 加上整合測試（Testcontainers）與覆蓋率門檻
```

> **本機若裝了會 MITM TLS 的防毒**（Avast 等），Maven 會失敗於
> `PKIX path building failed`。加上
> `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"`。
> 不要寫進 `.mvn/jvm.config` —— 那會讓 Linux 的 CI 掛掉。

## 技術

Java 21 · Spring Boot 4.1.0 · PostgreSQL 18 · Flyway · LINE Bot SDK 10.1.0 ·
Testcontainers · Docker

管理後台是**手寫原生 JS**（`server/src/main/resources/static/admin/`），
無框架、無 build step、無 npm 依賴 —— 改完直接重啟就生效。

---

## 安全須知

- **`.env` 絕不進版控**（已在 `.gitignore`）
- **`APP_SECRET_ENC_KEY` 遺失會使使用該版本的憑證與監控登入資料無法解密**。
  必須額外備份，且**不可與資料庫備份放在同一個地方** ——
  完整說明見 [`deploy/金鑰管理.md`](deploy/金鑰管理.md)
- Client secret 的 API 不提供讀回；CLI／Admin workflow 建立時會留下明文輸出，Actions raw log 仍可能保留該值。
- 文件與 nginx 範例只使用示例網域；歷史 commit／Actions logs 的個資及機密風險見隱私檢查報告。
