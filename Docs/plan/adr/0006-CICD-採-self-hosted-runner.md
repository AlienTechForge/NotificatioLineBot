# ADR-0006 — CI/CD 採 self-hosted runner

**狀態**：Accepted ｜ 2026-08-18（2026-08-19 由 [ADR-0010](0010-CICD-改採-GHCR-加-SSH-部署.md) 補充 image 建置方式）

> 本決策**仍然有效**。2026-08-19 曾一度被錯誤地標記為 Superseded —— 見 ADR-0010 的說明。
> 實際採用的是「GitHub-hosted 建 image + self-hosted runner 部署」的組合，
> 部署端仍是 self-hosted runner。

## 背景

需求是「配好 GitHub CI/CD，每次 Action 自動推到 server」。

Server 由使用者自行管理（含反向代理與 HTTPS）。Repo 為 **private**。

GitHub Actions 要把產物送上 server，主要有四種做法：

| 做法 | 需要什麼 |
|---|---|
| GHCR image + SSH 觸發 | 對外開放 SSH，SSH key 存在 GitHub Secrets |
| Self-hosted runner | 在 server 上跑一個 runner 服務 |
| scp jar + systemd restart | 對外開放 SSH |
| Watchtower 自動拉取 | server 上跑 Watchtower |

## 決策

**Self-hosted runner 裝在 server 上**，`deploy.yml` 以 `runs-on: [self-hosted, linux, notifyline]` 執行。

分工：

| Workflow | 跑在哪 | 為什麼 |
|---|---|---|
| `ci.yml`（PR 驗證） | **GitHub-hosted** `ubuntu-latest` | 測試用 Testcontainers 自備 PostgreSQL，不需要 server 任何東西 |
| `deploy.yml`（部署） | **self-hosted** | 需要 server 上的 Docker 與 `.env` |

## 理由

- **不用對外開放 SSH**。Runner 是 server 主動對 GitHub 建立 outbound 連線，不需要在防火牆開任何 inbound port。這比「把 SSH key 交給 GitHub 並開放 22 port」的攻擊面小。
- **不用把 server 憑證放進 GitHub Secrets**。`.env` 直接放在 server 上由 root 管理（`chmod 600`），容器以 `env_file` 掛載。憑證從來不進 GitHub、不進 workflow 環境變數、不進 runner 的 workspace。
- **部署步驟就是本機指令**。`docker compose build && up -d` 在 server 上直接執行，不用透過 SSH 傳遞指令字串（那容易有跳脫與引號問題）。
- **健康檢查與回滾都在本機**，`curl localhost:8080` 直接可達，不需要繞外網。

### 為什麼 PR 驗證刻意不跑在 self-hosted

三個理由：

1. **減少攻擊面**——PR 的程式碼完全不接觸 server
2. **避免資源競爭**——測試不會吃掉正式服務的 CPU/記憶體
3. **環境純淨**——GitHub-hosted 每次都是全新環境，能抓到「只在我機器上能跑」的問題

### 為什麼 private repo 是前提

> ⚠️ GitHub 官方明確**不建議** public repo 使用 self-hosted runner。
>
> 任何人都能發 fork PR，若 workflow 在 self-hosted runner 上執行該 PR 的程式碼，等於讓陌生人在你的 server 上執行任意指令。

本專案是 private repo，此風險不存在。**若未來要改成 public，必須先重新評估整個部署方案。**

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **GHCR image + SSH 觸發** | 需對外開放 SSH 且把私鑰交給 GitHub。好處是 image 有版本可追溯、回滾乾淨——但 self-hosted 也能用 git sha 打 tag 達到同樣效果 |
| **scp jar + systemd** | 不用 Docker，最簡單。但環境一致性差（server 的 JDK 版本、系統套件都可能與開發環境不同），回滾要自己留舊檔 |
| **Watchtower 自動拉取** | 不需要任何 GitHub → server 的憑證，最省事。但**部署時間不可控**（下次輪詢才生效），且無法在部署失敗時自動回滾 |
| **手動部署** | 使用者明確要求自動化 |

## 後果

**正面**

- 不開放 inbound SSH
- 憑證不進 GitHub
- 部署腳本簡單直接
- 健康檢查與回滾在本機執行

**負面 —— 必須以設定措施抵銷**

| 後果 | 對應措施 |
|---|---|
| **Runner 能在 server 上執行 repo 中的程式碼** | Repo 保持 private；限制可用 action；required reviewers |
| **Runner 進程需要維運** | 定期更新（GitHub 會標記過期版本）；服務異常時部署會靜默排隊 |
| Runner 若被入侵，等同 server 被入侵 | 非 root 專用帳號、加入 `docker` group、**禁止 sudo**；與應用資料目錄隔離 |
| Action 的浮動 tag 可被重新指向 | **所有 action 釘 40 字元 commit SHA**，不用 `@v4` |
| `GITHUB_TOKEN` 權限過大 | `permissions: contents: read` |
| `pull_request_target` 會帶 secrets 執行 PR 程式碼 | **禁用該事件** |
| Workspace 殘留前次執行的檔案 | `checkout` 的 `clean: true` |
| 其他 repo 的 workflow 誤跑到這台 | 專用 label `[self-hosted, linux, notifyline]` |
| 併發部署造成容器狀態錯亂 | `concurrency: { group: deploy-prod, cancel-in-progress: false }` |

完整清單見 [09-CICD與維運 §3](../09-CICD與維運.md#3-安全前提)。**這些不是建議，是本決策成立的前提。**

**未解決的限制**

單機部署 = 部署期間有短暫停機（容器重啟，數十秒）。零停機需要多實例，而多實例需先處理 [缺口 G12](../02-架構設計.md#g12--多實例的排程重複執行--加第二個實例前必須解決)。以通知服務的性質（訊息會排在 outbox 佇列，重啟後自動補送）而言，短暫停機不會掉單，可接受。
