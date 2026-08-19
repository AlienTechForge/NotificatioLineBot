# NotifyLine — 統一 LINE Notification Service

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
| T1 | 地基：Docker、Flyway 9 張表、SDK 驗證 | ✅ |
| T2 | 加密與 HMAC 簽章基礎 | ✅ |
| T3 | Client 領域 + Bootstrap CLI | ✅ |
| T4 | HMAC 認證、速率限制、統一錯誤處理 | ✅ |
| T5 | LINE User 管理 + Webhook（含冪等閘門） | ✅ |
| T6 | 自助申請金鑰的一次性連結 | ⏳ |
| T7 | `POST /api/v1/notifications` 本體 | ⏳ |
| T8 | 非同步分批派送（500 人一批、重試、斷路器） | ⏳ |
| T9 | 查詢 API、稽核、指標、資料保留 | ⏳ |
| T10 | CI/CD | ✅ |

**157 個測試**（97 單元 + 60 整合），覆蓋率門檻：整體 80% / `auth` 95%。

---

## 快速上手

```bash
cp .env.example .env      # 填入 LINE 憑證與加密金鑰，填法見 deploy/README.md
bash poc/poc.sh           # 起 Docker、建憑證、跑完 37 項端到端檢查
```

POC 說明見 [`poc/README.md`](poc/README.md) —— **不需要真實 LINE 憑證**也能跑完。

---

## 文件

| 想知道什麼 | 看哪裡 |
|---|---|
| **我要寫一個 client 來發通知** | [`Docs/AI-接入指南.md`](Docs/AI-接入指南.md) |
| 這專案要解決什麼、範圍到哪 | [`Docs/plan/01-PRD.md`](Docs/plan/01-PRD.md) |
| 系統長什麼樣、用什麼版本 | [`Docs/plan/02-架構設計.md`](Docs/plan/02-架構設計.md) |
| API 的設計理由與完整契約 | [`Docs/plan/05-API契約.md`](Docs/plan/05-API契約.md) |
| 誰能發給誰、金鑰怎麼發 | [`Docs/plan/03-權限與認證設計.md`](Docs/plan/03-權限與認證設計.md) |
| LINE 平台有哪些硬限制 | [`Docs/plan/06-LINE整合設計.md`](Docs/plan/06-LINE整合設計.md) |
| 部署到 server | [`deploy/README.md`](deploy/README.md) |
| **加密金鑰怎麼備份** | [`deploy/金鑰管理.md`](deploy/金鑰管理.md) |
| 當初為什麼這樣選 | [`Docs/plan/adr/`](Docs/plan/adr/README.md) |
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

Java 21 · Spring Boot 4.1 · PostgreSQL 18 · Flyway · LINE Bot SDK 10.1 ·
Testcontainers · Docker

---

## 安全須知

- **`.env` 絕不進版控**（已在 `.gitignore`）
- **`APP_SECRET_ENC_KEY` 遺失 = 所有 client 憑證報廢**（無法解密，只能全部重發）。
  必須額外備份，且**不可與資料庫備份放在同一個地方** ——
  完整說明見 [`deploy/金鑰管理.md`](deploy/金鑰管理.md)
- Client secret **只在建立時顯示一次**，之後任何人都無法讀回
