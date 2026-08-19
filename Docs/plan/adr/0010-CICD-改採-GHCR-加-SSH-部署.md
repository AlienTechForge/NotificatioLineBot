# ADR-0010 — image 走 GHCR，部署仍由 self-hosted runner 執行

**狀態**：Accepted ｜ 2026-08-19
**關係**：**補充**（不是取代）[ADR-0006](0006-CICD-採-self-hosted-runner.md)

> ### 修正紀錄
>
> 本文件 2026-08-19 的第一版把 ADR-0006 標記為 Superseded，主張改用
> 「GHCR + SSH 部署」。**那是錯的**，當天稍後就撤回了。
>
> 錯在哪：使用者在規劃階段明確選了 self-hosted runner，而且**早就在 server 上
> 裝好並註冊到組織層級**。後來他提供了一份 GHCR 的 CI/CD 範本，我把「請照這份
> 範本配」誤解成「請改掉部署方式」，於是自行推翻了一個已定案且已落地的決策，
> 也沒有先問。實際上兩者不衝突 —— 範本講的是 image 怎麼建，runner 講的是
> 部署在哪執行。
>
> 教訓：**收到新資訊時，先判斷它是「補充」還是「取代」，不確定就問。**
> 推翻既有決策的門檻要高於採納新做法。

## 背景

[ADR-0006](0006-CICD-採-self-hosted-runner.md) 決定用 self-hosted runner 部署。
實際環境：

- 組織 **AlienTechForge** 已有註冊在**組織層級**的 runner `alien-server`（`status=online`，
  labels `[self-hosted, Linux, X64]`）
- 組織層級的 runner **只服務組織內的 repo** —— 所以 repo 必須放在該組織下，
  放在個人帳號下觸發不到

同時，使用者提供的 CI/CD 範本要求 image 推上 GHCR。

## 決策

**三段式 workflow，兩種 runner 分工：**

| Job | 跑在哪 | 內容 |
|---|---|---|
| `test` | GitHub-hosted `ubuntu-latest` | `mvn verify`（Testcontainers + 覆蓋率門檻） |
| `image` | GitHub-hosted `ubuntu-latest` | build 並推 GHCR，打 `sha-<短碼>` tag |
| `deploy` | **self-hosted `[self-hosted, Linux, X64]`** | `docker compose pull` + `up -d` + 健康檢查 + 回滾 |

repo 置於 **AlienTechForge** 組織下。

## 理由

### 為什麼 test / image 在 GitHub-hosted

- 測試用 Testcontainers 自備 PostgreSQL，不需要 server 上的任何東西
- **PR 完全不接觸正式環境** —— 這是 ADR-0006 就有的好處，保留
- build 吃 CPU，不該跟正式服務搶資源
- image 建好推 GHCR，成為**可追溯、可回滾、可在別台機器重現**的產物

### 為什麼 deploy 在 self-hosted

runner **就在那台 server 上**，所以：

- **不需要 SSH 金鑰**，不用把私鑰放進 GitHub Secrets
- **不需要對外開 22 port**
- 健康檢查直接打 `127.0.0.1`，不繞外網
- `.env` 由 server 端管理，機密從頭到尾不進 GitHub

這是相對「SSH 部署」明確更好的一點：少了一把能登入 server 的長期憑證。

### 為什麼用 `sha-<短碼>` 而非 `latest`

`latest` 是浮動的，「現在跑的是哪一版」無法回答，回滾也沒有明確目標。
deploy job 傳入確切的 sha tag，於是每次部署都對應一個 commit，
回滾就是把 `NOTIFYLINE_IMAGE` 指回前一個 tag。

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **全部在 self-hosted 上做**（含 build） | build 吃 CPU 會跟正式服務搶資源；PR 的程式碼會在 server 上執行，攻擊面變大 |
| **GHCR + SSH 部署** | 多一把能登入 server 的私鑰在 GitHub Secrets。既然 runner 已經在那台機器上，沒有理由再開一條 SSH 路徑 |
| **Watchtower 自動拉取** | 不需要任何 GitHub → server 的憑證，但部署時間不可控且無法自動回滾 |

## 後果

**正面**

- PR 與 build 不接觸正式環境
- 部署不需要 SSH 金鑰，機密不進 GitHub
- image 可追溯、可回滾
- server 上不需要 JDK / Maven / Node，只要 Docker

**負面**

| 後果 | 緩解 |
|---|---|
| **repo 必須留在組織下** | 組織層級的 runner 的固有限制。轉出組織就會失去部署能力 |
| runner 會在 server 上執行 repo 中的程式碼 | **repo 保持 private**；`permissions` 最小化；不使用 `pull_request_target` |
| runner 是要維運的常駐服務 | 它掛掉時 workflow 會靜默排隊而非明顯失敗，需要留意 |
| 部署期間短暫停機 | 通知留在 outbox 佇列，重啟後自動補送，不掉單 |
| GHCR 上的 image 會累積 | 定期清理 untagged image |
