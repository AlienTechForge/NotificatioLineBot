# ADR-0010 — CI/CD 改採 GHCR + SSH 部署

**狀態**：Accepted ｜ 2026-08-19
**取代**：[ADR-0006](0006-CICD-採-self-hosted-runner.md)

## 背景

[ADR-0006](0006-CICD-採-self-hosted-runner.md) 決定用 self-hosted runner，理由是「不用對外開放 SSH、憑證不進 GitHub」。實際要落地時，情況與當初的假設不同：

1. **server 不是專屬的**。它同時跑 nginx、MinIO 等既有服務，`8080` 已被占用。在上面再掛一個會執行 repo 程式碼的 runner 服務，風險範圍是整台機器而不只是這個專案。
2. **維運成本被低估**。runner 是一個要註冊、更新、監控的常駐服務。它掛掉時 workflow 只會靜默排隊，沒有明顯的失敗訊號。
3. **使用者已有既定做法**。既有的 CI/CD 範本就是 GHCR + 選用的 SSH deploy job，沿用它讓這個專案跟其他專案的操作方式一致。
4. **image 產物本身有價值**。GHCR 上的 image 是可追溯、可回滾、可在別台機器重現的產物。self-hosted runner 的「在 server 上直接 build」沒有留下這個。

## 決策

**三段式 workflow**，全部跑在 GitHub-hosted runner：

| Job | 內容 | 觸發 |
|---|---|---|
| `test` | `mvn verify`（含 Testcontainers 整合測試與覆蓋率門檻） | 所有 push 與 PR |
| `image` | build 並推送到 GHCR，以 `sha-<短碼>` 打 tag | 非 PR |
| `deploy` | SSH 到 server：`docker compose pull` + `up -d` + 健康檢查 + 失敗回滾 | 僅 `main` |

**`deploy` job 在缺少 SSH secrets 時自動跳過**，不會讓整個 workflow 紅燈 —— 這讓「先把 image 推上去、之後再接部署」是一條合法路徑。

## 理由

### 為什麼 GitHub-hosted 就夠

原本擔心的「測試需要 server 環境」不成立 —— 整合測試用 Testcontainers 自備 PostgreSQL，`ubuntu-latest` 內建 Docker 就能跑。**PR 完全不接觸正式環境**，這一點比 ADR-0006 的方案更好，不是更差。

### 用 sha tag 而非 latest 部署

`latest` 是浮動的，「現在跑的是哪一版」無法回答，回滾也沒有明確目標。deploy job 傳入確切的 `sha-<短碼>`，於是：

- 每次部署都能對應到一個 commit
- 回滾就是把 `NOTIFYLINE_IMAGE` 指回前一個 tag
- 部署失敗時腳本會自動用 `docker inspect` 讀出前一版並復原

### `.env` 仍然只在 server 上

這是 ADR-0006 最值得保留的一點，**沒有因為改方案而放棄**：機密（LINE 憑證、加密金鑰、DB 密碼）永遠不進 GitHub Secrets、不進 workflow 環境變數、不進 runner。deploy job 只送 `docker-compose.yml`，`.env` 由 server 端 root 管理。

deploy job 若發現 `.env` 不存在會直接失敗並明講，不會用預設值硬跑。

### SSH 開放的取捨

這是本決策相對 ADR-0006 **變差**的一點，必須誠實面對：多了一把能登入 server 的私鑰放在 GitHub Secrets。緩解：

- 用**專用的部署金鑰**，不是個人的日常金鑰
- 該金鑰在 server 上限制成只能執行部署所需的操作（`command=` 限制或專用低權限帳號）
- `deploy` job 只在 `main` 分支跑，PR 碰不到

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **維持 self-hosted runner** | server 是共用的；多一個會執行 repo 程式碼的常駐服務，風險範圍是整台機器 |
| **Watchtower 自動拉取** | 完全不需要 GitHub → server 的憑證，攻擊面最小。但**部署時間不可控**（下次輪詢才生效），且失敗無法自動回滾。若日後覺得 SSH 金鑰的風險不可接受，這是最合理的替代 |
| **server 上 `git pull` 後自行 build** | 需要在 server 裝 JDK/Maven/Node，且每次部署都重跑一次已經在 CI 跑過的 build |
| **手動部署** | 使用者明確要求自動化 |

## 後果

**正面**

- PR 與測試完全不接觸正式環境
- image 是可追溯、可回滾、可在別台機器重現的產物
- server 上不需要 JDK、Maven、Node —— 只要 Docker
- 沒有常駐的 runner 服務要維運
- 機密仍然只在 server 上

**負面**

| 後果 | 緩解 |
|---|---|
| **多一把能登入 server 的私鑰在 GitHub Secrets** | 專用部署金鑰、限制可執行的操作、只在 `main` 觸發 |
| 需要對外開放 SSH（或至少對 GitHub 的 IP 範圍） | 若不可接受，改用 Watchtower（見上表） |
| 部署依賴 GitHub Actions 可用性 | `deploy/deploy.sh` 可在 server 上手動執行，不受影響 |
| GHCR 上的 image 會累積 | 定期清理舊的 untagged image，或設保留政策 |
| 部署期間短暫停機（容器重啟數十秒） | 通知會留在 outbox 佇列，重啟後自動補送，不掉單。零停機需要多實例，而那需先處理[缺口 G12](../02-架構設計.md#g12--多實例的排程重複執行--加第二個實例前必須解決) |

**ADR-0006 中仍然成立的部分**

那份文件對 self-hosted runner 的風險分析沒有錯，只是最後選擇不承擔它們。其中兩條**與方案無關、依然必須遵守**：

- Repo 保持 **private**
- `permissions: contents: read` + `packages: write`，不給多餘權限
